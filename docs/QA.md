# Screenshot QA

The QA suite is how AgentCraft is judged against [docs/visual-bar.md](visual-bar.md): ten fixed
camera shots of a deterministic showcase state, a contact sheet and a manifest per run, and
independent judges who score each shot on the rubric. Nothing ships until every shot scores
8 or more on every axis.

## Run it

In a fresh clone, install the QA runner's dependencies first with `npm ci --prefix tools`.

```sh
# the normal QA run: starts what is missing, shoots, writes the sheet, stops what it started
node tools/qa.mjs --port 27878 --dev-port 7889 --home /path/to/agentcraft/.agentcraft-home

node tools/qa.mjs ... --showcase late             # the later state (blocked, error, done agents)
node tools/qa.mjs ... --only qa03_task_wall,qa04_agent_desk --run-id 20261001-2310   # re-shoot into an existing run
node tools/qa.mjs ... --keep                      # leave Foreman + game running (fast re-runs)
node tools/qa.mjs ... --no-launch                 # use what is already running, never start/stop anything
node tools/qa.mjs ... --strict                    # skipped shots fail the run (use once the HQ has every anchor)
node tools/qa.mjs ... --stop-daemon               # also stop this checkout's Gradle daemon at the end
```

Defaults: `--port`/`--dev-port` come from `AGENTCRAFT_PORT`/`AGENTCRAFT_DEV_PORT` or 7878/7879.
`--home` defaults to `<main checkout>/.agentcraft-home` (QA never uses `~/.agentcraft`), and the
profile to `showcase` (`showcase-late` for `--showcase late`). When several agents run QA at the
same time, give each its own port pair **and** its own `--profile` (e.g. `--profile qa-tools`):
the profile lives in the shared home, and a live Foreman for a profile is always reused, even on
another agent's port. qa.json takes repo/worktree ids from the live state, so any profile name
works. At most one game per checkout.

What a run does:

1. Starts (or reuses) a **showcase Foreman** and the **game** with
   `tools/launch.ps1` on Windows or `tools/unix.mjs` on macOS and Linux (muted, never
   steals focus, no toasts). Whatever was already running is reused and left running.
2. Waits for a ready world and for the Foreman to hold the showcase state
   (`foreman.status.showcase`), then asks the mod for its camera anchors (`dev.anchors`).
3. Runs `tools/scenes/qa.json` through the scene runner (`tools/lib/scene.mjs`).
4. Writes `artifacts/shots/qa/<runId>/`:
   - `qa01_exterior_hero.png` ... `qa10_night.png` (1920x1080 framebuffer captures)
   - `contact_sheet.png`: all shots in one image, with name, status and camera source
   - `manifest.json`: git sha, ports, Foreman summary (agents, tasks, open decisions), the
     launch summary, per shot: status (`ok` / `skipped` / `failed`), reason, camera used and
     where it came from, Foreman checks (e.g. the real diff behind the diff shot), image stats
     (`meanLuma`, `stdLuma`, `darkFraction`) and warnings
   - `launch.log`, `stop.log`, `launch.json`
   - `artifacts/shots/qa/latest.json` points at the newest run
5. Puts the player and the clock back where they were before the run (`--no-restore` skips it;
   the QA world is the checkout's real HQ world in `mod/run`), then stops exactly what it started
   (through the platform's stop command, using the launch summary).

Exit code 0 = no shot failed (and, with `--strict`, none was skipped). A run takes about
30-50 s with a warm Gradle daemon.

Always **Read the PNGs** (the contact sheet first, then each shot at full size) before claiming
anything about how it looks.

## What is shot (tools/scenes/qa.json)

| shot | subject (visual-bar.md QA camera set) | camera | extra |
| --- | --- | --- | --- |
| qa01_exterior_hero | golden hour, 3/4 view of the HQ | `cam_exterior_hero`, fallback raw camera | time 12000 |
| qa02_entrance_atrium | entrance looking into the Goal Atrium | `cam_entrance_atrium` | |
| qa03_task_wall | Task Wall straight-on, ~10 cards across columns | `cam_task_wall` | waits for tasks in the Foreman state |
| qa04_agent_desk | agent at desk, monitor log legible | `cam_agent_desk` > `cam_desk_juniper` > `cam_desk_tove` | 2.5 s settle |
| qa05_wide_interior | 3+ agents working at different stations | `cam_wide_interior` | 2.5 s settle |
| qa06_decision_podium | open decision + agent waiting near the user | `cam_decision_podium` | needs an open decision |
| qa07_console | console screen with `@ju` autocomplete | `cam_console` > `cam_wide_interior` > `cam_entrance_atrium` > raw | screen `console`, types `@ju` |
| qa08_diff_review | diff screen with the real multi-file diff | `cam_merge_station` > `cam_wide_interior` > raw | checks the merge decision's diff has >= 2 files, opens screen `diff` |
| qa09_library | library / memory screen | `cam_library` > `cam_wide_interior` > raw | screen `library` |
| qa10_night | lit interior through the windows | `cam_night` > `cam_exterior_hero` > raw | time 18000 |

The showcase busy state (`foreman/README.md`): Marlow waiting_user@user, Juniper editing@desk,
Kit testing@testbench, Wren idle@lounge, Rowan reading@library, Tove thinking@desk; 9 tasks
(done 2, doing 2, review 1, todo 3, blocked 1); open decisions `d3` (merge of `wren-t4`, a real
2-file diff in repo `sim-demo-showcase`) and `d4` (question); 4 memory entries. The QA set is
judged on this state. `--showcase late` (Marlow thinking@meeting, Juniper running@terminal, Kit
done@lounge, Wren blocked@desk, Rowan error@library, Tove editing@desk) has **no open
decisions**, so qa06 shows an idle podium and qa08 is skipped ("no open merge decision"); use it
to check the blocked/error/done looks.

## Contract with the mod (what the HQ builder and the screens must provide)

### `dev.anchors` (DevBridge command)

```json
{ "type": "dev.anchors" }
-> { "ok": true, "anchors": { "cam_exterior_hero": { "x": -31.5, "y": 79.2, "z": -27.5, "yaw": -48, "pitch": 14, "fov": 70 }, ... } }
```

- `x, y, z` = the **eye** position (what `dev.camera` takes by default), `yaw`/`pitch` in
  Minecraft convention (yaw 0 = +Z south, 90 = -X west, -90 = +X east; pitch positive looks down).
  Instead of yaw/pitch an anchor may give `lookAt: {x, y, z}`. `fov` (30-110) is optional; without it
  the shot uses the scene's `fov` (70).
- The names may also be returned at the top level of the reply instead of under `anchors`; both work.
- Anchors must come from the same deterministic HQ builder that places the blocks, so they move
  with the building. Coordinates are absolute world coordinates.
- Until `dev.anchors` exists, shots fall back to the scene's raw cameras where that makes sense
  (exterior, night, screen shots) and are **skipped with a reason** otherwise. They never fail
  because an anchor is missing.

| anchor | required | what it must frame |
| --- | --- | --- |
| `cam_exterior_hero` | yes | the whole HQ from outside, 3/4 view, slightly above, roofline + terraces + landscaping, lit by the golden-hour sun (time 12000, sun in the west) |
| `cam_entrance_atrium` | yes | from the main entrance (inside the door), the Goal Atrium as the focal point: hologram + progress ring readable |
| `cam_task_wall` | yes | straight-on and centered on the Task Wall, all columns in frame, card text legible at 1080p |
| `cam_agent_desk` | yes | close-up of the desk where the showcase's editing agent (Juniper) sits: the agent and its monitor, log text legible |
| `cam_wide_interior` | yes | wide interior with 3+ stations and agents (desks, test bench, library...) |
| `cam_decision_podium` | yes | the Decision Podium (bell/beacon on while a decision is open) with Marlow waiting near the user spot |
| `cam_night` | recommended | exterior at night with the lit interior visible through the windows (falls back to `cam_exterior_hero`) |
| `cam_console` | optional | world behind the console screen (falls back to `cam_wide_interior`) |
| `cam_merge_station` | optional | world behind the diff screen (falls back to `cam_wide_interior`) |
| `cam_library` | optional | world behind the library screen (falls back to `cam_wide_interior`) |
| `cam_desk_<agentId>` | optional | per-desk close-ups (`cam_desk_juniper`, `cam_desk_tove`, ...) |

More anchors (`cam_testbench`, `cam_lounge`, ...) are welcome; scenes can use any name.

### Registered screens (`DevBridge.registerScreen`)

| `dev.screen` request | expected |
| --- | --- |
| `{"open": "console"}` | the command console with the input focused; `dev.type "@ju"` must show the agent autocomplete (Juniper) |
| `{"open": "diff", "decisionId": "d3", "repoId": "sim-demo-showcase", "worktree": "wren-t4"}` | the diff review screen for that worktree (the mod sends `diff.request` itself) |
| `{"open": "library"}` | the memory library / memory screen (optionally `"memoryId"`) |
| `{"open": "task"}` | the task detail screen (Task Wall card click); pick the task first with `dev.taskwall {open: "t3"}`, else the first doing task |

`registerScreen` factories currently receive only `mc`, not the request. Either give them the
request JSON (preferred: an overload `(mc, req) -> Screen`), or make the `diff` screen default to
the oldest open merge decision when it gets no arguments; qa.json works with both. A screen the
mod does not register makes the shot `skipped` ("screen 'diff' is not registered by this mod build").

### `dev.state.foreman`

The scene first waits (30 s) for `dev.state.foreman.connected == true`, so shots never show a
half-synced world. Expected shape (filled in by the Foreman link's state contributor):
`{"connected": true, "port": 7878, ...}`. If a mod build never reports it, the wait is skipped
after 5 s and noted in the run log.

## Scene format (tools/lib/scene.mjs; shoot.mjs and qa.mjs share it)

```jsonc
{
  "description": "...",
  "foreman": { "connect": true },             // shoot.mjs connects to the Foreman (AGENTCRAFT_PORT/7878 or --foreman N)
  "defaults": { "time": 12000, "weather": "clear", "hideHud": true, "mode": "spectator", "fov": 70 },
  "anchors": { "cam_x": { "x": 0, "y": 70, "z": 0, "yaw": 0, "pitch": 10 } },   // fallback anchor table (the mod's dev.anchors wins)
  "setup": ["/time set 12000"],               // commands once before the shots
  "setupDev": [{ "type": "dev.hud", "hidden": true }],                 // DevBridge requests once
  "setupForeman": [{ "type": "user.message", "to": "kit", "text": "hi" }],  // Foreman messages once
  "waitFor": [{ "path": "foreman.connected", "equals": true, "timeoutMs": 30000 }],
  "shots": [{
    "name": "qa04_agent_desk",                // -> <prefix>qa04_agent_desk.png
    "title": "shown on the contact sheet and in the manifest",
    "anchor": ["cam_agent_desk", "cam_desk_juniper"],   // first one available wins
    "camera": { "x": 0, "y": 70, "z": 0, "yaw": 0, "pitch": 10 },   // raw camera / fallback; or "lookAt": {x,y,z}
    "offset": { "dx": 0, "dy": 0.5, "dz": 0, "dyaw": 5, "dpitch": -2 },  // tweak an anchor without touching the mod
    "fov": 70, "mode": "spectator",
    "time": 18000, "weather": "clear",
    "commands": ["/setblock 0 65 0 lantern"], // before the camera move ({{templates}} allowed)
    "dev": [{ "type": "dev.key", "key": "escape", "expectOk": false }],
    "foreman": [                              // Foreman messages, each waits for its ack
      { "type": "diff.request", "repoId": "{{merge.repoId}}", "worktree": "{{merge.worktree}}", "minFiles": 2 },
      { "type": "decision.answer", "decisionId": "{{question.id}}", "option": 0, "optional": true }
    ],
    "screen": "console",                      // or { "open": "diff", ...fields passed to dev.screen }
    "type": "@ju",                            // dev.type into the open screen
    "keys": ["key.keyboard.tab"],             // dev.key presses
    "waitFor": { "foreman": "decision.id", "exists": true, "timeoutMs": 5000 },  // or { "path": "<dev.state path>", ... }
    "waitMs": 1500, "waitFrames": 10,         // per-shot settle before the capture (dev.wait)
    "hideHud": true, "frames": 3, "waitChunks": true, "chunkTimeoutMs": 30000,
    "keepScreen": false,                      // the screen is closed after the shot by default
    "onMissingScreen": "skip",                // or "fail"
    "requires": { "foreman": true },
    "skip": "reason"                          // temporarily disable a shot
  }]
}
```

Order per shot: camera is resolved (skip if impossible) -> `commands` -> `dev` -> `foreman` ->
time/weather -> `dev.camera` -> `screen` -> `type`/`keys` -> `waitFor` -> `waitMs`/`waitFrames` ->
`dev.screenshot` -> screen closed.

Templates: any string may use `{{...}}` over the live Foreman state: `merge`, `question`,
`permission`, `decision` (oldest open decision of that kind / any), `repo` (first repo), `goal`,
`foreman` (status), `agents.<id>`, `tasks.<id>`, `decisions.<id>`, `repos.<id>`. A whole-string
template keeps its type (`"{{merge.createdAt}}"` stays a number). A template that cannot resolve
skips the shot with the reason ("needs {{merge.repoId}} but the Foreman has no open merge decision").

`waitFor` conditions: `{path | foreman, equals | notEquals | gte | lte | exists, timeoutMs,
required, unreportedMs, optional}`. A timed-out wait is a warning unless `required: true`.

Older scene files keep working: a bare array of shots, and `delayMs` as another name for `waitMs`.

## Adding or changing a shot

1. If it needs a new place in the HQ, add an anchor to the HQ builder (`cam_<what>`), next to the
   blocks it frames, and list it in the table above.
2. Add the shot to `tools/scenes/qa.json` (or a new scene file for non-QA sets). Prefer anchors;
   give a raw `camera` only when the subject does not depend on the HQ layout.
3. Iterate on one shot quickly against a running game:
   `node tools/shoot.mjs tools/scenes/qa.json --only qa04_agent_desk --port 7889 --foreman 27878 --prefix wip/`
   and Read `artifacts/shots/wip/qa04_agent_desk.png`. `--anchors my.json` overrides anchors for
   experiments; `offset` nudges an anchor from the scene.
4. Run the full suite (`node tools/qa.mjs ...`) and Read the contact sheet.
5. Keep the visual-bar.md QA list and this file in sync if the set itself changes.

## How judges score

- Judge from the PNGs at full size (the contact sheet is only an index). Use `manifest.json` for
  context: which state was shown (agents, tasks, open decisions), where each camera came from
  (`mod` anchor vs fallback), and warnings (chunks not done, paused, nearly black).
- Score every shot 1-10 on each axis of the rubric in [docs/visual-bar.md](visual-bar.md)
  (section "Review rubric"):
  cohesion with art direction, composition & readability, lighting, character appeal,
  text/UI legibility & polish, alive-ness, practical clarity ("could a user understand the state
  of work in 5 seconds?"). Axes that genuinely do not apply to a shot (e.g. character appeal on a
  pure diff screen with no agent visible) are marked `null` with a reason, not given a free 10.
- A **skipped or fallback-camera shot is not passable**: it scores nothing until it is shot from
  its real anchor. Run with `--strict` once the HQ provides every anchor.
- Be concrete: each score below 8 needs the specific defect and where it is ("Task Wall cards:
  text unreadable below 1080p, card spacing 0", "dark corner left of the podium").
- Write scores next to the shots: `artifacts/shots/qa/<runId>/scores-<judge>.json`

```json
{
  "judge": "judge-a", "runId": "20261001-231000",
  "shots": {
    "qa03_task_wall": {
      "scores": { "cohesion": 8, "composition": 7, "lighting": 8, "character": null, "legibility": 6, "aliveness": 7, "clarity": 8 },
      "notes": ["card titles truncate at 14 chars", "no column headers"]
    }
  }
}
```

The bar: every shot >= 8 on every applicable axis from independent judges.

## Launch, stop and process hygiene

- `tools\launch.ps1 -Dev ...` is the launcher QA uses (see `tools/README.md`). `-Dev` = muted
  (`AGENTCRAFT_MUTE=1`), no focus stealing (`AGENTCRAFT_FOCUS=0`), Foreman `--no-notify`, Gradle
  daemon idle timeout 30 min, and the home defaults to `<main checkout>/.agentcraft-home`.
- `tools\stop.ps1` stops only what `launch.ps1` started (run files in `artifacts/run/` with pid +
  start time): the game via `dev.quit` (world saved), the Foreman via Ctrl+Break into its hidden
  console (state saved, `foreman.json` released), then a force-kill of only those process trees
  after a timeout. `-StopDaemon` also stops this checkout's Gradle daemon (marked with
  `-XX:ErrorFile=agentcraft-<checkout hash>-hs_err.log` in its JVM args, so it never touches other
  checkouts' daemons).
- Screenshots only ever come from the DevBridge (framebuffer capture); nothing captures the desktop.

## Troubleshooting

| symptom | what to do |
| --- | --- |
| `skipped: no camera: anchor ... not available (dev.anchors is not implemented by this mod build)` | expected before the HQ builder lands; the mod needs `dev.anchors` |
| `skipped: screen 'diff' is not registered` | the mod build has no such screen yet |
| warning `image is nearly black` / `nearly flat` | camera inside a block, or the world did not render; check the anchor |
| warning `chunks did not finish building` | far teleport on a slow frame; re-run the shot (`--only`) |
| warning `the game was paused during the capture` | a pause menu opened (see mod/DEV.md known issues); `dev.camera` closes it before each shot |
| `aborting: the game looks hung` | `tools\stop.ps1 -Game` (dev.quit force-exits a hung game), then re-run |
| launch fails `port N is already in use by pid ...` | another Foreman/game owns that port: use your own port pair |
| launch fails `a Minecraft client of this checkout is already running` | one client per checkout (shared `mod/run`): quit it (`node tools/devcli.mjs quit --port N`) |

## Testing the tools themselves

```powershell
npm test --prefix tools      # node:test: scene runner (fake DevBridge/Foreman), Foreman client (fake WS server), contact sheet
node tools/shoot.mjs tools/scenes/qa-selftest.json --port 7889 --foreman 27878 --prefix selftest/
```

`qa-selftest.json` exercises the scene-anchor fallback table, raw camera + offset, a vanilla
screen with typing, Foreman diff + message acks through templates, an unresolvable template
(skip), dev requests and waits against any running game and showcase Foreman.
