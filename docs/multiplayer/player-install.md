# Player install and join

> Type: how-to. Audience: someone joining the hosted AgentCraft server.
> Updated: 2026-10-04 (MP-14). Companions: [deploy runbook](deploy.md), [campaign README](README.md).

One shared Minecraft world, one studio plot per player. You bring your own Foreman: it runs on
**your** machine and owns your agents, code and credentials. The server only relays a redacted
public subset of your studio, so joining never uploads your repository or your keys.

The operator sends you the server's address and port. This guide uses `<server address>` and
`<port>` as placeholders; the real values are never written down in this public repository.

## What you need

| Component | Version | Where |
|---|---|---|
| Minecraft: Java Edition | **26.3** | your launcher |
| Java runtime | **25** | bundled with current Minecraft launchers; the mod targets Java 25 |
| Fabric Loader | **0.19.5 or newer** | <https://fabricmc.net/use/installer/> |
| Fabric API | **0.161.0+26.3** | Modrinth or CurseForge, for 26.3 |
| AgentCraft mod jar | the release the server runs | the `agentcraft-<version>.jar` attached to each GitHub **pre-release** |
| Node.js | **22.18 or newer** | <https://nodejs.org/> (for your Foreman) |
| This repository | `main` | clone `SandboxServers/agentcraft` (for `foreman/` and `tools/`) |

Install exactly the mod jar the server runs: download `agentcraft-<version>.jar` from the
GitHub pre-release the operator points you at, not a jar built from a random checkout. The
server jar and your client jar must match.

## Install the client

1. Run the Fabric installer for Minecraft **26.3** and let it create a profile in your launcher.
2. Open your game directory (`%APPDATA%\.minecraft` on Windows, `~/Library/Application Support/minecraft`
   on macOS, `~/.minecraft` on Linux) and put these two jars in `mods/`:
   - `fabric-api-0.161.0+26.3.jar`
   - `agentcraft-<version>.jar` from the pre-release.
3. Launch the Fabric 26.3 profile once and check the game log: `agentcraft` should be listed
   among the loaded Fabric mods. On launch the mod opens (and on first launch creates) a local
   singleplayer world named "AgentCraft HQ". That is expected: it is your own studio for
   singleplayer use.
4. **Turn the developer bridge off.** Add `-Dagentcraft.dev=0` to the profile's JVM arguments
   (in the launcher: Installations, edit the profile, More options, JVM arguments), or set the
   environment variable `AGENTCRAFT_DEV=0` for the game. The DevBridge is a control socket on
   `127.0.0.1` that the project's own tools use for screenshots, the camera and commands. It is
   on by default, a player does not need it, and any program on your machine that reaches it
   can drive your game. Its commands that act on a remote server also need
   `AGENTCRAFT_DEV_REMOTE=1`, which only the test harness sets: never set that for a client you
   play with on a shared server.

## Run your own Foreman

The Foreman is a Node process on your machine; the mod is only a view. It keeps your agents
working while the game is closed.

```sh
# from your clone of the repository, once:
npm ci --prefix foreman
npm ci --prefix tools
```

Then start the Foreman **before** you join. Start only the Foreman; you join the remote server
with your normal Minecraft launcher.

- **Windows (PowerShell):** `tools\launch.ps1 -NoGame -Repo <path to your repository>`
- **macOS:** `node tools/mac.mjs launch --no-game --repo <path to your repository>`
- **Linux:** from `foreman/`, `npm run start -- --backend claude --repo <path to your repository>`

The Claude backend needs an Anthropic API key (`ANTHROPIC_API_KEY`), or your local `claude` CLI
login (`-UseClaudeLogin` on Windows, `--use-claude-login` on macOS); `foreman/README.md` has the
details.

The mod dials your Foreman at `127.0.0.1:7878` by default. If you run it elsewhere, set
`AGENTCRAFT_PORT` (or the `-Dagentcraft.port=` system property) for the game to match.

## Join

1. With the Foreman running, start Minecraft and the Fabric 26.3 profile. The mod takes you
   straight into the local "AgentCraft HQ" world, so you do not see the title screen.
2. Leave that world (Esc → Save and Quit to Title), then Multiplayer → Add Server →
   `<server address>:<port>` → Join. To skip the local world, set `AGENTCRAFT_AUTOWORLD=0` in the
   launcher profile's environment (or add `-Dagentcraft.autoworld=0` to its JVM arguments), or
   start the game with the launcher's quick-play option for that server
   (`--quickPlayMultiplayer <server address>:<port>`), which the mod honours.
3. Your Minecraft account must be on the server's whitelist. If the connection is refused as
   "not whitelisted", ask the operator to run, in the server console:
   ```
   whitelist add <your Minecraft name>
   ```
4. On a healthy join you get your own plot and can walk to your studio. The server logs a
   `hello_received` line for your join.

The server runs in **online-mode**, so your Minecraft account is your studio identity; do not
join through an offline/cracked launcher.

## What the server sees

Only the redacted public subset of your Foreman state leaves your machine: agent identity and
skin, state and station, active/paused/awaiting, whether your Foreman is online, task counts,
the open-decision and open-merge counts, goal status and progress, a CI slot index per repo, and
the lamp, podium, merge-station and monitor states your studio shows (the authoritative list is
"The public studio state" in [work-packets.md](work-packets.md)). Activity text, speech text, task titles and
goal text are **off by default** and are separate opt-ins. Your code, diffs, decisions, console,
memory, logs and credentials never reach the server or another player. When you disconnect, your
studio dims to "Foreman offline".

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| "Incompatible mod set!" | Fabric API, Fabric Loader or the mod jar is the wrong version for 26.3. Use the exact versions above. |
| "You are not whitelisted on this server" | Ask the operator to `whitelist add <your Minecraft name>`. |
| "Connection refused" / cannot reach the server | Wrong `<server address>` or `<port>`, or the operator has not forwarded it. |
| You joined but your studio has no agents | Your Foreman is not running, or the game and the Foreman disagree on the port. Start the Foreman first and check `127.0.0.1:7878`. |
| Other players' studios do not appear | You are out of relay range, or the server still has multiplayer off (`"enabled": false`; see the [deploy runbook](deploy.md#multiplayer-config)). |
