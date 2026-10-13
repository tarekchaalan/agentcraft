package dev.agentcraft.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.agentcraft.launcher.LauncherPlan.Action;
import dev.agentcraft.launcher.LauncherPlan.Host;
import dev.agentcraft.launcher.LauncherPlan.Owner;
import dev.agentcraft.launcher.LauncherPlan.Probe;
import dev.agentcraft.launcher.LauncherPlan.Running;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The launcher's decisions (mod/DEV.md "Foreman launcher"). Host-neutral: expected paths are built with {@link Path} the
 * same way the code builds them, so the suite passes on Windows and on Unix.
 */
class LauncherPlanTest {
	private static Host mac(Map<String, String> env, Set<Path> files, Map<Path, List<Path>> dirs) {
		return new Host(env, "Mac OS X", "/Users/u", files::contains, d -> dirs.getOrDefault(d, List.of()));
	}

	private static Host win(Map<String, String> env) {
		return new Host(env, "Windows 11", "C:\\Users\\u", p -> false, d -> List.of());
	}

	/** What {@link LauncherPlan#source} makes of a configured folder on this host. */
	private static Path abs(String p) {
		return Path.of(p).toAbsolutePath().normalize();
	}

	// ------------------------------------------------------------------ node discovery

	@Test
	void nodeIsLookedForInOrder() {
		Map<Path, List<Path>> dirs = Map.of(Path.of("/Users/u/.nvm/versions/node"),
			List.of(Path.of("/Users/u/.nvm/versions/node/v20.1.0"), Path.of("/Users/u/.nvm/versions/node/v22.11.0"), Path.of("/Users/u/.nvm/versions/node/v9.0.0")));
		Host h = mac(Map.of("PATH", "/usr/bin:/custom/bin"), Set.of(), dirs);
		List<Path> c = LauncherPlan.nodeCandidates("~/tools/node", h);
		assertEquals(Path.of("/Users/u/tools/node"), c.get(0), "config nodePath first (~ expanded, a binary named node kept)");
		assertEquals(Path.of("/usr/bin/node"), c.get(1), "then PATH, in order");
		assertEquals(Path.of("/custom/bin/node"), c.get(2));
		assertEquals(Path.of("/opt/homebrew/bin/node"), c.get(3), "then Homebrew");
		assertEquals(Path.of("/usr/local/bin/node"), c.get(4));
		assertEquals(Path.of("/Users/u/.local/share/mise/shims/node"), c.get(5), "then mise shims");
		assertEquals(Path.of("/Users/u/.volta/bin/node"), c.get(6), "then volta");
		int v22 = c.indexOf(Path.of("/Users/u/.nvm/versions/node/v22.11.0/bin/node"));
		int v20 = c.indexOf(Path.of("/Users/u/.nvm/versions/node/v20.1.0/bin/node"));
		assertTrue(v22 > 6 && v22 < v20, "nvm installs newest first");
		assertEquals(1, c.stream().filter(p -> p.equals(Path.of("/usr/bin/node"))).count(), "duplicates dropped");
	}

	@Test
	void configNodePathMayBeAFolder() {
		Host h = mac(Map.of(), Set.of(), Map.of());
		assertEquals(Path.of("/opt/n/bin/node"), LauncherPlan.nodeCandidates("/opt/n/bin", h).get(0));
	}

	@Test
	void windowsCandidates() {
		Host w = win(Map.of("Path", "C:\\x;C:\\y", "ProgramFiles", "C:\\PF", "LOCALAPPDATA", "C:\\L", "NVM_SYMLINK", "C:\\nvm\\link", "SystemRoot",
			"C:\\WINDOWS"));
		List<Path> c = LauncherPlan.nodeCandidates(null, w);
		assertEquals(Path.of("C:\\x").resolve("node.exe"), c.get(0), "PATH (any spelling of the key) first, node.exe");
		assertEquals(Path.of("C:\\y").resolve("node.exe"), c.get(1));
		assertTrue(c.contains(Path.of("C:\\PF").resolve("nodejs").resolve("node.exe")), "the installer's folder");
		assertTrue(c.contains(Path.of("C:\\L").resolve("Programs").resolve("nodejs").resolve("node.exe")), "a per-user install");
		assertTrue(c.contains(Path.of("C:\\nvm\\link").resolve("node.exe")), "nvm-windows' symlink");
		assertTrue(c.contains(Path.of("C:\\nvm4w\\nodejs").resolve("node.exe")), "nvm-windows' default symlink");
		assertTrue(c.indexOf(Path.of("C:\\PF").resolve("nodejs").resolve("node.exe")) > 1, "fixed places after PATH");
		assertEquals(List.of("C:\\WINDOWS\\System32\\where.exe", "node"), LauncherPlan.shellProbe(w));
		assertTrue(LauncherPlan.loginEnvProbes(w).isEmpty(), "the game's environment is the user's on Windows");
		Host bare = win(Map.of());
		assertTrue(LauncherPlan.nodeCandidates(null, bare).contains(Path.of("C:\\Program Files").resolve("nodejs").resolve("node.exe")));
	}

	@Test
	void shellProbesUseTheLoginShell() {
		Host h = mac(Map.of("SHELL", "/bin/bash"), Set.of(), Map.of());
		assertEquals(List.of("/bin/bash", "-lc", "command -v node"), LauncherPlan.shellProbe(h));
		Host noShell = mac(Map.of(), Set.of(), Map.of());
		assertEquals("/bin/zsh", LauncherPlan.shellProbe(noShell).get(0));
		List<List<String>> env = LauncherPlan.loginEnvProbes(h);
		assertEquals("-lic", env.get(0).get(1), "interactive login first (rc files set up version managers)");
		assertEquals("-lc", env.get(1).get(1), "plain login as the fallback");
		assertTrue(env.get(0).get(2).contains(LauncherPlan.ENV_MARK) && env.get(0).get(2).contains("env -0"));
	}

	@Test
	void probeOutputAndVersions() {
		assertEquals(Path.of("/Users/u/.local/share/mise/shims/node"), LauncherPlan.parseProbe("Welcome!\n/Users/u/.local/share/mise/shims/node\n"));
		assertEquals(Path.of("C:\\nvm4w\\nodejs\\node.exe"), LauncherPlan.parseProbe("C:\\nvm4w\\nodejs\\node.exe\r\n"));
		assertNull(LauncherPlan.parseProbe("node not found"));
		assertTrue(LauncherPlan.nodeOk("v22.11.0\n"));
		assertTrue(LauncherPlan.nodeOk("v24.16.0"));
		assertFalse(LauncherPlan.nodeOk("v20.19.1"));
		assertFalse(LauncherPlan.nodeOk(null));
		assertFalse(LauncherPlan.nodeOk("garbage"));
	}

	@Test
	void npmRunsThroughNodeAndKeepsDevDependencies() {
		Path unixNode = Path.of("/opt/homebrew/bin/node");
		Path unixCli = Path.of("/opt/homebrew/lib/node_modules/npm/bin/npm-cli.js");
		assertEquals(unixCli, LauncherPlan.npmCli(unixNode, unixCli::equals), "Unix layout: <prefix>/lib/node_modules/npm");
		Path winNode = Path.of("/nvm4w/nodejs/node.exe");
		Path winCli = Path.of("/nvm4w/nodejs/node_modules/npm/bin/npm-cli.js");
		assertEquals(winCli, LauncherPlan.npmCli(winNode, winCli::equals), "Windows layout: next to node.exe");
		assertNull(LauncherPlan.npmCli(unixNode, p -> false), "no npm next to node");
		assertEquals(List.of(unixNode.toString(), unixCli.toString(), "ci", "--no-audit", "--no-fund"), LauncherPlan.installCommand(unixNode, unixCli),
			"node npm-cli.js ci: no shell, no .cmd; tsx is a dev dependency, so no --omit=dev");
		assertTrue(LauncherPlan.depsReady(Path.of("/r"), p -> p.equals(Path.of("/r/foreman/node_modules/.package-lock.json"))));
		assertFalse(LauncherPlan.depsReady(Path.of("/r"), p -> false));
	}

	@Test
	void npmCiRunsAgainWhenTheLockFileChanged() {
		String lock = LauncherPlan.lockHash("{\"lockfileVersion\": 3}".getBytes(StandardCharsets.UTF_8));
		assertEquals(64, lock.length());
		assertEquals(Path.of("/r/foreman/node_modules/.agentcraft-lock.sha256"), LauncherPlan.depsMarker(Path.of("/r")));
		assertEquals(Path.of("/r/foreman/package-lock.json"), LauncherPlan.lockFile(Path.of("/r")));
		assertTrue(LauncherPlan.needsInstall(false, lock, lock), "nothing installed");
		assertFalse(LauncherPlan.needsInstall(true, lock, lock), "the lock the launcher installed: node_modules is kept");
		assertFalse(LauncherPlan.needsInstall(true, lock, lock + "\n"), "a trailing newline in the marker");
		assertTrue(LauncherPlan.needsInstall(true, lock, LauncherPlan.lockHash(new byte[] {1})), "the lock changed (a pull)");
		assertTrue(LauncherPlan.needsInstall(true, lock, null), "installed by someone else (launch.ps1, by hand): once");
		assertFalse(LauncherPlan.needsInstall(true, null, null), "no lock file: npm ci could not run; the install stays");
	}

	// ------------------------------------------------------------------ source

	private static final Set<Path> CHECKOUTS = Set.of(abs("/dev/repo/foreman/package.json"), abs("/dev/repo/foreman/src/main.ts"),
		abs("/build/co/foreman/package.json"), abs("/build/co/foreman/src/main.ts"), abs("/cfg/co/foreman/package.json"),
		abs("/cfg/co/foreman/src/main.ts"), abs("/Users/u/co/foreman/package.json"), abs("/Users/u/co/foreman/src/main.ts"));

	@Test
	void sourceOrder() {
		var all = LauncherPlan.sourceCandidates("/cfg/co", "/nope", Path.of("/dev/repo"), "/build/co");
		LauncherPlan.Source o = LauncherPlan.source(all, "/Users/u", CHECKOUTS::contains);
		assertEquals(abs("/cfg/co"), o.root(), "the override wins (even over a bad config value)");
		assertEquals("AGENTCRAFT_FOREMAN_DIR", o.origin());
		var cfg = LauncherPlan.sourceCandidates(null, "/cfg/co/foreman", Path.of("/dev/repo"), "/build/co");
		LauncherPlan.Source s = LauncherPlan.source(cfg, "/Users/u", CHECKOUTS::contains);
		assertEquals(abs("/cfg/co"), s.root(), "launcher.foremanDir may name foreman/");
		assertEquals("launcher.foremanDir", s.origin());
		var home = LauncherPlan.sourceCandidates(null, "~/co", null, null);
		assertEquals(abs("/Users/u/co"), LauncherPlan.source(home, "/Users/u", CHECKOUTS::contains).root(), "~ expanded");
		var dev = LauncherPlan.sourceCandidates(null, null, Path.of("/dev/repo"), "/build/co");
		LauncherPlan.Source d = LauncherPlan.source(dev, "/Users/u", CHECKOUTS::contains);
		assertEquals(abs("/dev/repo"), d.root(), "a dev run's own checkout before the jar's build checkout");
		assertEquals("dev run", d.origin());
		var jar = LauncherPlan.sourceCandidates(null, null, null, "/build/co");
		LauncherPlan.Source j = LauncherPlan.source(jar, "/Users/u", CHECKOUTS::contains);
		assertEquals(abs("/build/co"), j.root(), "a jar: the checkout it was built from");
		assertEquals("mod build", j.origin());
		var gone = LauncherPlan.sourceCandidates(null, null, Path.of("/elsewhere"), "/build/co");
		assertEquals(abs("/build/co"), LauncherPlan.source(gone, "/Users/u", CHECKOUTS::contains).root(), "an implicit candidate that is no checkout is skipped");
	}

	@Test
	void anExplicitBadSourceStops() {
		var bad = LauncherPlan.sourceCandidates(null, "/nope", Path.of("/dev/repo"), null);
		LauncherPlan.Source s = LauncherPlan.source(bad, "/Users/u", CHECKOUTS::contains);
		assertNull(s.root(), "never silently another checkout than the one asked for");
		assertTrue(s.problem().contains("launcher.foremanDir = /nope is not an AgentCraft checkout"), s.problem());
		LauncherPlan.Source none = LauncherPlan.source(LauncherPlan.sourceCandidates(null, null, null, null), "/Users/u", CHECKOUTS::contains);
		assertNull(none.root());
		assertEquals("none", none.origin());
		assertNotNull(none.problem());
		assertTrue(none.problem().contains("AGENTCRAFT_FOREMAN_DIR"));
	}

	@Test
	void homeExpansion() {
		assertEquals("/Users/u", LauncherPlan.expandHome("~", "/Users/u"));
		assertEquals(Path.of("/Users/u", "x/y").toString(), LauncherPlan.expandHome("~/x/y", "/Users/u"));
		assertEquals(Path.of("C:\\Users\\u", "x").toString(), LauncherPlan.expandHome("~\\x", "C:\\Users\\u"));
		assertEquals("/abs/~", LauncherPlan.expandHome("/abs/~", "/Users/u"));
	}

	@Test
	void devCheckoutConflict() {
		List<Path> repos = List.of(Path.of("/code/agentcraft"), Path.of("/code/other"));
		assertEquals(Path.of("/code/agentcraft"), LauncherPlan.devCheckoutConflict(Path.of("/code/agentcraft"), null, repos));
		assertEquals(Path.of("/code/agentcraft"), LauncherPlan.devCheckoutConflict(Path.of("/code/agentcraft/.claude/worktrees/x"), null, repos));
		assertEquals(Path.of("/code/agentcraft"), LauncherPlan.devCheckoutConflict(Path.of("/elsewhere/wt"), Path.of("/code/agentcraft/.git"), repos),
			"a worktree of a listed repo shares its common dir");
		assertNull(LauncherPlan.devCheckoutConflict(Path.of("/code/agentcraft-stable"), Path.of("/code/agentcraft-stable/.git"), repos),
			"a sibling folder with a shared prefix is not inside");
		assertNull(LauncherPlan.devCheckoutConflict(Path.of("/code/agentcraft"), null, List.of()), "no repos registered");
	}

	// ------------------------------------------------------------------ stale, ownership, the decision

	@Test
	void staleReasons() {
		assertTrue(LauncherPlan.staleReasons("/s", "abc123456789", "/s", "abc123456789").isEmpty());
		assertTrue(LauncherPlan.staleReasons("/s/../s", "abc", "/s", "abc").isEmpty(), "paths compared normalized");
		List<String> other = LauncherPlan.staleReasons("/dev", "abc", "/s", "abc");
		assertEquals(1, other.size());
		assertTrue(other.get(0).contains("different checkout (/dev)"));
		List<String> older = LauncherPlan.staleReasons("/s", "111111111aaaa", "/s", "222222222bbbb");
		assertTrue(older.get(0).contains("commit 111111111") && older.get(0).contains("222222222"));
		assertEquals(2, LauncherPlan.staleReasons("/dev", "1", "/s", "2").size());
		assertTrue(LauncherPlan.staleReasons("/s", "1", "/s", null).isEmpty(), "no git: the commit is not compared");
		assertEquals(1, LauncherPlan.staleReasons(null, null, "/s", "1").size(), "a Foreman that does not say where it came from: stale");
	}

	@Test
	void sameProcessNeedsAMatchingStartTime() {
		assertTrue(LauncherPlan.sameProcess(1_000_500, 1_000_000));
		assertTrue(LauncherPlan.sameProcess(999_000, 1_000_000), "clocks may disagree a little");
		assertFalse(LauncherPlan.sameProcess(2_000_000, 1_000_000), "a later process that reused the pid");
		assertFalse(LauncherPlan.sameProcess(-1, 1_000_000), "unknown start: never ours");
		assertFalse(LauncherPlan.sameProcess(1_000_000, 0));
	}

	@Test
	void onlyTheProcessWeStartedIsOurs() {
		Running r = new Running(200, "p", "/s", "c", null);
		assertEquals(Owner.FOREIGN, LauncherPlan.owner(0, 0, false, r), "no record");
		assertEquals(Owner.OURS, LauncherPlan.owner(200, 5, true, r));
		assertEquals(Owner.OURS, LauncherPlan.owner(200, 5, true, null), "ours, alive, no run file yet (starting or hung)");
		assertEquals(Owner.FOREIGN, LauncherPlan.owner(100, 5, true, r), "ours is alive but another holds the port");
		assertEquals(Owner.FOREIGN, LauncherPlan.owner(100, 5, false, r),
			"ours is gone and another took its place, even from our checkout: someone else started it, it is never stopped by us");
		assertEquals(Owner.FOREIGN, LauncherPlan.owner(200, 5, false, r), "the pid now belongs to another process (start time differs)");
		assertEquals(Owner.FOREIGN, LauncherPlan.owner(200, 0, true, r), "a record without a start time");
	}

	@Test
	void decisions() {
		List<String> current = List.of();
		List<String> old = List.of("started at commit 1, the checkout is now at 2");
		assertEquals(Action.REUSE, LauncherPlan.decide(Probe.FOREMAN, false, current, false, false), "a current one, anyone's: reused");
		assertEquals(Action.REUSE, LauncherPlan.decide(Probe.FOREMAN, true, current, false, false));
		assertEquals(Action.RESTART, LauncherPlan.decide(Probe.FOREMAN, true, old, false, false), "ours and stale: restarted");
		assertEquals(Action.REUSE_OLDER, LauncherPlan.decide(Probe.FOREMAN, false, old, false, false), "not ours and stale: never killed");
		assertEquals(Action.REUSE_OLDER, LauncherPlan.decide(Probe.FOREMAN, false, old, false, true), "not even on Restart");
		assertEquals(Action.REUSE, LauncherPlan.decide(Probe.FOREMAN, false, current, false, true), "Restart never touches a foreign current one");
		assertEquals(Action.RESTART, LauncherPlan.decide(Probe.FOREMAN, true, current, false, true), "Restart of ours");
		assertEquals(Action.START, LauncherPlan.decide(Probe.CLOSED, false, current, false, false));
		assertEquals(Action.RESTART, LauncherPlan.decide(Probe.CLOSED, true, current, false, false), "ours alive but not listening (hung)");
		assertEquals(Action.PROFILE_BUSY, LauncherPlan.decide(Probe.CLOSED, false, current, true, false));
		assertEquals(Action.PORT_TAKEN, LauncherPlan.decide(Probe.NOT_FOREMAN, false, current, false, false), "not a Foreman, not ours: left alone");
		assertEquals(Action.RESTART, LauncherPlan.decide(Probe.NOT_FOREMAN, true, current, false, false), "ours, not answering");
	}

	// ------------------------------------------------------------------ command line

	@Test
	void theForemansCommandLine() {
		List<String> args = LauncherPlan.foremanArgs("claude", "hardcore", "/Users/u/.agentcraft", 7880);
		assertEquals(List.of("--import", "tsx", "src/main.ts", "--backend", "claude", "--profile", "hardcore", "--home", "/Users/u/.agentcraft", "--port",
			"7880"), args, "what tools/launch.ps1 passes");
		List<String> cmd = LauncherPlan.spawnCommand(Path.of("/n/node"), Path.of("/r/artifacts/logs/f.log"), Path.of("/r/foreman"), args);
		assertEquals(Path.of("/n/node").toString(), cmd.get(0));
		assertEquals("-e", cmd.get(1));
		assertEquals(LauncherPlan.SPAWN_SCRIPT, cmd.get(2));
		assertEquals(Path.of("/r/artifacts/logs/f.log").toString(), cmd.get(3));
		assertEquals(Path.of("/r/foreman").toString(), cmd.get(4));
		assertEquals(args, cmd.subList(5, cmd.size()));
		assertTrue(LauncherPlan.SPAWN_SCRIPT.contains("detached:true"));
		assertFalse(LauncherPlan.SPAWN_SCRIPT.contains("\"") || LauncherPlan.SPAWN_SCRIPT.contains("\\"),
			"no double quotes or backslashes: Windows' argument quoting passes it through unchanged");
		assertEquals(4242, LauncherPlan.parsePid("4242"));
		assertEquals(-1, LauncherPlan.parsePid("Error: spawn ENOENT"));
		assertEquals(-1, LauncherPlan.parsePid(null));
		for (String a : cmd) {
			assertFalse(a.toLowerCase().contains("token") || a.toLowerCase().contains("key"), "no secrets on the command line: " + a);
		}
	}

	@Test
	void foremanCommandLines() {
		assertTrue(LauncherPlan.isForemanCommand("/n/node --import tsx src/main.ts --backend claude --profile hardcore --port 7880", "hardcore"));
		assertTrue(LauncherPlan.isForemanCommand("node /r/foreman/src/main.ts --profile=hardcore", "hardcore"));
		assertTrue(LauncherPlan.isForemanCommand("node.exe --import tsx src\\main.ts --profile hardcore", "hardcore"));
		assertFalse(LauncherPlan.isForemanCommand("node src/main.ts --profile hardcore2", "hardcore"));
		assertFalse(LauncherPlan.isForemanCommand("vim src/main.ts", "hardcore"));
		assertFalse(LauncherPlan.isForemanCommand(null, "hardcore"));
	}

	// ------------------------------------------------------------------ Windows: hidden console and Ctrl+Break

	@Test
	void windowsCommandLineQuoting() {
		assertEquals("plain", LauncherPlan.cmdArg("plain"));
		assertEquals("\"\"", LauncherPlan.cmdArg(""));
		assertEquals("\"C:\\Program Files\\nodejs\\node.exe\"", LauncherPlan.cmdArg("C:\\Program Files\\nodejs\\node.exe"));
		assertEquals("\"a\\\"b\"", LauncherPlan.cmdArg("a\"b"), "a quote is escaped");
		assertEquals("\"C:\\a b\\\\\"", LauncherPlan.cmdArg("C:\\a b\\"), "trailing backslashes doubled before the closing quote");
		assertEquals("'it''s'", LauncherPlan.psLiteral("it's"));
	}

	@Test
	void hiddenStartGoesThroughStartProcessLikeLaunchPs1() {
		Path node = Path.of("C:\\Program Files\\nodejs\\node.exe");
		Path bgrun = Path.of("C:\\Users\\O'Neil\\agentcraft\\tools\\lib\\bgrun.mjs");
		Path spec = Path.of("C:\\Users\\O'Neil\\agentcraft\\artifacts\\run\\mod-launcher-claude.spec.json");
		Path cwd = Path.of("C:\\Users\\O'Neil\\agentcraft\\foreman");
		assertEquals(Path.of("/r/tools/lib/bgrun.mjs"), LauncherPlan.bgrun(Path.of("/r")));
		String s = LauncherPlan.hiddenStartScript(node, bgrun, spec, cwd);
		assertTrue(s.contains("Start-Process -FilePath 'C:\\Program Files\\nodejs\\node.exe'"), s);
		assertTrue(s.contains("-WindowStyle Hidden -PassThru"), "hidden window, own console, ShellExecute (no inherited handles)");
		assertFalse(s.contains("-Redirect"), "no redirection: that would inherit the game's handles");
		assertTrue(s.contains("-ArgumentList 'C:\\Users\\O''Neil\\agentcraft\\tools\\lib\\bgrun.mjs C:\\Users\\O''Neil"), "quotes doubled: " + s);
		assertTrue(s.contains("-WorkingDirectory 'C:\\Users\\O''Neil\\agentcraft\\foreman'"));
		assertTrue(s.contains(LauncherPlan.PID_MARK));
		String spaced = LauncherPlan.hiddenStartScript(node, Path.of("C:\\My Code\\tools\\lib\\bgrun.mjs"), Path.of("C:\\My Code\\s.json"), cwd);
		assertTrue(spaced.contains("-ArgumentList '\"C:\\My Code\\tools\\lib\\bgrun.mjs\" \"C:\\My Code\\s.json\"'"), "paths with spaces quoted: " + spaced);
		assertEquals(4242, LauncherPlan.parseMarkedPid("#< CLIXML\nnoise " + LauncherPlan.PID_MARK + "4242"));
		assertEquals(-1, LauncherPlan.parseMarkedPid("4242"), "only the marked pid counts");
		assertEquals(-1, LauncherPlan.parseMarkedPid(null));
	}

	@Test
	void ctrlBreakAndPowerShell() {
		String s = LauncherPlan.ctrlBreakScript(4242);
		assertTrue(s.contains("[AgentCraftCtrl]::Send([uint32]4242)"));
		assertTrue(s.contains("GenerateConsoleCtrlEvent(1, 0)"), "Ctrl+Break: node's SIGBREAK, the Foreman's clean shutdown");
		assertTrue(s.lines().anyMatch(l -> l.equals("\"@")), "the here-string ends at column 0, or PowerShell never closes it");
		List<String> ps = LauncherPlan.powershell("C:\\WINDOWS", "Write-Output 'x'");
		assertEquals("C:\\WINDOWS\\System32\\WindowsPowerShell\\v1.0\\powershell.exe", ps.get(0));
		assertEquals("-EncodedCommand", ps.get(ps.size() - 2));
		assertEquals("Write-Output 'x'", new String(Base64.getDecoder().decode(ps.get(ps.size() - 1)), StandardCharsets.UTF_16LE),
			"UTF-16LE base64: no quoting reaches a command line");
		assertEquals("C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe", LauncherPlan.powershell(null, "x").get(0));
	}

	// ------------------------------------------------------------------ environment

	@Test
	void loginEnvParsing() {
		String out = "Last login: today\nbanner " + LauncherPlan.ENV_MARK + " in text\n" + LauncherPlan.ENV_MARK + "\nPATH=/a:/b\0DOTNET_ROOT=/d\0X=1=2\0";
		Map<String, String> env = LauncherPlan.parseLoginEnv(out);
		assertEquals("/a:/b", env.get("PATH"));
		assertEquals("/d", env.get("DOTNET_ROOT"));
		assertEquals("1=2", env.get("X"));
		assertTrue(LauncherPlan.parseLoginEnv("no mark").isEmpty());
		assertTrue(LauncherPlan.parseLoginEnv(null).isEmpty());
	}

	@Test
	void environmentComposesPath() {
		Map<String, String> game = new HashMap<>(Map.of("PATH", "/usr/bin:/bin", "HOME", "/Users/u", "AGENTCRAFT_DEV_PORT", "7879",
			"AGENTCRAFT_MUTE", "1", "AGENTCRAFT_LAUNCHER", "1", "AGENTCRAFT_LEAD_MODEL", "opus", "JAVA_HOME", "/j"));
		Map<String, String> login = Map.of("PATH", "/Users/u/.local/bin:/opt/homebrew/opt/dotnet@8/libexec:/usr/bin", "DOTNET_ROOT", "/d", "SHLVL",
			"2", "__MISE_DIFF", "x", "HOME", "/elsewhere", "AGENTCRAFT_DEV", "1", "AGENTCRAFT_FOREMAN_DIR", "/x");
		Path node = Path.of("/Users/u/.local/share/mise/installs/node/22/bin/node");
		Map<String, String> env = LauncherPlan.environment(game, login, node, "/Users/u", false);
		List<String> path = List.of(env.get("PATH").split(":"));
		assertEquals(node.getParent().toString(), path.get(0), "node's folder first");
		assertEquals("/Users/u/.local/bin", path.get(1), "then the login shell's PATH");
		assertTrue(path.indexOf("/opt/homebrew/opt/dotnet@8/libexec") < path.indexOf("/opt/homebrew/bin"));
		assertTrue(path.contains(Path.of("/Users/u", ".local/share/mise/shims").toString()) && path.contains("/usr/local/bin"), "the usual fallbacks");
		assertEquals(path.size(), path.stream().distinct().count(), "no duplicates");
		assertEquals("/d", env.get("DOTNET_ROOT"), "login variables the game lacks are added");
		assertEquals("/Users/u", env.get("HOME"), "the game's own variables win");
		assertEquals("/j", env.get("JAVA_HOME"));
		assertFalse(env.containsKey("SHLVL") || env.containsKey("__MISE_DIFF"), "shell bookkeeping dropped");
		assertFalse(env.containsKey("AGENTCRAFT_DEV_PORT") || env.containsKey("AGENTCRAFT_DEV") || env.containsKey("AGENTCRAFT_MUTE")
			|| env.containsKey("AGENTCRAFT_LAUNCHER") || env.containsKey("AGENTCRAFT_FOREMAN_DIR"), "the game's own switches never reach the Foreman");
		assertEquals("opus", env.get("AGENTCRAFT_LEAD_MODEL"), "a Foreman setting the user exports reaches it, as from a terminal");
	}

	@Test
	void windowsEnvironmentKeepsThePathKey() {
		Map<String, String> game = Map.of("Path", "C:\\Windows\\system32;C:\\Git\\cmd", "SystemRoot", "C:\\WINDOWS", "AGENTCRAFT_DEV", "1");
		Path node = Path.of("/nvm4w/nodejs/node.exe"); // host-neutral: only the key, the separator and the order matter here
		Map<String, String> env = LauncherPlan.environment(game, Map.of(), node, "C:\\Users\\u", true);
		assertFalse(env.containsKey("PATH"), "Windows spells it Path; a second key would make two variables");
		List<String> path = List.of(env.get("Path").split(";"));
		assertEquals(node.getParent().toString(), path.get(0), "node's folder first");
		assertEquals(List.of("C:\\Windows\\system32", "C:\\Git\\cmd"), path.subList(1, path.size()), "then the game's, no Unix fallbacks");
		assertEquals("C:\\WINDOWS", env.get("SystemRoot"));
		assertFalse(env.containsKey("AGENTCRAFT_DEV"));
	}

	@Test
	void environmentWithoutALoginShell() {
		Path node = Path.of("/opt/homebrew/bin/node");
		Map<String, String> env = LauncherPlan.environment(Map.of("PATH", "/usr/bin"), Map.of(), node, "/Users/u", false);
		assertTrue(env.get("PATH").startsWith(node.getParent() + ":"));
		assertTrue(env.get("PATH").contains("/usr/bin"));
	}

	// ------------------------------------------------------------------ the rest

	@Test
	void tailKeepsTheLastNonBlankLines() {
		assertEquals(List.of("b", "c"), LauncherPlan.tail(List.of("a", "", "b", " ", "c"), 2));
		assertEquals(List.of(), LauncherPlan.tail(List.of(), 5));
	}

	@Test
	void stateWireNames() {
		assertEquals("node-missing", LauncherPlan.State.NODE_MISSING.wire());
		assertEquals("running-older", LauncherPlan.State.RUNNING_OLDER.wire());
		assertEquals("checking", LauncherPlan.State.CHECKING.wire());
	}
}
