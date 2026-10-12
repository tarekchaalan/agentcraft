# tools/

## macOS and Linux

Requires Node 22+, git, and Java 25. On macOS install Java with `brew install openjdk@25`; on
Linux install your distribution's Java 25 JDK (Arch: `pacman -S jdk25-openjdk`) or set
`JAVA_HOME` to one. `unix.mjs` uses that JDK directly, so no system Java changes are needed.

```sh
node tools/unix.mjs launch --backend sim            # free simulated team
node tools/unix.mjs stop --profile sim
node tools/unix.mjs launch --repo /path/to/repo --use-claude-login
node tools/unix.mjs stop                           # save/quit game, stop Foreman
```

The launcher installs npm dependencies on first use, runs the Fabric development client,
and waits for the studio world. It reuses a running Foreman or game from the same profile.
Use `--dev` for mute/no focus/no notifications; `--no-game` or `--no-foreman` to run just
one component; `--no-wait` to return immediately while Minecraft builds. Repeat
`--foreman-arg VALUE` to pass extra Foreman options. Logs and process records live in
`artifacts/logs/unix-*.log` and `artifacts/run/unix-*.json`. `stop` only signals processes
recorded by this launcher. Agent decisions show a desktop notification (Notification Center on
macOS, `notify-send` on Linux). The screenshot QA command, `node tools/qa.mjs`, also uses this
launcher on macOS and Linux.

### Your own launcher instance (Prism, MultiMC, ...)

The mod also runs in a normal Fabric instance for Minecraft 26.3 with Fabric Loader 0.19.5+:

1. Build the mod: `JAVA_HOME=<Java 25 JDK> sh mod/gradlew -p mod build`, then copy
   `mod/build/libs/agentcraft-<version>.jar` and Fabric API (`fabric_api_version` in
   `mod/gradle.properties`) into the instance's `mods/` folder.
2. Set the instance's environment variables `AGENTCRAFT_MUTE=0` (otherwise the mod forces your
   master and music volume to 0) and `AGENTCRAFT_FOCUS=1`. See mod/DEV.md for the rest.
3. Start only the Foreman: `node tools/unix.mjs launch --no-game --backend sim` (or `--repo ...`).
4. Launch the instance. The title screen loads (or creates) the `AgentCraft HQ` world (or
   `AgentCraft Survival` with `AGENTCRAFT_WORLD=survival` in the instance's environment).

`node tools/devcli.mjs state` reports the running game either way while the DevBridge is on.

## Windows

Windows PowerShell 5.1 or PowerShell 7 (`pwsh -File tools\launch.ps1 ...`) and Node 22. `launch.ps1` installs the npm dependencies it needs on the
first run (`npm ci` in `foreman/` and `tools/`); the Gradle wrapper downloads Gradle, Minecraft
and Fabric by itself. Java 25 must be installed (Temurin 25: https://adoptium.net).

### Guided start

`start-agentcraft.cmd` in the repository root (double-click it, or run it from cmd.exe) runs
`tools\start-agentcraft.ps1`, which does the checking so you do not have to:

1. The checkout itself, free disk space, and whether `winget` is available.
2. **Git**: offers `winget install Git.Git`.
3. **Node.js 22+**: offers `nvm install 22` if you have nvm-windows, else winget
   (`OpenJS.NodeJS.LTS`) or a portable copy in `%LOCALAPPDATA%\Programs\nodejs` added to your user
   PATH.
4. **Java 25 JDK**: finds one in `JAVA_HOME` or the usual install folders (Temurin, Oracle,
   Microsoft, Zulu, Corretto, `~/.jdks`); else offers a portable Temurin 25 from
   api.adoptium.net into `%LOCALAPPDATA%\Programs\Eclipse Adoptium`. No admin rights, nothing else
   on the machine changes; `JAVA_HOME` is set for that launch only.
5. **How the agents run**: your Claude login (installs the `claude` CLI with Anthropic's installer
   if missing and runs `claude auth login` if you are not logged in), an API key you paste (kept
   for that launch only), or the free simulated team. `-Backend codex` checks for the Codex CLI.
6. The repository the agents should work in (Enter = the demo repo in `sandbox/`), the world
   (Studio or Survival), then `tools\launch.ps1` with the matching arguments.

Every install is asked about first (yes / skip / quit), waited for, and checked again
afterwards; an internet check runs before any download. Each run is logged to
`artifacts\logs\start-<timestamp>.log`. Exit codes: 0 ok, 1 something is still missing, 2 you
quit, 3 the launch failed, 4 unexpected error; the window stays open on any of them.

| argument | |
| --- | --- |
| `studio` / `survival` (or `-World`) | which world, skips the question |
| `-Repo <path>` | repository to register, skips the question |
| `-Backend sim\|claude\|codex` | as for `launch.ps1` |
| `-UseClaudeLogin` | login mode without being asked |
| `-Check` | only check and offer installs, do not launch |
| `-Yes` | unattended: every question gets its default (installs included) |
| `-NoInstall` | never install, just report |
| `-LaunchArgs '-Dev','-Seed','1234'` | passed through to `launch.ps1` |

## Daily use

```powershell
tools\launch.ps1                              # claude backend, state in ~/.agentcraft, Foreman :7878, DevBridge :7879
tools\launch.ps1 -Repo C:\code\life-tracker   # also register a repo with the Foreman
tools\launch.ps1 -UseClaudeLogin              # your `claude` CLI login instead of an API key (personal use); banner: auth ok (claude login)
tools\launch.ps1 -Backend sim                 # scripted demo team (no API calls), demo repo in sandbox/
tools\launch.ps1 -Showcase                    # static showcase state (sim); -Showcase late for the later one
tools\stop.ps1                                # stop what launch.ps1 started (game + Foreman)
tools\stop.ps1 -Game                          # just the game: agents keep working, relaunch any time
```

From `cmd.exe` or Explorer: `tools\launch.cmd` / `tools\stop.cmd` (same arguments).

`launch.ps1`:
1. Reuses a running Foreman for `<home>/<profile>` (its `foreman.json` pid alive + port answering),
   otherwise starts one in the background (hidden console; log `artifacts\logs\foreman-<profile>.log`).
2. Builds if needed and starts Minecraft (`gradlew runClient`, `GRADLE_USER_HOME` =
   `<repo>\.gradle-home`), passing `AGENTCRAFT_PORT`, `AGENTCRAFT_DEV_PORT`, `AGENTCRAFT_HOME`,
   `AGENTCRAFT_PROFILE`, `AGENTCRAFT_MUTE=0`, `AGENTCRAFT_FOCUS=1` to the game. Log: `artifacts\logs\game.log`.
3. Waits until the HQ world is ready and prints what runs where and how to stop it.

If the game of this checkout is already running it is reused (one client per checkout: they share
`mod/run`). Quitting the game window leaves the Foreman running (agents keep working); the next
`launch.ps1` reuses it.

| parameter | default | |
| --- | --- | --- |
| `-Backend sim\|claude` | `claude` (`AGENTCRAFT_BACKEND`) | `-Showcase` implies `sim` |
| `-World studio\|survival` | `studio` (`AGENTCRAFT_WORLD`) | which world the game opens: the agents' creative meadow, or a normal world in survival mode with the HQ built into its terrain (`AgentCraft Survival`) |
| `-Seed <n or text>` | studio: fixed; survival: random | seed of a world created on this launch (`AGENTCRAFT_WORLD_SEED`) |
| `-Repo <path>[,<path>]` | | registered at start, or sent as `repo.add` to a running Foreman |
| `-Profile <name>` | backend name; `showcase` / `showcase-late` | state lives in `<home>/<profile>` |
| `-Showcase [busy\|late]` | | hold a static scripted state (QA screenshots); always a fresh (`--reset`) profile |
| `-Home <dir>` | `~/.agentcraft` (`AGENTCRAFT_HOME`); with `-Dev`: `<main checkout>\.agentcraft-home` | **QA/tests must pass the project home** |
| `-Port N` / `-DevPort N` | 7878 / 7879 (`AGENTCRAFT_PORT` / `AGENTCRAFT_DEV_PORT`) | 3000/5173/8080 are refused |
| `-Dev` | | unattended runs: muted, never steals focus, no toasts, Gradle daemon exits after 30 idle min |
| `-Reset` | | wipe the profile before starting (new Foreman only) |
| `-Speed x`, `-Autostart`, `-Goal "..."` | | sim speed / start the scripted goal / submit a goal at start |
| `-ForemanArgs @('--workers','kit,wren')` | | extra Foreman flags (`npm run start -- --help`) |
| `-Notify` / `-NoNotify` | Foreman default (on for claude) | Windows toasts |
| `-NoGame`, `-NoForeman`, `-NoWait` | | only the Foreman / only the game / don't wait for the world |
| `-GradleHome <dir>` | `GRADLE_USER_HOME` or `<main checkout>\.gradle-home` | |
| `-TimeoutSec N` | 600 | how long to wait for the world |
| `-SummaryJson <file>` | | machine-readable result (what was started or reused, pids, ports, logs) |
| `-DryRun` | | print the Foreman command, the game command and the game env; start nothing |

`stop.ps1` stops only what `launch.ps1` started, using the run files in `artifacts\run\`
(pid + process start time, so a reused pid is never touched): the game via the DevBridge
`dev.quit` (world saved), the Foreman via Ctrl+Break into its hidden console (the Foreman saves
its state and releases `foreman.json`, like Ctrl+C in a terminal), then, after `-TimeoutSec`
(30), a force-kill of only those process trees. A Foreman that `launch.ps1` reused but did not
start is left alone. `-Game` / `-Foreman` / `-Profile` / `-Home` / `-Port` narrow it down;
`-FromSummary <launch summary>` stops exactly what one launch started; `-StopDaemon` also stops
this checkout's Gradle daemon (never another checkout's).

## Dev / QA tools

```powershell
node tools/devcli.mjs state --port 7889                 # DevBridge CLI (mod/DEV.md has the full command list)
node tools/foremancli.mjs status --port 27878           # Foreman: backend/auth, agents, tasks, open decisions
node tools/foremancli.mjs diff --decision d3 --port 27878
node tools/foremancli.mjs send user.message to=kit "text=hi there" --port 27878   # any client message, prints the ack
node tools/shoot.mjs tools/scenes/qa.json --only qa01_exterior_hero --port 7889 --foreman 27878 --prefix wip/
node tools/qa.mjs --port 27878 --dev-port 7889 --home C:\Projects\agentcraft\.agentcraft-home
node tools/record.mjs tools/shots/desk_story.json --port 7889 --hold 3000   # play a camera shot for OBS (shots/README.md)
npm test --prefix tools
```

Screenshot QA (scene format, anchor contract, judging): [docs/QA.md](../docs/QA.md).

| file | |
| --- | --- |
| `launch.ps1`, `stop.ps1`, `launch.cmd`, `stop.cmd` | launcher |
| `lib/procs.ps1` | shared PowerShell helpers (run files, process identity, Ctrl+Break, ports) |
| `lib/bgrun.mjs` | background runner: owns the log files and the hidden console of a background process |
| `devcli.mjs`, `lib/devclient.mjs` | DevBridge client |
| `foremancli.mjs`, `lib/foremanclient.mjs` | Foreman WS client (hello, acks, diff, live state mirror) |
| `shoot.mjs`, `lib/scene.mjs` | scene runner (anchors, screens, Foreman messages, waits) |
| `record.mjs`, `shots/*.json` | real-time shot player for screen recording (`dev.play`: camera paths, timed Foreman injections, typing); format in `shots/README.md` |
| `qa.mjs`, `lib/contactsheet.mjs`, `scenes/qa.json` | QA suite, contact sheet (pngjs) |
| `scenes/phase1.json`, `scenes/qa-selftest.json` | Phase 1 proof scene, runner self-test |
