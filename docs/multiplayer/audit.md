# Multiplayer: Audit Against `main`

> Type: reference. Audience: the coordinator and packet workers.
> Updated: 2026-10-03 (MP-00 spike, `main` @ `0be815d`). Companions: [campaign README](README.md), [work packets](work-packets.md), [MP-00 worknote](worknotes/MP-00.md).

Paths are relative to the repo root; `mod/src/main/java/dev/agentcraft/` is shortened to `main/…` and `mod/src/client/java/dev/agentcraft/client/` to `client/…`. IDs (`A-nn`) are cited by the work packets. "Verified" means the spike ran it on this machine; everything else is read from the code, and inferences are marked.

## 1. Toolchain and baseline (verified)

| ID | Finding | Evidence |
|---|---|---|
| A-01 | Java 25.0.2, Node 22.12.0, npm 10.9, git 2.52 and Docker 29.1 are installed. | `java -version`, `node --version`, `docker --version` |
| A-02 | `cd mod && gradlew build` succeeds with an isolated `GRADLE_USER_HOME=<repo>/.gradle-home` (cold: about 60 s including the Minecraft and Fabric downloads). | `artifacts/logs/spike-mod-build2.log` (gitignored) |
| A-03 | `gradlew build mcSources` **in one invocation fails** Gradle's task validation (`:compileJava` uses an output of `:genCommonSourcesWithVineflower` without a declared dependency). Run `build` and `mcSources` as separate invocations; both then succeed. Not a code bug, but every packet worker will hit it once. | `artifacts/logs/spike-mod-build.log` |
| A-04 | `cd foreman && npm ci && npm run check`: typecheck passes, **481/482 tests pass**. The one failure, `test/ws.test.ts:118` (`expected [...'unknown'...] to include 'fail'`), is deterministic on this machine. | `artifacts/logs/spike-foreman-check.log` |
| A-05 | Root cause of A-04: the sim demo repo declares `"engines": {"node": ">=22.18"}` and runs its `.ts` tests with plain `node --test` (`sandbox/create-demo.mjs:55-61`). On Node 22.12, `node --test` does not pick up `*.test.ts` files (type stripping arrived later in 22.x), so every CI run in the sim "passes" and the scripted CI failure beat (`foreman/src/agents/sim/scenario.ts:195-200`) never fails. `foreman/package.json` and `tools/package.json` declare only `node >=22`, and the README says "Node 22+". **Inference, strongly supported:** Node ≥ 22.18 turns the suite green. | `sandbox/create-demo.mjs:55-61`; `foreman/package.json` `engines` |

## 2. Dedicated server (verified)

| ID | Finding | Evidence |
|---|---|---|
| A-06 | `gradlew runServer` boots a dedicated 26.3 server with the mod: Fabric loads 44 mods, common init registers 16 blocks and the six-agent cast, and the server reaches `Done (1.786s)`. `src/main` has no client references (grep finds only a Javadoc mention), `agentcraft.mixins.json` is empty, and the client entrypoint and mixins are environment-gated in `fabric.mod.json`. | `artifacts/logs/spike-runserver.log`; `mod/src/main/resources/fabric.mod.json:12-26`; `main/AgentCraft.java:19` |
| A-07 | `/agentcraft hq` from the dedicated server's console builds the full studio on a default-terrain world (`level-name=spike-world`): planned in 38 ms, applied 346,983 cells in 2,363 ms (152,690 changed, 100 bindings), published layout `studio` rev 1 with 69 anchors, replaced 5,627 non-HQ blocks. `/agentcraft anchors` lists the layout. The command works even though the level is not named "AgentCraft HQ". | `artifacts/logs/spike-runserver-hq.log` lines 81-87 |
| A-08 | With no console input, `runServer` under Gradle does not stop on its own (it autosaves and pauses when empty). Harness tooling must stop a dedicated server with `stop` on stdin or by killing the recorded server pid, never "wait for exit". | spike observation |
| A-09 | The 26.3 client accepts `--quickPlayMultiplayer <host:port>`, so a dev client can join a local dedicated server with no clicks. | `mod/build/mcsrc/net/minecraft/client/main/Main.java:90,211` |

## 3. No networking exists

| ID | Finding | Evidence |
|---|---|---|
| A-10 | The mod has **no custom networking at all**: no `CustomPacketPayload`, `PayloadTypeRegistry`, `ServerPlayNetworking` or `ClientPlayNetworking`. The only server→client data is vanilla: block states and `StationBlockEntity`'s `binding` string via `getUpdatePacket` / `getUpdateTag`. `ServerPlayConnectionEvents.JOIN` is used only to force game mode. | grep over `mod/src` (0 hits); `main/block/entity/StationBlockEntity.java:55-76`; `main/world/HqWorld.java:36-45` |
| A-11 | Station clicks are handled client-side and return FAIL, so nothing is sent to the server (console terminal, podium, merge station, archive and catalog, lectern, task board). Agents are client-only entities, and clicks on them never reach the server either. | `client/world/StationInteractions.java:40-51`; `client/agents/AgentsFeature.java:115-122` |

## 4. The layout reaches the client only through a shared JVM static

| ID | Finding | Evidence |
|---|---|---|
| A-12 | `Anchors.current` is one `private static volatile Layout` per JVM. It is filled by the `SERVER_STARTED` load or `publish()` and cleared on `SERVER_STOPPED`. The Javadoc says it outright: "the client (same JVM in singleplayer) reads it directly." | `main/layout/Anchors.java:31,58,64-71,90-95` |
| A-13 | On a remote server, then, every client reader sees `Layout.EMPTY` (inference from A-12): `AgentManager` falls back to a row of agents in front of world spawn, and `HqWorldDriver`, `HqCheck`, `HqClientFeature`, `StatusLampRenderer`, `MonitorFeature`, `Seats`, `AgentLife`, `CameraPath` and `AnchorsDev` bail out or default. | `client/agents/AgentManager.java:140,148,442-451`; `client/hq/HqWorldDriver.java:91`; `client/hq/StatusLampRenderer.java:101-103`; `client/monitor/MonitorFeature.java:172`; `client/agents/Seats.java:82`; `client/agents/AgentLife.java:605`; `client/dev/play/CameraPath.java:195`; `client/world/AnchorsDev.java:25` |
| A-14 | `Anchor` already has an unused `offset(dx, dy, dz)`. `Anchors.Builder.put` is the single path that `spot` / `camera` / `cameraLookAt` go through, so an origin offset in the builder covers every anchor. | `main/layout/Anchor.java:28-30`; `main/layout/Anchors.java:220-223` |
| A-15 | Anchor naming contract: per agent `desk_<id>`, `monitor_<id>`, `seat_<id>`; shared stations `library`, `terminal`, `testbench`, `mergestation`, `meeting`, `lounge`, `user` with slots `<station>_2..N`; fixed `task_wall`, `decision_podium`, `podium_user`, `goal_atrium`, `entrance`, `spawn`; cameras `cam_*`. Names are not studio-scoped, so two studios must live in two `Layout`s, not one. | `main/layout/AnchorNames.java:10-59`; `client/agents/StationAssigner.java:37-45,106-116` |

## 5. Every Foreman-driven world change needs the integrated server

| ID | Finding | Evidence |
|---|---|---|
| A-16 | `ServerTasks.run` is the single route for client-driven world changes. It calls `getSingleplayerServer()`, returns `false` when there is none, and otherwise queues the task on the integrated server's overworld. | `client/world/ServerTasks.java:24-32` |
| A-17 | `HqWorldDriver.tick` (END_CLIENT_TICK) returns early without an integrated server. Otherwise it recomputes the wanted state on a ForemanState or layout revision change, or every 40 ticks, then scans every block entity within the layout bounds ±3 on the server thread. It sets lamp `STATUS`, podium `OPEN`, merge station `ACTIVE`, monitor `LIT`, and vanilla `CopperBulbBlock.LIT` near the podium and merge anchors. Inputs: agents (active, state family), open decisions, task assignee, repo CI, goal status. Binding keys: `agent:<id>`, `ci:<repoId>`, `ci:#n`, `goal`, `goal:atrium`, `decisions`, `merge`, `beacon`. All of its state is static. | `client/hq/HqWorldDriver.java:58,68-72,87-110,130-187,247-327` |
| A-18 | `DecisionsFeature.syncPodium` (called from the podium renderer every frame) also writes `OPEN` through `ServerTasks`. On a remote server the pending entry is stored even though `run` returned false, so the podium would never change (bug-shaped; inference). | `client/decisions/DecisionsFeature.java:46,120-141`; `client/decisions/DecisionPodiumRenderer.java:99` |
| A-19 | The only `setBinding` call is the server-side builder. Client code never writes block entity fields; it writes only block states, and only via A-16. | `main/hq/Plan.java:378` |
| A-20 | DevBridge server work (`dev.camera`, `dev.release`, `dev.time`, `dev.weather`, `dev.command`, `dev.quit`'s world save) goes through `DevCommands.needServer`, which throws "needs a singleplayer (integrated server) world" on a remote server. | `client/dev/DevCommands.java:207-214,479-541,611-626,684-693,849-889` |

## 6. One static Foreman state, rendered everywhere

| ID | Finding | Evidence |
|---|---|---|
| A-21 | `Foreman` holds one static `ForemanState` and one `ForemanLink` (to `ws://127.0.0.1:<AGENTCRAFT_PORT>`). This is right for "each player's own Foreman", but nothing can hold a second studio's state. `ForemanState`'s constructor, `receive` and `apply` are package-private; public `inject` / `patch` already accept arbitrary wire JSON. | `client/foreman/Foreman.java:23-24,29`; `client/foreman/ForemanState.java:82,272,333,342,368` |
| A-22 | `Foreman.state()` appears 102 times on 88 lines in 36 files (console 16, decisions 18, taskwall 12, diff 12, library 11, agents 10, hud 9, foreman 4, hq 4, dev/play 3, world 2, monitor 1). Another 35 `UiBits.agentName/userName/…` calls read it indirectly. 8 `Foreman.addListener` sites. | grep (audit agent count) |
| A-23 | Every block-entity renderer draws from the viewer's own static ForemanState, not from block entity data: monitor, task board, merge station, console terminal, status lamp hologram, podium, memory archive. **On a shared server every player would see their own Foreman's data on every studio's screens** (inference). | `client/monitor/MonitorRenderer.java:82`; `client/taskwall/TaskBoardRenderer.java:93`; `client/diff/MergeStationRenderer.java:91,148`; `client/console/ConsoleTerminalRenderer.java:99`; `client/hq/StatusLampRenderer.java:93,126`; `client/decisions/DecisionPodiumRenderer.java:89-99`; `client/library/MemoryArchiveRenderer.java:85-106` |
| A-24 | Other per-JVM statics keyed to "the" studio: `AgentManager.INSTANCE`, `HqWorldDriver` last/lastRevision, `MonitorFeature` LOG_SEQ / AGENT_SEQ / SCREENS, `MergeStationRenderer` queue cache, `DecisionQueue` cachedRevision, `DecisionsFeature` ANSWERING / ANSWERS / PODIUM_PENDING, `MemoryIndex` SEEN, `TaskWallFeature` taskSeq / agentSeq. | `client/agents/AgentManager.java:49`; `client/hq/HqWorldDriver.java:68-69`; `client/monitor/MonitorFeature.java:47-54`; `client/diff/MergeStationRenderer.java:75-76`; `client/decisions/DecisionsFeature.java:42-46` |

## 7. What the world rendering of agents consumes (the public subset)

| ID | Finding | Evidence |
|---|---|---|
| A-25 | Agent entities (manager, view, nameplate, life, skins) read: `Agent.id`, `name`, `skin`, `state`, `station`, `active`, `paused`, `activity` (nameplate line), plus a derived "awaiting user" that needs open decisions (`kind`, `agentId`, `taskId`) and `Task.assignee`. Colour comes from the id through `cast.json`, not `Agent.color`. | `client/agents/AgentManager.java:135-256`; `client/agents/AgentView.java:65-82`; `client/agents/Nameplate.java:58-105`; `client/agents/StationAssigner.java:29-52` |
| A-26 | Events that animate agents: `agent.say` (`agentId`, `to`, `text`; text length sets listen time) drives bubble, talk pose and head look; a task changing to `done` fires confetti on its assignee. | `client/agents/AgentsFeature.java:84-114`; `client/agents/SpeechBubble.java:54-63,160-171`; `client/agents/AgentLife.java:179-199` |
| A-27 | Free text that can leak code or plans, and where it shows: `Agent.activity` (nameplate, monitor; e.g. "editing src/cli.ts"), `agent.say` text (bubbles), log lines with tool arguments, paths and diff lines (monitors, worst), `Task.title` / `blockedReason` (task wall, merge station), goal text (monitor feed mode, atrium hologram), `Decision.question` (podium; permission prompts contain commands), memory titles (archive), feed text (console terminal), `userName`. | `foreman/src/protocol.ts:80-96,106-124,134-151,191-199`; consumers in A-23 |
| A-28 | Lamps need only status: agent state family, CI per repo, goal status, "a decision is open", "a merge is open". The `goal:atrium` hologram is the exception and shows goal text and task counts. | `client/hq/HqWorldDriver.java:169-185`; `client/hq/StatusLampRenderer.java:93-206` |
| A-29 | Viewer-relative behaviour that is wrong for a visitor: an agent in `waiting_user` walks to the local `mc.player`, and a bubble addressed to "user" is labelled with the *viewer's* name. On a visitor's client, the owner's agents would walk up to and address the visitor. | `client/agents/AgentManager.java:152,259-276`; `client/agents/SpeechBubble.java:164-166` |
| A-30 | What binds a block to an agent: the monitor's BE `binding` (agent id or `feed`, else resolved by the `monitor_<id>` anchor), the lamp's BE `binding`, merge stations by row position (k-th station shows the k-th open merge), archive by scope binding plus shelf position. Podium, task wall and console terminal have no binding and render the whole state. | `client/monitor/MonitorFeature.java:168-200`; `client/diff/MergeStationRenderer.java:109-126`; `client/library/MemoryArchiveRenderer.java:60-109` |
| A-31 | `ClientAgentEntity` ids come from a decreasing counter starting at −10,000 in the singleton `AgentManager`, never reset. One counter for all studios avoids collisions as long as there is one manager. If studios each get their own manager, they need disjoint id ranges (inference). | `client/agents/AgentManager.java:69,355` |

## 8. The HQ is hard-wired to the world origin

| ID | Finding | Evidence |
|---|---|---|
| A-32 | The studio builder uses absolute constants and literals: `GROUND=64, FLOOR=65, FEET=66`, hall x −24..24 z −10..6, atrium at (0,15), site box `{-46,60,-36, 46,100,54}` (93 × 41 × 91), desk x values, plus many literal coordinates (library, workshop, lounge, meeting, cameras, dormers, chimney). | `main/hq/StudioHqBuilder.java:56-85,185,426-440,926-1094,1308-1323` |
| A-33 | `Plan` is an in-memory box, applied by diffing against the world with `setBlock` and `FLAGS`, connecting blocks in a second pass, then bindings, then a drop and XP sweep. Chunks are loaded synchronously (42 chunks at the origin). It is idempotent and keeps player-changed cells unless `force`. `Plan.set` drops anything outside the box, so a build never writes outside its site. | `main/hq/Plan.java:62-108,138-142,190-213,224-285,294-408` |
| A-34 | `HqLandscape` reshapes the whole site box (hills to about 7 blocks, pond, garden, paths, lychgate, tree rings). Its randomness hashes **absolute** x/z, so translating the plan at apply time (not at plan time) gives every plot the identical studio (inference). | `main/hq/HqLandscape.java:31-43,48-81,583-651` |
| A-35 | `PlanStore` keeps one file, `agentcraft-hq-plan.dat`, in the world root, keyed only by builder id plus box. Another builder's run deletes it. Two plots cannot share it. | `main/hq/PlanStore.java:30-49,99-117`; `main/hq/HqFeature.java:104` |
| A-36 | `HqWorld`: `isHq` compares the level name to `"AgentCraft HQ"`. First start runs `setworldspawn 0 65 0`, time 12000 and clear weather. Every start applies world-wide game rules (no daylight or weather cycle, no mobs, keep inventory, …, `max_block_modifications 1,000,000`, `LOG_ADMIN_COMMANDS` off). Every join forces SURVIVAL or SPECTATOR players into CREATIVE. A dedicated server with the default `level-name=world` skips all of it, plus anchor loading and auto-build (inference). | `main/world/HqWorld.java:24,36-45,48-50,58-68,73-96` |
| A-37 | `HqFeature`: auto-build on `SERVER_STARTED` when the world has no anchors (`AGENTCRAFT_HQ_AUTOBUILD=0` turns it off). `/agentcraft hq [builder] [force]` (op level 2) builds at the fixed site, publishes the single layout, and **moves the global world spawn** to the `spawn` anchor on every build. | `main/hq/HqFeature.java:29-54,55-64,75-118`; `main/command/AgentCraftCommands.java:45-50` |
| A-38 | `TestRoomBuilder` is absolute too (`FLOOR=64`, clears x −16..16, z −32..16) and places blocks directly with `setBlock` and `UPDATE_CLIENTS`, bypassing `Plan`. It is a development fixture; nothing in multiplayer needs it at an offset. | `main/hq/TestRoomBuilder.java:49-94,217,339-353` |
| A-39 | `AutoWorld` opens or creates the local "AgentCraft HQ" world on the first title screen: creative, peaceful, cheats on, superflat with grass at y=64. It runs before a player can pick a server. Its only off switch is `AGENTCRAFT_AUTOWORLD=0`. | `client/AutoWorld.java:46-69,73-110`; `client/ClientEnv.java:29` |
| A-40 | QA's contract states "Coordinates are absolute world coordinates". `tools/scenes/qa.json` uses anchors with an absolute fallback camera. The other scenes (`agents*.json`, `console.json`, `displays*.json`, `phase1.json`) use raw coordinates around the origin. As long as singleplayer keeps the studio at plot 0 = origin, QA is unaffected. | `docs/QA.md:97-98`; `tools/scenes/*.json` |

## 9. Shared-file hotspots (sizes)

| File | Lines | Why it matters |
|---|---|---|
| `main/hq/StudioHqBuilder.java` | 1330 | Only needs an origin passed to `Plan` and the anchors builder. Keep it in local coordinates. |
| `main/hq/HqLandscape.java` | 800 | Unchanged if translation happens at apply time (A-34). |
| `client/dev/DevCommands.java` | 1003 | Every `needServer` site (A-20). One owner. |
| `client/foreman/ForemanState.java` | 534 | Read-only for this campaign. The publisher listens; nobody edits it. |
| `client/agents/AgentManager.java` | 452 | The multi-studio agents packet owns it. |
| `main/hq/Plan.java` | 415 | The build-at-offset packet owns it. |
| `client/hq/HqWorldDriver.java` | 329 | The world-intent packet owns it. |
| `main/layout/Anchors.java` | 245 | Made per-studio once, by the foundation. |
| `client/ClientFeatures.java` / `main/AgentCraft.java` | 45 / 41 | One wiring line per feature. The foundation adds every multiplayer line once. |
| `foreman/src/protocol.ts` / `client/foreman/Protocol.java` | 504 / 325 | **Untouched by v1.** No Foreman protocol change is needed. |

## 10. What the handoff assumed that the spike confirmed or changed

| ID | Finding |
|---|---|
| A-41 | **Confirmed:** the jar loads on a dedicated server, and the server-side builder works there (A-06, A-07). The singleplayer assumptions are exactly `AutoWorld`, `ServerTasks` / `HqWorldDriver` / `DecisionsFeature.syncPodium`, and `DevCommands.needServer`, plus the server-side singletons in §8. |
| A-42 | **Added:** the anchor layout never reaches a remote client (A-12, A-13), so a "layout sync" payload is a hard prerequisite for rendering anything, including your own studio. The handoff's plan did not list it. |
| A-43 | **Added:** every block-entity renderer shows the viewer's own Foreman state on every studio (A-23). Studio-aware renderers are required for correctness, not only for privacy. |
| A-44 | **Added:** the mod has no unit-test source set and no game tests. All verification today is in-game through the DevBridge plus screenshot QA. Pure-logic multiplayer code (codecs, redaction, plot math, intent validation) needs JUnit, and server behaviour needs a headless test path. |
| A-45 | **Added:** the baseline Foreman suite is red on Node < 22.18 (A-04, A-05). Every worker machine needs Node ≥ 22.18. |

## 11. The colo and the release pipeline (verified 2026-10-03)

Host details (addresses, hostnames, the SSH alias) are deliberately not recorded: this repository is public.

| ID | Finding | Evidence |
|---|---|---|
| A-46 | The colo runs Debian with Linux 6.1, Docker Engine 28.0.1 (API 1.48, minimum 1.24) and Compose 2.33.1, has ample free RAM, and keeps Docker's data on a large NVMe volume. Watchtower 1.7.1 (API 1.25) works there unchanged. | read-only SSH inspection |
| A-47 | Cimmeria's deployment: `/opt/cimmeria/compose.yml` and `.env`, root-owned, mode 600, with dated `.bak-<date>-<why>` copies before each edit. One **unscoped** Watchtower (`containrrr/watchtower:latest`) with `WATCHTOWER_LABEL_ENABLE=true`, `REMOVE_VOLUMES=true`, a 5-minute poll and Discord notifications. It pulls `ghcr.io/sandboxservers/cimmeria-server:latest-prerelease` with **no registry login**, so that package is public. | `docker inspect` labels and env names; `~/.docker/config.json` has no ghcr entry |
| A-48 | **Two Watchtowers on one host:** an unscoped instance's startup duplicate check lists every container labelled `com.centurylinklabs.watchtower=true`, with no scope filter, and stops all but the newest. The Watchtower image sets that label on itself, so adding a second, newer Watchtower makes Cimmeria's stop **itself** on its next restart. Reproduced locally (`Found multiple running watchtower instances... Stopping /wt-cimmeria-sim`). Fix: override the label to `"false"` on ours (the check requires exactly `"true"`). | watchtower v1.7.1 `internal/actions/check.go:41-60`, `pkg/container/metadata.go:44-47`, `pkg/filters/filters.go:88-100,123-170` |
| A-49 | Isolation of updates: a scoped instance considers only containers whose scope label equals its scope (an unlabelled container counts as scope `none`). A label-enable instance considers only containers with `watchtower.enable`. With our server labelled scope `agentcraft` and no enable label, neither instance can update the other's containers. | `pkg/filters/filters.go` |
| A-50 | A Watchtower update is: kill with the stop signal (SIGTERM), wait up to `WATCHTOWER_TIMEOUT` (default 10 s), force-remove (with `RemoveVolumes` if set: anonymous volumes only), then recreate. Our world is a named volume, and our timeout is 120 s. | `pkg/container/client.go:184-225`; `internal/flags/flags.go:425` |
| A-51 | A Watchtower run without `--label-enable` or `--scope` updates **every** container on the host whose image has a newer tag. A diagnostic `--run-once` on the owner's machine recreated an unrelated local container (`cimmeria-signoz-mcp`, now on a newer `:latest`, healthy). Never run one unfiltered. | incident during the spike |
| A-52 | Docker Desktop at version 29 rejects Watchtower 1.7.1 (`client version 1.25 is too old. Minimum supported API version is 1.44`), so local Watchtower trials need `DOCKER_API_VERSION=1.44`. The owner's direction: run these tests on the colo, not locally. | local run |
| A-53 | The colo host has no firewall beyond Docker's own nftables rules. It sits on a private network, so player reachability on the chosen port depends on the colo network's edge forwarding (not verifiable from the host). | `nft list ruleset`; no `ufw` |
| A-54 | The pinned action SHAs copied from Cimmeria resolve on GitHub's runners (first release run). | run `37153802366` |
| A-55 | **The full update cycle works on the colo.** Release `2026-10-03.2` was picked up by the AgentCraft Watchtower at its next poll: the pre-update backup hook ran, then a SIGTERM stop, then a new container. The world reloaded from the volume ("Loaded layout 'studio' rev 1", no rebuild), the old image was cleaned up, and the Cimmeria containers and Watchtower were untouched. The first attempt at release 2 failed a false-negative smoke check (`docker logs \| grep -q` under `pipefail` gets SIGPIPE when grep exits early); the checks now read the logs from a file. | colo `agentcraft-watchtower` log, 21:19 UTC; workflow fix in `release-container.yml` |
