# Multiplayer AgentCraft

> Type: how-to. Audience: the Claude Code coordinator, packet workers and the owner.
> Updated: 2026-10-04 (Wave 1 written and reviewed; see the session resume). Companions: [audit](audit.md), [work packets](work-packets.md), [session resume](handoffs/session-resume.md), [deploy runbook](deploy.md), [MP-00 worknote](worknotes/MP-00.md), [original handoff](handoff-2026-10-03.md).

## Purpose

A fully multiplayer AgentCraft. People join one shared Minecraft world hosted on the colo, each with their own studio plot. Each player brings their own AI and drives **their own Foreman on their own machine**. The server hosts only Minecraft: never anyone's agents, code or credentials.

The design keeps upstream's two principles: **"the game is only a view"** and **agents are client-side entities**. The server is a relay and a landlord, not a brain:

- Each player's client publishes a redacted public subset of its own Foreman state.
- The server relays that subset to nearby players and applies world changes only inside the sender's own plot.
- Private data (code, diffs, decisions, console, memory, logs) never leaves the owner's machine.

Multiplayer is a mode. A dedicated server with `"enabled": false` (the default), and every singleplayer world, behave exactly as upstream does today. That is the rollback lever and what keeps upstream merges cheap.

Out of scope for v1 (the handoff's v2):

- a Foreman connecting straight to the server (studios live while their owner is offline);
- backends for other providers.

## What the spike found (MP-00)

Details and evidence: [audit.md](audit.md). Headlines:

- The mod jar **boots on a dedicated 26.3 server**, and `/agentcraft hq` builds the whole studio there (347k cells in 2.4 s, 69 anchors) (A-06, A-07).
- The mod has **no networking at all**. The anchor layout reaches the client only through a static shared in one JVM, so on a remote server even your own studio renders wrong (A-10, A-12, A-13). This adds a layout-sync packet the handoff didn't list.
- Every block renderer draws the **viewer's own** Foreman on **every** studio (A-23), so studio-aware renderers are needed for correctness, not only privacy.
- The HQ is hard-wired to the world origin. Building it at an offset is a contained change if translation happens at apply time (A-32 to A-35).
- The baseline Foreman suite is 481/482 on this machine because Node 22.12 < 22.18 (A-04, A-05).
- The mod has no unit or game tests (A-44).

## Decisions

Proposed defaults are what the campaign builds against. The owner can change any of them, and each is isolated so that a change is cheap: data, config or one packet. D-MP09 is decided.

| ID | Decision | Status | Blocks |
|---|---|---|---|
| D-MP01 | **What is public.** By default only agent identity and skin, state, station, active/paused, "awaiting user", task counts by status, the open-decision count, goal status and progress, and CI per repo slot. Activity text, speech text, task titles and goal text are opt-in per studio, all off by default. The allowlist is the `PublicStudioState` record itself (MP-F). | Proposed (handoff default) | nothing; flipping it is data |
| D-MP02 | **A studio is live only while its owner is online** in v1. When the owner logs off, their agents show the dimmed "Foreman offline" state. v2 (the direct gateway) lifts this. | Proposed (handoff recommendation) | nothing |
| D-MP03 | **Access:** a whitelisted server, `online-mode=true`. Players get the mod jar from each GitHub pre-release (attached by the release workflow). | Proposed | the colo deploy only (MP-Z) |
| D-MP04 | **Upstream:** keep multiplayer behind a mode, mostly in new packages, and decide later whether to offer it upstream. | Proposed | nothing |
| D-MP05 | **Plot protection:** only the owner and ops can break, place or use blocks inside a plot. Visitors can walk in and click stations (read-only). | Proposed | MP-13's default only |
| D-MP06 | **Game mode on the shared server:** creative and peaceful for everyone (as the HQ world forces today), configurable (`forceCreative`). Op commands stay logged in multiplayer. | Proposed | nothing |
| D-MP07 | **World and plot layout:** a superflat world (grass top y = 64, which the builder assumes), and plots on a square spiral with stride 128. **Plot 0 is the origin**, so singleplayer and QA are unchanged. | Proposed | nothing |
| D-MP08 | **One studio per player.** Ops can assign or free plots. | Proposed | nothing |
| D-MP09 | **Release pipeline like Cimmeria's** (live on the colo since 2026-10-03; see audit §11): gated GHCR releases (`/release` on a merged PR, or a manual dispatch from `main`), a Trivy gate, a persistence smoke test, `latest-prerelease` promotion, and a Watchtower on the colo that pulls (never pushed to). It uses its own Watchtower scope beside Cimmeria's, takes a world backup before every swap, and stores no host details in the repo or its secrets. | **Decided** (owner, 2026-10-03); shipped with the plan | nothing |

## Open questions for the owner

1. Confirm or change D-MP01 (the public default). It is the one decision that shapes what other players can see.
2. Confirm D-MP05: should visitors be able to build anywhere, or only outside plots?
3. Who is on the first whitelist? The colo already runs today's server (as a preview; remote players' agents don't render correctly until the campaign lands, A-13). Players also need the edge to forward TCP 25565 (A-53).
4. Should the cast's `upstream-sync-steward` open a conversation with upstream about contributing multiplayer later (D-MP04)?
5. The harness's live up/down cycle on Windows was run on 2026-10-04 (`worknotes/MP-H.md`). Who runs the one `down` on macOS that #4 still owes?

## Owner rulings from the pull-request review (2026-10-04)

Given by the owner's reviewer on #5, in answer to the questions the review left open:

- **Plot stride.** A server refuses to start when `plotStride` no longer matches its stored plots. No wire change (a plot's origin is not sent).
- **Plot protection (MP-13, #18), under D-MP05.** Approved as built: with an empty hand a visitor may use only doors, trapdoors, fence gates, buttons and levers inside a plot; protection covers the plot plus a margin of one block; the whole column above a plot is protected; non-player entities in a plot are protected; structures completed from outside that need blocks the owner placed across the edge (a nether portal, a golem pattern) are left open. **Pistons pushing in from outside stay a tracked follow-up** (it needs a mixin the packet was not allowed); so do the other known limits in its worknote (fluids and fire from outside, projectiles, trampling).
- **Telemetry catalog.** A refused `plot_command` carries no `reason`. `studio_event_rejected` gains the reason `bad_version`. Both are now in work-packets.md.
- **Typed telemetry values in `MpLog`.** Deferred until after Wave 1; not blocking.
- **Singleplayer and MP-07 (#12).** Singleplayer stays unchanged: the wire filter (no `ci:<repoId>` key, eight CI slots, wire-legal agent ids, the 64 caps) applies only to what is sent to a server. The `ci:<repoId>` lamp binding is a documented feature.
- **`/agentcraft` open to every player, and no cap on plots.** Fine for now behind the whitelist. **Every new subcommand must carry its own permission check.**
- **Remote DevBridge commands** stay behind `AGENTCRAFT_DEV_REMOTE=1`, which the harness sets for the clients it launches (#4).
- **Diffs for Wave 1 reviews** are taken from `3d6a58f`.

## Packet status

| Packet | Status | Wave | PR | Worknote |
|---|---|---|---|---|
| MP-00 Spike and plan | **Done** | — | (this commit) | [MP-00](worknotes/MP-00.md) |
| MP-F Foundation | Review | 0 | #5 | [MP-F](worknotes/MP-F.md) |
| MP-T Game tests | Review | 0 | #1 | [MP-T](worknotes/MP-T.md) |
| MP-B Baseline (Node ≥ 22.18) | Review | 0 | #2 | [MP-B](worknotes/MP-B.md) |
| MP-H Two-client harness | Review | 0 | #4 | [MP-H](worknotes/MP-H.md) |
| MP-01 Server mode and world rules | Review (reviewed and verified, in `mp/integration`) | 1 | #6 | on the branch `mp/MP-01-server-mode` |
| MP-02 Build at an offset | Review (reviewed and verified, in `mp/integration`) | 1 | #7 | on the branch `mp/MP-02-offset-build` |
| MP-03 Plots: registry, lifecycle, commands | Review (reviewed and verified, in `mp/integration`) | 1 | #8 | on the branch `mp/MP-03-plots` |
| MP-04 Layout sync | Review (reviewed and verified, in `mp/integration`) | 1 | #9 | on the branch `mp/MP-04-layout-sync` |
| MP-05 Public-state publisher and redaction | Review (reviewed and verified, in `mp/integration`) | 1 | #10 | on the branch `mp/MP-05-publisher` |
| MP-06 Relay and presence | Review (reviewed and verified, in `mp/integration`) | 1 | #11 | on the branch `mp/MP-06-relay` |
| MP-07 World intents | Review (reviewed and verified, in `mp/integration`) | 1 | #12 | on the branch `mp/MP-07-world-intents` |
| MP-08 Multi-studio agents | Review (reviewed and verified, in `mp/integration`) | 1 | #13 | on the branch `mp/MP-08-multi-studio-agents` |
| MP-09 Studio-aware displays | Review (reviewed and verified, in `mp/integration`) | 1 | #14 | on the branch `mp/MP-09-displays` |
| MP-10 Studio-aware stations | Review (reviewed and verified, in `mp/integration`) | 1 | #15 | on the branch `mp/MP-10-stations` |
| MP-11 Visitor interactions | Review (reviewed and verified, in `mp/integration`) | 1 | #16 | on the branch `mp/MP-11-visitors` |
| MP-12 DevBridge in multiplayer | Review (reviewed and verified, in `mp/integration`) | 1 | #17 | on the branch `mp/MP-12-dev-tools` |
| MP-13 Plot protection | Review (reviewed and verified, in `mp/integration`) | 1 | #18 | on the branch `mp/MP-13-plot-protection` |
| MP-14 Deployment for multiplayer | Review (reviewed and verified, in `mp/integration`) | 1 | #19 | on the branch `mp/MP-14-deploy` |
| MP-I Integration | Not started (Wave 1 is on `mp/integration`; it starts when the owner says so) | 2 | | |
| MP-Z Close-out, UAT, colo deploy | BlockedDependency (MP-I) | 3 | | |

## Parallelization plan

The full design, the frozen contract, the file-ownership matrix and the dependency graph are in [work-packets.md](work-packets.md). Summary:

- **Wave 0 runs four packets in parallel now.** MP-F is the only gate for code. It freezes every contract and pre-wires empty feature classes, so no later packet edits the shared wiring. MP-T (game tests), MP-B (Node baseline) and MP-H (the dedicated server + two clients + two Foremen harness) need nothing from it.
- **Wave 1 runs 14 packets in parallel** once MP-F merges, and no Wave-1 packet waits for another to build and test:
  - **The renderer packets** (MP-08 to MP-11) verify in singleplayer through MP-F's `dev.mp.fake` overlay, which makes your own studio render as someone else's.
  - **The networking packets** verify with JUnit and game tests against a fake `PlotDirectory`. Their harness checks that need an owned plot run once MP-03's registry is merged (merge MP-03 first among them), otherwise in MP-I.
  - Runtime meeting points go through MP-F contracts and are proven together in MP-I.
- **Wave 2** is MP-I: end-to-end on the harness, a leak scan of relayed bytes and remote screenshots, and a full singleplayer QA compare against MP-F's baseline.
- **Wave 3** is MP-Z: docs, the shipped config, the owner UAT, and the colo deploy (owner-confirmed).
- **Maximum useful parallelism** is 4, then 14, then 1, then 1. The **critical path** is four packets deep: MP-F → the longest Wave-1 packet → MP-I → MP-Z.

## The cast

The agents are defined in `.claude/agents/`:

| Role | Agents |
|---|---|
| Writers | `fabric-mod-dev`, `foreman-node-dev` |
| Advisors | `minecraft-netcode-advisor`, `studio-world-advisor`, `agent-presence-advisor` |
| Adversarial reviewers | `server-authority-enforcer`, `privacy-redaction-auditor`, `agent-sandbox-guardian`, `testing-validation-engineer`, `visual-qa-judge` |
| Operations and stewardship | `colo-release-operator`, `upstream-sync-steward`, `documentation-writer` |

Who reviews which packet is listed in [work-packets.md § Dispatch rules](work-packets.md#dispatch-rules).

## Telemetry

Every multiplayer event is structured (`event=<name> key=value`) on the `agentcraft.mp` logger, with player, studio, plot and revision correlators and stable refusal reasons. No Foreman free text is ever logged. The catalog and its guard test are in [work-packets.md § Telemetry contract](work-packets.md#telemetry-contract).
