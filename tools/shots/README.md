# Camera shots for screen recording

`tools/play-camera-shots.mjs` plays these shots **in real time** in a running game (mod command `dev.play`)
so a screen recorder (OBS window capture at 60 fps) can film them: the camera follows a smooth
path every rendered frame and a timeline injects Foreman events (agents walking, cards moving,
speech bubbles), opens screens and types text. Internals: mod/DEV.md "Shot playback".

```powershell
# game running, e.g. tools\launch.ps1 -Dev -Showcase busy -Home C:\Projects\agentcraft\.agentcraft-home -Port 51878 -DevPort 7951 -Profile rec
node tools/devbridge-cli.mjs --port 7951 cmd "agentcraft hq"                       # once per world: build the HQ
node tools/play-camera-shots.mjs tools/shots/hq_orbit.json --port 7951 --window 2560x1440 --hold 3000
#   setup -> camera on the first pose -> holds that frame 3 s (start OBS now) -> plays -> prints a JSON summary
node tools/play-camera-shots.mjs tools/shots/*.json --port 7951                         # several shots in a row
node tools/play-camera-shots.mjs tools/shots/desk_story.json --port 7951 --stills 1,2.5,4   # check a take: PNG stills during playback
```

| option | |
| --- | --- |
| `--port N` | DevBridge port (default `AGENTCRAFT_DEV_PORT` or 7879) |
| `--hold MS` | after the setup, keep the first frame on screen this long before playing (start the recorder) |
| `--window WxH` | resize the game window first (`dev.window`); 2560x1440 fills a 1440p monitor for a full-frame capture |
| `--stills T1,T2` | also save `artifacts/shots/play/<name>_t<T>.png` at those times (for checking; each still costs a little GPU time, leave it off for the real take) |
| `--no-setup` | skip time/weather/camera placement (repeat a take right away) |
| `--verbose` | log every DevBridge request |

The summary per shot has `perf` (fps, frame time median/p99/max, frames over 20/33 ms,
`pathStepMsMin/Max` = path time between frames), `cameraVsPath` (rendered camera minus path, 0
when the camera is exact), `events` (when each timeline event ran and whether it worked) and
`frameLog` (`artifacts/shots/play/<name>.frames.json`: per frame t, frame time, rendered and
requested camera). Exit code 1 if a shot failed.

## Shot file

```jsonc
{
  "name": "desk_story",                 // letters, digits, _ -; default: the file name
  "duration": 5,                        // seconds (required)
  "setup": {                            // done by play-camera-shots.mjs before playing (not filmed)
    "foreman": { "minAgents": 6, "requireShowcase": true },   // wait until the Foreman link is synced (and in a showcase)
    "time": 12600,                      // dev.time (6000 noon, 12000-12800 golden hour, 18000 night)
    "weather": "clear",                 // dev.weather
    "commands": ["time set 12600"],     // dev.command, server commands
    "requests": [{ "type": "dev.foreman.inject", "patch": { "agent": "marlow", "set": { "station": "lounge" } } }],
                                        // any DevBridge requests, in order
    "settleAgents": true,               // dev.agents {settle:true} after the camera is placed (default true)
    "waitMs": 0
  },
  "camera": { ... },                    // see below
  "timeline": [ ... ],                  // see below
  "showHud": false,                     // HUD hidden by default (spectator: no hand either)
  "holdEndMs": 300,                     // keep the end pose this long before the HUD comes back
  "foreman": { "hold": true, "release": "reconnect" }   // default: hold while a timeline runs, then reconnect
}
```

### Camera

All positions are **eye** positions in world coordinates. Yaw 0 looks south (+Z), 90 west (-X),
-90 east (+X); pitch positive looks down. `fov` 30-110 (default: the player's FOV option).

**Keyframes** (time-based Catmull-Rom through the keys, shortest-arc yaw, smooth start and stop):

```jsonc
"camera": {
  "type": "keys",
  "fov": 66,
  "easeEnds": true,                     // zero velocity at the first and last key (default)
  "keys": [
    { "t": 0,   "x": 0.5, "y": 68.8, "z": 12.5, "yaw": 180, "pitch": 3 },
    { "t": 2.5, "x": -0.3, "y": 68.6, "z": 5.2, "yaw": 172, "pitch": 4, "fov": 60 },
    { "t": 4,   "anchor": "cam_hall" },                         // an anchor from dev.anchors
    { "t": 6,   "x": -5.2, "y": 68.3, "z": -2.0, "lookAt": { "x": -12.8, "y": 67.4, "z": -7.9 }, "ease": "inOut" }
  ]
}
```

- A key gives `yaw`/`pitch`, or `lookAt` (a point to aim at). If **every** key has `lookAt` (or the
  camera has one for all), the look target itself is splined: the subject stays centred while the
  camera moves.
- `ease` on a key (`inOut`, `in`, `out`, `linear`) remaps time inside the segment starting at that key.

**Orbit** (exact circle or arc at constant height around a point):

```jsonc
"camera": { "type": "orbit", "center": { "x": 0.5, "z": 7 }, "radius": 46, "y": 85,
            "from": -34, "to": -2, "lookAt": { "x": 0.5, "y": 71, "z": 6 }, "fov": 62, "ease": "inOut" }
```

`from`/`to` are angles in degrees around the centre: 0 = south of it, 90 = west (like yaw).

Preview a path without playing: `dev.play.pose {camera, t}` returns the pose at time t; put the
camera there with `dev.camera` and take a `dev.screenshot`.

### Timeline

Events at `t` seconds after the first frame. Each runs at the start of the first frame whose time
has reached `t`, before that frame's game tick.

| event | |
| --- | --- |
| `{ "t": 0.4, "patch": { "task": "t6", "set": { "status": "doing", "assignee": "marlow" } } }` | change a few wire fields of the current task (card glides to its new column) |
| `{ "t": 0.6, "patch": { "agent": "marlow", "set": { "station": "desk", "state": "editing", "activity": "editing README.md" } } }` | ... or agent (walks to the new station, sits at a desk) |
| `{ "t": 3.7, "say": { "agent": "marlow", "text": "On it", "to": "user" } }` | speech bubble |
| `{ "t": 1, "inject": { "type": "notify", "level": "info", "text": "..." } }` | any Foreman message (docs/protocol.md) as if the Foreman sent it |
| `{ "t": 1, "cmd": "dev.screen", "open": "console" }` | any DevBridge command; `"open": null` closes the screen |
| `{ "t": 1.3, "cmd": "dev.type", "text": "@juniper add a test", "msPerChar": 75, "jitter": 0.3, "seed": 1 }` | typed one character at a time (natural, repeatable rhythm; longer after spaces and punctuation) |
| `{ "t": 3, "cmd": "dev.key", "key": "return" }` | key press into the open screen |
| `{ "t": 2, "cmd": "dev.command", "command": "time set 13000" }` | server command (applied when the server thread gets to it) |
| `{ "t": 0, "cmd": "dev.agents", "settle": true }` | snap walking agents to their spots |

Injected changes live only in the mod's model: when the shot ends the Foreman hold is released by
reconnecting, and the fresh snapshot puts everything back (agents walk back). `dev.play.stop`
aborts a shot.

Opening a screen for the first time in a session can cost one slow frame (about 35 ms measured
for the console): open it during a moment the camera does not care about, or once before the take.

**Timing is real time.** The camera is exact every frame; agent walks, sitting and bubbles run on
game ticks, so they can shift by a tick (50 ms) between takes. Start walks early enough that the
camera never depends on an agent's exact arrival.

## Examples

| file | what | checked |
| --- | --- | --- |
| `hq_orbit.json` | 6 s golden-hour drone orbit of the HQ exterior (arc -34..-2 deg, radius 46, 14 blocks up) | 60 fps at 2560x1440, camera = path |
| `studio_dolly.json` | 6 s dolly from the atrium doorway down the hall, past the lounge and library, ending on Tove and Juniper at their desks | 60 fps at 2560x1440 |
| `desk_story.json` | 5 s: the "README + help text" card glides to Doing, the camera swoops into the studio as Marlow walks over from the lounge, sits at his desk and says "On it" | 60 fps at 2560x1440; needs the busy showcase (`-Showcase busy`) |
