# tools/

The launchers, the stop scripts and the developer tools: everything that starts AgentCraft, drives a
running game, or checks how it looks. Node 22+ for the `.mjs` scripts (`npm ci --prefix tools` once;
the launchers do it for you). Windows PowerShell 5.1 or PowerShell 7 for the `.ps1` scripts.

## Scripts

| Script | What it does | Run it |
| --- | --- | --- |
| `start-agentcraft.cmd` (repository root), `start-agentcraft.ps1` | Guided start for Windows: checks and installs the prerequisites, asks how the agents run, which folder and which world, then calls `launch.ps1` | double-click it, or `start-agentcraft.cmd [studio\|survival] [-Check]` |
| `launch.ps1` (`launch.cmd` from cmd.exe) | Windows launcher: starts or reuses the Foreman, builds and starts Minecraft, waits for the world | `tools\launch.ps1 -Backend sim` |
| `stop.ps1` (`stop.cmd`) | Stops what `launch.ps1` started, and nothing else | `tools\stop.ps1`, `tools\stop.ps1 -Game` |
| `launch-unix.mjs` | macOS and Linux launcher: `launch`, `stop` and `login` in one script | `node tools/launch-unix.mjs launch --backend sim` |
| `devbridge-cli.mjs` | Talks to the running game's DevBridge: state, camera, screenshots, commands, keys, quit | `node tools/devbridge-cli.mjs state --port 7879` |
| `foreman-cli.mjs` | Talks to a Foreman: status, diffs, `repo-add`, any client message | `node tools/foreman-cli.mjs status --port 7878` |
| `capture-screenshots.mjs` | Runs a scene file (`scenes/*.json`) through the DevBridge and writes the PNGs | `node tools/capture-screenshots.mjs tools/scenes/qa.json --port 7879 --foreman 7878` |
| `run-screenshot-qa.mjs` | The screenshot QA suite: starts a showcase Foreman and the game if needed, shoots `scenes/qa.json`, writes a contact sheet and a manifest, stops what it started | `node tools/run-screenshot-qa.mjs --home <checkout>/.agentcraft-home` |
| `play-camera-shots.mjs` | Plays camera shots (`shots/*.json`) in real time in the game, for a screen recorder such as OBS | `node tools/play-camera-shots.mjs tools/shots/hq_orbit.json --port 7879` |
| `multiplayer-harness.mjs` | A dedicated server, one Foreman per player and one or two clients on this machine: `up`, `status`, `down` | `node tools/multiplayer-harness.mjs up --slot 92 --backend sim` |
| `watch-agent-life.mjs` | Watches the agents for a while: a screenshot every few seconds and each agent's posture, bubble and particles in a log | `node tools/watch-agent-life.mjs --port 7879 --seconds 60` |
| `check-agent-card-typing.mjs` | Checks typing in the agent card's message line through real SDL key events (game started with `AGENTCRAFT_DEV_TEST=1`) | `node tools/check-agent-card-typing.mjs --port 7879` |

The names say what each script does: one-job scripts are verb-noun (`capture-screenshots`,
`play-camera-shots`, `run-screenshot-qa`), tools with subcommands are named after what they drive
(`devbridge-cli`, `foreman-cli`, `multiplayer-harness`), and the launchers keep the launch and stop
verbs. They were renamed on 2026-10-13:

| Old name | New name |
| --- | --- |
| `devcli.mjs` | `devbridge-cli.mjs` |
| `foremancli.mjs` | `foreman-cli.mjs` |
| `shoot.mjs` | `capture-screenshots.mjs` |
| `record.mjs` | `play-camera-shots.mjs` |
| `qa.mjs` | `run-screenshot-qa.mjs` |
| `mp.mjs` | `multiplayer-harness.mjs` |
| `unix.mjs` (earlier `mac.mjs`) | `launch-unix.mjs` |
| `agents-live.mjs` | `watch-agent-life.mjs` |
| `agents-typing.mjs` | `check-agent-card-typing.mjs` |

The run files and logs the launchers write keep their names (`artifacts/run/foreman-*.json`,
`game.json`, `unix-*.json`), so a Foreman or game started by an older script is still found, reused
and stopped. `scenes/phase1.json`, the first DevBridge proof, is gone: it built a mock studio where the
HQ now stands.

| Library or data | What it is |
| --- | --- |
| `lib/procs.ps1` | PowerShell helpers of `launch.ps1` and `stop.ps1`: run files, process identity (pid + start time), Ctrl+Break, ports, the background start |
| `lib/checks.ps1` | Argument checks, Java 25 discovery, the Node version check and captured tool runs with a timeout, shared by `launch.ps1`, `stop.ps1` and `start-agentcraft.ps1` |
| `lib/bgrun.mjs` | Background runner: owns the log files and the hidden console of a background Foreman or game. The mod's Foreman launcher uses it too, so its path stays |
| `lib/launcher.mjs` | `launch-unix.mjs`'s option checks, Java discovery and exit codes |
| `lib/devclient.mjs`, `lib/foremanclient.mjs` | DevBridge and Foreman WebSocket clients for scripts |
| `lib/scene.mjs`, `lib/contactsheet.mjs` | Scene runner (anchors, screens, Foreman messages, waits) and the QA contact sheet (pngjs) |
| `lib/mp/` | The multiplayer harness library and its Gradle init script |
| `scenes/*.json` | Scene files: `qa.json` (the QA set), `qa-selftest.json` (runner self-test), `console.json`, `displays.json`, `displays_bay.json`, `agents.json`, `agents-late.json` |
| `shots/*.json` | Camera shots for `play-camera-shots.mjs`; the format is in [shots/README.md](shots/README.md) |
| `test/` | `npm test --prefix tools` |

## Windows

Windows PowerShell 5.1 or PowerShell 7 (`pwsh -File tools\launch.ps1 ...`) and Node 22+.
`launch.ps1` installs the npm dependencies it needs on the first run (`npm ci` in `foreman/` and
`tools/`); the Gradle wrapper downloads Gradle, Minecraft and Fabric by itself. You need a Java 25
JDK (Temurin 25: https://adoptium.net); the guided start can install a portable one.

### Guided start

`start-agentcraft.cmd` in the repository root (double-click it, or run it from cmd.exe) runs
`tools\start-agentcraft.ps1`, which does the checking so you do not have to:

1. The checkout itself, free disk space, and whether `winget` is available.
2. **Git**: offers `winget install Git.Git`.
3. **Node.js 22+**: offers `nvm install 22` if you have nvm-windows, else winget
   (`OpenJS.NodeJS.LTS`) or a portable copy in `%LOCALAPPDATA%\Programs\nodejs` added to your user
   PATH.
4. **Java 25 JDK**: finds one in `JAVA_HOME` or the usual install folders (Temurin, Oracle,
   Microsoft, Zulu, Corretto, Liberica, `~/.jdks`); else offers a portable Temurin 25 from
   api.adoptium.net into `%LOCALAPPDATA%\Programs\Eclipse Adoptium`. No admin rights, nothing else
   on the machine changes; `JAVA_HOME` is set for that launch only.
5. **How the agents run**: your Claude login (installs the `claude` CLI with Anthropic's installer
   if missing and runs `claude auth login` if you are not logged in), an API key you paste (kept
   for that launch only), or the free simulated team. `-Backend codex` checks for the Codex CLI.
6. The repository the agents should work in (Enter = the demo repo in `sandbox/`), the world
   (Studio or Survival), then `tools\launch.ps1` with the matching arguments.

Every install is asked about first (yes / skip / quit), waited for, and checked again
afterwards; an internet check runs before any download. Each run is logged to
`artifacts\logs\start-<timestamp>.log`. Exit codes: 0 ok, 1 something is missing or an argument is
wrong, 2 you quit, 3 the launch failed (`launch.ps1` says why), 4 unexpected error; the window stays
open on any of them.

| argument | |
| --- | --- |
| `studio` / `survival` (or `-World`) | which world, skips the question |
| `-Repo <path>` | repository to register, skips the question |
| `-Backend sim\|claude\|codex` | as for `launch.ps1` |
| `-UseClaudeLogin` | login mode without being asked |
| `-Check` | only check and offer installs, do not launch |
| `-Yes` | unattended: every question gets its default (installs included) |
| `-NoInstall` | never install, just report |
| `-LaunchArgs '-Dev','-Seed','1234'` | passed through to `launch.ps1`; the names are checked against its parameters before anything is installed |

### Daily use

```powershell
tools\launch.ps1                              # claude backend, state in ~/.agentcraft, Foreman :7878, DevBridge :7879
tools\launch.ps1 -Repo C:\code\life-tracker   # also register a repo with the Foreman
tools\launch.ps1 -UseClaudeLogin              # your `claude` CLI login instead of an API key (personal use); banner: auth ok (claude login)
tools\launch.ps1 -Backend sim                 # scripted demo team (no API calls), demo repo in sandbox/
tools\launch.ps1 -Showcase                    # static showcase state (sim); -Showcase late for the later one
tools\launch.ps1 -Login                       # sign the agents' Claude Code in once (separate from the desktop app); -UseClaudeLogin fails fast without it
tools\launch.ps1 -Backend sim -DryRun         # print the commands and the game environment, start nothing
tools\stop.ps1                                # stop what launch.ps1 started (game + Foreman)
tools\stop.ps1 -Game                          # just the game: agents keep working, relaunch any time
```

From `cmd.exe` or Explorer: `tools\launch.cmd` / `tools\stop.cmd` (same arguments and exit codes;
they use PowerShell 7 when it is installed).

`launch.ps1`:
1. Checks everything before it starts anything: every argument (ports are whole numbers from 1 to
   65535 and not 3000, 5173 or 8080; folders exist; `-World`, `-Backend`, `-Seed`, `-Speed`,
   `-TimeoutSec` are valid; the same for `AGENTCRAFT_*` environment defaults), this checkout,
   Node.js 22+, a Java 25 JDK, and that the ports are free or held by our own Foreman / game.
   Every problem with the arguments is listed at once.
2. Reuses a running Foreman for `<home>/<profile>` (its `foreman.json` pid alive + port answering),
   otherwise starts one in the background (hidden console; log `artifacts\logs\foreman-<profile>.log`).
3. Builds if needed and starts Minecraft (`gradlew runClient`, `GRADLE_USER_HOME` =
   `<repo>\.gradle-home`), passing `JAVA_HOME`, `AGENTCRAFT_PORT`, `AGENTCRAFT_DEV_PORT`,
   `AGENTCRAFT_HOME`, `AGENTCRAFT_PROFILE`, `AGENTCRAFT_MUTE=0`, `AGENTCRAFT_FOCUS=1` to the game.
   Log: `artifacts\logs\game.log`.
4. Waits until the HQ world is ready and prints what runs where and how to stop it.

**Java.** The game runs on `JAVA_HOME` when that is a JDK 25 or newer, else on the newest JDK 25+
in the usual install folders (`%LOCALAPPDATA%\Programs\Eclipse Adoptium`, `Program Files\Eclipse
Adoptium`, `Java`, `Microsoft`, `Zulu`, `Amazon Corretto`, `BellSoft`, `~/.jdks`), else on the `java`
on PATH. A `JAVA_HOME` that points at an older JDK (Java 17, say) no longer breaks a launch: the
banner says which JDK the game uses, and your `JAVA_HOME` is not changed.

**Failures and Ctrl+C.** When a launch fails, or you press Ctrl+C, what *this* launch started is
stopped again the way `stop.ps1` does it (the game through `dev.quit`, the Foreman through
Ctrl+Break, both force-stopped after a timeout), together with an `npm` install it was running.
A Foreman or game it reused, and anything it did not start, is left alone. `-KeepOnFailure` leaves
what it started running instead (to look at a failed build, say). Every wait has a timeout, and a
failure prints the end of the log that explains it.

Exit codes:

| code | meaning |
| --- | --- |
| 0 | running (or `-DryRun`, `-Login`, `-NoGame`, `-NoWait` done) |
| 1 | a step failed: the Foreman did not come up, the build failed, the game hung, the world or the showcase was not ready in time, or an unexpected error |
| 2 | bad arguments: an unknown parameter, a value out of range, a folder that does not exist |
| 3 | a prerequisite is missing: Node.js 22+, a Java 25 JDK, part of the checkout, an `npm` install that failed, or the agents' Claude Code is not signed in (`-UseClaudeLogin`) |
| 4 | something else is in the way: a port held by a process this launcher did not start, or a game of this checkout started some other way |
| 130 | interrupted (Ctrl+C) |

If the game of this checkout is already running it is reused (one client per checkout: they share
`mod/run`). Quitting the game window leaves the Foreman running (agents keep working); the next
`launch.ps1` reuses it.

The mod has its own Foreman launcher (mod/DEV.md "Foreman launcher"): it finds the Foreman
`launch.ps1` started (or reused) on the port and leaves it alone (the console's `/foreman` says
"started by tools\launch.ps1"; `stop.ps1` stops it as before). With `-NoForeman` the game gets
`AGENTCRAFT_LAUNCHER=0` so it does not start one either; set `$env:AGENTCRAFT_LAUNCHER='1'` before
`launch.ps1 -NoForeman` to let the game start its own (how to try the launcher in a dev run).

| parameter | default | |
| --- | --- | --- |
| `-Backend sim\|claude\|codex` | `claude` (`AGENTCRAFT_BACKEND`) | `-Showcase` implies `sim` |
| `-World studio\|survival` | `studio` (`AGENTCRAFT_WORLD`) | which world the game opens: the agents' creative meadow, or a normal world in survival mode with the HQ built into its terrain (`AgentCraft Survival`) |
| `-Seed <n or text>` | studio: fixed; survival: random | seed of a world created on this launch (`AGENTCRAFT_WORLD_SEED`), one line of text |
| `-Repo <path>[,<path>]` | | existing folders; registered at start, or sent as `repo.add` to a running Foreman |
| `-Profile <name>` | backend name; `showcase` / `showcase-late` | state lives in `<home>/<profile>`; letters, digits, `-` and `_` |
| `-Showcase [busy\|late]` | | hold a static scripted state (QA screenshots); always a fresh (`--reset`) profile |
| `-Home <dir>` | `~/.agentcraft` (`AGENTCRAFT_HOME`); with `-Dev`: `<main checkout>\.agentcraft-home` | **QA/tests must pass the project home** |
| `-Port N` / `-DevPort N` | 7878 / 7879 (`AGENTCRAFT_PORT` / `AGENTCRAFT_DEV_PORT`) | 1 to 65535; 3000/5173/8080 are refused |
| `-Dev` | | unattended runs: muted, never steals focus, no toasts, Gradle daemon exits after 30 idle min |
| `-Reset` | | wipe the profile before starting (new Foreman only) |
| `-Speed x`, `-Autostart`, `-Goal "..."` | | sim speed (above 0) / start the scripted goal / submit a goal at start |
| `-ForemanArgs @('--workers','kit,wren')` | | extra Foreman flags (`npm run start -- --help`) |
| `-Notify` / `-NoNotify` | Foreman default (on for claude) | Windows toasts; not both |
| `-NoGame`, `-NoForeman`, `-NoWait` | | only the Foreman / only the game (the mod's launcher off too, unless `AGENTCRAFT_LAUNCHER` is set) / don't wait for the world |
| `-GradleHome <dir>` | `GRADLE_USER_HOME` or `<main checkout>\.gradle-home` | |
| `-TimeoutSec N` | 600; 1800 on a checkout's first build | how long to wait for the world (10 to 86400) |
| `-SummaryJson <file>` | | machine-readable result (what was started or reused, pids, ports, logs, `exitCode`) |
| `-DryRun` | | print the Foreman command, the game command and the game env; start nothing |
| `-KeepOnFailure` | | on a failure or Ctrl+C, leave what this launch started running |
| `-Login` | | sign the agents' Claude Code in, then exit |

`stop.ps1` stops only what `launch.ps1` started, using the run files in `artifacts\run\`
(pid + process start time, so a reused pid is never touched): the game via the DevBridge
`dev.quit` (world saved), the Foreman via Ctrl+Break into its hidden console (the Foreman saves
its state and releases `foreman.json`, like Ctrl+C in a terminal), then, after `-TimeoutSec`
(30), a force-kill of only those process trees. A Foreman that `launch.ps1` reused but did not
start is left alone. `-Game` / `-Foreman` / `-Profile` / `-Home` / `-Port` narrow it down;
`-FromSummary <launch summary>` stops exactly what one launch started; `-StopDaemon` also stops
this checkout's Gradle daemon (never another checkout's). Exit codes: 0 stopped (or nothing to
stop), 1 something is still running or the summary cannot be read.

## macOS and Linux

Requires Node 22+, git, and Java 25. On macOS install Java with `brew install openjdk@25`; on
Linux install your distribution's Java 25 JDK (Arch: `pacman -S jdk25-openjdk`) or set
`JAVA_HOME` to one. `launch-unix.mjs` uses that JDK directly, so no system Java changes are needed.

```sh
node tools/launch-unix.mjs launch --backend sim            # free simulated team
node tools/launch-unix.mjs stop --profile sim
node tools/launch-unix.mjs login                          # sign the agents' Claude Code in, once
node tools/launch-unix.mjs launch --repo /path/to/repo --use-claude-login
node tools/launch-unix.mjs stop                           # save/quit game, stop Foreman
node tools/launch-unix.mjs --help                         # every option
```

The launcher checks the options, Node 22+, Java 25 and the ports before it starts anything, installs
npm dependencies on first use, runs the Fabric development client, and waits for the studio world. It
reuses a running Foreman or game from the same profile. Use `--dev` for mute/no focus/no
notifications; `--no-game` or `--no-foreman` to run just one component; `--no-wait` to return
immediately while Minecraft builds; `--timeout SECONDS` to wait longer for the world (600, or 1800 on
a checkout's first build). Repeat `--foreman-arg VALUE` to pass extra Foreman options.

Java: `JAVA_HOME` when it is 25 or newer, else Homebrew's `openjdk@25` and `openjdk`,
`/Library/Java/JavaVirtualMachines` (macOS) or `/usr/lib/jvm` (Linux), SDKMAN and `~/.jdks`, newest
first, else the `java` on PATH. The game gets that JDK as its `JAVA_HOME`; your own is not changed.

If a launch fails or you press Ctrl+C, what that launch started is stopped again (`--keep-on-failure`
leaves it running); what it reused is left alone. The exit codes are the same as `launch.ps1`'s
(0 ok, 1 a step failed, 2 bad arguments, 3 a prerequisite is missing, 4 something else holds a port
or the game, 130 interrupted). Logs and process records live in `artifacts/logs/unix-*.log` and
`artifacts/run/unix-*.json` (named before the script was renamed from `unix.mjs`; kept so older runs
are still found). `stop` only signals processes recorded by this launcher. Agent decisions show a
desktop notification (Notification Center on macOS, `notify-send` on Linux). The screenshot QA
command, `node tools/run-screenshot-qa.mjs`, also uses this launcher on macOS and Linux. The mod's own
Foreman launcher (mod/DEV.md "Foreman launcher") finds the Foreman `launch-unix.mjs` started and
leaves it alone; with `--no-foreman` the game gets `AGENTCRAFT_LAUNCHER=0` (export
`AGENTCRAFT_LAUNCHER=1` first to let the game start its own).

### Your own launcher instance (Prism, MultiMC, ...)

The mod also runs in a normal Fabric instance for Minecraft 26.3 with Fabric Loader 0.19.5+:

1. Build the mod: `JAVA_HOME=<Java 25 JDK> sh mod/gradlew -p mod build`, then copy
   `mod/build/libs/agentcraft-<version>.jar` and Fabric API (`fabric_api_version` in
   `mod/gradle.properties`) into the instance's `mods/` folder.
2. Set the instance's environment variables `AGENTCRAFT_MUTE=0` (otherwise the mod forces your
   master and music volume to 0) and `AGENTCRAFT_FOCUS=1`. See mod/DEV.md for the rest.
3. Launch the instance. The title screen loads (or creates) the `AgentCraft HQ` world (or
   `AgentCraft Survival` with `AGENTCRAFT_WORLD=survival` in the instance's environment).

The mod starts the Foreman itself (mod/DEV.md "Foreman launcher"): from the checkout the jar was
built from, or the one `launcher.foremanDir` in `~/.agentcraft/config.json` names (or
`-Dagentcraft.foreman.dir=<checkout>` in the instance's JVM arguments). It needs Node 22+ and runs
`npm ci` in `foreman/` on first use; the HUD pill and the console's `/foreman` say what it is doing.
The backend and profile come from `AGENTCRAFT_BACKEND` / `AGENTCRAFT_PROFILE` (default `claude`).
The Foreman keeps running after the game exits; set `launcher.stopOnExit` to stop it with the game.
To run the Foreman yourself instead (`node tools/launch-unix.mjs launch --no-game --backend sim`, or
`tools\launch.ps1 -NoGame` on Windows), start it before the game: the mod finds it and leaves it alone.
`AGENTCRAFT_LAUNCHER=0` turns the mod's launcher off.

`node tools/devbridge-cli.mjs state` reports the running game either way while the DevBridge is on.

## Multiplayer harness

`multiplayer-harness.mjs` prepares Loom's development launch configuration once, then starts a
dedicated server, one local Foreman per player, and one or two clients. Requires Node 22.18+, Java
25, and `npm ci --prefix tools` / `npm ci --prefix foreman`. Uses portable macOS, Linux and Windows
launch paths. Windows process control uses the PowerShell helpers (`lib/procs.ps1`) and the background
runner. Two full `up` / `down` cycles with two clients were run on Windows on 2026-10-04 (the MP-H
worknote, kept on the [SandboxServers multiplayer branch](https://github.com/SandboxServers/agentcraft/blob/mp/integration/docs/multiplayer/worknotes/MP-H.md));
the Windows paths that run did not reach are marked below.

```sh
node tools/multiplayer-harness.mjs up --slot 92 --clients 2 --backend sim --json
node tools/multiplayer-harness.mjs status --slot 92 --json
node tools/multiplayer-harness.mjs down --slot 92 --json
```

The harness was built on SandboxServers' shared machines, where `game` holds a GPU slot and `gw` /
`gw-raw` wrap Gradle. There, launch through `game node tools/multiplayer-harness.mjs up --slot 92 ...`
with `--gradle-command gw-raw`, so preparation uses that environment and does not try to reacquire the
Gradle lock already held by `game`, and keep the slot held for the entire live check (including
`down`), for example with a shell script run by `game`. For separate preparation, run
`gw -I ../tools/lib/mp/export-launch.gradle mpExportLaunch -PmpLaunchFile=../artifacts/run/mp-92/launch.json --no-configuration-cache --console=plain`
from `mod/` (with the plain wrapper elsewhere), then `up --slot 92 --no-build ...`.

`--slot` is required (0..99), with the campaign's fixed port formulas. Slot 92 uses server
25692, Foremen 27984/27985 and DevBridges 8084/8085. `--clients` defaults to 2 and accepts 1.
The default backend is `sim`; `--backend claude` requires your usual credentials.
`--home` defaults to this checkout's `.agentcraft-home`, and `--profile` to `mp-<slot>`;
client profiles append `-a` / `-b`. Never use a shared home/profile with another launcher.

Each JVM has an explicit `-Xms256M` and a **2G maximum heap**, independently configurable
with `--server-heap` / `--client-heap` or `AGENTCRAFT_MP_SERVER_HEAP` /
`AGENTCRAFT_MP_CLIENT_HEAP` (flag wins; accepted range 256M..8G). Native memory and graphics
use additional RAM. Clients use 30 FPS in a 960x540 window and preserve the template
graphics preset. In Minecraft 26.3, Fancy applies render distance 16 and simulation distance
12 after loading options; the dedicated server separately limits view distance to 6 and
simulation distance to 4. The client minimum simulation distance is 5. Existing options
persist; delete only a generated client directory's `options.txt` to reseed it.
`JAVA_TOOL_OPTIONS`, `_JAVA_OPTIONS` and `JDK_JAVA_OPTIONS` must be unset
so injected JVM flags cannot override the caps. Gradle's own memory configuration is unchanged.

Java 25 is checked before preparation, including with `--no-build`. Gradle preparation
inherits `JAVA_HOME` and `GRADLE_USER_HOME`; when the latter is unset it uses this checkout's
`.gradle-home`. The portable default is `sh ./gradlew` on POSIX or `gradlew.bat` via PowerShell on
Windows. Override the executable with `--gradle-command` or `AGENTCRAFT_MP_GRADLE`. On Windows a
`.bat` or `.cmd` override runs through the same PowerShell invocation, because Node refuses to spawn
a batch file directly (`EINVAL`); a bare file name found in `mod/` is run from there. `--no-build`
reuses this slot's previously exported metadata; export again after changing code, Java/Loom
settings or platforms. The init script depends on Loom 1.18.2's `configureClientLaunch` (including
`downloadAssets` and platform natives when needed), then reads Loom's real task classpaths, JVM
arguments and DLI configuration without running `runClient` / `runServer` or editing
`mod/build.gradle`. Direct client launches use separate working directories and `--gameDir`,
preserving Loom's native/asset configuration. Java argfiles keep Windows command lines short.

Everything is recorded under `artifacts/run/mp-<slot>/`: `state.json`, `launch.json`, logs,
Java argfiles, `server/` and `client-a/` / `client-b/`. The local server is offline and bound
to loopback, accepts the Minecraft EULA for this development run, has both test players
opped, turns AgentCraft's multiplayer mode on (`config/agentcraft-server.json`), and seeds a
superflat world with grass at y=64. Worlds and client options persist across `down` / `up`; use a
fresh slot directory for a fresh world.

`up` refuses occupied ports (checked before the Gradle step and again right after it, before
anything is started) or an existing live run, waits up to `--timeout` seconds
(default 600) per startup stage, and rolls back on failure/interruption. Readiness requires
every client's `dev.state` to show a loaded remote world, the expected player and its own
synced Foreman. The harness reads "remote" as `world.name === null` plus a loaded dimension (the
rule from before MP-12 added mode and server fields); it does not imply that the server has sent the
multiplayer hello. The harness sets `AGENTCRAFT_DEV_REMOTE=1` for the game clients it launches (not
for the server or the Foremen), because it drives them through the DevBridge commands that act on a
remote server; a player's own client must not set it.

The library scans each client stdout log from the beginning for terminal DevBridge errors.
`up` handles a bind failure by stopping only that client, waiting 35 seconds on macOS or
65 seconds on Linux for TIME_WAIT, and relaunching it once. Windows uses no cooldown
(unverified). It prints the client, port, cause and log path with the recovery notice.
The retry shares the readiness stage's `--timeout` budget; insufficient time, a second bind
failure or a non-bind bridge error fails immediately and triggers rollback. Thus an immediate
`down` followed by `up` can take longer than a cold start; allow time for this recovery.
The port preflight uses a TCP connection, because Node's address reuse makes a `listen()`
probe unreliable for detecting Java's TIME_WAIT bind failures.
`status --json` includes process identities and the last startup states, dated by
`statesCapturedAt`; those DevBridge snapshots are historical. Status reports `ready`,
`partial` or `stopped` from live PID/start-time checks. JSON stdout contains one object;
progress goes to stderr. Success is `{ok:true, ...summary}`; failure is `{ok:false,error}`
and exits nonzero. Summary version 1 has these fields:

| Fields | Shape |
|---|---|
| `version`, `slot`, `phase` | `1`, slot number, `ready` / `partial` / `stopped` |
| `home`, `profile`, `stateFile` | Strings identifying the run |
| `error`, `statesCapturedAt` | Last startup error / ISO snapshot timestamp, or `null` |
| `server` | `port`, `heap`, `gameDir`, stdout `log`, `debugLog`, boolean `running` |
| `clients[]` | `id`, `username`, `profile`, `foremanPort`, `devPort`, `heap`, `gameDir`, `log`, `debugLog`, `foremanLog`, booleans `running` / `foremanRunning`, last `state` or `null` |
| `processes[]` | `kind`, `client` (id or `null`), `log`, recorded `pid` (or `null`), `primaryRunning`, live `processes[]` |
| `processes[].processes[]` | Only `pid`, `startTime`, `role` (`primary`, `wrapper`, `member`) |

A stopped run can retain historical states and process entries; each live process list is empty.

Always run `down`, including after a failed `up`. It stops only recorded PID/start-time
identities, can recover a launcher crash using unique per-process launch markers, and
never kills shared Gradle daemons or foreign port owners. An unfinished build is recovered
only from a process that is the Gradle launch itself (the default launcher, or Java running
Gradle with the harness's arguments), never from a command that merely quotes that command
line. It asks an owned DevBridge to quit and gives a client that answered up to 5 seconds to
exit by itself, then signals the clients, Foremen and dedicated server, with bounded waits and a
force-stop fallback. On Windows only Foremen receive Ctrl+Break (a JVM answers it with a
thread dump) and clients get their post-`dev.quit` wait; stopping an unfinished build there
kills its process tree except a Gradle daemon that build started (not yet run on Windows).
The Windows server has no stdin under the background runner, so there `up` enables RCON for it:
loopback only, a random password, and whichever port is free (kept in `state.json`; the slot's
block has none for it). `down` sends the console `stop` through it and waits up to 30 seconds for
the save and exit before killing (run on Windows: the server saved its worlds and exited by itself).
When the server had to be killed, on any platform, `down` says that it was killed without a final
save: changes since its last autosave are lost and the world is still reused. Incomplete cleanup
retains the records and returns an error so `down` can be retried. Only one controller can take
over a stale launcher control lock: a takeover claims the next generation under `control.lock/`
with an exclusive `mkdir`, and the others get "another up/down is active". Process-table access is
required: a sandbox denying `ps` or PowerShell inspection fails before launch.

Scripts can import the harness helpers:

```js
import { clientCommand, waitForClients, readServerEvents, readClientEvents } from './lib/mp/index.mjs';
// plan = the parsed state.json or CLI JSON summary (same server/client fields).
const states = await waitForClients(plan);
await clientCommand(plan, 'a', 'dev.camera', { anchor: 'cam_room' });
await clientCommand(plan, 'b', 'dev.state');
let { events, offset, generation } = readServerEvents(plan, { event: 'hello_sent' });
// pass both back: the generation tells a log replaced by a later `up` from one that only grew
({ events, offset, generation } = readServerEvents(plan, { offset, generation }));
const clientEvents = readClientEvents(plan, 'a'); // retain a separate cursor per file
```

`readServerEvents` and `readClientEvents` read each game directory's `logs/debug.log`,
including DEBUG-level telemetry. They return complete telemetry lines, parsed fields and
a byte offset for incremental reads. Stdout captures remain the startup/error logs.
`clientCommand` uses the existing `DevClient`, with bounded connection
and command timeouts, and always closes its connection. Run `npm test --prefix tools`
for port/CLI, launch isolation, memory caps, PID recovery, cleanup and library checks.

## Dev and QA tools

```powershell
node tools/devbridge-cli.mjs state --port 7889                       # DevBridge CLI (mod/DEV.md has the full command list)
node tools/foreman-cli.mjs status --port 27878                       # Foreman: backend/auth, agents, tasks, open decisions
node tools/foreman-cli.mjs diff --decision d3 --port 27878
node tools/foreman-cli.mjs send user.message to=kit "text=hi there" --port 27878   # any client message, prints the ack
node tools/capture-screenshots.mjs tools/scenes/qa.json --only qa01_exterior_hero --port 7889 --foreman 27878 --prefix wip/
node tools/run-screenshot-qa.mjs --port 27878 --dev-port 7889 --home C:\Projects\agentcraft\.agentcraft-home
node tools/play-camera-shots.mjs tools/shots/desk_story.json --port 7889 --hold 3000   # play a camera shot for OBS (shots/README.md)
node tools/watch-agent-life.mjs --port 7889 --seconds 60 --prefix agents_live/          # agent life, logged to artifacts/logs/
npm test --prefix tools                                              # the tools' own tests (launcher checks run under every PowerShell found)
```

Screenshot QA (scene format, anchor contract, judging): [docs/QA.md](../docs/QA.md).
