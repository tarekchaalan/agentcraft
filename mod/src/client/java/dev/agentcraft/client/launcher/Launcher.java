package dev.agentcraft.client.launcher;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.agentcraft.AgentCraft;
import dev.agentcraft.client.ClientEnv;
import dev.agentcraft.client.dev.DevBridge;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanJson;
import dev.agentcraft.client.foreman.ForemanListener;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.Protocol;
import dev.agentcraft.client.hud.Toasts;
import dev.agentcraft.launcher.LauncherConfig;
import dev.agentcraft.launcher.LauncherPlan;
import dev.agentcraft.launcher.LauncherPlan.Action;
import dev.agentcraft.launcher.LauncherPlan.Owner;
import dev.agentcraft.launcher.LauncherPlan.Probe;
import dev.agentcraft.launcher.LauncherPlan.Running;
import dev.agentcraft.launcher.LauncherPlan.State;
import dev.agentcraft.launcher.LauncherProcs;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/**
 * Starts the Foreman with the game, so a player needs neither a terminal nor tools/launch.ps1 (mod/DEV.md "Foreman
 * launcher"). On client start, on its own worker thread (never the render thread):
 * <ol>
 *   <li>reads the {@code launcher} section of {@code <home>/config.json} ({@link LauncherConfig});</li>
 *   <li>picks the checkout to run from ({@link LauncherPlan#source}) and asks git for its commit;</li>
 *   <li>probes the port: {@code hello}, as the link does; an answer with a snapshot is a Foreman. Its identity (pid,
 *       profile, checkout, commit) comes from its run file {@code <home>/<profile>/foreman.json}, who started it from
 *       our record or the launch scripts' run files;</li>
 *   <li>decides ({@link LauncherPlan#decide}): reuse a current one, leave one it did not start alone (never stopped, even
 *       when older: "running (older version)"), restart its own stale one, or start one;</li>
 *   <li>starting: finds node 22+, runs {@code npm ci} in foreman/ when node_modules is missing or {@code package-lock.json}
 *       changed since its last install (not while another Foreman of the checkout runs on it), and starts the Foreman in
 *       the background with its output in {@code <checkout>/artifacts/logs/foreman-launcher-<profile>.log}: on Windows
 *       hidden in its own console through tools/lib/bgrun.mjs, as tools/launch.ps1 does; elsewhere detached through node
 *       with the login shell's environment. It records what it started (pid and start time) in
 *       {@code <home>/<profile>/launcher.json}.</li>
 * </ol>
 * The Foreman keeps running after the game exits (agents keep working) unless {@code launcher.stopOnExit} is set, and
 * then only when this launcher started it. tools/launch.ps1 and tools/launch-unix.mjs start their own Foreman before the game,
 * so the launcher finds it and reuses it; with {@code -NoForeman} / {@code --no-foreman} they turn the launcher off.
 */
public final class Launcher {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "AgentCraft-Launcher");
		t.setDaemon(true);
		return t;
	});
	private static final long START_TIMEOUT_MS = 120_000;
	private static final long LOG_ROTATE_BYTES = 5L << 20;

	// settings (config.json is read again on every launch)
	private static volatile LauncherConfig config = LauncherConfig.DEFAULT;
	private static final boolean LINK_ON = ClientEnv.flag("AGENTCRAFT_FOREMAN", true);
	private static final int PORT = ClientEnv.intValue("AGENTCRAFT_PORT", 7878);
	private static final String BACKEND = backendSetting();
	private static final String PROFILE = profileSetting(BACKEND);

	// what the HUD, the console and dev.launcher.state show
	private static volatile State state = State.IDLE;
	private static volatile String detail = "";
	private static volatile List<String> logTail = List.of();
	private static volatile @Nullable String installLine;
	private static volatile @Nullable String installNote;
	private static volatile LauncherPlan.@Nullable Source source;
	private static volatile @Nullable Path node;
	private static volatile @Nullable String nodeVersion;
	private static volatile @Nullable String expectedCommit;
	private static volatile long pid;
	private static volatile long wrapperPid;
	private static volatile @Nullable String spawnHow;
	private static volatile boolean ours;
	private static volatile boolean startedThisSession;
	private static volatile @Nullable String runningVersion;
	private static volatile @Nullable String runningRoot;
	private static volatile @Nullable String runningCommit;
	private static volatile @Nullable String runningBy;
	private static volatile List<String> stale = List.of();
	private static volatile @Nullable Action lastAction;
	private static volatile long changedAt = System.currentTimeMillis();
	private static volatile boolean stopping;
	private static volatile long watchGen;
	private static @Nullable Map<String, String> loginEnv;

	private Launcher() {
	}

	public static void init() {
		config = loadConfig();
		ClientLifecycleEvents.CLIENT_STARTED.register(mc -> {
			if (!LINK_ON) {
				set(State.DISABLED, "the Foreman link is off (AGENTCRAFT_FOREMAN=0)");
			} else if (config.enabled()) {
				start();
			} else {
				set(State.DISABLED, ClientEnv.raw("AGENTCRAFT_LAUNCHER") != null ? "off (AGENTCRAFT_LAUNCHER=0); /foreman start still starts one"
					: "off (launcher.enabled is false in config.json); /foreman start still starts one");
			}
		});
		ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> onGameExit());
		if (Foreman.state() != null) {
			Foreman.state().addListener(new ForemanListener() {
				@Override
				public void onSnapshot(ForemanState s) {
					// the link is up again: a Foreman came back after the launcher saw ours crash or one stop
					if (state == State.STOPPED || state == State.CRASHED) {
						WORKER.execute(() -> run(Launcher::relink));
					}
				}
			});
		}
		registerDev();
	}

	/**
	 * After the link came back while the launcher said stopped or crashed: who runs on the port now (someone restarted a
	 * Foreman by hand or with a launch script, or ours opened its port after the start timeout). Starts nothing; ours
	 * only when our record still names that very process.
	 */
	private static void relink() {
		if (state != State.STOPPED && state != State.CRASHED || probe() != Probe.FOREMAN) {
			return;
		}
		Path root = source == null ? null : source.root();
		Running r = readIdentity(root);
		if (r == null) {
			return;
		}
		JsonObject rec = LauncherProcs.readJson(recordFile());
		ours = num(rec, "pid") == r.pid() && LauncherPlan.sameProcess(LauncherProcs.startMs(r.pid()), num(rec, "startedAt"));
		pid = r.pid();
		stale = root == null ? List.of() : LauncherPlan.staleReasons(r.root(), r.commit(), root.toString(), expectedCommit);
		if (ours) {
			set(State.RUNNING, oursDetail(""));
		} else {
			set(stale.isEmpty() ? State.RUNNING : State.RUNNING_OLDER, (stale.isEmpty() ? "" : "running (older version): " + String.join("; ", stale)
				+ ". ") + "Reused: started by " + who() + "; " + where());
		}
		watch(pid);
	}

	// ------------------------------------------------------------------ reads (any thread)

	public static State state() {
		return state;
	}

	public static String detail() {
		return detail;
	}

	public static List<String> logTail() {
		return logTail;
	}

	public static @Nullable String installLine() {
		return installLine;
	}

	public static @Nullable String installNote() {
		return installNote;
	}

	public static long pid() {
		return pid;
	}

	/** Whether the running Foreman was started by this launcher (this session or an earlier one). */
	public static boolean ours() {
		return ours;
	}

	public static List<String> stale() {
		return stale;
	}

	public static LauncherConfig config() {
		return config;
	}

	public static LauncherPlan.@Nullable Source source() {
		return source;
	}

	/** The Foreman log of the current source ({@code <checkout>/artifacts/logs/foreman-launcher-<profile>.log}), or null. */
	public static @Nullable Path logFile() {
		LauncherPlan.Source s = source;
		return s == null || s.root() == null ? null : s.root().resolve("artifacts").resolve("logs").resolve("foreman-launcher-" + PROFILE + ".log");
	}

	private static @Nullable Path npmLog() {
		LauncherPlan.Source s = source;
		return s == null || s.root() == null ? null : s.root().resolve("artifacts").resolve("logs").resolve("foreman-launcher-npm.log");
	}

	/** Looking, installing or starting: a second Start would only queue behind it. */
	public static boolean busy() {
		return state == State.CHECKING || state == State.INSTALLING || state == State.STARTING;
	}

	// ------------------------------------------------------------------ control

	/** Runs the launch sequence on the worker thread (client start, /foreman start). */
	public static void start() {
		WORKER.execute(() -> run(() -> launch(false)));
	}

	/** Restart: stops a Foreman this launcher started (never one it reused), then launches again. */
	public static void restart() {
		WORKER.execute(() -> run(() -> launch(true)));
	}

	/** Stops a Foreman this launcher started; anything else is left alone. */
	public static void stop() {
		WORKER.execute(() -> run(() -> {
			if (!ours || pid <= 0) {
				set(state, "nothing to stop: the running Foreman was not started by the game");
				return;
			}
			long was = pid;
			if (stopOurs("Stop", 8_000, false)) {
				set(State.STOPPED, "stopped the Foreman (pid " + was + "); /foreman start starts it again");
			} else {
				set(State.CRASHED, "could not stop the Foreman (pid " + was + "); see the game log");
			}
		}));
	}

	private interface Step {
		void run() throws Exception;
	}

	private static void run(Step s) {
		try {
			s.run();
		} catch (Throwable t) {
			AgentCraft.LOGGER.error("Foreman launcher failed", t);
			set(State.CRASHED, "launcher error: " + t);
		}
	}

	// ------------------------------------------------------------------ the sequence

	private static void launch(boolean forceRestart) throws Exception {
		if (!LINK_ON) {
			set(State.DISABLED, "the Foreman link is off (AGENTCRAFT_FOREMAN=0): a Foreman started now would never be used");
			return;
		}
		stopping = false;
		config = loadConfig();
		for (String p : config.problems()) {
			AgentCraft.LOGGER.warn("Foreman launcher: config.json: {}", p);
		}
		set(State.CHECKING, "looking for a Foreman on :" + PORT);
		String home = home().toString();
		LauncherPlan.Source src = LauncherPlan.source(
			LauncherPlan.sourceCandidates(config.foremanDirOverride(), config.foremanDir(), devRunCheckout(), buildCheckout()),
			System.getProperty("user.home", ""), Files::exists);
		Path root = src.root() == null ? null : realpath(src.root());
		source = src = root == null ? src : new LauncherPlan.Source(root, src.origin(), null);
		AgentCraft.LOGGER.info("Foreman launcher: source {} ({}), profile {}, port {}, home {}", root, src.origin(), PROFILE, PORT, home);
		Path conflict = root == null || ClientEnv.DEV_RUN ? null : devConflict(root);
		if (root == null || conflict != null) {
			String problem = root == null ? String.valueOf(src.problem()) : "refusing to run the Foreman from " + root + ": it is (inside) " + conflict
				+ ", a repository the agents work on. Set launcher.foremanDir to a separate checkout";
			// nothing the launcher may start from, but a running Foreman is still worth reporting (and the link uses it)
			if (probe() == Probe.FOREMAN) {
				Running r = readIdentity(root);
				ours = false;
				pid = r == null ? 0 : r.pid();
				stale = List.of();
				set(State.RUNNING, "reused: started by " + who() + "; " + where() + " (the game could not start one: " + problem + ")");
				watch(pid);
			} else {
				set(State.NO_SOURCE, problem);
			}
			return;
		}
		expectedCommit = gitHead(root);

		// what runs on the port, and whose is it
		Probe probe = probe();
		Running running = readIdentity(root);
		JsonObject record = LauncherProcs.readJson(recordFile());
		long recordPid = num(record, "pid");
		long recordStart = num(record, "startedAt");
		boolean recordAlive = recordPid > 0 && LauncherPlan.sameProcess(LauncherProcs.startMs(recordPid), recordStart);
		if (record != null && !recordAlive) {
			clearRecord(recordPid); // what it names is gone (or the pid belongs to another process now)
		}
		Owner owner = LauncherPlan.owner(recordPid, recordStart, recordAlive, running);
		ours = owner == Owner.OURS;
		long ourPid = ours ? recordPid : 0;
		if (ours) {
			wrapperPid = num(record, "wrapperPid");
			spawnHow = record == null ? null : str(record, "how");
		}
		List<String> reasons = probe != Probe.FOREMAN ? List.of()
			: running != null ? LauncherPlan.staleReasons(running.root(), running.commit(), root.toString(), expectedCommit)
			: List.of("its run file was not found (another AGENTCRAFT_HOME?)");
		stale = reasons;
		Running busy = profileElsewhere(home);
		Action action = LauncherPlan.decide(probe, ours, reasons, busy != null, forceRestart);
		lastAction = action;
		AgentCraft.LOGGER.info("Foreman launcher: port {} {}, run file pid {}, record pid {} (alive {}), {}, stale {}, expected commit {}: {}", PORT,
			probe, running == null ? "-" : running.pid(), recordPid, recordAlive, owner, reasons, LauncherPlan.shortSha(expectedCommit), action);
		switch (action) {
			case REUSE -> {
				pid = running != null ? running.pid() : ourPid;
				set(State.RUNNING, ours ? oursDetail(startedThisSession ? "" : "started by the game earlier; ") : "reused: started by " + who() + "; "
					+ where());
				watch(pid);
				return;
			}
			case REUSE_OLDER -> {
				pid = running != null ? running.pid() : 0;
				set(State.RUNNING_OLDER, "running (older version): " + String.join("; ", reasons) + ". Started by " + who()
					+ ", so the game leaves it alone; restart it with the tool that started it");
				watch(pid);
				return;
			}
			case PORT_TAKEN -> {
				pid = 0;
				set(State.BLOCKED, "port " + PORT + " is in use by something that is not an AgentCraft Foreman (or does not answer); the game did not"
					+ " start it and leaves it alone. Free the port or set AGENTCRAFT_PORT");
				return;
			}
			case PROFILE_BUSY -> {
				pid = busy.pid();
				set(State.BLOCKED, "profile \"" + PROFILE + "\" is already running on another port (pid " + busy.pid() + ", see " + foremanJson(home)
					+ "); the game connects to :" + PORT + ". Stop that Foreman or start the game with AGENTCRAFT_PORT=<its port>");
				return;
			}
			case RESTART -> {
				pid = ourPid;
				set(State.STARTING, forceRestart ? "restarting the Foreman (pid " + ourPid + ")" : "restarting the Foreman (pid " + ourPid + "): "
					+ (reasons.isEmpty() ? "it does not answer" : String.join("; ", reasons)));
				if (!stopOurs("restart", 8_000, false)) {
					set(State.CRASHED, "could not stop the Foreman (pid " + ourPid + ") to restart it; see the game log");
					return;
				}
				stopping = false;
			}
			case START -> {
			}
		}
		spawn(root, home);
	}

	private static void spawn(Path root, String home) throws Exception {
		Path nodePath = findNode();
		if (nodePath == null) {
			set(State.NODE_MISSING, "Node.js " + LauncherPlan.MIN_NODE + " or newer was not found. Install it (" + LauncherPlan.NODE_INSTALL_URL
				+ ") or set launcher.nodePath in " + configFile() + " (or AGENTCRAFT_NODE); then /foreman start");
			return;
		}
		node = nodePath;
		Map<String, String> env = LauncherPlan.environment(System.getenv(), loginEnv(), nodePath, System.getProperty("user.home", ""), windows());
		installNote = null;
		if (needsInstall(root)) {
			String user = LauncherPlan.depsReady(root, Files::exists) ? othersOnCheckout(root, home) : null;
			if (user != null) {
				// replacing node_modules under a running Foreman breaks it (Windows refuses to delete files in use): as tools/launch.ps1
				installNote = "not reinstalling foreman/node_modules (package-lock.json changed): " + user + " runs on it";
				AgentCraft.LOGGER.warn("Foreman launcher: {}", installNote);
			} else if (!install(root, nodePath, env)) {
				return;
			}
		}
		Path log = logFile();
		Files.createDirectories(log.getParent());
		rotate(log);
		Files.writeString(log, "[agentcraft launcher " + Instant.now() + "] starting the Foreman: profile " + PROFILE + ", port " + PORT + ", home "
			+ home + ", backend " + BACKEND + ", commit " + LauncherPlan.shortSha(expectedCommit) + "\n", StandardCharsets.UTF_8,
			StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		List<String> args = LauncherPlan.foremanArgs(BACKEND, PROFILE, home, PORT);
		Path cwd = LauncherPlan.foremanDir(root);
		set(State.STARTING, "starting the Foreman (" + BACKEND + ", profile " + PROFILE + ")");
		long before = System.currentTimeMillis();
		Path bgrun = LauncherPlan.bgrun(root);
		LauncherProcs.Started st = windows() && Files.isRegularFile(bgrun)
			? LauncherProcs.startHidden(nodePath, bgrun, args, cwd, log, root.resolve("artifacts").resolve("run"), "mod-launcher-" + PROFILE, env,
				System.getenv("SystemRoot"), 20_000)
			: LauncherProcs.startDetached(nodePath, args, cwd, log, env, root, 20_000);
		if (!st.ok()) {
			logTail = readTail(log, 20);
			set(State.CRASHED, "could not start the Foreman: " + st.error());
			return;
		}
		long newPid = st.pid();
		long started = LauncherProcs.startMs(newPid);
		pid = newPid;
		wrapperPid = st.wrapperPid();
		spawnHow = st.how();
		ours = true;
		startedThisSession = true;
		stale = List.of();
		runningRoot = root.toString();
		runningCommit = expectedCommit;
		runningBy = LauncherPlan.BY;
		writeRecord(newPid, started > 0 ? started : before, st, root, home, log, expectedCommit);
		AgentCraft.LOGGER.info("Foreman launcher: started the Foreman (pid {}, {}) from {}; log {}", newPid, st.how(), root, log);
		long deadline = System.currentTimeMillis() + START_TIMEOUT_MS;
		while (System.currentTimeMillis() < deadline) {
			if (!LauncherProcs.alive(newPid)) {
				if (probe() == Probe.FOREMAN) {
					// another launcher (tools/launch.ps1, a terminal) won the race for the port: use its Foreman
					clearRecord(newPid);
					ours = false;
					startedThisSession = false;
					Running r = readIdentity(root);
					pid = r == null ? 0 : r.pid();
					stale = r == null ? List.of() : LauncherPlan.staleReasons(r.root(), r.commit(), root.toString(), expectedCommit);
					set(stale.isEmpty() ? State.RUNNING : State.RUNNING_OLDER, "another launcher started the Foreman first; reusing it (started by "
						+ who() + ")");
					watch(pid);
					return;
				}
				logTail = readTail(log, 20);
				set(State.CRASHED, "the Foreman exited during startup; last lines of " + log.getFileName());
				return;
			}
			if (portOpen()) {
				break;
			}
			Thread.sleep(400);
		}
		if (!portOpen()) {
			logTail = readTail(log, 20);
			set(State.CRASHED, "the Foreman did not open :" + PORT + " within " + START_TIMEOUT_MS / 1000 + " s (still starting? /foreman shows its log)");
			watch(newPid);
			return;
		}
		probe();
		if (Foreman.link() != null) {
			Foreman.link().reconnectNow();
		}
		set(State.RUNNING, oursDetail(""));
		watch(newPid);
	}

	/** Whether foreman/ needs {@code npm ci} ({@link LauncherPlan#needsInstall}: not installed, or the lock file changed since). */
	private static boolean needsInstall(Path root) throws IOException {
		Path lock = LauncherPlan.lockFile(root);
		Path marker = LauncherPlan.depsMarker(root);
		return LauncherPlan.needsInstall(LauncherPlan.depsReady(root, Files::exists),
			Files.exists(lock) ? LauncherPlan.lockHash(Files.readAllBytes(lock)) : null, Files.exists(marker) ? Files.readString(marker) : null);
	}

	/**
	 * {@code npm ci} in foreman/ (first use, and again when {@code package-lock.json} changed), its output in
	 * artifacts/logs/foreman-launcher-npm.log; on success the lock's hash goes into node_modules ({@link LauncherPlan#depsMarker}).
	 */
	private static boolean install(Path root, Path nodePath, Map<String, String> env) throws IOException, InterruptedException {
		Path dir = LauncherPlan.foremanDir(root);
		Path npmCli = LauncherPlan.npmCli(nodePath, Files::isRegularFile);
		if (npmCli == null) {
			set(State.CRASHED, "npm was not found next to " + nodePath + " (node_modules/npm/bin/npm-cli.js); install Node.js from " + LauncherPlan.NODE_INSTALL_URL
				+ ", or run `npm ci` in " + dir + " yourself and /foreman start");
			return false;
		}
		List<String> cmd = LauncherPlan.installCommand(nodePath, npmCli);
		Path lock = LauncherPlan.lockFile(root);
		byte[] installing = Files.exists(lock) ? Files.readAllBytes(lock) : null; // read before: what npm ci installs
		set(State.INSTALLING, "installing the Foreman's npm packages (npm ci in " + dir + (LauncherPlan.depsReady(root, Files::exists)
			? ", package-lock.json changed)" : ", first use)"));
		Path log = npmLog();
		Files.createDirectories(log.getParent());
		ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true);
		pb.environment().clear();
		pb.environment().putAll(env);
		Process p = pb.start();
		p.getOutputStream().close();
		List<String> lines = new ArrayList<>();
		try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
			for (String line; (line = r.readLine()) != null;) {
				lines.add(line);
				if (!line.isBlank()) {
					installLine = line.strip();
				}
			}
		}
		int code = p.waitFor();
		List<String> logged = new ArrayList<>();
		logged.add("[agentcraft launcher " + Instant.now() + "] " + String.join(" ", cmd) + " in " + dir + " -> exit " + code);
		logged.addAll(lines);
		Files.write(log, logged, StandardCharsets.UTF_8);
		installLine = null;
		if (code != 0) {
			logTail = LauncherPlan.tail(lines, 20);
			set(State.CRASHED, "npm ci failed in " + dir + " (exit " + code + "); see " + log);
			return false;
		}
		if (installing != null) {
			Files.writeString(LauncherPlan.depsMarker(root), LauncherPlan.lockHash(installing) + "\n", StandardCharsets.UTF_8);
		}
		return true;
	}

	/**
	 * Another live Foreman of this checkout (so on its foreman/node_modules), described, or null: launch.ps1's and
	 * launch-unix.mjs's run files in the checkout, and the run files of every profile in the home that name this checkout.
	 */
	private static @Nullable String othersOnCheckout(Path root, String home) {
		Path run = root.resolve("artifacts").resolve("run");
		for (Path f : list(run)) {
			String name = f.getFileName().toString();
			boolean ps = name.startsWith("foreman-") && name.endsWith(".json") && !name.endsWith(".spec.json") && !name.endsWith(".status.json");
			boolean unix = name.startsWith("unix-foreman-") && name.endsWith(".json");
			long p = num(LauncherProcs.readJson(f), "pid");
			if ((ps || unix) && LauncherProcs.alive(p)) {
				return "the Foreman pid " + p + " (" + (ps ? "tools\\launch.ps1" : "tools/launch-unix.mjs") + ")";
			}
		}
		for (Path dir : list(Path.of(home))) {
			JsonObject o = LauncherProcs.readJson(dir.resolve("foreman.json"));
			long p = num(o, "pid");
			String r = o == null ? null : str(o, "root");
			if (r != null && LauncherProcs.alive(p) && realpath(Path.of(r)).equals(root)) {
				return "the Foreman pid " + p + " (profile " + str(o, "profile") + ")";
			}
		}
		return null;
	}

	// ------------------------------------------------------------------ exits

	/** Follows the Foreman's pid: an exit is a crash (ours), another Foreman taking its place, or the end of a reused one. */
	private static void watch(long p) {
		long gen = ++watchGen;
		if (p <= 0) {
			return;
		}
		ProcessHandle.of(p).ifPresent(h -> h.onExit().thenRun(() -> WORKER.execute(() -> run(() -> exited(p, gen)))));
	}

	private static void exited(long oldPid, long gen) throws Exception {
		if (gen != watchGen || stopping) {
			return;
		}
		boolean wasOurs = ours;
		String oldBy = who();
		if (wasOurs) {
			clearRecord(oldPid);
		}
		// someone may start another Foreman in its place (tools/launch.ps1, a terminal): it takes over the run file within
		// seconds. Never ours, even from our checkout: the Foreman does not restart itself.
		String home = home().toString();
		Path root = source == null ? null : source.root();
		for (int i = 0; i < 30 && gen == watchGen && !stopping; i++) {
			Running r = runFileOnPort(home);
			if (r != null && r.pid() != oldPid && isProfileForeman(r)) {
				Running id = readIdentity(root);
				ours = false;
				pid = r.pid();
				stale = root == null || id == null ? List.of() : LauncherPlan.staleReasons(id.root(), id.commit(), root.toString(), expectedCommit);
				AgentCraft.LOGGER.info("Foreman launcher: pid {} exited, pid {} took over (started by {})", oldPid, r.pid(), who());
				set(stale.isEmpty() ? State.RUNNING : State.RUNNING_OLDER, "the Foreman (pid " + oldPid + ") exited and pid " + r.pid()
					+ " took its place; reused: started by " + who() + "; " + where());
				watch(r.pid());
				return;
			}
			Thread.sleep(500);
		}
		if (gen != watchGen || stopping) {
			return;
		}
		if (wasOurs) {
			Path log = logFile();
			logTail = log == null ? List.of() : readTail(log, 20);
			set(State.CRASHED, "the Foreman (pid " + oldPid + ") exited; last lines of " + (log == null ? "its log" : log.getFileName())
				+ "; /foreman start starts it again");
		} else {
			set(State.STOPPED, "the Foreman (pid " + oldPid + ", started by " + oldBy + ") exited; /foreman start starts one");
		}
		pid = 0;
	}

	/** On client exit: stop the Foreman only with {@code launcher.stopOnExit} and only when this launcher started it. */
	private static void onGameExit() {
		if (!config.stopOnExit() || !ours || pid <= 0) {
			return;
		}
		stopOurs("game closing (launcher.stopOnExit)", 0, true);
	}

	/**
	 * Stops the Foreman our record names, after checking it is that process (pid and start time): the stop the Foreman
	 * saves its state on ({@link LauncherProcs#requestStop}: Ctrl+Break into its console on Windows, SIGTERM elsewhere),
	 * then after {@code graceMs} a forced kill of it and everything it started. At game exit only the request is sent: the
	 * Foreman finishes by itself (it force-exits after 8 s) and the game does not wait for it. True when it is gone (or
	 * asked to go, at exit).
	 */
	private static boolean stopOurs(String why, long graceMs, boolean atExit) {
		stopping = true;
		watchGen++;
		JsonObject record = LauncherProcs.readJson(recordFile());
		long p = pid;
		if (p <= 0 || num(record, "pid") != p || !LauncherPlan.sameProcess(LauncherProcs.startMs(p), num(record, "startedAt"))) {
			AgentCraft.LOGGER.warn("Foreman launcher: not stopping pid {} ({}): it is not the process our record names", p, why);
			return !LauncherProcs.alive(p);
		}
		AgentCraft.LOGGER.info("Foreman launcher: stopping the Foreman we started (pid {}): {}", p, why);
		boolean asked = LauncherProcs.requestStop(p, windows(), System.getenv("SystemRoot"), atExit ? 4_000 : 15_000);
		if (atExit) {
			return asked;
		}
		if (!asked || !LauncherProcs.waitExit(p, graceMs)) {
			AgentCraft.LOGGER.warn("Foreman launcher: pid {} did not stop {}; killing it and its children", p, asked ? "within " + graceMs + " ms"
				: "on request");
			LauncherProcs.killTree(p);
			LauncherProcs.waitExit(p, 3_000);
		}
		if (LauncherProcs.alive(p)) {
			return false;
		}
		clearRecord(p);
		pid = 0;
		return true;
	}

	// ------------------------------------------------------------------ records

	/** Our own record: {@code <home>/<profile>/launcher.json} (kept per profile, so a change of checkout keeps it). */
	private static Path recordFile() {
		return home().resolve(PROFILE).resolve("launcher.json");
	}

	private static void writeRecord(long p, long startedAt, LauncherProcs.Started st, Path root, String home, Path log, @Nullable String commit)
		throws IOException {
		JsonObject r = new JsonObject();
		r.addProperty("pid", p);
		r.addProperty("startedAt", startedAt);
		r.addProperty("startedAtIso", Instant.ofEpochMilli(startedAt).toString());
		r.addProperty("wrapperPid", st.wrapperPid());
		r.addProperty("how", st.how());
		r.addProperty("root", root.toString());
		r.addProperty("commit", commit);
		r.addProperty("port", PORT);
		r.addProperty("profile", PROFILE);
		r.addProperty("backend", BACKEND);
		r.addProperty("home", home);
		r.addProperty("log", log.toString());
		r.addProperty("gamePid", ProcessHandle.current().pid());
		r.addProperty("by", LauncherPlan.BY);
		writeJson(recordFile(), r);
	}

	private static void clearRecord(long p) {
		try {
			JsonObject rec = LauncherProcs.readJson(recordFile());
			if (rec == null || num(rec, "pid") == p) {
				Files.deleteIfExists(recordFile());
			}
		} catch (IOException ignored) {
			// best effort
		}
	}

	// ------------------------------------------------------------------ identity

	/**
	 * The Foreman on our port: the run file (profile's, home's, any profile's) whose port is ours and whose pid is alive;
	 * its checkout and commit from the run file (Foremen write them since this launcher). Who started it: our record,
	 * else the launch scripts' run files in the checkout ({@code artifacts/run/foreman-<profile>-*.json} for
	 * tools/launch.ps1, {@code artifacts/run/unix-foreman-<profile>.json} for tools/launch-unix.mjs; that name predates the
	 * script's rename from tools/unix.mjs and is kept, so a Foreman the old script started is still recognised). Fills
	 * the running* fields.
	 * Null when none.
	 */
	private static @Nullable Running readIdentity(@Nullable Path root) {
		Running r = runFileOnPort(home().toString());
		runningRoot = null;
		runningCommit = null;
		runningBy = null;
		if (r == null) {
			return null;
		}
		String by = null;
		JsonObject rec = LauncherProcs.readJson(recordFile());
		if (num(rec, "pid") == r.pid()) {
			by = LauncherPlan.BY;
		}
		List<Path> roots = new ArrayList<>();
		if (root != null) {
			roots.add(root);
		}
		if (r.root() != null && (root == null || !Path.of(r.root()).equals(root))) {
			roots.add(Path.of(r.root()));
		}
		for (Path co : roots) {
			if (by != null) {
				break;
			}
			by = launcherScriptFor(co, r.pid());
		}
		runningRoot = r.root();
		runningCommit = r.commit();
		runningBy = by;
		return new Running(r.pid(), r.profile(), r.root(), r.commit(), by);
	}

	/** Which launch script's run file in {@code checkout/artifacts/run} names {@code pid}, or null. */
	private static @Nullable String launcherScriptFor(Path checkout, long p) {
		for (Path f : list(checkout.resolve("artifacts").resolve("run"))) {
			String name = f.getFileName().toString();
			boolean ps = name.startsWith("foreman-" + PROFILE + "-") && name.endsWith(".json") && !name.endsWith(".spec.json")
				&& !name.endsWith(".status.json");
			boolean unix = name.equals("unix-foreman-" + PROFILE + ".json");
			if ((ps || unix) && num(LauncherProcs.readJson(f), "pid") == p) {
				return ps ? "tools\\launch.ps1" : "tools/launch-unix.mjs";
			}
		}
		return null;
	}

	private static @Nullable Running runFileOnPort(String home) {
		List<Path> files = new ArrayList<>();
		Path h = Path.of(home);
		files.add(h.resolve(PROFILE).resolve("foreman.json"));
		files.add(h.resolve("foreman.json"));
		for (Path d : list(h)) {
			if (Files.isDirectory(d)) {
				files.add(d.resolve("foreman.json"));
			}
		}
		for (Path f : files) {
			JsonObject o = LauncherProcs.readJson(f);
			if (o == null || !o.has("port") || num(o, "port") != PORT) {
				continue;
			}
			long p = num(o, "pid");
			if (LauncherProcs.alive(p)) {
				return running(o, p);
			}
		}
		return null;
	}

	/** A run file as a {@link Running}; its checkout as a real path (Java's and node's spelling of a path may differ). */
	private static Running running(JsonObject o, long p) {
		String root = str(o, "root");
		return new Running(p, str(o, "profile"), root == null ? null : realpath(Path.of(root)).toString(), str(o, "commit"), null);
	}

	/** The profile's Foreman when it runs on another port than ours (a second one would be refused), else null. */
	private static @Nullable Running profileElsewhere(String home) {
		JsonObject o = LauncherProcs.readJson(foremanJson(home));
		if (o == null || !o.has("port") || num(o, "port") == PORT) {
			return null;
		}
		long p = num(o, "pid");
		Running r = running(o, p);
		return LauncherProcs.alive(p) && isProfileForeman(r) ? r : null;
	}

	/**
	 * Whether the process a run file names is a Foreman of our profile: its command line says so, or, where the OS does
	 * not show command lines (Windows: Java has no arguments there), the run file's profile is ours.
	 */
	private static boolean isProfileForeman(Running r) {
		String cmd = commandLine(r.pid());
		if (cmd != null && cmd.contains("main.ts")) {
			return LauncherPlan.isForemanCommand(cmd, PROFILE);
		}
		return PROFILE.equals(r.profile());
	}

	private static Path foremanJson(String home) {
		return Path.of(home).resolve(PROFILE).resolve("foreman.json");
	}

	private static String where() {
		return "profile " + PROFILE + " on :" + PORT;
	}

	private static String oursDetail(String prefix) {
		return prefix + where() + "; " + (config.stopOnExit() ? "stops when the game exits (launcher.stopOnExit)" : "keeps running after the game exits");
	}

	private static String who() {
		String by = runningBy;
		if (by == null) {
			return "something else (a terminal or another launcher)";
		}
		return by.equals(LauncherPlan.BY) ? "the game" : by;
	}

	// ------------------------------------------------------------------ the port

	private static boolean portOpen() {
		try (Socket s = new Socket()) {
			s.connect(new InetSocketAddress("127.0.0.1", PORT), 500);
			return true;
		} catch (IOException e) {
			return false;
		}
	}

	/**
	 * {@code hello} as the link sends it (client "launcher", so the Foreman's log tells the two apart): a Foreman answers
	 * with a {@code snapshot}; its {@code foreman.version} is noted. CLOSED when nothing listens, NOT_FOREMAN when
	 * something listens but no snapshot comes within 4 s.
	 */
	static Probe probe() {
		if (!portOpen()) {
			return Probe.CLOSED;
		}
		HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
		CompletableFuture<JsonObject> snapshot = new CompletableFuture<>();
		WebSocket ws = null;
		try {
			ws = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(2)).buildAsync(URI.create("ws://127.0.0.1:" + PORT), new WebSocket.Listener() {
				private final StringBuilder buf = new StringBuilder();

				@Override
				public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
					buf.append(data);
					if (last) {
						try {
							JsonObject o = JsonParser.parseString(buf.toString()).getAsJsonObject();
							if (o.has("type") && "snapshot".equals(o.get("type").getAsString())) {
								snapshot.complete(o);
							}
						} catch (RuntimeException ignored) {
							// not ours
						}
						buf.setLength(0);
					}
					w.request(1);
					return null;
				}
			}).get(3, TimeUnit.SECONDS);
			JsonObject hello = ForemanJson.msg("hello").put("modVersion", modVersion()).put("protocol", Protocol.VERSION).put("client", "launcher").json();
			ws.sendText(hello.toString(), true);
			JsonObject snap = snapshot.get(4, TimeUnit.SECONDS);
			JsonObject st = snap.has("foreman") && snap.get("foreman").isJsonObject() ? snap.getAsJsonObject("foreman") : null;
			runningVersion = st != null && st.has("version") ? st.get("version").getAsString() : null;
			return Probe.FOREMAN;
		} catch (Exception e) {
			return Probe.NOT_FOREMAN;
		} finally {
			if (ws != null) {
				ws.abort();
			}
		}
	}

	// ------------------------------------------------------------------ node and the shell

	/** The first node 22+ ({@link LauncherPlan#nodeCandidates}, then the shell's answer), as an absolute path; null when none. */
	private static @Nullable Path findNode() {
		LauncherPlan.Host host = host();
		for (Path p : LauncherPlan.nodeCandidates(config.nodePath(), host)) {
			if (host.executable().test(p) && checkNode(p)) {
				return p.toAbsolutePath();
			}
		}
		String out = LauncherProcs.run(LauncherPlan.shellProbe(host), null, null, 8000);
		Path probed = out == null ? null : LauncherPlan.parseProbe(out);
		if (probed != null && Files.isExecutable(probed) && checkNode(probed)) {
			return probed.toAbsolutePath();
		}
		return null;
	}

	private static LauncherPlan.Host host() {
		return new LauncherPlan.Host(System.getenv(), System.getProperty("os.name", ""), System.getProperty("user.home", ""),
			p -> Files.isRegularFile(p) && Files.isExecutable(p), Launcher::list);
	}

	private static boolean checkNode(Path p) {
		String v = LauncherProcs.run(List.of(p.toString(), "--version"), null, null, 8000);
		if (LauncherPlan.nodeOk(v)) {
			nodeVersion = v == null ? null : v.strip();
			return true;
		}
		AgentCraft.LOGGER.info("Foreman launcher: {} is not node {}+ ({})", p, LauncherPlan.MIN_NODE, v == null ? "did not run" : v.strip());
		return false;
	}

	/** The login shell's environment (computed once; empty on Windows or when the shell does not answer). Never logged. */
	private static synchronized Map<String, String> loginEnv() {
		if (loginEnv == null) {
			Map<String, String> env = Map.of();
			for (List<String> probe : LauncherPlan.loginEnvProbes(host())) {
				env = LauncherPlan.parseLoginEnv(LauncherProcs.run(probe, null, null, 10_000));
				if (!env.isEmpty()) {
					AgentCraft.LOGGER.info("Foreman launcher: login environment from {} {} ({} variables)", probe.get(0), probe.get(1), env.size());
					break;
				}
			}
			loginEnv = env;
		}
		return loginEnv;
	}

	private static @Nullable String gitHead(Path root) {
		String out = LauncherProcs.run(List.of("git", "rev-parse", "HEAD"), root, null, 8000);
		return out == null || !out.strip().matches("[0-9a-f]{7,64}") ? null : out.strip();
	}

	/**
	 * The repository the agents work on that this checkout is (inside), or shares its Git common dir with (a worktree), or
	 * null. Repositories: config.json {@code repos} and the ones registered with the profile ({@code state.json}).
	 */
	private static @Nullable Path devConflict(Path root) {
		String user = System.getProperty("user.home", "");
		List<Path> repos = new ArrayList<>();
		for (String r : config.repos()) {
			repos.add(realpath(Path.of(LauncherPlan.expandHome(r, user))));
		}
		JsonObject st = LauncherProcs.readJson(home().resolve(PROFILE).resolve("state.json"));
		JsonElement registered = st == null ? null : st.get("repos");
		if (registered != null && registered.isJsonArray()) {
			for (JsonElement e : registered.getAsJsonArray()) {
				String p = e.isJsonObject() ? str(e.getAsJsonObject(), "path") : null;
				if (p != null) {
					repos.add(realpath(Path.of(p)));
				}
			}
		}
		if (repos.isEmpty()) {
			return null;
		}
		String common = LauncherProcs.run(List.of("git", "rev-parse", "--path-format=absolute", "--git-common-dir"), root, null, 8000);
		Path commonDir = common == null || common.isBlank() ? null : realpath(Path.of(common.strip()));
		return LauncherPlan.devCheckoutConflict(root, commonDir, repos);
	}

	// ------------------------------------------------------------------ processes

	private static @Nullable String commandLine(long p) {
		if (!windows()) {
			String out = LauncherProcs.run(List.of("ps", "-p", Long.toString(p), "-o", "command="), null, null, 5000);
			if (out != null && !out.isBlank()) {
				return out.strip();
			}
		}
		return ProcessHandle.of(p).flatMap(h -> h.info().commandLine()).orElse(null);
	}

	// ------------------------------------------------------------------ settings

	/** The Foreman's home: {@code AGENTCRAFT_HOME} (also {@code -Dagentcraft.home}), else {@code ~/.agentcraft}. */
	public static Path home() {
		String h = ClientEnv.raw("AGENTCRAFT_HOME");
		String user = System.getProperty("user.home", "");
		return Path.of(h != null ? LauncherPlan.expandHome(h, user) : Path.of(user, ".agentcraft").toString()).toAbsolutePath().normalize();
	}

	public static Path configFile() {
		return home().resolve("config.json");
	}

	public static String profile() {
		return PROFILE;
	}

	public static int port() {
		return PORT;
	}

	public static String backend() {
		return BACKEND;
	}

	/** {@code AGENTCRAFT_BACKEND}: sim, claude or codex (the Foreman's backends); else claude, the Foreman's own default. */
	private static String backendSetting() {
		String b = ClientEnv.raw("AGENTCRAFT_BACKEND");
		return b != null && (b.equals("sim") || b.equals("claude") || b.equals("codex")) ? b : "claude";
	}

	/** {@code AGENTCRAFT_PROFILE}, else the backend's name (the Foreman's own default). */
	private static String profileSetting(String backend) {
		String p = ClientEnv.raw("AGENTCRAFT_PROFILE");
		return p != null && p.matches("[A-Za-z0-9_-]+") ? p : backend;
	}

	private static LauncherConfig loadConfig() {
		String text = null;
		try {
			Path f = configFile();
			if (Files.exists(f)) {
				text = Files.readString(f, StandardCharsets.UTF_8);
			}
		} catch (IOException e) {
			AgentCraft.LOGGER.warn("Foreman launcher: could not read {}", configFile(), e);
		}
		return LauncherConfig.parse(text, ClientEnv::raw);
	}

	/** A dev run's checkout: the game dir is {@code <repo>/mod/run}. */
	private static @Nullable Path devRunCheckout() {
		if (!ClientEnv.DEV_RUN) {
			return null;
		}
		Path game = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
		Path mod = game.getParent();
		return mod == null || mod.getParent() == null ? null : mod.getParent();
	}

	/** The checkout the jar was built from ({@code agentcraft-build.properties}, written by mod/build.gradle), or null. */
	private static @Nullable String buildCheckout() {
		try (InputStream in = Launcher.class.getClassLoader().getResourceAsStream("agentcraft-build.properties")) {
			if (in == null) {
				return null;
			}
			Properties p = new Properties();
			p.load(in);
			return p.getProperty("checkout");
		} catch (IOException e) {
			return null;
		}
	}

	// ------------------------------------------------------------------ files

	private static Path realpath(Path p) {
		try {
			return p.toRealPath();
		} catch (IOException e) {
			return p.toAbsolutePath().normalize();
		}
	}

	private static List<Path> list(Path dir) {
		if (!Files.isDirectory(dir)) {
			return List.of();
		}
		try (Stream<Path> s = Files.list(dir)) {
			return s.toList();
		} catch (IOException e) {
			return List.of();
		}
	}

	private static void writeJson(Path f, JsonObject o) throws IOException {
		Files.createDirectories(f.getParent());
		Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
		Files.writeString(tmp, GSON.toJson(o) + "\n", StandardCharsets.UTF_8);
		try {
			Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static @Nullable String str(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
	}

	/** A whole number field, or 0 (missing, null, not a number). */
	private static long num(@Nullable JsonObject o, String key) {
		JsonElement e = o == null ? null : o.get(key);
		if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
			return 0;
		}
		try {
			return e.getAsLong();
		} catch (NumberFormatException ex) {
			return 0;
		}
	}

	/** At 5 MB the log moves to .1 (.1 to .2, .2 to .3). */
	private static void rotate(Path log) {
		try {
			if (!Files.exists(log) || Files.size(log) < LOG_ROTATE_BYTES) {
				return;
			}
			for (int i = 2; i >= 1; i--) {
				Path from = log.resolveSibling(log.getFileName() + "." + i);
				if (Files.exists(from)) {
					Files.move(from, log.resolveSibling(log.getFileName() + "." + (i + 1)), StandardCopyOption.REPLACE_EXISTING);
				}
			}
			Files.move(log, log.resolveSibling(log.getFileName() + ".1"), StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			AgentCraft.LOGGER.warn("Foreman launcher: could not rotate {}", log, e);
		}
	}

	static List<String> readTail(Path log, int n) {
		try {
			return LauncherPlan.tail(Files.readAllLines(log, StandardCharsets.UTF_8), n);
		} catch (IOException e) {
			return List.of();
		}
	}

	/** Opens the log in the OS's viewer ({@code /foreman log}). Returns false when there is no log yet. */
	public static boolean openLog() {
		Path log = logFile();
		if (log == null || !Files.exists(log)) {
			return false;
		}
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		List<String> cmd = os.contains("mac") ? List.of("open", "-t", log.toString()) : windows() ? List.of("explorer.exe", log.toString())
			: List.of("xdg-open", log.toString());
		try {
			new ProcessBuilder(cmd).start();
			return true;
		} catch (IOException e) {
			AgentCraft.LOGGER.warn("Foreman launcher: could not open {}", log, e);
			return false;
		}
	}

	private static String modVersion() {
		return FabricLoader.getInstance().getModContainer(AgentCraft.MOD_ID).map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("0");
	}

	private static boolean windows() {
		return LauncherPlan.Host.windows(System.getProperty("os.name", ""));
	}

	// ------------------------------------------------------------------ state

	private static void set(State s, String why) {
		State before = state;
		state = s;
		detail = why;
		changedAt = System.currentTimeMillis();
		if (s == State.RUNNING || s == State.RUNNING_OLDER || s == State.CHECKING) {
			logTail = List.of();
		}
		AgentCraft.LOGGER.info("Foreman launcher: {} ({})", s.wire(), why);
		if (before == s) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc == null) {
			return;
		}
		mc.execute(() -> {
			switch (s) {
				case CRASHED -> toast(Protocol.NotifyLevel.WARN, "The Foreman stopped: " + why);
				case NODE_MISSING -> toast(Protocol.NotifyLevel.NEED_USER, "The Foreman needs Node.js " + LauncherPlan.MIN_NODE
					+ "+: install it, then /foreman start in the console");
				case BLOCKED, NO_SOURCE -> toast(Protocol.NotifyLevel.WARN, "The Foreman could not start: /foreman in the console has the details");
				default -> {
				}
			}
		});
	}

	private static void toast(Protocol.NotifyLevel level, String text) {
		if (Minecraft.getInstance().player == null) {
			return; // no HUD yet; the pill and /foreman carry the state
		}
		Toasts.push(new Protocol.Notify(level, text, null, System.currentTimeMillis()));
	}

	/** One line for the console's /status and /foreman. */
	public static String summaryLine() {
		StringBuilder sb = new StringBuilder("Foreman launcher: ").append(state.wire());
		if (pid > 0) {
			sb.append(", pid ").append(pid);
		}
		if (runningVersion != null) {
			sb.append(", Foreman ").append(runningVersion);
		}
		if (!detail.isEmpty()) {
			sb.append(" (").append(detail).append(')');
		}
		return sb.toString();
	}

	/** For the DevBridge ({@code dev.launcher.state}). */
	public static JsonObject json() {
		JsonObject o = new JsonObject();
		o.addProperty("state", state.wire());
		o.addProperty("detail", detail);
		o.addProperty("changedAgoMs", System.currentTimeMillis() - changedAt);
		o.addProperty("installLine", installLine);
		o.addProperty("installNote", installNote);
		o.addProperty("action", lastAction == null ? null : lastAction.name().toLowerCase(Locale.ROOT));
		o.addProperty("pid", pid);
		o.addProperty("wrapperPid", wrapperPid);
		o.addProperty("how", spawnHow);
		o.addProperty("ours", ours);
		o.addProperty("startedThisSession", startedThisSession);
		o.addProperty("runningBy", runningBy);
		o.addProperty("runningRoot", runningRoot);
		o.addProperty("runningCommit", runningCommit);
		o.addProperty("runningVersion", runningVersion);
		JsonArray st = new JsonArray();
		stale.forEach(st::add);
		o.add("stale", st);
		LauncherPlan.Source s = source;
		o.addProperty("source", s == null || s.root() == null ? null : s.root().toString());
		o.addProperty("sourceOrigin", s == null ? null : s.origin());
		o.addProperty("sourceProblem", s == null ? null : s.problem());
		o.addProperty("expectedCommit", expectedCommit);
		o.addProperty("node", node == null ? null : node.toString());
		o.addProperty("nodeVersion", nodeVersion);
		o.addProperty("profile", PROFILE);
		o.addProperty("port", PORT);
		o.addProperty("backend", BACKEND);
		o.addProperty("home", home().toString());
		Path log = logFile();
		o.addProperty("log", log == null ? null : log.toString());
		o.addProperty("record", recordFile().toString());
		o.addProperty("enabled", config.enabled());
		o.addProperty("stopOnExit", config.stopOnExit());
		JsonArray problems = new JsonArray();
		config.problems().forEach(problems::add);
		o.add("configProblems", problems);
		JsonArray tail = new JsonArray();
		logTail.forEach(tail::add);
		o.add("logTail", tail);
		return o;
	}

	/** The short form for {@code dev.state.launcher}. */
	public static JsonObject summary() {
		JsonObject o = new JsonObject();
		o.addProperty("state", state.wire());
		o.addProperty("pid", pid);
		o.addProperty("ours", ours);
		o.addProperty("enabled", config.enabled());
		return o;
	}

	private static void registerDev() {
		DevBridge.addStateContributor((mc, o) -> o.add("launcher", summary()));
		DevBridge.register("dev.launcher.state", 5_000, "{} - the Foreman launcher: state (idle|disabled|checking|node-missing|no-source|installing|"
			+ "starting|running|running-older|crashed|blocked|stopped), detail, action, pid, ours, runningBy, stale, source, node, log, logTail",
			(req, mc) -> CompletableFuture.completedFuture(json()));
		DevBridge.register("dev.launcher.start", 5_000, "{} - run the launch sequence (reuse, start, or report); poll dev.launcher.state",
			(req, mc) -> {
				start();
				return CompletableFuture.completedFuture(json());
			});
		DevBridge.register("dev.launcher.restart", 5_000, "{} - restart the Foreman the game started (refused for one it did not start)",
			(req, mc) -> {
				if (!ours) {
					throw new DevBridge.DevException("the running Foreman was not started by the game; it is never restarted from here");
				}
				restart();
				return CompletableFuture.completedFuture(json());
			});
		DevBridge.register("dev.launcher.stop", 5_000, "{} - stop the Foreman the game started (refused for one it did not start); poll dev.launcher.state",
			(req, mc) -> {
				if (!ours || pid <= 0) {
					throw new DevBridge.DevException("the running Foreman was not started by the game; it is never stopped from here");
				}
				stop();
				return CompletableFuture.completedFuture(json());
			});
	}
}
