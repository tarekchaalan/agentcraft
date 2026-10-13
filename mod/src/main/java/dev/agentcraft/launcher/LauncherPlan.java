package dev.agentcraft.launcher;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The Foreman launcher's decisions as pure functions (mod/DEV.md "Foreman launcher"), so they can be read and checked
 * without a game, a node binary or a process: where node is looked for and in which order, which node is good enough,
 * which checkout the Foreman runs from, whether a Foreman already on the port is reused, restarted or left alone, the
 * command line, and the environment. The client's {@code Launcher} does the I/O around them.
 *
 * <p>The rules match tools/launch.ps1 and tools/launch-unix.mjs where they overlap: a Foreman is stale when it runs from
 * another checkout or another commit than the one it would be started from now.
 */
public final class LauncherPlan {
	/** Node major version the Foreman needs (foreman/package.json engines). */
	public static final int MIN_NODE = 22;
	public static final String NODE_INSTALL_URL = "https://nodejs.org/en/download";
	/** Who wrote a launcher record (the {@code by} field). */
	public static final String BY = "agentcraft-mod";

	private LauncherPlan() {
	}

	// ------------------------------------------------------------------ the host, injected

	/**
	 * What node discovery may look at: environment variables, the OS, the user's home, and a file system.
	 *
	 * @param executable whether a path is an executable file
	 * @param list the entries of a directory (empty when it is none)
	 */
	public record Host(Map<String, String> env, String osName, String home, Predicate<Path> executable, Function<Path, List<Path>> list) {
		public boolean windows() {
			return windows(osName);
		}

		public static boolean windows(String osName) {
			return osName.toLowerCase(Locale.ROOT).startsWith("windows");
		}

		@Nullable String get(String key) {
			String v = env.get(key);
			if (v == null && windows()) {
				// Windows env keys are case-insensitive ("Path", "ProgramFiles")
				for (Map.Entry<String, String> e : env.entrySet()) {
					if (e.getKey().equalsIgnoreCase(key)) {
						v = e.getValue();
						break;
					}
				}
			}
			return v == null || v.isBlank() ? null : v;
		}
	}

	// ------------------------------------------------------------------ node discovery

	/**
	 * Where node may be, in the order it is tried (first match wins, duplicates dropped): the config's {@code nodePath};
	 * every {@code PATH} entry; on Windows {@code %ProgramFiles%\nodejs}, {@code %LOCALAPPDATA%\Programs\nodejs}, nvm-windows'
	 * symlink and Volta; elsewhere {@code /opt/homebrew/bin}, {@code /usr/local/bin} and the version managers' shims and
	 * installs (mise, volta, nvm's {@code NVM_BIN} and its newest installed version, asdf, fnm). A launcher-started
	 * Minecraft has a minimal PATH, which is why the fixed places follow. The login shell ({@link #shellProbe}) is asked
	 * last, by the caller, because it spawns a process.
	 */
	public static List<Path> nodeCandidates(@Nullable String configNodePath, Host h) {
		String exe = h.windows() ? "node.exe" : "node";
		Set<Path> out = new LinkedHashSet<>();
		if (configNodePath != null && !configNodePath.isBlank()) {
			Path p = Path.of(expandHome(configNodePath.strip(), h.home()));
			// the config may name the binary or its folder
			out.add(p.getFileName() != null && p.getFileName().toString().toLowerCase(Locale.ROOT).startsWith("node") ? p : p.resolve(exe));
		}
		String path = h.get("PATH");
		if (path != null) {
			for (String dir : path.split(h.windows() ? ";" : ":")) {
				if (!dir.isBlank()) {
					out.add(Path.of(dir.strip()).resolve(exe));
				}
			}
		}
		Path home = Path.of(h.home());
		if (h.windows()) {
			String pf = h.get("ProgramFiles");
			out.add(Path.of(pf != null ? pf : "C:\\Program Files").resolve("nodejs").resolve(exe));
			String local = h.get("LOCALAPPDATA");
			if (local != null) {
				out.add(Path.of(local).resolve("Programs").resolve("nodejs").resolve(exe));
			}
			String nvmSym = h.get("NVM_SYMLINK");
			if (nvmSym != null) {
				out.add(Path.of(nvmSym).resolve(exe));
			}
			String volta = h.get("VOLTA_HOME");
			out.add((volta != null ? Path.of(volta) : Path.of(local != null ? local : h.home()).resolve("Volta")).resolve("bin").resolve(exe));
			out.add(Path.of("C:\\nvm4w\\nodejs").resolve(exe));
		} else {
			out.add(Path.of("/opt/homebrew/bin").resolve(exe));
			out.add(Path.of("/usr/local/bin").resolve(exe));
			String miseData = h.get("MISE_DATA_DIR");
			out.add((miseData != null ? Path.of(miseData) : home.resolve(".local/share/mise")).resolve("shims").resolve(exe));
			String volta = h.get("VOLTA_HOME");
			out.add((volta != null ? Path.of(volta) : home.resolve(".volta")).resolve("bin").resolve(exe));
			String nvmBin = h.get("NVM_BIN");
			if (nvmBin != null) {
				out.add(Path.of(nvmBin).resolve(exe));
			}
			String nvmDir = h.get("NVM_DIR");
			Path versions = (nvmDir != null ? Path.of(nvmDir) : home.resolve(".nvm")).resolve("versions").resolve("node");
			for (Path v : newestFirst(h.list().apply(versions))) {
				out.add(v.resolve("bin").resolve(exe));
			}
			out.add(home.resolve(".asdf/shims").resolve(exe));
			out.add(home.resolve(".local/share/fnm/aliases/default/bin").resolve(exe));
			out.add(Path.of("/usr/bin").resolve(exe));
		}
		return List.copyOf(out);
	}

	/** Version folders ({@code v22.11.0}) newest first by numeric version; others last. */
	static List<Path> newestFirst(List<Path> dirs) {
		List<Path> sorted = new ArrayList<>(dirs);
		sorted.sort(Comparator.comparing((Path p) -> versionKey(p.getFileName() == null ? "" : p.getFileName().toString())).reversed());
		return sorted;
	}

	private static String versionKey(String name) {
		int[] v = parseVersion(name);
		return v == null ? "" : String.format(Locale.ROOT, "%06d.%06d.%06d", v[0], v[1], v[2]);
	}

	/** The user's login shell: {@code $SHELL}, else {@code /bin/zsh} on macOS and {@code /bin/bash} elsewhere. */
	public static String loginShell(Host h) {
		String shell = h.get("SHELL");
		return shell != null ? shell : h.osName().toLowerCase(Locale.ROOT).contains("mac") ? "/bin/zsh" : "/bin/bash";
	}

	/**
	 * The process that asks the user's shell for node (it sets up PATH from the profile, version managers included):
	 * {@code $SHELL -lc 'command -v node'}, or {@code where.exe node} on Windows.
	 */
	public static List<String> shellProbe(Host h) {
		if (h.windows()) {
			String sysRoot = h.get("SystemRoot");
			return List.of((sysRoot != null ? sysRoot : "C:\\Windows") + "\\System32\\where.exe", "node");
		}
		return List.of(loginShell(h), "-lc", "command -v node");
	}

	/** The node path in a probe's output: the last line that looks like an absolute path (profiles may print banners). */
	public static @Nullable Path parseProbe(String output) {
		Path found = null;
		for (String line : output.split("\\R")) {
			String t = line.strip();
			if (t.startsWith("/") || t.matches("^[A-Za-z]:\\\\.*")) {
				found = Path.of(t);
			}
		}
		return found;
	}

	private static final Pattern VERSION = Pattern.compile("v?(\\d+)\\.(\\d+)\\.(\\d+)");

	/** {@code v22.11.0} -> {22, 11, 0}; null when it is not a version. */
	public static int @Nullable [] parseVersion(@Nullable String s) {
		if (s == null) {
			return null;
		}
		Matcher m = VERSION.matcher(s.strip());
		if (!m.find()) {
			return null;
		}
		return new int[] {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))};
	}

	/** Whether {@code node --version}'s output is new enough ({@value #MIN_NODE}+). */
	public static boolean nodeOk(@Nullable String versionOutput) {
		int[] v = parseVersion(versionOutput);
		return v != null && v[0] >= MIN_NODE;
	}

	/**
	 * npm's command-line script next to a node binary, run through node itself (no shell, no {@code .cmd} quoting on
	 * Windows): {@code <nodeDir>/node_modules/npm/bin/npm-cli.js} (Windows installs, portable zips) or
	 * {@code <nodeDir>/../lib/node_modules/npm/bin/npm-cli.js} (Unix installs, nvm, Homebrew). Null when neither exists.
	 */
	public static @Nullable Path npmCli(Path node, Predicate<Path> exists) {
		Path dir = node.getParent();
		if (dir == null) {
			return null;
		}
		for (Path c : List.of(dir.resolve("node_modules").resolve("npm").resolve("bin").resolve("npm-cli.js"),
			dir.resolve("..").resolve("lib").resolve("node_modules").resolve("npm").resolve("bin").resolve("npm-cli.js").normalize())) {
			if (exists.test(c)) {
				return c;
			}
		}
		return null;
	}

	/**
	 * The Foreman's dependencies, as the launchers install them: {@code npm ci --no-audit --no-fund}, run as
	 * {@code node npm-cli.js ...}. Dev dependencies are kept: the Foreman runs its TypeScript through {@code tsx}, a dev
	 * dependency.
	 */
	public static List<String> installCommand(Path node, Path npmCli) {
		return List.of(node.toString(), npmCli.toString(), "ci", "--no-audit", "--no-fund");
	}

	/** Whether {@code foreman/node_modules} is installed (npm writes {@code node_modules/.package-lock.json}). */
	public static boolean depsReady(Path checkout, Predicate<Path> exists) {
		return exists.test(foremanDir(checkout).resolve("node_modules").resolve(".package-lock.json"));
	}

	/** {@code foreman/package-lock.json}: what {@code npm ci} installs. */
	public static Path lockFile(Path checkout) {
		return foremanDir(checkout).resolve("package-lock.json");
	}

	/**
	 * The launcher's install marker: the hash of the {@code package-lock.json} its last {@code npm ci} installed, inside
	 * {@code node_modules} (so {@code npm ci}, which replaces the folder, takes it away, and a failed install leaves none).
	 */
	public static Path depsMarker(Path checkout) {
		return foremanDir(checkout).resolve("node_modules").resolve(".agentcraft-lock.sha256");
	}

	/** The marker's content for a lock file: its SHA-256, hex. */
	public static String lockHash(byte[] lock) {
		try {
			return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(lock));
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/**
	 * Whether the launcher runs {@code npm ci} before starting the Foreman: nothing installed, or a lock file
	 * ({@code lockHash}, null when there is none) other than the one the marker ({@code marker}, null when missing) says was
	 * installed: a pull changed the dependencies, or someone else installed them (no marker). An unchanged lock keeps
	 * {@code node_modules} (the Agent SDK is large).
	 */
	public static boolean needsInstall(boolean installed, @Nullable String lockHash, @Nullable String marker) {
		if (!installed) {
			return true;
		}
		return lockHash != null && (marker == null || !lockHash.equals(marker.strip()));
	}

	// ------------------------------------------------------------------ where the Foreman comes from

	/** A checkout's {@code foreman/}. */
	public static Path foremanDir(Path checkout) {
		return checkout.resolve("foreman");
	}

	/** Whether a folder is an AgentCraft checkout (it has {@code foreman/package.json} and {@code foreman/src/main.ts}). */
	public static boolean isCheckout(Path dir, Predicate<Path> exists) {
		return exists.test(foremanDir(dir).resolve("package.json")) && exists.test(foremanDir(dir).resolve("src").resolve("main.ts"));
	}

	/** A configured folder may be the checkout or its {@code foreman/}: the checkout. */
	public static Path checkoutOf(Path dir) {
		Path name = dir.getFileName();
		return name != null && name.toString().equals("foreman") && dir.getParent() != null ? dir.getParent() : dir;
	}

	/**
	 * Where the Foreman runs from, and why.
	 *
	 * @param root the checkout (null when none was found)
	 * @param origin where it came from ({@code AGENTCRAFT_FOREMAN_DIR}, {@code launcher.foremanDir}, "dev run", "mod build",
	 *               "none")
	 * @param problem why there is none, or why the configured one is unusable (null when fine)
	 */
	public record Source(@Nullable Path root, String origin, @Nullable String problem) {
	}

	/** One place the source may be, in order; null paths are skipped. */
	public record Candidate(String origin, @Nullable String path, boolean explicit) {
	}

	/**
	 * The candidates in the order they are tried: the {@code AGENTCRAFT_FOREMAN_DIR} override (env or
	 * {@code -Dagentcraft.foreman.dir}, what a Prism instance's JVM arguments carry), the config's
	 * {@code launcher.foremanDir}, a dev run's own checkout ({@code <repo>/mod/run} is the game dir), and the checkout the
	 * mod jar was built from ({@code agentcraft-build.properties}).
	 */
	public static List<Candidate> sourceCandidates(@Nullable String override, @Nullable String configDir, @Nullable Path devRunCheckout,
		@Nullable String buildCheckout) {
		List<Candidate> out = new ArrayList<>();
		out.add(new Candidate("AGENTCRAFT_FOREMAN_DIR", override, true));
		out.add(new Candidate("launcher.foremanDir", configDir, true));
		out.add(new Candidate("dev run", devRunCheckout == null ? null : devRunCheckout.toString(), false));
		out.add(new Candidate("mod build", buildCheckout, false));
		return out;
	}

	/**
	 * The first candidate that is a checkout. An explicit one (the override, the config) that is not a checkout stops the
	 * search with a problem: the player asked for that folder, silently running another would surprise them.
	 */
	public static Source source(List<Candidate> candidates, String home, Predicate<Path> exists) {
		for (Candidate c : candidates) {
			if (c.path() == null || c.path().isBlank()) {
				continue;
			}
			Path dir = checkoutOf(Path.of(expandHome(c.path().strip(), home)).toAbsolutePath().normalize());
			if (isCheckout(dir, exists)) {
				return new Source(dir, c.origin(), null);
			}
			if (c.explicit()) {
				return new Source(null, c.origin(), c.origin() + " = " + c.path().strip() + " is not an AgentCraft checkout (no foreman/package.json)");
			}
		}
		return new Source(null, "none",
			"no AgentCraft checkout to run the Foreman from: set launcher.foremanDir in config.json or AGENTCRAFT_FOREMAN_DIR (-Dagentcraft.foreman.dir)");
	}

	/** {@code ~} and {@code ~/x} -> the home folder. */
	public static String expandHome(String p, String home) {
		if (p.equals("~")) {
			return home;
		}
		if (p.startsWith("~/") || p.startsWith("~\\")) {
			return Path.of(home, p.substring(2)).toString();
		}
		return p;
	}

	/** {@code a} is {@code b} or inside it. */
	static boolean inside(Path child, Path parent) {
		Path c = child.toAbsolutePath().normalize();
		Path p = parent.toAbsolutePath().normalize();
		return c.startsWith(p);
	}

	/**
	 * The repository (from config.json {@code repos}) that {@code root} is, or sits inside, or whose Git common dir it
	 * shares (a worktree), or null. Agents merge into such a checkout, so a launcher-started everyday game must not run
	 * its Foreman from there. Paths are real paths (the caller's).
	 */
	public static @Nullable Path devCheckoutConflict(Path root, @Nullable Path commonDir, List<Path> repos) {
		for (Path repo : repos) {
			if (repo == null) {
				continue;
			}
			if (inside(root, repo) || commonDir != null && inside(commonDir, repo)) {
				return repo;
			}
		}
		return null;
	}

	// ------------------------------------------------------------------ what runs on the port

	/** What answered on the port. */
	public enum Probe {
		/** Nothing listens. */
		CLOSED,
		/** Something listens but did not answer {@code hello} with a snapshot (not a Foreman, or a hung one). */
		NOT_FOREMAN,
		/** An AgentCraft Foreman answered {@code hello} with a snapshot. */
		FOREMAN
	}

	/** The identity of a running Foreman: its run file ({@code <home>/<profile>/foreman.json}) and launcher records. */
	public record Running(long pid, @Nullable String profile, @Nullable String root, @Nullable String commit, @Nullable String by) {
	}

	/**
	 * Why a recorded Foreman launch is not what would be started now (another checkout, another commit); empty =
	 * current. A record without checkout and commit is stale (we cannot tell). {@code currentCommit} null (no git) skips
	 * the commit check.
	 */
	public static List<String> staleReasons(@Nullable String recordedRoot, @Nullable String recordedCommit, String currentRoot,
		@Nullable String currentCommit) {
		List<String> reasons = new ArrayList<>();
		if (blank(recordedRoot) && blank(recordedCommit)) {
			reasons.add("it did not record its checkout or commit (started by an older Foreman)");
			return reasons;
		}
		if (!blank(recordedRoot) && !samePath(recordedRoot, currentRoot)) {
			reasons.add("started from a different checkout (" + recordedRoot + ")");
		}
		if (!blank(recordedCommit) && currentCommit != null && !recordedCommit.equals(currentCommit)) {
			reasons.add("started at commit " + shortSha(recordedCommit) + ", the checkout is now at " + shortSha(currentCommit));
		}
		return reasons;
	}

	static boolean samePath(String a, String b) {
		return Path.of(a).toAbsolutePath().normalize().equals(Path.of(b).toAbsolutePath().normalize());
	}

	public static String shortSha(@Nullable String sha) {
		return sha == null ? "?" : sha.length() > 9 ? sha.substring(0, 9) : sha;
	}

	private static boolean blank(@Nullable String s) {
		return s == null || s.isBlank();
	}

	/** How far a process's start may lie from the launcher's recorded spawn time and still be the one it spawned. */
	public static final long SAME_PROCESS_MS = 10_000;

	/**
	 * Whether a live pid is really the process the launcher spawned, not a later one that reused the pid: its start time
	 * ({@code processStartMs}, -1 when the OS does not say) lies within {@link #SAME_PROCESS_MS} of the start time
	 * recorded in {@code launcher.json} ({@code recordedStartMs}, 0 when unknown). Unknown is false: the launcher never
	 * stops a process it cannot identify.
	 */
	public static boolean sameProcess(long processStartMs, long recordedStartMs) {
		if (processStartMs < 0 || recordedStartMs <= 0) {
			return false;
		}
		return processStartMs >= recordedStartMs - 2_000 && processStartMs <= recordedStartMs + SAME_PROCESS_MS;
	}

	/** Whose the running Foreman is. */
	public enum Owner {
		/** The process our record names (same pid, same start time). */
		OURS,
		/** Not started by this launcher (tools/launch.ps1, tools/launch-unix.mjs, a terminal), or unknown. */
		FOREIGN
	}

	/**
	 * Whose the running Foreman is. Only the very process the launcher spawned is ours: the Foreman never restarts itself,
	 * so a new process on the profile after ours is gone was started by someone else (tools/launch.ps1, a terminal) and
	 * is never adopted (the launcher would otherwise stop it on {@code /foreman stop} or {@code launcher.stopOnExit}).
	 *
	 * @param recordPid the pid in our record ({@code <home>/<profile>/launcher.json}; 0 = no record)
	 * @param recordStartMs the process start time our record holds (ms)
	 * @param recordAlive whether {@code recordPid} is a live process whose start time matches ({@link #sameProcess})
	 * @param running the Foreman the run files name on our port (null = none alive)
	 */
	public static Owner owner(long recordPid, long recordStartMs, boolean recordAlive, @Nullable Running running) {
		if (recordPid <= 0 || recordStartMs <= 0 || !recordAlive) {
			return Owner.FOREIGN;
		}
		return running == null || running.pid() == recordPid ? Owner.OURS : Owner.FOREIGN;
	}

	/** What the launcher does about the port. */
	public enum Action {
		/** A current Foreman answers: use it, start nothing. */
		REUSE,
		/** An older Foreman we did not start answers: use it, never stop it; the status says "running (older version)". */
		REUSE_OLDER,
		/** Nothing answers and the profile is free: start ours. */
		START,
		/** Ours is stale (other checkout or commit) or hangs: stop it, then start ours. */
		RESTART,
		/** Something we did not start holds the port and is not a Foreman (or does not answer): leave it, report it. */
		PORT_TAKEN,
		/** The profile's Foreman runs on another port (a second one would be refused): report it. */
		PROFILE_BUSY
	}

	/**
	 * The decision ("never kill what we did not start").
	 *
	 * @param probe what the port answered
	 * @param ours whether the process on the port (or the profile's) is ours ({@link Owner#OURS} or OURS_RESTARTED)
	 * @param stale {@link #staleReasons} of the Foreman that answered (empty = current)
	 * @param profileBusyElsewhere the profile's run file names a live Foreman on another port
	 */
	public static Action decide(Probe probe, boolean ours, List<String> stale, boolean profileBusyElsewhere, boolean forceRestart) {
		return switch (probe) {
			case FOREMAN -> ours && (forceRestart || !stale.isEmpty()) ? Action.RESTART : stale.isEmpty() ? Action.REUSE : Action.REUSE_OLDER;
			case NOT_FOREMAN -> ours ? Action.RESTART : Action.PORT_TAKEN;
			case CLOSED -> ours ? Action.RESTART : profileBusyElsewhere ? Action.PROFILE_BUSY : Action.START;
		};
	}

	// ------------------------------------------------------------------ the command line

	/** The Foreman's arguments (what tools/launch.ps1 passes), run in {@code <checkout>/foreman}. */
	public static List<String> foremanArgs(String backend, String profile, String home, int port) {
		return List.of("--import", "tsx", "src/main.ts", "--backend", backend, "--profile", profile, "--home", home, "--port", Integer.toString(port));
	}

	/**
	 * macOS and Linux (and Windows when the checkout has no {@link #bgrun}): a tiny node program that starts the Foreman
	 * detached (its own session and process group, so neither the game's exit nor a signal to the game's process group
	 * reaches it), with stdout and stderr appended to the log, and prints its pid. Node itself does it
	 * ({@code process.execPath}: the real binary, not a version manager's shim), so it needs no file from the checkout.
	 * argv: log, cwd, Foreman arguments. No double quotes and no backslashes in the script, so Windows' argument quoting
	 * passes it through unchanged.
	 */
	public static final String SPAWN_SCRIPT = "const{spawn}=require('node:child_process');const fs=require('node:fs');"
		+ "const[log,cwd,...args]=process.argv.slice(1);const out=fs.openSync(log,'a');"
		+ "const c=spawn(process.execPath,args,{cwd,detached:true,stdio:['ignore',out,out],windowsHide:true});"
		+ "c.on('error',e=>{console.error(String(e));process.exit(1)});"
		+ "if(c.pid){c.unref();process.stdout.write(String(c.pid));process.exit(0)}";

	/** {@code node -e SPAWN_SCRIPT <log> <cwd> <args...>}. */
	public static List<String> spawnCommand(Path node, Path log, Path cwd, List<String> args) {
		List<String> c = new ArrayList<>(List.of(node.toString(), "-e", SPAWN_SCRIPT, log.toString(), cwd.toString()));
		c.addAll(args);
		return List.copyOf(c);
	}

	/** The spawn helper's output: the pid it printed, or -1. */
	public static long parsePid(@Nullable String out) {
		if (out == null) {
			return -1;
		}
		Matcher m = Pattern.compile("(\\d+)\\s*$").matcher(out.strip());
		return m.find() ? Long.parseLong(m.group(1)) : -1;
	}

	/** Whether a process command line is a Foreman of {@code profile}. */
	public static boolean isForemanCommand(@Nullable String command, String profile) {
		if (command == null || !command.contains("src/main.ts") && !command.contains("src\\main.ts")) {
			return false;
		}
		return Pattern.compile("--profile[ =]" + Pattern.quote(profile) + "(\\s|$)").matcher(command).find();
	}

	// ------------------------------------------------------------------ Windows: a hidden console, as tools/launch.ps1 does it

	/**
	 * tools/launch.ps1's background runner, {@code tools/lib/bgrun.mjs}: it opens the log and runs the Foreman in its own
	 * hidden console. On Windows the launcher starts it the same way launch.ps1 does (through {@link #hiddenStartScript}),
	 * so a Foreman the game started is like one launch.ps1 started:
	 * <ul>
	 *   <li>it has a console, so the agents' console programs (git, claude, npm) share it instead of each opening a
	 *       window, and {@link #ctrlBreakScript Ctrl+Break} stops it cleanly (state saved, run file released);</li>
	 *   <li>it is started through ShellExecute (no handle inheritance), so it never holds the game's stdout: a
	 *       {@code gradlew runClient} ends with the game, not with the Foreman;</li>
	 *   <li>it is outside the game's process tree, so it keeps running after the game exits.</li>
	 * </ul>
	 */
	public static Path bgrun(Path checkout) {
		return checkout.resolve("tools").resolve("lib").resolve("bgrun.mjs");
	}

	/** One argument for a Windows command line (CommandLineToArgvW rules, as tools/lib/procs.ps1 ConvertTo-CmdArg). */
	public static String cmdArg(String s) {
		if (s.isEmpty()) {
			return "\"\"";
		}
		if (!s.matches(".*[\\s\"].*")) {
			return s;
		}
		StringBuilder out = new StringBuilder("\"");
		int backslashes = 0;
		for (char c : s.toCharArray()) {
			if (c == '\\') {
				backslashes++;
				continue;
			}
			if (c == '"') {
				out.append("\\".repeat(2 * backslashes + 1)).append('"');
			} else {
				out.append("\\".repeat(backslashes)).append(c);
			}
			backslashes = 0;
		}
		return out.append("\\".repeat(2 * backslashes)).append('"').toString();
	}

	/** A PowerShell string literal: single quotes, a quote doubled. */
	public static String psLiteral(String s) {
		return "'" + s.replace("'", "''") + "'";
	}

	/** Printed by {@link #hiddenStartScript} before the wrapper's pid (PowerShell may write other noise). */
	public static final String PID_MARK = "AGENTCRAFT_PID=";

	/**
	 * The PowerShell that starts {@code node bgrun.mjs <spec>} hidden, with its own console, through ShellExecute
	 * ({@code Start-Process} without redirection, exactly as tools/lib/procs.ps1 {@code Start-Bg}); prints
	 * {@link #PID_MARK} and the wrapper's pid. The environment is PowerShell's own (the caller sets it).
	 */
	public static String hiddenStartScript(Path node, Path bgrun, Path spec, Path cwd) {
		return "$ErrorActionPreference = 'Stop'\n$ProgressPreference = 'SilentlyContinue'\n"
			+ "$p = Start-Process -FilePath " + psLiteral(node.toString()) + " -ArgumentList " + psLiteral(cmdArg(bgrun.toString()) + " "
			+ cmdArg(spec.toString())) + " -WorkingDirectory " + psLiteral(cwd.toString()) + " -WindowStyle Hidden -PassThru\n"
			+ "[Console]::Out.Write('" + PID_MARK + "' + $p.Id)\n";
	}

	/** The pid after {@link #PID_MARK} in a script's output, or -1. */
	public static long parseMarkedPid(@Nullable String out) {
		if (out == null) {
			return -1;
		}
		Matcher m = Pattern.compile(Pattern.quote(PID_MARK) + "(\\d+)").matcher(out);
		return m.find() ? Long.parseLong(m.group(1)) : -1;
	}

	/**
	 * Ctrl+Break into the console of {@code pid} (tools/lib/procs.ps1 {@code Send-CtrlBreak}, verbatim): node turns it into
	 * SIGBREAK, which the Foreman handles like Ctrl+C (saves its state, releases foreman.json, exits). Exit code 0 when
	 * the event was sent, 10 when the process has no console to attach to.
	 */
	public static String ctrlBreakScript(long pid) {
		return """
			$src = @"
			using System;
			using System.Runtime.InteropServices;
			public static class AgentCraftCtrl {
			  public delegate bool Handler(uint ctrlType);
			  static Handler keep = delegate (uint t) { return true; };
			  [DllImport("kernel32.dll", SetLastError=true)] static extern bool AttachConsole(uint pid);
			  [DllImport("kernel32.dll", SetLastError=true)] static extern bool FreeConsole();
			  [DllImport("kernel32.dll", SetLastError=true)] static extern bool SetConsoleCtrlHandler(Handler h, bool add);
			  [DllImport("kernel32.dll", SetLastError=true)] static extern bool GenerateConsoleCtrlEvent(uint ev, uint group);
			  public static int Send(uint pid) {
			    FreeConsole();
			    if (!AttachConsole(pid)) return 10;
			    SetConsoleCtrlHandler(keep, true);
			    bool ok = GenerateConsoleCtrlEvent(1, 0);
			    System.Threading.Thread.Sleep(400);
			    FreeConsole();
			    return ok ? 0 : 11;
			  }
			}
			"@
			Add-Type -TypeDefinition $src
			exit [AgentCraftCtrl]::Send([uint32]__PID__)
			""".replace("__PID__", Long.toString(pid));
	}

	/**
	 * Windows PowerShell 5.1 running {@code script} ({@code -EncodedCommand}: UTF-16LE, base64, so no quoting reaches a
	 * command line). {@code systemRoot} null = {@code C:\Windows}.
	 */
	public static List<String> powershell(@Nullable String systemRoot, String script) {
		String exe = (systemRoot != null && !systemRoot.isBlank() ? systemRoot : "C:\\Windows") + "\\System32\\WindowsPowerShell\\v1.0\\powershell.exe";
		String encoded = java.util.Base64.getEncoder().encodeToString(script.getBytes(java.nio.charset.StandardCharsets.UTF_16LE));
		return List.of(exe, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded);
	}

	// ------------------------------------------------------------------ the environment

	/** Printed before {@code env -0} so profile banners never corrupt the parse. */
	public static final String ENV_MARK = "__AGENTCRAFT_LOGIN_ENV__";

	/**
	 * The commands that read the login shell's environment, tried in order: an interactive login shell first (rc files
	 * such as {@code ~/.zshrc} are where mise, dotnet and friends are usually set up), then a plain login shell
	 * ({@code -lc}) when that fails or hangs. Empty on Windows (the game's environment is the user's there).
	 */
	public static List<List<String>> loginEnvProbes(Host h) {
		if (h.windows()) {
			return List.of();
		}
		String script = "printf '\\n%s\\n' " + ENV_MARK + "; env -0";
		String shell = loginShell(h);
		return List.of(List.of(shell, "-lic", script), List.of(shell, "-lc", script));
	}

	/** The variables after {@link #ENV_MARK} in a probe's output ({@code env -0}: NUL-separated {@code KEY=value}). */
	public static Map<String, String> parseLoginEnv(@Nullable String out) {
		Map<String, String> env = new LinkedHashMap<>();
		if (out == null) {
			return env;
		}
		int at = out.lastIndexOf(ENV_MARK + "\n");
		if (at < 0) {
			return env;
		}
		for (String entry : out.substring(at + ENV_MARK.length() + 1).split("\0")) {
			int eq = entry.indexOf('=');
			if (eq > 0) {
				env.put(entry.substring(0, eq), entry.substring(eq + 1));
			}
		}
		return env;
	}

	/**
	 * The game's own switches that must not reach the Foreman: the DevBridge's, the client's sound/focus/world switches
	 * and the launcher's own. Other {@code AGENTCRAFT_*} (a lead model, the backend, the home) reach it, as they would
	 * from a terminal; the command line's flags still win over them.
	 */
	static boolean gameOnly(String key) {
		String k = key.toUpperCase(Locale.ROOT);
		if (k.startsWith("AGENTCRAFT_DEV") || k.startsWith("AGENTCRAFT_LAUNCHER") || k.startsWith("AGENTCRAFT_WORLD")) {
			return true;
		}
		return switch (k) {
			case "AGENTCRAFT_MUTE", "AGENTCRAFT_FOCUS", "AGENTCRAFT_AUTOWORLD", "AGENTCRAFT_SHOTS_DIR", "AGENTCRAFT_FOREMAN", "AGENTCRAFT_FOREMAN_DIR",
				"AGENTCRAFT_NODE", "AGENTCRAFT_PLAYER" -> true;
			default -> false;
		};
	}

	/** Shell bookkeeping that is not the user's environment. */
	static boolean shellInternal(String key) {
		return key.equals("_") || key.equals("SHLVL") || key.equals("PWD") || key.equals("OLDPWD") || key.startsWith("__") || key.equals("MISE_SHELL")
			|| key.equals("PS1") || key.equals("PROMPT") || key.equals("RPROMPT") || key.equals("FPATH") || key.equals("TERM_SESSION_ID");
	}

	/**
	 * The Foreman's environment. Base: the game's, without the {@link #gameOnly} switches. Added: the login shell's
	 * variables the game lacks (e.g. {@code DOTNET_ROOT}, {@code GOROOT}), except game-only ones. PATH: node's folder,
	 * the login shell's PATH, the usual Unix fallbacks (mise shims, Homebrew, /usr/local/bin, ~/.local/bin), then the
	 * game's PATH, without duplicates; agents run git, gh, dotnet, cargo and friends through it. Nothing secret is added
	 * here: the Foreman reads its own config.
	 */
	public static Map<String, String> environment(Map<String, String> game, Map<String, String> login, Path node, String home, boolean windows) {
		Map<String, String> env = new HashMap<>();
		for (Map.Entry<String, String> e : game.entrySet()) {
			if (!gameOnly(e.getKey())) {
				env.put(e.getKey(), e.getValue());
			}
		}
		for (Map.Entry<String, String> e : login.entrySet()) {
			String k = e.getKey();
			if (!k.equalsIgnoreCase("PATH") && !shellInternal(k) && !gameOnly(k) && !env.containsKey(k)) {
				env.put(k, e.getValue());
			}
		}
		String key = windows ? pathKey(game) : "PATH";
		String sep = windows ? ";" : ":";
		LinkedHashSet<String> dirs = new LinkedHashSet<>();
		Path nodeDir = node.getParent();
		if (nodeDir != null) {
			dirs.add(nodeDir.toString());
		}
		addPath(dirs, login.get("PATH"), sep);
		if (!windows) {
			dirs.add(Path.of(home, ".local/share/mise/shims").toString());
			dirs.add("/opt/homebrew/bin");
			dirs.add("/usr/local/bin");
			dirs.add(Path.of(home, ".local/bin").toString());
		}
		addPath(dirs, game.get(key), sep);
		if (!windows) {
			for (String d : List.of("/usr/bin", "/bin", "/usr/sbin", "/sbin")) {
				dirs.add(d);
			}
		}
		env.put(key, String.join(sep, dirs));
		return env;
	}

	/** Windows env keys are case-insensitive ("Path"). */
	static String pathKey(Map<String, String> env) {
		for (String k : env.keySet()) {
			if (k.equalsIgnoreCase("PATH")) {
				return k;
			}
		}
		return "PATH";
	}

	private static void addPath(Set<String> dirs, @Nullable String path, String sep) {
		if (path == null) {
			return;
		}
		for (String d : path.split(Pattern.quote(sep))) {
			if (!d.isBlank()) {
				dirs.add(d.strip());
			}
		}
	}

	// ------------------------------------------------------------------ state

	/** The launcher's state for the HUD pill, the console, the toasts and {@code dev.launcher.state} (wire names: {@link #wire}). */
	public enum State {
		/** Not looked yet. */
		IDLE,
		/** {@code launcher.enabled} false or {@code AGENTCRAFT_LAUNCHER=0}. */
		DISABLED,
		/** Looking at the port and the run files (a second or two). */
		CHECKING,
		/** No node 22+ found (with how to install it). */
		NODE_MISSING,
		/** No checkout to run the Foreman from, or the configured one is unusable. */
		NO_SOURCE,
		/** {@code npm ci} in foreman/. */
		INSTALLING,
		/** Spawned (or restarting), waiting for the port. */
		STARTING,
		/** A current Foreman answers (ours or reused). */
		RUNNING,
		/** A Foreman we did not start answers, from another checkout or commit (never stopped by us). */
		RUNNING_OLDER,
		/** Ours exited or never answered (the log's last lines). */
		CRASHED,
		/** The port or the profile is held by something we did not start. */
		BLOCKED,
		/** Ours was stopped (Stop, or the game is closing). */
		STOPPED;

		public String wire() {
			return name().toLowerCase(Locale.ROOT).replace('_', '-');
		}
	}

	/** The last {@code n} non-blank lines of a log. */
	public static List<String> tail(List<String> lines, int n) {
		List<String> nonBlank = new ArrayList<>();
		for (String l : lines) {
			if (!l.isBlank()) {
				nonBlank.add(l);
			}
		}
		return List.copyOf(nonBlank.subList(Math.max(0, nonBlank.size() - n), nonBlank.size()));
	}
}
