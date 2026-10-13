package dev.agentcraft.launcher;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * The Foreman launcher's process plumbing (mod/DEV.md "Foreman launcher"): short commands, starting the Foreman in the
 * background, asking it to stop, force-stopping its tree. Plain JDK and Gson, no game classes, so it runs the same in a
 * test harness as in the game. The decisions live in {@link LauncherPlan}; the client's {@code Launcher} calls both.
 */
public final class LauncherProcs {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private LauncherProcs() {
	}

	/**
	 * What a start produced.
	 *
	 * @param pid the Foreman's pid (-1 when it did not start)
	 * @param wrapperPid the background runner's pid on Windows ({@code tools/lib/bgrun.mjs}), else 0
	 * @param how {@code hidden-console} (Windows, like tools/launch.ps1) or {@code detached}
	 * @param error why it did not start (null when it did)
	 */
	public record Started(long pid, long wrapperPid, String how, @Nullable String error) {
		public boolean ok() {
			return pid > 0;
		}
	}

	// ------------------------------------------------------------------ short commands

	/**
	 * Runs a short command (stdin closed) and returns its output (stdout and stderr), or null when it could not start,
	 * exited non-zero or outlived {@code timeoutMs} (then it and its children are killed). {@code env} null = inherit.
	 */
	public static @Nullable String run(List<String> cmd, @Nullable Path dir, @Nullable Map<String, String> env, long timeoutMs) {
		Result r = runResult(cmd, dir, env, timeoutMs);
		return r != null && r.exit() == 0 ? r.out() : null;
	}

	/** A finished short command: exit code and output. */
	public record Result(int exit, String out) {
	}

	/** Like {@link #run} but keeps a non-zero exit (null only when it could not start or timed out). */
	public static @Nullable Result runResult(List<String> cmd, @Nullable Path dir, @Nullable Map<String, String> env, long timeoutMs) {
		try {
			ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
			if (dir != null) {
				pb.directory(dir.toFile());
			}
			if (env != null) {
				pb.environment().clear();
				pb.environment().putAll(env);
			}
			Process p = pb.start();
			p.getOutputStream().close();
			CompletableFuture<String> out = CompletableFuture.supplyAsync(() -> {
				try (InputStream in = p.getInputStream()) {
					return new String(in.readAllBytes(), StandardCharsets.UTF_8);
				} catch (IOException e) {
					return "";
				}
			});
			if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
				p.descendants().forEach(ProcessHandle::destroyForcibly);
				p.destroyForcibly();
				return null;
			}
			return new Result(p.exitValue(), out.get(2, TimeUnit.SECONDS));
		} catch (Exception e) {
			return null;
		}
	}

	// ------------------------------------------------------------------ processes

	public static boolean alive(long pid) {
		return pid > 0 && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
	}

	/** The process's start time (ms), or -1 when unknown or gone. */
	public static long startMs(long pid) {
		if (pid <= 0) {
			return -1;
		}
		return ProcessHandle.of(pid).filter(ProcessHandle::isAlive).flatMap(h -> h.info().startInstant()).map(Instant::toEpochMilli).orElse(-1L);
	}

	/** Waits up to {@code ms} for {@code pid} to exit; true when it is gone. */
	public static boolean waitExit(long pid, long ms) {
		Optional<ProcessHandle> h = ProcessHandle.of(pid);
		if (h.isEmpty() || !h.get().isAlive()) {
			return true;
		}
		try {
			h.get().onExit().get(ms, TimeUnit.MILLISECONDS);
		} catch (Exception ignored) {
			// still running (or interrupted)
		}
		return !h.get().isAlive();
	}

	/** Force-kills {@code pid} and everything it started, children before parents (none left orphaned). */
	public static void killTree(long pid) {
		Optional<ProcessHandle> h = ProcessHandle.of(pid);
		if (h.isEmpty()) {
			return;
		}
		List<ProcessHandle> kids = h.get().descendants().sorted(Comparator.comparingInt(LauncherProcs::depth).reversed()).toList();
		kids.forEach(ProcessHandle::destroyForcibly);
		h.get().destroyForcibly();
	}

	private static int depth(ProcessHandle h) {
		int d = 0;
		for (Optional<ProcessHandle> p = h.parent(); p.isPresent() && d < 64; p = p.get().parent()) {
			d++;
		}
		return d;
	}

	/**
	 * Asks the Foreman to stop the way it saves its state: Ctrl+Break into its console on Windows
	 * ({@link LauncherPlan#ctrlBreakScript}; false when it has none, as one started {@code detached}), SIGTERM elsewhere.
	 * Returns whether the request was delivered; the caller waits for the exit.
	 */
	public static boolean requestStop(long pid, boolean windows, @Nullable String systemRoot, long timeoutMs) {
		if (!alive(pid)) {
			return true;
		}
		if (windows) {
			Result r = runResult(LauncherPlan.powershell(systemRoot, LauncherPlan.ctrlBreakScript(pid)), null, null, timeoutMs);
			return r != null && r.exit() == 0;
		}
		return ProcessHandle.of(pid).map(ProcessHandle::destroy).orElse(false);
	}

	// ------------------------------------------------------------------ starting the Foreman

	/**
	 * Windows: {@code node bgrun.mjs <spec>} through PowerShell's {@code Start-Process} (hidden window, own console, no
	 * inherited handles), as tools/launch.ps1 starts its Foreman ({@link LauncherPlan#bgrun}). The spec and the runner's
	 * status file are {@code <runDir>/<name>.spec.json} / {@code .status.json}; the Foreman's output is appended to
	 * {@code log}. {@code env} becomes PowerShell's environment, which the runner and the Foreman inherit.
	 */
	public static Started startHidden(Path node, Path bgrun, List<String> args, Path cwd, Path log, Path runDir, String name,
		Map<String, String> env, @Nullable String systemRoot, long timeoutMs) {
		Path spec = runDir.resolve(name + ".spec.json");
		Path status = runDir.resolve(name + ".status.json");
		try {
			Files.createDirectories(runDir);
			Files.deleteIfExists(status);
			JsonObject s = new JsonObject();
			s.addProperty("command", node.toString());
			JsonArray a = new JsonArray();
			args.forEach(a::add);
			s.add("args", a);
			s.addProperty("cwd", cwd.toString());
			s.addProperty("log", log.toString());
			s.add("env", new JsonObject());
			s.addProperty("verbatim", false);
			s.addProperty("statusFile", status.toString());
			Files.writeString(spec, GSON.toJson(s) + "\n", StandardCharsets.UTF_8);
		} catch (IOException e) {
			return new Started(-1, 0, "hidden-console", "could not write " + spec + ": " + e.getMessage());
		}
		Result r = runResult(LauncherPlan.powershell(systemRoot, LauncherPlan.hiddenStartScript(node, bgrun, spec, cwd)), cwd, env, 30_000);
		long wrapper = r == null ? -1 : LauncherPlan.parseMarkedPid(r.out());
		if (wrapper <= 0) {
			return new Started(-1, 0, "hidden-console", "PowerShell could not start " + bgrun.getFileName() + (r == null ? " (timed out)"
				: " (exit " + r.exit() + "): " + r.out().strip()));
		}
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline) {
			JsonObject st = readJson(status);
			if (st != null && st.has("childPid") && st.get("childPid").isJsonPrimitive()) {
				return new Started(st.get("childPid").getAsLong(), wrapper, "hidden-console", null);
			}
			if (st != null && st.has("error")) {
				return new Started(-1, wrapper, "hidden-console", st.get("error").getAsString());
			}
			if (!alive(wrapper)) {
				// it may have written the status just before exiting
				st = readJson(status);
				if (st != null && st.has("childPid") && st.get("childPid").isJsonPrimitive()) {
					return new Started(st.get("childPid").getAsLong(), wrapper, "hidden-console", null);
				}
				return new Started(-1, wrapper, "hidden-console", st != null && st.has("error") ? st.get("error").getAsString()
					: "the background runner exited without starting the Foreman");
			}
			sleep(100);
		}
		killTree(wrapper);
		return new Started(-1, wrapper, "hidden-console", "no status from the background runner within " + timeoutMs / 1000 + " s");
	}

	/** macOS / Linux (and Windows without bgrun.mjs): {@link LauncherPlan#SPAWN_SCRIPT} through node, detached. */
	public static Started startDetached(Path node, List<String> args, Path cwd, Path log, Map<String, String> env, Path dir, long timeoutMs) {
		Result r = runResult(LauncherPlan.spawnCommand(node, log, cwd, args), dir, env, timeoutMs);
		long pid = r == null ? -1 : LauncherPlan.parsePid(r.out());
		if (r == null || r.exit() != 0 || pid <= 0) {
			return new Started(-1, 0, "detached", r == null ? "the node helper timed out" : "the node helper failed: " + r.out().strip());
		}
		return new Started(pid, 0, "detached", null);
	}

	// ------------------------------------------------------------------ files

	public static @Nullable JsonObject readJson(@Nullable Path f) {
		if (f == null) {
			return null;
		}
		try {
			// bgrun and PowerShell may write a BOM
			String text = Files.readString(f, StandardCharsets.UTF_8);
			return JsonParser.parseString(text.startsWith("﻿") ? text.substring(1) : text).getAsJsonObject();
		} catch (Exception e) {
			return null;
		}
	}

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
