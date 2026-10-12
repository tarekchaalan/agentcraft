# Multiplayer Work Packets

> Type: how-to. Audience: the coordinator and packet workers.
> Updated: 2026-10-03 (MP-00 plan). Companions: [campaign README and decisions](README.md), [audit](audit.md), [session resume](handoffs/session-resume.md), [original handoff](handoff-2026-10-03.md).

## Why this shape

The handoff's plan ran in five mostly serial steps (dedicated boot → relay → server-side blocks → deploy → v2). Most of that work does not depend on the step before it. Building at an offset, publishing your studio state, making the renderers studio-aware and packaging the server are all independent. What they share is a handful of **contracts**: how a studio is named, where a plot is, what the wire payloads look like, what is public, and how a renderer asks "whose studio is this block in?". This ledger follows one rule: **freeze those contracts once, in one foundation packet, then fan out as wide as the file graph allows.**

Changes from the handoff's sequence, and why:

- **MP-F is inserted before everything else and is the only serial gate for code.** It lands every payload record and codec, the public-state record (the redaction allowlist is the record's shape), the plot and studio types, per-studio `Anchors`, the client `Studios` registry, the mode detector, the server config, the telemetry catalog, and empty pre-split feature classes already wired into `ClientFeatures` and `AgentCraft`. Later packets fill in classes. They do not invent contracts or edit the wiring.
- **A layout-sync packet (MP-04) is added.** The spike found that a remote client never receives the anchor layout (A-12, A-13), so nothing renders correctly, not even your own studio, until it exists. The handoff did not list it.
- **Studio-aware renderers are first-class packets (MP-09, MP-10), not "visitor screens later".** Every block renderer currently shows the *viewer's* Foreman on every studio (A-23). Fixing that is needed for correctness, not only privacy.
- **A dev overlay in the foundation removes the renderers' dependency on the network.** `dev.mp.fake` makes the singleplayer studio render *as if it were someone else's*, from a public state you inject. The agents, displays, stations and visitor packets build and verify in singleplayer from the first day, without waiting for the relay, layout sync or plots.
- **World intents use a desired-state snapshot, not "set block X".** The client sends what the studio should look like (lamp per binding, podium open, …). The server resolves positions inside the sender's own plot only. This is the authority boundary, and it makes the singleplayer and multiplayer appliers share one pure function.
- **The test harness (MP-H) and the test infrastructure (MP-T) run in Wave 0, alongside the foundation**, because every networking packet's verification needs "one dedicated server, two clients, two sim Foremen on one machine", and the mod has no unit or game tests today (A-44).
- **Deployment (MP-14) is authored in Wave 1.** Packaging and the local Docker run are independent of the gameplay code. Only the actual colo deploy waits for the owner (D-MP03) and runs in close-out.

## Dispatch rules

- **One worktree per packet:** `git worktree add -b mp/<packet>-<slug> ../agentcraft-wt/<packet> main`. Never work in the main checkout.
- **Gradle:** every worktree uses the main checkout's `GRADLE_USER_HOME` (`C:\Users\Steve\source\projects\agentcraft\.gradle-home`); Gradle's own locking makes the shared cache safe. Run `gradlew build` and `gradlew mcSources` as **separate** invocations (A-03). Each worktree has its own `mod/run`, so each can run its own client.
- **Ports per packet, so parallel workers never collide.** Packet number `n` (MP-F = 0, MP-B = 90, MP-T = 91, MP-H = 92, MP-I = 98, MP-Z = 99):

  | | Port |
  |---|---|
  | Foreman (owner A / owner B) | `27800 + 2n` / `27801 + 2n` |
  | DevBridge (client A / client B) | `7900 + 2n` / `7901 + 2n` |
  | Dedicated server | `25600 + n` |

  Always pass `--home <worktree>/.agentcraft-home --profile mp-<n>`. Never use `~/.agentcraft` or the default ports 7878/7879/25565.
- **The coordinator is the single writer of `README.md` and this file.** A packet writes only its own `worknotes/MP-xx.md`. A packet that needs a contract change (a new payload field, a renamed method, a file not in its row) raises it with the coordinator instead of editing the contract locally.
- **Merge train:** PRs on `SandboxServers/agentcraft` (`origin`), into `main`, in dependency order. Whoever merges second rebases onto whoever merged first, for any two packets that share a file (see the matrix). Every PR, drafts included, requests a review from the fork's owner (`Cadacious`). Never push to `upstream`.
- **Definition of done, every packet:**
  1. `gradlew build` is green, including the JUnit tests from MP-F and the game tests from MP-T where present.
  2. `npm run check` is green in `foreman/` and `npm test` in `tools/` if the packet touched them, on Node ≥ 22.18 (A-05).
  3. Every new telemetry event has a capture test.
  4. **Singleplayer is unchanged:** a packet that touches the HQ, rendering or agents runs `node tools/qa.mjs` (with its port block and home) and compares the contact sheet to the MP-F baseline run. It records the run id in its worknote and Reads the PNGs before claiming no change.
  5. The worknote is written.
  6. `/code-review` has run, and `/security-review` too for any packet marked **(sec)**.
- **Status vocabulary:** Ready, BlockedDependency, BlockedDecision, Writing, Review, Integrated, UATPending, Done.

**Who writes and who reviews** (`.claude/agents/`):

- **Writers:** `fabric-mod-dev` is the default for everything under `mod/`; `foreman-node-dev` writes MP-B and MP-H and anything under `foreman/` or `tools/`; `colo-release-operator` writes MP-14; `documentation-writer` writes MP-Z and the worknotes' polish.
- **Advisors**, consulted before code:
  - `minecraft-netcode-advisor`: MP-F's payloads and codecs, MP-04, MP-05, MP-06, MP-07, MP-12;
  - `studio-world-advisor`: MP-01, MP-02, MP-03, MP-13;
  - `agent-presence-advisor`: MP-08, MP-09, MP-10, MP-11.
- **Reviewers**, before merge:
  - `server-authority-enforcer`: every **(sec)** packet plus MP-F's codecs;
  - `privacy-redaction-auditor`: MP-F's public-state record, MP-05, MP-06, MP-09, MP-10, MP-11, MP-14, and every new telemetry event;
  - `testing-validation-engineer`: every packet's tests;
  - `visual-qa-judge`: the singleplayer QA compare for any packet that touches rendering or the HQ, plus MP-I's remote-studio shots;
  - `agent-sandbox-guardian`: any change under `foreman/src/{policy,gitsafety,repos}.ts`;
  - `upstream-sync-steward`: any packet that edits an upstream hot file outside its matrix row, and every upstream merge during the campaign.
- **Resource budget (practical, not a dependency):** a packet that runs a game client needs about 4 GB of RAM plus a GPU share. Pure-logic and server-only packets need far less. If the machine cannot hold every Wave-1 client at once, start the server-only and pure-logic packets first (MP-01, MP-02, MP-03, MP-05, MP-06, MP-07, MP-13, MP-14), then the client-heavy ones (MP-04, MP-08, MP-09, MP-10, MP-11, MP-12).

## Contract fixed by MP-F

Parallel packets build against these names. Source roots: `mod/src/main/java/dev/agentcraft/` (`main/`) and `mod/src/client/java/dev/agentcraft/client/` (`client/`).

### Identity, plots and mode

- `main/mp/StudioId.java`: `record StudioId(UUID owner)`. `StudioId.LOCAL` (the nil UUID) is the singleplayer studio. In multiplayer a player's studio is `StudioId.of(player.getUUID())`; the server never takes a studio id from a client payload (it uses the connection's player).
- `main/mp/Plot.java`: `record Plot(int index, StudioId owner, BlockPos origin)`, plus `AABB box()` (the studio site box `{-46,60,-36, 46,100,54}` translated by `origin`) and `boolean contains(BlockPos)`. **Plot 0 has origin (0,0,0)**, so singleplayer, QA and every absolute-coordinate scene stay valid (A-40).
- `main/mp/PlotGrid.java`, pure and unit-tested: `BlockPos originOf(int index, int stride)` (a square spiral around 0,0; x and z multiples of 16; y = 0) and `OptionalInt indexAt(int x, int z, int stride)`. The default stride is **128** (the site is 93 × 91; 35+ blocks of margin).
- `main/mp/PlotDirectory.java`: the interface `Optional<Plot> plotOf(StudioId)`, `Optional<Plot> plotAt(BlockPos)`, `Collection<Plot> all()`. `Plots.directory()` / `Plots.install(PlotDirectory)` is a static holder whose default is the singleplayer directory (plot 0 owned by `LOCAL`). MP-03 installs the real one.
- `client/mp/MpMode.java`: `SINGLEPLAYER` (integrated server), `MULTIPLAYER` (the server sent `HelloS2C`), `REMOTE_VANILLA` (a remote server without AgentCraft: features stay local and the HUD says so). `MpMode.current()`, plus a listener. MP-F implements the detection: the hello handler, and a timeout on join with no hello. `MpMode.serverInfo()` returns the accepted hello's `ServerInfo` (an `Optional`, empty unless the mode is `MULTIPLAYER`), so later packets read the stride and the rates without a second hello receiver.

### Anchors per studio (MP-F edits `main/layout/Anchors.java` once)

- `Layout forStudio(StudioId)`, `void publish(StudioId, Layout)`, `void remove(StudioId)`, `Map<StudioId, Layout> all()`, `addStudioListener(BiConsumer<StudioId, Layout>)`.
- `Anchors.self()` / `Anchors.setSelf(StudioId)`: `LOCAL` in singleplayer, the player's own id once the multiplayer hello arrives.
- **The existing `current()`, `get(name)`, `publish(Layout)` and `addListener` keep their signatures** and act on `self()`. No existing caller has to change in MP-F. Callers migrate in their own packets.
- `Anchors.Builder.origin(BlockPos)` offsets every anchor and the bounds at `put` / `bounds()` (A-14). Builders stay in local coordinates.
- Persistence: MP-F keeps exactly today's behaviour for `LOCAL` (`agentcraft-anchors.json`). Per-plot persistence is MP-03's (`agentcraft/plots/<index>/anchors.json`), through `Anchors.publish(StudioId, Layout)`.

### HQ builder options

- `main/hq/HqBuilder.java`: `Options` gains `BlockPos origin` (default `BlockPos.ZERO`) and `StudioId studio` (default `LOCAL`). The old constructor stays. Builders ignore `origin` until MP-02 makes them honour it.

### Payloads (all in `main/mp/net/`, registered once by `MpPayloads.register()`)

The protocol version is `MpProtocol.VERSION = 1`. Every codec enforces caps (the "caps" column), so an oversized or malformed payload is refused at decode time and never reaches a handler. The same caps are refused where a record is built (an `IllegalArgumentException` naming the field), so an oversized record fails in its caller, on the caller's thread, and never reaches the encoder. Both hello codecs read `protocol` first; a hello of another protocol has the rest of its bytes skipped and decodes to a placeholder that the receiver refuses by the protocol alone, so a version mismatch is reported instead of ending the connection. `protocol` must stay the first field, a VarInt, in every later protocol. Names: `agentcraft:<id>`.

| Payload | Direction | Fields | Caps | Handler owner |
|---|---|---|---|---|
| `HelloS2C` | S→C | `protocol`, `you: StudioId`, `plotIndex` (−1 = none yet), `ServerInfo{plotStride, relayRadiusChunks, publicStatePerSecond, intentsPerSecond}` | stride and rates checked at decode; a plot the grid cannot place decodes and is refused by `MpMode.receiveHello` | MP-F (mode detection) |
| `HelloC2S` | C→S | `protocol`, `modVersion` | version ≤ 32 chars | MP-F (logs; server marks the player as mod-equipped) |
| `LayoutS2C` | S→C | `studio`, `plotIndex`, `Layout` (name, revision, bounds, anchors) | ≤ 512 anchors, names ≤ 48 chars | MP-04 |
| `LayoutRemoveS2C` | S→C | `studio` | | MP-04 |
| `PublicStateC2S` | C→S | `PublicStudioState` | see below | MP-06 (server) |
| `StudioStateS2C` | S→C | `studio`, `PublicStudioState` | see below | MP-06 (client half) |
| `StudioEventC2S` / `StudioEventS2C` | both | (`studio` on S2C), `PublicEvent` | | MP-06 |
| `PresenceS2C` | S→C | `studio`, `ownerName`, `online` | name ≤ 16 | MP-06 |
| `WorldIntentC2S` | C→S | `WorldIntent` | ≤ 64 bindings | MP-07 (server) |

### The public studio state (the redaction allowlist is this record's shape)

`main/mp/state/`. Anything that is not a field here cannot leave the owner's machine. Adding a field is a contract change and needs owner sign-off (D-MP01).

```java
record PublicStudioState(int rev, boolean foremanOnline, List<PublicAgent> agents /* ≤ 16 */,
                         Counts counts, GoalSummary goal, List<CiSlot> ci /* ≤ 8 */,
                         PublicPolicy policy, @Nullable List<PublicTask> tasks /* opt-in, ≤ 32 */)
record PublicAgent(String id, String name, String skin,      // id and skin: an identifier path [a-z0-9/._-], ≤ 16 chars; name ≤ 16 chars
                   AgentStateWire state, StationWire station, // validated enums
                   boolean active, boolean paused, boolean awaitingUser,
                   @Nullable String activity)                 // opt-in, ≤ 48 chars
record Counts(int todo, int doing, int review, int done, int blocked, int openDecisions, int openMerges)
record GoalSummary(GoalStatusWire status, float progress, @Nullable String text /* opt-in, ≤ 120 */)
record CiSlot(int slot, CiStatusWire ci)                      // repo ids never leave: slot index only
record PublicTask(String id, String title /* ≤ 80 */, TaskStatusWire status, @Nullable String assignee)
record PublicPolicy(boolean activityText, boolean sayText, boolean taskTitles, boolean goalText)  // all false by default
sealed interface PublicEvent: Say(agentId, @Nullable to /* agent id or "user" */, @Nullable text /* opt-in ≤ 120 */, int length)
                            | TaskDone(agentId)
record WorldIntent(int rev, Map<String, LampStatusWire> lamps /* binding → status */,
                   boolean podiumOpen, boolean mergeActive, Set<String> litMonitors /* agent ids */)
```

- Every string is sanitized at the codec (length cap; `§` formatting codes, control and format characters, line and paragraph separators and unpaired surrogates stripped). Every enum is the wire value, validated. A state or event the server accepts always re-encodes within its envelope, so it can be relayed. An agent `id` or `skin` that is not a valid identifier path after sanitizing is refused, at decode and where the record is built: a viewer's client builds resource identifiers from both, and the game throws on any other character.
- `GoalStatusWire` has `NONE` (wire `none`) for a studio with no current goal, sent with `progress` 0 and `text` null. The other wire enums carry the Foreman protocol's values only.
- `WorldIntent.lamps` keys are binding names of exactly these forms: `agent:<agent id, 1 to 16 chars>`, `ci:#1` to `ci:#8`, `goal`, `goal:atrium`, `decisions`, `merge`, `beacon`. Any other key, and any key that sanitizing would change, is refused when the record is built and at decode (`WorldIntent.isBinding(String)`). A repo id is never a key: `ci:<repoId>` is not sent.
- Opt-in fields are `null` unless the owner's `PublicPolicy` flag is on. The publisher (MP-05) fills them. The record makes "on by accident" structurally visible in review, and enforces it: a `PublicStudioState` that carries an activity, a goal text or a task list while that policy flag is off cannot be built, encoded or decoded. `Say.text` has no such guard, because an event carries no policy: MP-05 withholds it on send and MP-06 on relay, both from the studio's current `sayText`.
- MP-F also ships a Gson codec for these records (`PublicJson`), used by `dev.mp.fake` and by the state, event and intent payloads. Its output is never passed to `MpLog`.

### Client studio registry and the renderer seam

- `client/mp/Studios.java` (a real, simple implementation): `StudioView own()`, `Optional<StudioView> view(StudioId)`, `Optional<StudioView> at(BlockPos)` (by plot box, else by layout bounds), `Collection<StudioView> all()`, `Optional<Plot> plot(StudioId)` (the plot a layout sync registered with `setPlot`), `int entityIdBase(StudioId)`, listeners.
- `client/mp/StudioView.java`: `record StudioView(StudioId id, boolean own, String ownerName, boolean online, Layout layout, @Nullable PublicStudioState publicState, int slot)`.
- **The renderer rule every Wave-1 rendering packet follows:** `Studios.at(pos)` empty, or `own()` → today's code path, unchanged (the viewer's own `ForemanState`). A remote studio → the packet's remote rendering from `publicState` only. A renderer must never read `Foreman.state()` for a block in a remote studio.
- **Entity id ranges:** studio slot `k` (the client-local registration order; own = 0) uses ids `−10,000 − 1,000·k` and down, 1,000 per studio. Slot 0 is today's range (A-31).
- `client/mp/RemoteAgentClicks.java`: the seam between MP-08 (which owns the click) and MP-11 (which owns the read-only card). `set(BiConsumer<StudioView, String>)` registers the handler, `fire(StudioView, String agentId)` calls it, and with no handler registered a click on a remote agent does nothing.

### Dev overlay (removes the network from the renderer packets' critical path)

`client/mp/dev/MpDevFake.java`, DevBridge commands:

- `dev.mp.fake {state: <PublicStudioState JSON>, owner?: "Bob", overlay?: true}`: registers a fake remote studio. With `overlay: true` (the default), `Studios.at(pos)` returns the fake studio for the own studio's area. Your own HQ's blocks and agents then render *as a visitor would see someone else's studio*, driven by the injected state. Repeat the call to update the state. Each call fires the same listeners a relayed `StudioStateS2C` would.
- `dev.mp.fake {event: <PublicEvent JSON>}` injects a say or task-done event.
- `dev.mp.fake {clear: true}` removes it.
- `dev.mp.studios` lists `Studios.all()` and `MpMode.current()`.

### Server config

`main/mp/MpServerConfig.java` loads `config/agentcraft-server.json` on a **dedicated** server only. The defaults keep today's behaviour exactly:

```json
{ "enabled": false, "plotStride": 128, "autoAllocate": true, "autoBuild": true,
  "forceCreative": true, "worldRules": true, "protectPlots": true,
  "relayRadiusChunks": 12, "publicStatePerSecond": 4, "intentsPerSecond": 10 }
```

`enabled: false` means the dedicated server acts as an ordinary AgentCraft server today does (no hello, no plots). An unknown or invalid value logs a WARN and uses the default.

**Rates.** `publicStatePerSecond` and `intentsPerSecond` are per player. The server enforces each with `main/mp/RateBucket.java` (pure, unit-tested): a bucket that holds up to twice the rate and refills at the rate, so jitter never gets a compliant client refused. States and events are counted in separate buckets, both at `publicStatePerSecond`. A client sends at most the rate per second of each, read from `MpMode.serverInfo()`: states and intents are coalesced to the newest, and events beyond the rate are dropped.

`MpServerConfig.install(MpServerConfig)` replaces the current config and returns the previous one. It is the seam for game tests, like `Plots.install`. Server features gate on `server.isDedicatedServer() && MpServerConfig.current().enabled()` and read `current()` at call time, never cached at `init()`. Fabric's game-test server reports itself as dedicated and finds no config file, so its config is the default. A game test that needs multiplayer installs a config, acts, asserts and restores the previous value inside one server-thread call; a multi-tick multiplayer test sets its own `environment`, so it does not share a batch with singleplayer tests. Do not seed a config file into the game-test run directory.

### Who sees which studio (server)

`main/mp/server/StudioRange.java` is the one authority for which studios a player sees. MP-04 and MP-06 both use it, so a studio's layout and its public state appear and disappear together.

- **The rule.** A player always sees their own plot. They see another plot only while they are in the overworld and the plot is within `relayRadiusChunks`: the horizontal Chebyshev distance, in chunks, from the player's chunk to the nearest chunk that the plot's site box touches (`StudioRange.chunkDistance(plot, chunkX, chunkZ)`, pure) is at most `relayRadiusChunks`. Only mod-equipped players are viewers (their `HelloC2S` was accepted).
- **Tracking.** In multiplayer the server recomputes every viewer's set once a second, when a player's hello is accepted, and when a feature calls `StudioRange.refresh(server)` (MP-03 does, after it allocates, assigns or frees a plot). Listeners (`StudioRange.addListener`) get `entered(viewer, plot)` and `left(viewer, plot)` on the server thread, in registration order: layout sync first, then the relay. A viewer who disconnects is forgotten without callbacks.
- **Queries.** `StudioRange.viewersOf(server, studio)` (the players who see it now) and `StudioRange.sees(viewer, studio)`.
- **What the two features do with it.**
  - `entered`: MP-04 sends `LayoutS2C` if the studio has a layout. MP-06 then sends `PresenceS2C` and the studio's latest state, if it has one.
  - `left`: MP-04 sends `LayoutRemoveS2C`. MP-06 sends nothing.
  - In between, MP-04 sends a new or rebuilt layout, and MP-06 relays states, events and presence changes, to `viewersOf(studio)` only. States and events never go back to the owner.
- **The client mirrors it.** `LayoutS2C`, `StudioStateS2C` and `PresenceS2C` each create the studio's view if it is missing, or update it (`Studios` upserts). `LayoutRemoveS2C` means "this studio left your range": the client calls `Anchors.remove(studio)` and `Studios.remove(studio)`, which drops the layout, the plot, the state and the presence together. Nothing else removes a remote view before disconnect. Offline presence does not remove it: the studio dims (D-MP02).

### Feature flag

Multiplayer is on only when a dedicated server has `enabled: true` **and** the client received a matching-protocol `HelloS2C`. Singleplayer never takes a multiplayer code path except through `dev.mp.fake`. This is what keeps upstream merges cheap (the handoff's "behind a mode") and is the campaign's rollback lever: set `enabled: false` and restart the server.

### Telemetry contract

- **Logger:** `agentcraft.mp` (slf4j), through `main/mp/MpLog.java`. Every line is `event=<name>` followed by `key=value` pairs, never free text alone. Event and reason strings are constants in `MpEvents` / `MpReasons`.
- **Correlators on every event that has them:** `player` (the acting player's UUID), `studio` (owner UUID), `plot` (index), `rev`.
- **Refusals** carry a stable `reason=` from the packet's reason list. **State changes** carry before and after values.
- **Guards:** `MpLog.capture()` is a JUnit test hook. Every event row below has a capture test in its packet. MP-F ships the test `catalog_covers_every_mp_event_in_the_sources`, which scans `mod/src/main` and `mod/src/client` for `MpLog.event(` and fails on any event missing from `MpEvents`. It reads the first argument as text, so write it as `MpEvents.NAME` (no static import, no variable), and do not write `MpLog.event(` in a comment.
- **Content rule:** no opt-in text, no Foreman free text and no repo or task names in any log line. Counts and ids only: a field value is a number, a boolean, an enum constant, a UUID or a constant from `MpReasons`, never a record, a collection or a string that came from a Foreman or from another player.

| Event | Level | Packet | Fields beyond the correlators |
|---|---|---|---|
| `config_loaded` / `config_invalid` | info / warn | MP-F, MP-01 | `enabled`, `file` (`none` when there is no config file); `key`, `value`, and for MP-01's world check also `reason` (`surface_not_64`) |
| `hello_sent` / `hello_received` | info | MP-F | `protocol`, `plot`; `mode`. A hello the client refuses is logged too, with the mode it stayed in |
| `mode_changed` | info | MP-F | `from`, `to`, `server` (address hash, never the address) |
| `fake_studio` | info | MP-F | `action` (`set`, `clear`), `agents` |
| `world_rules_applied` / `creative_forced` | info | MP-01 | `rules` (the number of game rules set); `from_mode` |
| `autoworld_skipped` | info | MP-01 | `reason` (`quickplay_multiplayer`, `multiplayer_screen`, `disabled`) |
| `plot_built` / `plot_build_failed` | info / error | MP-02 | `ms`, `changed`, `kept`; `reason` (`no_plot`, `bad_origin`, `io_error`, `build_error`). Logged by the builder, and only for a studio other than `LOCAL` |
| `plot_allocated` / `plot_allocation_failed` | info / error | MP-03 | `index`, `origin`; `reason` (`grid_full`, `io_error`) |
| `plot_command` | info | MP-03 | `command`, `target_plot`, `ok`. A refused command is logged with `ok=false` and carries no `reason` |
| `layout_sent` / `layout_applied` / `layout_removed` | debug | MP-04 | `anchors`, `to` (the viewer) / `anchors` / none. `layout_sent` is logged by the server, the other two by the client |
| `public_state_sent` / `public_state_skipped` | debug | MP-05 | `agents`, `bytes`, `policy`; `reason` (`unchanged`, `rate_limited`, `not_multiplayer`) |
| `policy_changed` | info | MP-05 | `before`, `after` (flag bits) |
| `public_state_rejected` | warn | MP-06 | `reason` (`no_plot`, `rate_limited`, `bad_version`, `decode_failed`). `bad_version` means the sender has no accepted hello. `decode_failed` comes from MP-F's codec, before any handler, so it has no `player` |
| `studio_event_rejected` | warn | MP-06 | `reason` (`no_plot`, `rate_limited`, `bad_version`, `unknown_agent`). `bad_version` means the sender has no accepted hello, as for `public_state_rejected` |
| `relay_sent` | debug | MP-06 | `recipients`, `bytes` |
| `presence` | info | MP-06 | `online` |
| `remote_studio_added` / `remote_studio_removed` | info | MP-06 | `slot` |
| `world_intent_applied` / `world_intent_rejected` | debug / warn | MP-07 | `changed`, `lamps`; `reason` (`no_plot`, `rate_limited`, `unknown_binding`). `unknown_binding`: a block entity in the plot whose binding is not a legal lamp key (an old `ci:<repoId>`, for example) is skipped and logged once per plot, without the binding, which may hold a repo id. The rest of the intent is still applied |
| `agents_studio_attached` / `agents_studio_detached` | debug | MP-08 | `slot`, `agents` |
| `visitor_readonly` | debug | MP-11 | `station` (one of a closed set of names the packet defines, `agent` for an agent card), `action` (`open`) |
| `plot_edit_refused` | info | MP-13 | `action` (`break`, `place`, `use`), `pos`. No `reason`: there is one cause. At most one line per player, action and plot every 5 seconds |

Close-out (MP-Z) adds any rows the worknotes introduce, through the coordinator.

## File-ownership matrix

A packet edits only the files in its row. **(new)** files are created by that packet. "F-stub" means MP-F created the file empty (declared and wired), and the packet fills it in.

**Every packet also owns its own new test files and its worknote**, whether or not its row names them:

- JUnit tests under `mod/src/test/java/dev/agentcraft/mp/<area>/`, or in the package of the class under test when the test needs package-private access (for example `mod/src/test/java/dev/agentcraft/client/monitor/`);
- game tests under `mod/src/gametest/java/dev/agentcraft/gametest/mp/<area>/`;
- `<area>` is the packet's own directory name (`world`, `offset`, `plot`, `layout`, `publish`, `relay`, `intent`, `agents`, `display`, `station`, `visitor`, `dev`, `protect`). Simple class names are unique across packets (the game-test id has no package), so prefix them with the area.

A packet never edits a test file or a fixture that MP-F or another packet created. If its change breaks one, it raises that with the coordinator. MP-F ships the fixtures for tests that need a `ForemanState`: `mod/src/test/java/dev/agentcraft/client/foreman/ForemanStates.java` (`showcase()` and `showcaseLate()`, built from two captured sim snapshots under `mod/src/test/resources/dev/agentcraft/fixtures/`). MP-05 and MP-07 use them.

| Packet | Owned files | Must not touch |
|---|---|---|
| **MP-F** Foundation | everything under `main/mp/` and `client/mp/` listed in the contract (new), the F-stub files below (new, empty), `main/layout/Anchors.java`, `main/hq/HqBuilder.java`, `main/AgentCraft.java` and `client/ClientFeatures.java` (one wiring line per multiplayer feature, all at once), `mod/build.gradle` (`test` block: JUnit 5 only), `mod/src/test/java/**` (new) | every other existing file |
| **MP-B** Baseline | `foreman/package.json` and `tools/package.json` (`engines` → `>=22.18`), `tools/launch.ps1` and `tools/mac.mjs` (a Node version check with a clear message), `README.md` (Quick start: "Node 22.18+") | everything else |
| **MP-T** Game tests | `mod/build.gradle` (game-test source set, `runGameTest`, and `mcSources` ordering after `compileJava`), `mod/src/gametest/**` (new: one smoke test that boots, builds the HQ at plot 0 and checks 69 anchors) | `mod/src/main/**`, `mod/src/client/**` |
| **MP-H** Harness | `tools/mp.mjs` (new), `tools/lib/mp/**` (new), `tools/test/mp-*.test.mjs` (new), `tools/README.md` (a "Multiplayer harness" section) | `tools/launch.ps1`, `tools/mac.mjs`, `tools/qa.mjs`, `tools/lib/*.mjs` that exist today (call them, don't change them) |
| **MP-01** Server mode and world rules | `main/world/HqWorld.java`, `client/AutoWorld.java`, `client/ClientEnv.java`, `main/mp/server/world/ServerWorldFeature.java` (F-stub) | `HqFeature.java` (MP-03) |
| **MP-02** Build at an offset | `main/hq/Plan.java`, `main/hq/PlanStore.java`, `main/hq/StudioHqBuilder.java` (pass `origin` to `Plan` and the anchors builder only; no geometry edits), `mod/src/gametest/**/BuildAtOffsetTest.java` (new) | `HqLandscape.java`, `HqFeature.java`, `TestRoomBuilder.java` |
| **MP-03** Plots: registry, lifecycle, commands **(sec)** | `main/hq/HqFeature.java`, `main/command/AgentCraftCommands.java`, `main/mp/server/plot/**` (`PlotFeature.java` F-stub; `PlotRegistry.java`, `PlotStore.java`, `PlotCommands.java`, `PlotSpawn.java` new), gametests under `mp/plot/` (new) | `Plan.java`, `PlanStore.java` (MP-02), `HqWorld.java` (MP-01) |
| **MP-04** Layout sync | `main/mp/server/layout/LayoutSyncFeature.java` (F-stub), `client/mp/layout/LayoutSyncClient.java` (F-stub) | `Anchors.java` (call its API; raise contract changes) |
| **MP-05** Public-state publisher and redaction **(sec)** | `client/mp/publish/**` (`PublishFeature.java` F-stub; `Redactor.java`, `PolicyStore.java`, `PublishScheduler.java` new), JUnit tests under `mp/publish/` (new) | `ForemanState.java`, `Protocol.java` (listen only) |
| **MP-06** Relay and presence **(sec)** | `main/mp/server/relay/**` (`RelayFeature.java` F-stub; `StudioRelay.java`, `RateLimiter.java` new), `client/mp/remote/RemoteStudiosFeature.java` (F-stub) | `Studios.java` (call its API) |
| **MP-07** World intents **(sec)** | `client/hq/HqWorldDriver.java`, `client/world/ServerTasks.java`, `client/decisions/DecisionsFeature.java` (the `syncPodium` method only, A-18), `main/mp/server/intent/**` (`WorldIntentFeature.java` F-stub; `IntentApplier.java` new), JUnit and gametests (new) | `StatusLampRenderer.java`, `HqClientFeature.java` (MP-10), every other `decisions/` file |
| **MP-08** Multi-studio agents | `client/agents/AgentManager.java`, `AgentsFeature.java`, `StationAssigner.java`, `AgentView.java`, `Nameplate.java`, `SpeechBubble.java`, `AgentLife.java`, `Seats.java`, `AgentSkins.java`, `client/agents/StudioAgents.java` (new); `PlateLayout.java`, `PlateStack.java`, `AgentRenderer.java` and `AgentRenderState.java` for a studio-qualified plate key only | `AgentCardScreen.java` (MP-11), `ClientAgentEntity.java` unless the coordinator agrees |
| **MP-09** Studio-aware displays | `client/monitor/**`, `client/taskwall/**` | `StationInteractions.java` (MP-11) |
| **MP-10** Studio-aware stations | `client/decisions/DecisionPodiumRenderer.java`, `client/decisions/DecisionQueue.java`, `client/diff/MergeStationRenderer.java`, `client/library/MemoryArchiveRenderer.java`, `client/library/MemoryIndex.java`, `client/console/ConsoleTerminalRenderer.java`, `client/hq/StatusLampRenderer.java`, `client/hq/HqClientFeature.java` | `HqWorldDriver.java` (MP-07), `DecisionsFeature.java` (MP-07), screens |
| **MP-11** Visitor interactions | `client/world/StationInteractions.java`, `client/agents/AgentCardScreen.java`, `client/mp/visitor/**` (`VisitorFeature.java` F-stub; read-only screens new) | every feature's own `*Feature.java` handler (the gate sits in front of them) |
| **MP-12** DevBridge and dev tools in multiplayer | `client/dev/DevCommands.java`, `client/world/AnchorsDev.java`, `client/hq/HqCheck.java`, `client/dev/play/CameraPath.java`, `client/mp/dev/MpDevCommands.java` (new) | `client/mp/dev/MpDevFake.java` (MP-F's) |
| **MP-13** Plot protection **(sec)** | `main/mp/server/protect/**` (`PlotProtectionFeature.java` F-stub), gametests (new); one explosion mixin class in the common mixin package and its one line in `agentcraft.mixins.json` | `HqWorld.java` |
| **MP-14** Deployment for multiplayer | `deploy/**`, `.github/workflows/release-container.yml`, `docs/multiplayer/deploy.md`, `docs/multiplayer/player-install.md` (new) | any mod or Foreman source; **never a colo address, hostname or credential** |
| **MP-I** Integration | anything a seam needs, by coordinator assignment only | |
| **MP-Z** Close-out | `README.md`, `CLAUDE.md`, `mod/DEV.md`, `mod/FEATURES.md`, `docs/QA.md`, `docs/multiplayer/**`, `config` defaults (`enabled`) | code, except one-line fixes the coordinator assigns |

**Shared files, and why they stay cheap:**

- `ClientFeatures.java` and `AgentCraft.java`: MP-F adds every line once, so no Wave-1 packet edits them.
- `Anchors.java`: MP-F only.
- `mod/build.gradle`: MP-F (test block) and MP-T (game-test source set and `mcSources`) edit different blocks. The second to merge rebases.
- `mod/src/main/resources/assets/agentcraft/lang/en_us.json`: packets that add UI text (MP-11, MP-09, MP-10) add keys under their own prefix (`mp.visitor.*`, `mp.display.*`, `mp.station.*`). The second to merge rebases. `sync.py` keeps mod-owned keys (CLAUDE.md, Assets).
- `DecisionsFeature.java` is split by method: MP-07 owns `syncPodium`, and nobody else touches the file.

## Dependency graph and waves

```text
Wave 0 (parallel, start now)
  MP-F Foundation ──────────────┐   (the only gate for code)
  MP-T Game tests  ─────────────┤   (needed by gametest-writing packets; tiny)
  MP-B Baseline (Node ≥ 22.18)  │   (no dependents; green Foreman suite)
  MP-H Two-client harness ──────┤   (needed for networked verification; uses today's mod)
                                ▼
Wave 1 (parallel, after MP-F; MP-T and MP-H for their tests)
  MP-01 Server mode & world rules      MP-08 Multi-studio agents      (verify with dev.mp.fake)
  MP-02 Build at an offset             MP-09 Studio-aware displays    (verify with dev.mp.fake)
  MP-03 Plots: registry & lifecycle    MP-10 Studio-aware stations    (verify with dev.mp.fake)
  MP-04 Layout sync                    MP-11 Visitor interactions     (verify with dev.mp.fake)
  MP-05 Public-state publisher         MP-12 DevBridge in multiplayer
  MP-06 Relay & presence               MP-13 Plot protection
  MP-07 World intents                  MP-14 Deployment for multiplayer
                                ▼
Wave 2  MP-I Integration: dedicated server + 2 clients + 2 sim Foremen, end to end; singleplayer QA regression
                                ▼
Wave 3  MP-Z Close-out: docs, enabled-by-default in the shipped server config, owner UAT, colo deploy (owner-confirmed)
```

**No Wave-1 packet depends on another Wave-1 packet to build, or to verify in JUnit and game tests.** Four harness checks need MP-03's plot registry (see the MP-03 row). Where two meet at runtime, they meet through an MP-F contract, and each side verifies against MP-F's default or fake:

| Runtime meeting point | How each side verifies alone |
|---|---|
| MP-02 (honours `origin`) ↔ MP-03 (allocates plots, calls the builder with an origin) | MP-02 gametests build at plot 1's origin directly. MP-03 tests allocation and lifecycle at plot 0 plus the persisted origin of plot 1. They meet in MP-I. Merge MP-02 first. |
| MP-03 (real `PlotDirectory`) ↔ MP-04 / MP-06 / MP-07 / MP-13 (consult it) | They code against `Plots.directory()`. MP-F's default directory gives no connected player a plot: plot 0 belongs to `LOCAL`, and `HelloS2C.plotIndex` is −1 until MP-03 allocates. Owner-gated paths and multi-plot cases are verified in JUnit and game tests against a fake `PlotDirectory`, installed with `Plots.install` and restored in the same call. The harness checks of MP-04, MP-06, MP-07 and MP-12 that need an owned plot run once MP-03 is merged (merge MP-03 first among these), otherwise in MP-I. |
| MP-04 (layouts reach clients) ↔ MP-08 / 09 / 10 / 11 (need layouts) | The renderer packets use `dev.mp.fake {overlay}`, which reuses the singleplayer layout. |
| MP-05 (owner publishes) ↔ MP-06 (server relays) | MP-06 tests with `PublicStateC2S` sent by a test client (the harness's `tools/lib/mp` can send any payload through `dev.mp.*` once MP-12 lands; until then, gametests and JUnit). MP-05 tests the Redactor in JUnit and the send path against the MP-F codec round trip. |
| MP-07 client (intents) ↔ MP-07 server (applier) | The same packet, by design (one authority boundary, one reviewer). |

**Maximum useful parallelism:** Wave 0 = 4, Wave 1 = **14**, Wave 2 = 1, Wave 3 = 1.
**Critical path:** MP-F → (the longest Wave-1 packet, probably MP-08 or MP-03) → MP-I → MP-Z. That is four packets deep.

---

## MP-F: foundation

**Status: Ready.** Writer: general-purpose. Review: `/code-review` plus `/security-review` (the codecs are the trust boundary).

Scope: everything in [§ Contract fixed by MP-F](#contract-fixed-by-mp-f), real where stated, plus:

- the F-stub files: `main/mp/server/{world/ServerWorldFeature, plot/PlotFeature, layout/LayoutSyncFeature, relay/RelayFeature, intent/WorldIntentFeature, protect/PlotProtectionFeature}.java` and `client/mp/{layout/LayoutSyncClient, publish/PublishFeature, remote/RemoteStudiosFeature, visitor/VisitorFeature}.java`, each an empty `init()`, all wired in `AgentCraft` / `ClientFeatures`;
- a **singleplayer QA baseline**: run `node tools/qa.mjs` on the MP-F branch before merge and record the run id. Every later packet compares against it.

Nothing player-visible changes: `enabled` defaults to false, singleplayer never sends a payload, and `Anchors.current()` behaves as today.

Tests:

- **Unit (JUnit, new in this packet):**
  - every payload's codec round-trips;
  - every cap is enforced (17 agents, a 49-char anchor name and a 121-char opt-in text are each refused at decode and where the record is built);
  - sanitization strips `§` and control characters;
  - `PlotGrid` spiral: indexes 0..200 give distinct origins, 16-aligned, with no overlapping boxes at stride 128, and `indexAt(originOf(i)) == i`;
  - `Anchors.Builder.origin` offsets every anchor and the bounds;
  - `Anchors.current()` equals `forStudio(LOCAL)` in singleplayer;
  - `MpServerConfig` defaults, and an invalid value falls back with a WARN;
  - the telemetry catalog scan.
- **In game:**
  - singleplayer QA baseline (above);
  - `dev.mp.fake` with a two-agent state and then `dev.mp.studios` reports the fake studio; `{clear:true}` removes it;
  - a dedicated server with `enabled: true` sends `HelloS2C`, and a harness or dev client logs `hello_received` and `mode_changed`.

## MP-B: baseline

**Status: Ready.** Tiny. Bump `engines` to `>=22.18` in `foreman/` and `tools/`. Make `launch.ps1` and `mac.mjs` fail early with "Node 22.18 or newer is required (you have X)". Update the README prerequisite. **Precondition (owner or coordinator):** install Node ≥ 22.18 on every worker machine. Done when `npm run check` is 482/482 on this machine (A-04, A-05).

## MP-T: game tests

**Status: Ready.** Add the Fabric game-test source set (`mod/src/gametest`) and a headless `runGameTest` task, the way 26.3 Loom and `fabric-gametest-api-v1` expect (check `fabric-client-gametest-api-v1` sources for the 26.3 shape, mod/DEV.md "Finding Minecraft APIs"). Make `mcSources` safe to run alongside `build` (A-03). Ship one smoke test: a server boots, `/agentcraft hq` at plot 0 publishes 69 anchors (A-07). Document the command in the worknote.

## MP-H: two-client harness

**Status: Ready** (it uses today's mod; nothing in MP-F is needed to start). Writer: general-purpose (Node).

Scope: `node tools/mp.mjs up --slot <n> [--clients 2] [--backend sim]` starts, all derived from the slot's port block:

- a local dedicated server (`runServer` or a direct Fabric dev launch from Loom's generated launch config; `online-mode=false`, `level-name`, superflat as in A-39, the two test players opped, `config/agentcraft-server.json` with `enabled: true`);
- one sim Foreman per client (`--profile mp-<n>-a/b`, own ports);
- N clients with their own game dirs, usernames, Foreman ports and DevBridge ports, joining with `--quickPlayMultiplayer localhost:<port>` and `AGENTCRAFT_AUTOWORLD=0` (A-09, A-39).

Also: `down` (stop exactly what it started, by recorded pid plus start time, like `stop.ps1`; never "wait for exit", A-08), `status`, and a `--json` summary. A library `tools/lib/mp/` that later packets' scripts and MP-I use: wait for both clients ready, run a DevBridge command on client A or B, read server log events.

Risk to resolve inside the packet: two clients from one checkout need separate game dirs. Check whether `runClient --args="--gameDir …"` is enough, or launch through the DLI config directly. If neither works without a `build.gradle` change, raise it with the coordinator (MP-T owns `build.gradle`).

Tests: `npm test --prefix tools` for the port math, pid-record handling and summary shape. Live: `up`, then both clients' `dev.state` report a remote server, then `down` leaves no processes.

## MP-01: server mode and world rules

**Status: BlockedDependency (MP-F).**

"Multiplayer" below means `server.isDedicatedServer() && MpServerConfig.current().enabled()`, read at call time.

- `HqWorld`: in multiplayer the HQ rules apply by config instead of by level name (A-36).
  - **`HqWorld.isHq(server)` is false in multiplayer**, whatever the level name. Its three callers are the legacy level-name paths: `HqWorld`'s own rules, `Anchors`' load of the `LOCAL` layout, and `HqFeature`'s auto-build at the origin. None of them runs in multiplayer; plots, their layouts and their builds belong to MP-03. The deployed server keeps `level-name=AgentCraft HQ`, so without this an enabled server would build and publish a `LOCAL` studio at the origin.
  - With `enabled: false`, and in singleplayer, today's code path runs unchanged (level-name gate, every rule, the first-start block, creative on join). This is the rollback lever.
  - `worldRules`: the same world-wide game rules as today, except `LOG_ADMIN_COMMANDS`, which is set to **true** (op commands stay auditable on a shared server). On the first start of a world the time is set and the weather cleared, as in singleplayer, and the marker file is written. `setworldspawn` is **not** run: the world spawn is not moved in multiplayer (MP-03 sets each player's respawn point).
  - `forceCreative`: a joining player in survival or spectator is switched to creative, as today. With the flag off nobody is switched.
  - Singleplayer behaviour is byte-for-byte unchanged.
- `AutoWorld` skips, for the whole client session, in three cases, and logs `autoworld_skipped` once with the reason:
  - `disabled`: the existing env switch (A-39);
  - `quickplay_multiplayer`: the client was launched with `--quickPlayMultiplayer`. Read the launch arguments through Fabric Loader (`FabricLoader.getLaunchArguments(true)`), never log them, and keep the parser a pure function;
  - `multiplayer_screen`: a multiplayer screen (the server list or a connect screen) is open before AutoWorld has opened the HQ world, for example after a failed quick-play connection. The deferred open re-checks this.
- The server's world type: a multiplayer world must be superflat with the grass top at y = 64 (A-32). Document it in the worknote and in MP-14's config.
  - `ServerWorldFeature` checks it at `SERVER_STARTED` in multiplayer, from the overworld generator's settings (a `FlatLevelSource` whose top layer is grass at y = 64), not from blocks: a built plot or an unloaded chunk would make a block probe lie.
  - On failure it logs `config_invalid key=world.surface value=<not_flat | top_y_<n> | top_not_grass> reason=surface_not_64` and then refuses to start: it throws, so the server stops before any rule is applied, any plot is built or any player joins. `ServerWorldFeature.init()` is wired first, so its listener runs before every other feature's.
  - The game-test server is not multiplayer by default (its world is sandstone at y = 3), so the check does not run there. A game test calls the check's pure function directly.

Tests: a gametest that the rules apply with `enabled: true` on a non-HQ level name, that `LOG_ADMIN_COMMANDS` ends up true, that `worldRules: false` changes nothing, and that creative forcing honours the flag. Unit tests for the quick-play detection and for the surface rule. Singleplayer QA compare.

## MP-02: build at an offset

**Status: BlockedDependency (MP-F; MP-T for gametests).**

- `Plan` gains an apply-time origin: chunk loop, `setBlock` positions, deferred connection positions, bindings and the drop sweep, all translated (A-33). The plan, landscape hash and every constant stay local (A-34).
- `StudioHqBuilder` passes `options.origin()` to `Plan` and to `Anchors.Builder.origin` and touches no geometry.
- `PlanStore` keys its file by plot (`agentcraft/plots/<index>/hq-plan.dat`; `LOCAL` keeps `agentcraft-hq-plan.dat`), and `invalidateUnless` becomes per plot (A-35).
- Origins must be 16-aligned (assert).

Decisions:

- **The plot index comes from the directory.** `PlanStore` resolves `Plots.directory().plotOf(options.studio())`. `LOCAL` keeps `agentcraft-hq-plan.dat`. Any other studio needs a plot whose origin equals `options.origin()`: no plot fails with `no_plot`, a different origin with `bad_origin`. The path is built from the integer index only.
- **Alignment:** x and z are multiples of 16 (otherwise `bad_origin`). y is translated as given; plots use y = 0.
- **`invalidateUnless`:** the existing `(server, builderId)` form keeps acting on `LOCAL`, so `HqFeature` compiles unchanged. MP-02 adds the per-plot form and MP-03 moves `HqFeature` to it.
- **Telemetry:** the builder logs `plot_built` and `plot_build_failed` itself, only for a studio other than `LOCAL` (singleplayer stays silent). An exception from the build is logged as `build_error` and rethrown.
- The game test installs a fake `PlotDirectory` with `Plots.install` and restores it in the same call. It builds at plots 1 and 2 and never force-rebuilds plot 0, which other packets' tests share.

Tests (gametest):

- building at plot 1's origin writes nothing outside plot 1's box;
- produces block-for-block the same studio as plot 0, translated;
- publishes 69 anchors offset by the origin;
- a rebuild keeps player-changed cells in plot 1 without touching plot 0's plan file.

Timing must stay in A-07's range. Singleplayer QA compare.

## MP-03: plots (registry, lifecycle, commands) (sec)

**Status: BlockedDependency (MP-F; MP-T for gametests).** Writer: general-purpose. Review: `/security-review`.

- The real `PlotDirectory`:
  - **Allocation:** a player with no plot gets the next free index on first join (when `autoAllocate`).
  - **Persistence:** in the world folder (`agentcraft/plots.json` plus per-plot dirs), an atomic write.
  - The registry is installed with `Plots.install` at server start.
  - Allocation telemetry.
- **Build:** a newly allocated plot is built at its origin (`autoBuild`) through `HqFeature`'s builder path with `Options.origin`, off the join tick if needed (A-07 says about 2.4 s per build; don't block the join packet). Plot 0 stays the singleplayer and QA plot.
- **Spawn:** the player's respawn point is set to their plot's `spawn` anchor, and the global world spawn is no longer moved by builds in multiplayer (A-37).
- **Anchors:** each plot's layout is published with `Anchors.publish(studio, layout)` and persisted per plot (`agentcraft/plots/<index>/anchors.json`). All of them are loaded at server start.
- **Commands** (on `/agentcraft`), with permissions:
  - `plot info` (anyone, own plot);
  - `plot home` (anyone; teleport to own spawn);
  - `plot rebuild [force]` (owner; ops for any plot);
  - `plot list` / `plot assign <player> <index>` / `plot free <index>` (ops).
- `/agentcraft hq` in multiplayer builds the caller's own plot, not the origin.

Decisions:

- **Allocation starts at index 0** and happens in `ServerPlayConnectionEvents.INIT`, which Fabric fires before `JOIN`. MP-F sends the hello on `JOIN`, so the hello already carries the index. Only the registry entry is made there: the build is queued on the server thread, one plot per tick, never inside the join.
- **In multiplayer `HqWorld.isHq` is false (MP-01)**, so the legacy auto-build at the origin and the `LOCAL` anchor load do not run. MP-03 owns building and loading there. Until MP-02 is merged the builder ignores `origin`: test with plot 0 only.
- **`plot rebuild [index] [force]`:** without an index it rebuilds the caller's own plot. The index is for ops.
- **`plot assign <player> <index>`:** the player is online, or a name the server's profile cache knows. It is refused when the player already has a plot or the index is taken. **`plot free <index>`** removes the registry entry, unpublishes the layout (`Anchors.remove`) and deletes that plot's directory (anchors and plan), so a later owner gets a clean build. The blocks stay. A freed owner gets a new plot on their next join when `autoAllocate` is on.
- After every allocation, assignment or free, call `StudioRange.refresh(server)`.
- **The command root opens.** `plot info` and `plot home` are for everyone, so `/agentcraft` itself loses its gamemaster requirement, and `hq`, `anchors` and the op-only plot commands each carry it. The acting player is always the command source's player, never an argument, except for the op commands above.
- **Persistence** uses `Anchors.toJson` and `Anchors.fromJson` (both public), so there is one layout format. `plots.json` holds UUIDs, indexes and origins only. A persisted index is validated before it becomes part of a path.
- `plot_built` and `plot_build_failed` are MP-02's, logged by the builder. MP-03 logs `plot_allocated`, `plot_allocation_failed` and `plot_command`.

Tests: gametests for allocation order, persistence round-trip across a restart, the spawn anchor, owner-vs-op permissions on every command, and a double-join not double-allocating. Unit tests against `PlotGrid`.

## MP-04: layout sync

**Status: BlockedDependency (MP-F).**

- **Server:** after `HelloS2C`, send `LayoutS2C` for the player's own plot plus every plot within `relayRadiusChunks` of the player, and re-send on publish and rebuild. Send `LayoutRemoveS2C` when a plot leaves range (track per player, check on a cheap interval).
- **Client:** apply into `Anchors.publish(studio, layout)` and `Anchors.setSelf(you)`. `Studios` sees new studios through MP-F's listener.

Decisions:

- **Range comes from `StudioRange`** (see "Who sees which studio"). MP-04 keeps no range set of its own: it registers a listener, sends `LayoutS2C` on `entered` when the studio has a layout and `LayoutRemoveS2C` on `left`, and sends a newly published or rebuilt layout to `StudioRange.viewersOf(studio)`. A removed layout (`Anchors.remove`) is sent as `LayoutRemoveS2C` to the same viewers.
- **Client:** on `LayoutS2C`, `Anchors.publish(studio, layout)` and `Studios.setPlot(studio, plotIndex, stride)` with the stride from `MpMode.serverInfo()`. On `LayoutRemoveS2C`, `Anchors.remove(studio)` and `Studios.remove(studio)`. MP-04 does not call `Anchors.setSelf`: MP-F's hello handler already has.
- The unit tests cover what MP-04 decides (which layout goes to whom, and when), not the range rule, which MP-F tests.

Tests: unit tests for the in-range set computation. Harness (needs MP-03's registry: once MP-03 is merged, otherwise in MP-I): client A's `dev.anchors` shows its plot's 69 anchors offset by its origin. When client B walks into range, B receives A's layout, and walking away removes it. Telemetry rows.

## MP-05: public-state publisher and redaction (sec)

**Status: BlockedDependency (MP-F).** Review: `/security-review`. This is the packet that decides what leaves the owner's machine.

- **`Redactor`:** a pure function from `ForemanState` plus `PublicPolicy` to `PublicStudioState`.
  - `awaitingUser` is computed from open decisions and task assignee exactly as `AgentManager` does today (A-25), so no decision or task leaves the machine.
  - Repo CI is reduced to slot indexes.
  - Opt-in fields are filled only when their flag is on.
- **`PublishScheduler`:** send on a ForemanState revision change, coalesced to at most `publicStatePerSecond` (from `MpMode.serverInfo()`), and skip unchanged states. Also: re-send on reconnect, `foremanOnline=false` while the Foreman link is down, say and task-done events as `StudioEventC2S` (text only when `sayText`).
- **Decisions:**
  - `rev` is the scheduler's own counter, starting at 1 for each connection and stamped when a state is sent. The `Redactor` stays pure and leaves it 0. "Unchanged" compares the content without `rev`. Nobody compares revs on receipt: the latest state wins.
  - A protocol value the wire cannot carry is mapped, never sent: agent state `UNKNOWN` to `idle`, station `UNKNOWN` to `desk`, goal status `UNKNOWN` and "no goal" to `none` (progress 0, no text). A task whose status is `UNKNOWN` is left out of `tasks` and of `counts`.
  - `counts.openDecisions` is every open decision, merges included. `counts.openMerges` is the merge subset.
  - Every string is cut to its cap and sanitized by the `Redactor`, and a blank opt-in text becomes `null`: the codec refuses rather than trims, and a refused state disconnects the owner. A record over a cap, or an agent whose id or skin is not an identifier path, throws where the `Redactor` builds it, so it leaves such an agent out and replaces such a skin.
  - Events are sent as they happen, at most `publicStatePerSecond` per second; the excess is dropped. `Say.text` is `null` unless `sayText` is on. `Say.length` is always sent.
  - The publisher only sends. It never writes the public projection into `Studios` for the owner's own id.
- **`PolicyStore`:** the owner's flags, persisted client-side (`<gameDir>/config/agentcraft-public.json`), all false by default (D-MP01). There is a `/agentcraft-public` client command or console command to view and toggle them (UI polish is MP-Z's).

Tests (JUnit):

- with all flags off, the published JSON for a showcase snapshot contains **none** of: any `activity` text, any task title, the goal text, any decision question, any log text, any repo id, any file path. Assert by scanning the serialized bytes for every free-text value in the input;
- each flag adds exactly its field;
- `awaitingUser` matches `AgentManager`'s rule on the showcase state;
- coalescing and rate limiting.

## MP-06: relay and presence (sec)

**Status: BlockedDependency (MP-F).** Review: `/security-review`.

- **Server:** accept `PublicStateC2S` only from a player who owns a plot, and attribute it to the connection's player, never to a payload field. Enforce `publicStatePerSecond` per player and refuse with telemetry. Store the latest state per studio, and relay `StudioStateS2C` to mod-equipped players within `relayRadiusChunks` of the studio's plot (on change, and to a player entering range). Relay events the same way. Send `PresenceS2C` on join and leave. When the owner leaves, relay `foremanOnline=false`, so agents dim (D-MP02).
- **Client (`RemoteStudiosFeature`):** apply `StudioStateS2C` / `PresenceS2C` / events into `Studios` (add or update or remove remote views, events to listeners). Ignore any state for the own studio id.

Decisions:

- **Range comes from `StudioRange`** (see "Who sees which studio"). The relay keeps no range set of its own. On `entered` it sends `PresenceS2C` and the studio's latest state. It relays to `StudioRange.viewersOf(studio)`, minus the owner.
- **A remote view is removed only by MP-04's `LayoutRemoveS2C`.** MP-06 sends no removal. `RemoteStudiosFeature` logs `remote_studio_added` and `remote_studio_removed` from a `Studios` listener.
- **Refusals.** A state from a player with no accepted hello is `bad_version`, from a player with no plot `no_plot`, over the rate `rate_limited`. `decode_failed` is logged by MP-F's codec. A refused state is not stored.
- **Events:** their own bucket at `publicStatePerSecond` (see "Rates"). An event is refused with `studio_event_rejected` when the sender has no accepted hello (`bad_version`), has no plot, is over the rate, or names an agent that is not in the studio's latest state. When that state's `policy.sayText` is off, the relay clears `Say.text` before sending it on.
- **Presence** goes to the studio's viewers, not to everyone: on `entered`, and when the owner joins or leaves. `ownerName` is the owner's player name, or empty when the server does not know it. When the owner leaves, the stored state is kept with `foremanOnline=false` and relayed once.
- **The "forged id" game test** proves attribution: `StudioStateS2C.studio` is the connection's player even when the ids inside the state look like another studio's. The payload has no studio field to forge.
- The handlers take the sender's UUID and the state as plain arguments, so JUnit can drive every refusal without a connection.

Tests: unit tests for the rate limiter and range set. Gametests: a non-owner's state is refused, and a state is attributed to the sender, not to a forged id. Harness (needs MP-03's registry: once MP-03 is merged, otherwise in MP-I): A's agents' states appear in B's `dev.mp.studios` within 1 s, and A disconnecting turns them offline on B.

## MP-07: world intents (sec)

**Status: BlockedDependency (MP-F).** Review: `/security-review`.

- **Client:** split `HqWorldDriver` into a pure `WorldIntent compute(ForemanState, Layout)` and an applier.
  - The singleplayer applier keeps today's `ServerTasks` path, so behaviour is unchanged (A-16, A-17).
  - The multiplayer applier sends `WorldIntentC2S` (the snapshot), on change and every 40 ticks as today.
  - `DecisionsFeature.syncPodium` goes through the same intent (`podiumOpen`) and no longer leaves a pending entry when nothing applies it (A-18).
  - **Decision:** `compute` stays pure. `syncPodium` no longer writes to the world: it sets the driver's podium override (open, closed or none), and the driver applies `compute(...)` with `podiumOpen` replaced by the override while one is set, through the same applier as everything else and on the same tick as today. So the podium still closes at once while the player is answering, in singleplayer and in multiplayer.
  - **Decision:** sends are coalesced to at most `intentsPerSecond` (from `MpMode.serverInfo()`), on change, plus the resync every 40 ticks.
  - **Decision (server):** an illegal key never reaches the applier, because the codec refuses it. What can still be unknown is a block entity in the plot whose own binding is not a legal lamp key. The applier skips that block, applies the rest, and logs `unknown_binding` once per plot, never with the binding text. A legal key that no block entity carries is simply unused.
- **Server (`IntentApplier`):**
  - Resolve the sender's own plot only (no plot means refused).
  - Within that plot's box, set the lamp `STATUS` by each block entity's binding, podium `OPEN`, merge `ACTIVE`, monitor `LIT` by binding, and the copper bulbs near the podium and merge anchors, the way `HqWorldDriver.apply` does today.
  - Rate limit per player. An unknown binding is ignored and logged once.
  - Nothing outside the box is ever touched.

Tests:

- JUnit: `compute` on the showcase and showcase-late states equals what today's driver applies, with the `ci:<repoId>` keys removed: `compute` emits `ci:#1` to `ci:#8` only. Capture today's results first, as a golden file, on the MP-F baseline. A test that the encoded showcase intent contains no repo id.
- Gametests:
  - an intent changes only blocks in the sender's plot;
  - a forged intent for another plot changes nothing;
  - rate limiting works.
- Harness (needs MP-03's registry: once MP-03 is merged, otherwise in MP-I): A's lamps change in the world, and B sees them change.
- Singleplayer QA compare: lamps, podium and merge station unchanged.

## MP-08: multi-studio agents

**Status: BlockedDependency (MP-F).** Verify with `dev.mp.fake` in singleplayer.

- `AgentManager` becomes a manager of `StudioAgents`, one per studio in `Studios`.
  - Each has its own entities, assigner, seats and user spots over its studio's layout, with ids from `Studios.entityIdBase` (A-31).
  - The own studio is driven by `ForemanState` exactly as today. A remote studio is driven by `PublicStudioState` and events.
- **Remote nameplates:** show the state word instead of activity unless the state carries `activity` (opt-in). Remote say bubbles show "…" with the same timing unless `text` is present.
- **Viewer-relative fixes (A-29):** a remote studio's agent in `waiting_user` walks to its **owner** if the owner's player entity is loaded and inside the plot, otherwise to that studio's `podium_user` spot, and never to the viewer. A bubble addressed to "user" is labelled with the studio's owner name.
- **Offline:** `foremanOnline=false` or presence offline shows the existing dimmed "Foreman offline" plate (A-25).

Decisions:

- **Plates are keyed per studio.** Agent ids are cast ids, so two studios share them. `PlateLayout` and `PlateStack` key their tracks by studio and agent, not by agent id alone.
- **`dev.mp.fake` with the overlay on replaces** the own studio's agents with the fake studio's (decide by what `Studios.at` returns for the own studio's area). With `overlay: false` both sets exist in the same building: use that for the 12-agent measurement.
- **A click on a remote studio's agent never opens the own agent card** (it has message and pause controls for your own Foreman). `AgentsFeature` calls `RemoteAgentClicks.fire(studio, agentId)` instead, a foundation seam that does nothing until MP-11 registers its read-only card.

Tests: JUnit for the per-studio id ranges and for awaiting-target selection. In game, with `dev.mp.fake {overlay}` and a showcase-shaped public state:

- remote agents walk to the right stations;
- plates show no activity text;
- the waiting agent goes to `podium_user`, not to you;
- `dev.agents` shows the fake studio's ids in the slot-1 range.

Also: QA shots `qa04_agent_desk`-style for remote, and a singleplayer QA compare. Measure `plateLayoutUs` with two studios (12 agents) and record it.

## MP-09: studio-aware displays (monitor, task wall)

**Status: BlockedDependency (MP-F).** Verify with `dev.mp.fake`.

Monitors and task wall boards inside a remote studio render a **remote look**:

- **Monitor:** the agent's name, a state badge and an "activity hidden" line (or the opt-in activity). Never a log line, file path or diff. Feed mode shows counts and goal status, plus goal text only if opted in.
- **Task wall:** the columns' card counts per status from `Counts`, plus cards with titles only when `tasks` is present (opt-in).

The own studio renders exactly as today. Keep the remote rendering cheap: no log buffers for remote studios, and no per-frame allocation (check `dev.displays` stats).

Tests: JUnit for the remote view models from a `PublicStudioState`. In game with `dev.mp.fake {overlay}`: monitor and task wall shots, plus a check that **no** string from the singleplayer Foreman's state appears on a remote display (feed the overlay a state whose names differ). Singleplayer QA compare (`qa03_task_wall`, `qa04_agent_desk`).

Decisions:

- **The remote wall keeps four lanes.** A blocked public task sits in the Todo lane, and the Todo header shows the red "N blocked" count from `Counts.blocked`, as the own wall does.
- **`StationRenderer`'s base extract** reads the own state's revision counter into `foremanRevision` for every station. That read stays (the file is shared). A remote renderer does not use `foremanRevision`: it keys its caches on the remote state's `rev`.
- **`Studios.at` empty while in multiplayer** takes today's own path, as the renderer rule says. MP-I checks the window before a layout arrives.

## MP-10: studio-aware stations (podium, merge station, archive, console terminal, lamp hologram)

**Status: BlockedDependency (MP-F).** Verify with `dev.mp.fake`.

Remote looks, from `PublicStudioState` only:

- **podium:** shows "N decisions waiting" and the waiting agent's face (no question text);
- **merge station:** "merge waiting" (no task title, no line counts);
- **memory archive:** a closed-shelf look (no titles);
- **console terminal:** an idle screen;
- **atrium hologram:** goal status and progress, plus goal text only if opted in.

`HqClientFeature` ambience reads block states (already server-side) and the studio's online flag. The own studio renders exactly as today.

Tests: JUnit view models. In-game overlay shots of each station. The same "no own-Foreman string leaks into a remote station" check as MP-09. Singleplayer QA compare (`qa05`-`qa08`-style shots).

Decisions:

- Under the singleplayer overlay the lamp, podium and merge **block states** stay the viewer's own (the singleplayer driver still runs). Verify the remote stations by their text and holograms, and note the caveat in the worknote. On a dedicated server the applier is the only writer.
- Station text stays plain literals, like every station today: no lang keys, no edit to `en_us.json`.
- The decision podium's cache is keyed on the remote state's `rev` for a remote studio, not on `foremanRevision`.

## MP-11: visitor interactions

**Status: BlockedDependency (MP-F).** Verify with `dev.mp.fake`.

- **`StationInteractions`:** a click on a station in a remote studio never reaches the station's feature handler (so it can never reach your own Foreman with someone else's context). Instead it opens a read-only visitor panel: the owner's name, the station kind and what the public state allows. Log `visitor_readonly`.
- **`AgentCardScreen`:** for a remote agent, a read-only card (name, state, station, awaiting) with no message box and no pause, stop or resume buttons.
- **The own-studio console key (`)** keeps working anywhere in the world and always talks to your own Foreman. Decisions (`J`) always answer your own.

Tests: JUnit for the gate's routing table. In game with the overlay: clicking each station and an agent opens the read-only panel, and `dev.foreman` shows no message was sent. Singleplayer: every station still opens its real screen.

Decisions:

- `visitor_readonly` is logged for stations and for agent cards (`station=agent`), with `action=open`. The station names are a closed set of constants in the packet's own feature class.
- **The remote agent card** is opened through the foundation seam: `VisitorFeature.init()` registers it with `RemoteAgentClicks.set(...)`. MP-11 does not edit `AgentsFeature` (MP-08's), and MP-08 does not open any card for a remote agent.
- Screen text uses whatever the neighbouring screens use. Lang keys (`mp.visitor.*`) only where that screen already uses translatable components.

## MP-12: DevBridge and dev tools in multiplayer

**Status: BlockedDependency (MP-F).**

- `DevCommands`:
  - In multiplayer, `needServer` paths fall back to commands the player sends to the server (`/tp`, `/gamemode`, `/time`, `/weather`: requires op, which the harness grants) with the same strict field validation.
  - `dev.camera` keeps its exact-pose check (A-20).
  - `dev.quit` disconnects and exits without trying to save a world it doesn't own.
  - `dev.state` reports `mode`, `studios` and the own plot.
- `AnchorsDev`, `HqCheck` and `CameraPath`: take an optional `studio` (default own) and resolve anchors through `Anchors.forStudio`.
- `MpDevCommands` (new): `dev.mp.send {payload}` sends any C2S payload through the real codec (for MP-06's and MP-I's tests), and `dev.mp.relay` shows the client's last received states and events.

Tests: on the harness, `dev.camera {anchor:"cam_room"}` lands on client A's own plot camera (needs MP-03's registry: once MP-03 is merged, otherwise in MP-I), and `dev.screenshot` works on both clients. Singleplayer DevBridge behaviour is unchanged (run `tools/test`, plus a QA compare).

Decisions:

- **`dev.mp.send`** takes one flat request: `{payload: "public_state", state: {…}}`, `{payload: "studio_event", event: {…}}`, `{payload: "world_intent", intent: {…}}` or `{payload: "hello", protocol, modVersion}`. The JSON goes through `PublicJson` and the real codec, so a malformed payload is refused on the client with the codec's own message.
- **A dev command names a studio by its UUID string**, as `dev.mp.studios` prints it. Absent or `"own"` means `Anchors.self()`.
- **`CameraPath`** reads the optional `studio` from the camera JSON itself, so `PlayCommands` (outside the row) does not change.
- JUnit for the pure parts (request parsing, studio resolution). The rest is checked on the harness.

## MP-13: plot protection (sec)

**Status: BlockedDependency (MP-F); BlockedDecision for the default only (D-MP05).** Review: `/security-review`.

- When `protectPlots` is on, refuse block break, place, bucket use, block-entity use that changes state, and explosions that affect blocks inside a plot by anyone but its owner and ops (Fabric `PlayerBlockBreakEvents`, `UseBlockCallback`, `AttackBlockCallback`; vanilla explosion hooks). Opening a container is a use, and a visitor can't.
- Right-clicking AgentCraft stations is **allowed**: it is client-only and read-only for visitors (MP-11, A-11).
- Between plots (outside every box) is open ground.

Tests: gametests per action (owner allowed, visitor refused, op allowed, outside-box allowed), with telemetry.

Decisions:

- **`protectPlots` ships on** (D-MP05, the default MP-F froze). The owner can turn it off in the server config.
- **Explosions never change a block inside a plot**, whoever caused them: the cause of an explosion cannot be attributed reliably, so there is no owner exception. Fabric has no explosion event, so this is the one mixin the packet may add: it removes the positions inside any plot from the blocks an explosion is about to change. Blocks outside every plot are affected as usual.
- **The owner test** is `Plots.directory().plotAt(pos)` and the acting player's UUID from the event, never anything a client sent. An op (gamemaster permission) passes. A position in no plot is open ground.
- The telemetry capture is asserted in the game tests (`MpLog.capture()` works there too).

## MP-14: deployment for multiplayer

**Status: BlockedDependency (MP-F).** The release pipeline itself already exists. It landed with the plan, modelled on Cimmeria's (see [deploy.md](deploy.md)):

- `deploy/Dockerfile`: Fabric 26.3, Fabric API and the mod, Java 25, non-root, world in `/data`.
- The entrypoint and backup hook.
- `deploy/compose.yaml`: a scoped Watchtower that backs up before every swap.
- `release-container.yml`: build → Trivy → dated tag with provenance → persistence smoke → `latest-prerelease` → pre-release.
- `release-on-comment.yml`: `/release` on a merged PR.

It was verified locally on 2026-10-03:

- boot to `Done` in about 10 s;
- the studio built on the superflat preset with 0 foreign blocks replaced;
- the backup hook with an RCON flush;
- a placed block survived a SIGTERM stop and restart;
- Trivy CRITICAL gate clean with one documented, expiring exception.

What remains for multiplayer:

- Seed `config/agentcraft-server.json` from `deploy/server/config/` with `enabled: true` and the plot settings (contract in MP-F). Extend the release smoke test: a plot is allocated for a fake join (gametest hook or RCON `agentcraft plot assign`) and `HelloS2C` is sent.
- Decide whether multiplayer servers keep the level name `AgentCraft HQ` once MP-01 makes the rules config-driven.
- Write `docs/multiplayer/player-install.md`: Fabric loader, Fabric API and the mod jar for 26.3, plus running your own Foreman (`tools\launch.ps1 -NoGame` or `node tools/mac.mjs launch --no-game`) and joining.

**Never commit the colo's address, hostname, SSH alias or credentials; `.env` is gitignored.**

Tests: the release workflow's smoke test, extended as above, plus a harness client (MP-H) joining a locally run image and receiving `HelloS2C`.

Decisions (they replace the text above where the two differ):

- **The level name stays `AgentCraft HQ`.** It is the world folder of a server that already exists, and with MP-01 it no longer decides anything in multiplayer.
- **The shipped config keeps `enabled: false`.** The colo updates itself from `latest-prerelease`, so a release must not switch the live server to multiplayer. MP-Z flips it with the owner. The smoke test mounts its own config with `enabled: true` for the multiplayer checks.
- **The image smoke test has no client**, so it proves `config_loaded enabled=true` (and `world_rules_applied` once MP-01 is in), not the hello. The hello is proven on the MP-H harness against the source-built server, and on the real image in MP-Z's owner UAT.
- **Plot allocation is not in the image smoke test.** The smoke test checks that the plot commands answer over RCON once MP-03 is in. Allocation is checked on the harness and in MP-I.
- **The image build runs `gradlew build -x runGameTest`:** JUnit still runs, and the game tests stay in CI, which runs them on every pull request and on `main`.
- **No Docker on the owner's machine** (CLAUDE.md). "A locally run image" becomes the image on the colo, which needs the owner's confirmation and belongs to MP-Z. MP-14 itself changes files and documentation only and is verified by the release workflow's own run.

## MP-I: integration

**Status: BlockedDependency (all of Wave 1).**

On the harness, with two sim Foremen:

- both players get plots;
- each sees their own studio fully;
- each sees the other's agents moving, dimmed when the other's Foreman stops, and offline when they log off;
- lamps change on both;
- visitor clicks are read-only;
- a restart of the server keeps the plots;
- nothing private appears on the other client. Run MP-05's leak scan on the bytes the server relayed (a server-side debug capture) and on screenshots of every remote station.

Also run the full singleplayer QA and compare it to MP-F's baseline. Fix seams by coordinator-assigned edits only, and record each in the worknote.

## MP-Z: close-out

**Status: BlockedDependency (MP-I).**

- Docs: README (multiplayer status and limits), CLAUDE.md, mod/DEV.md, mod/FEATURES.md, docs/QA.md (plots), and this ledger's status.
- Flip `enabled: true` in the **shipped** deploy config (not the code default).
- Write the owner UAT checklist in `handoffs/session-resume.md`, with a log query per step.
- With the owner's confirmation: deploy to the colo per `deploy.md`, then run the UAT with real players.
