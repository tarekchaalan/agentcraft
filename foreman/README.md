# AgentCraft Foreman

The Foreman is the brain of AgentCraft: a Node 22 + TypeScript service that runs a team of Claude
agents (one lead, up to five workers) in a project folder (a git repo, or any folder) and streams everything to the Minecraft
mod over a WebSocket. The game is only a view. The Foreman owns all state, keeps working while
Minecraft is closed, and survives restarts.

```
                     ws://127.0.0.1:7878  (protocol v1, docs/protocol.md)
 Minecraft mod  <------------------------------------------------>  Foreman
 (or npm run tui)     hello -> snapshot, then upserts                  |
                      goal.submit / decision.answer / ...              |
                                                                       v
   Foreman (src/foreman.ts) -- owns state, applies user intents, exposes primitives to backends
     |- TaskGraph     src/taskgraph.ts   tasks, deps, statuses, assignment, goal progress
     |- MessageBus    src/bus.ts         agent<->agent, agent<->user, activity feed
     |- Memory        src/memory.ts      markdown notes, shared + per agent
     |- DecisionQueue src/decisions.ts   question | permission | merge; answer wakes the agent
     |- RepoManager   src/repos.ts       repos, per-task worktrees, structured diffs, guarded merges
     |- Notifier      src/notifier.ts    desktop notification + console bell when you are needed
     |- Store         src/store.ts       atomic JSON state + JSONL logs under AGENTCRAFT_HOME
     `- Backend       claude: src/agents/claude/  (Claude Agent SDK sessions)
                      sim:    src/agents/sim/     (deterministic scripted team, real git)
```

## Run it

```sh
cd foreman
npm install

# real agents: needs ANTHROPIC_API_KEY (or CLAUDE_CODE_USE_BEDROCK / _VERTEX / _FOUNDRY)
npm run start -- --backend claude --repo C:\path\to\your\repo   # a git repo, or any existing folder, even an empty one
# personal use only: your local `claude` CLI login instead of an API key
npm run start -- --backend claude --repo C:\path\to\your\repo --use-claude-login
# Codex agents on your Codex login (`codex login`), or a mixed team
npm run start -- --backend codex --repo C:\path\to\your\repo
npm run start -- --backend claude --worker-engine codex --repo C:\path\to\your\repo

# simulated team on a fresh sandbox repo (no API calls) - for demos and screenshot QA
npm run start -- --backend sim --reset --speed 2

# static "showcase" states for screenshots (fast-forward through the real script, then hold)
npm run start -- --backend sim --profile showcase --reset --showcase            # busy mid-run
npm run start -- --backend sim --profile showcase-late --reset --showcase late  # blocked/error/done

# terminal client that connects exactly like the mod
npm run tui
```

In the TUI (and in the mod's console) type:

| input | does |
| --- | --- |
| `Add OAuth to life-tracker` | new goal for the lead |
| `@kit please also cover emoji tags` | message an agent (`@all` for everyone) |
| `/answer` / `/answer d3 2 more tests please` | answer the oldest / a specific decision (1-based option, optional text) |
| `/diff kit-t2` or `/diff d3` | show a worktree's diff (or a merge decision's) |
| `/repo add C:\path\to\repo` | connect a repo |
| `/pause @kit`, `/resume @kit`, `/stop @kit`, `/spawn @tove` | steer agents (see Steering) |
| `/model`, `/model kit`, `/model juniper sonnet high`, `/model foreman opus`, `/model kit default` | show or change an agent's model (see Per-agent models) |
| `/task t3 cancel\|retry\|prioritize [n]\|reassign @wren` | steer tasks |
| `/status`, `/tasks`, `/agents`, `/decisions`, `/memory [id]`, `/feed` | views |

`npm run tui -- --script scripts/sim-demo.script --transcript out.txt` replays a whole session
unattended (`/wait d3`, `/wait goal done`, `/wait 2` are available in scripts);
`--commands <file>` tails a file for commands; `--auto-answer merge,permission` answers for you.

Stop the Foreman with Ctrl+C (or `q` + Enter). State is saved continuously; a hard kill loses at
most ~100 ms of state, and interrupted agent turns resume on the next start.

## Options

`npm run start -- --help` prints everything. The important ones:

| flag / env | default | |
| --- | --- | --- |
| `--backend sim\|claude\|codex` / `AGENTCRAFT_BACKEND` | `claude` | codex: an all-Codex team |
| `--lead-engine` / `--worker-engine` `claude\|codex` | the backend's | mixed teams, e.g. a Claude lead with Codex workers |
| `--engines kit=codex,...` / `AGENTCRAFT_ENGINES` | | engine per agent |
| `--codex-model`, `--codex-lead-model`, `--codex-worker-model`, `--codex-effort` | your Codex config | Codex models and reasoning effort |
| `--codex-path` / `AGENTCRAFT_CODEX_PATH` | `codex` on PATH, else the Codex app's | the Codex CLI |
| `--port` / `AGENTCRAFT_PORT` | `7878` | WebSocket port (127.0.0.1 only) |
| `--home` / `AGENTCRAFT_HOME` | `~/.agentcraft` | state root |
| `--use-claude-login` / `AGENTCRAFT_USE_CLAUDE_LOGIN=1` / config `claude.useClaudeLogin` | off | personal use only: your `claude` CLI login instead of an API key. Any `ANTHROPIC_API_KEY`, `ANTHROPIC_AUTH_TOKEN`, `ANTHROPIC_BASE_URL` or `CLAUDE_CODE_USE_*` switch in the shell is withheld from the agents; `foreman.status.authMode` reads `claude login` |
| `--user-name` / `AGENTCRAFT_USER_NAME` / config `userName` | OS user name | how the agents address you; sent to the mod in `foreman.status` |
| `--profile` | backend name | state lives in `<home>/<profile>` |
| `--repo <path>[,<path>]` | | work in these existing folders at start: git repos with commits, or any other folder; repeatable (sim: a fresh `sandbox/sim-demo`) |
| `--goal "<text>"` | | submit a goal right away |
| `--reset` | | wipe this profile first |
| `--notify` / `--no-notify` / `AGENTCRAFT_NOTIFY` | on for claude, off for sim | Windows, macOS or Linux (`notify-send`) notifications |
| `--toast-silent` | | toast without sound |
| `--model`, `--lead-model`, `--worker-model` | lead `opus`, workers `sonnet` | any model id/alias the CLI accepts |
| config `claude.agentModels` / `codex.agentModels` | none | one agent's own model and effort, beating the lead's / workers' model and effort (and those flags) for that agent; the console's `/model` writes it (see Per-agent models) |
| `--effort low..max` | `medium` | |
| `--lead-fallback-model` / `AGENTCRAFT_LEAD_FALLBACK_MODEL` / config `claude.leadFallback.model` | none | the lead's model while a usage limit of its own model is in force, e.g. a Fable lead at the Fable limit (see Lead fallback); `--no-lead-fallback-model` turns off one set in config.json |
| `--lead-fallback-effort` / `AGENTCRAFT_LEAD_FALLBACK_EFFORT` / config `claude.leadFallback.effort` | the lead's effort | its effort, `low..max` |
| `--workers 3` or `--workers kit,wren` | `juniper,kit,wren` | team (others stay "off shift") |
| `--max-concurrent` | `3` | workers running at once |
| `--throttle-concurrent` | `1` | workers at once while your plan (claude.ai login) reports a usage warning; at an account-wide limit nobody starts a turn until it resets, at a model's own limit only the agents on that model wait, and interrupted turns resume then (see Usage limits and Lead fallback) |
| `--max-turns`, `--max-budget <usd>` | 40 lead / 80 worker, none | per turn caps |
| `--ci "<cmd>"` | detected (`npm test`, `cargo test`, ...) | run after each task |
| `--lead-read-commands "<cmd>,..."` | none | read commands the lead runs without asking, by prefix: `"bd show,gh issue view"` lets it read your issue tracker |
| `--no-lead-review` | | merge decisions go to you without a lead review turn |
| `--no-usage` / config `claude.usagePoll: false` | on | no `foreman.status.usage` polling (with `--use-claude-login` each poll runs a `claude` process: every minute while Claude agents work, every 5 min idle) |
| `--repo-poll-ms` | `10000` | how often checkouts are checked for head/dirty changes |
| `--merge-style merge\|squash` / `AGENTCRAFT_MERGE_STYLE` | `merge` | approved merges: a merge commit that keeps the agents' commits, or one squashed commit (see Safety guarantees) |
| `--no-sign-merges` / `AGENTCRAFT_SIGN_MERGES=0` | signed if your git config signs (claude) | never sign approved merge commits; the sim never signs |
| sim: `--speed`, `--seed`, `--autostart`, `--showcase [late]`, `--auto-answer`, `--no-ambient` | | |

`<home>/config.json` can hold the same settings (`{"backend":"claude","claude":{"workers":["kit","wren"]}}`).

### Usage limits (Claude login)

With your Claude login the agents share your plan's limits. A turn the plan refuses is not failed:
its task stays where it is, and the same job resumes its session once the limit resets. Who waits
depends on which limit it is:

- An account-wide limit (the 5-hour session, the weekly limit, usage credits, or a limit that names no
  model) holds everyone: nobody starts a turn until it resets.
- A model's own weekly limit holds only the agents whose next turn runs that model; everyone else
  keeps working. The rate-limit type of the refusal says which (`seven_day_overage_included` is the
  Fable limit, `seven_day_opus` and `seven_day_sonnet` those models'), else the CLI's "You've hit
  your Fable limit" / "You've reached your Fable limit" message. An agent's model is its own
  (`claude.agentModels`, `/model`), else the lead's or workers' configured model (the lead's fallback
  while it runs it). A Fable limit holds a Fable lead
  and Fable workers, never the workers on Opus; a worker on a model that is limited gets no new task
  meanwhile.
- The usage poll sees a model's limit coming: a model's weekly window (`weekly:fable`) at 100% with a
  reset time holds that model's agents before any turn is refused, and lifts again when a later poll
  shows room in it.
- A limit without a reset time lasts until that model's weekly window resets in the usage poll, else a
  wait from 5 minutes doubling to an hour.
- Shown: a feed line ("Fable limit reached (seven day overage included). Kit (on Fable) starts no new
  turn until 14:05; the others keep working." and "Fable limit reset: Kit resumes"), the waiting
  agents' activity ("Fable limit - resumes 14:05"), the banner (`foreman.status.message`: "Fable
  limit reached (...): Kit waits until 14:05, then resumes; the others keep working") and the log.
  An account-wide limit says "Usage limit reached (five hour): agents wait until 14:05".
- The limits are saved (`backend.claude.limit` for the account's, `backend.claude.modelLimits.<model>`
  for a model's own) and hold after a restart until they reset. A model's own limit that a Foreman
  from before per-model holds saved as the account's `limit` (it held everyone) is moved to that
  model at start, and logged.
- A usage warning only throttles: `--throttle-concurrent` workers at once until that window resets.

### Lead fallback (`claude.leadFallback`)

Some models have a weekly limit of their own besides the plan's 5-hour and weekly limits (the CLI
reports it as e.g. the "Fable limit"). By default a lead on that model waits for it like any agent on
it (see Usage limits). With a fallback, a lead whose own model reaches its own limit switches models
instead:

```json
{ "claude": {
    "leadModel": "claude-fable-5-1", "leadEffort": "high",
    "leadFallback": { "model": "claude-opus-5-5", "effort": "xhigh" } } }
```

- The lead switches whenever its own model's limit is in force, however it is learned: its own refused
  turn (that turn continues at once on the fallback model and effort, the same job, its session
  resumed), a worker on the same model refused by it, the usage poll showing that model's weekly
  window full (before any turn of the lead is refused), or the state saved before a restart (its
  first turn after the start runs the fallback). Workers keep their own model and limits.
- An account-wide limit holds the lead (and everyone) as before: another model would not get past
  it. So does a limit the fallback model reaches too; it holds the agents on the fallback's model.
- Back: when the limit resets (or a usage poll shows room again in a window only the poll had
  reported), the lead's next turn runs its own model and effort again.
- Shown: a feed line ("Marlow switched to Opus 5.5 (xhigh): Fable limit reached, back at 14:05", and
  "Marlow is back on Fable 5.1 (high): ..."), the lead's nameplate model, the banner
  (`foreman.status.message`: "lead Opus 5.5 while Fable is limited") and the Foreman log.
- The state is saved: after a restart the lead stays on the fallback until the reset, unless the
  config no longer names a fallback for that lead model (it then waits for the limit on its own
  model). Claude lead only (a Codex lead has none).

### Per-agent models (`claude.agentModels`, `codex.agentModels`)

One agent can run its own model (and effort) instead of the lead's or workers':

```json
{ "claude": {
    "leadModel": "opus", "workerModel": "sonnet",
    "agentModels": {
      "marlow": { "model": "fable", "effort": "high" },
      "juniper": { "model": "haiku" } } },
  "codex": { "agentModels": { "kit": { "model": "gpt-5.1-codex", "effort": "high" } } } }
```

- The key is the agent's id (`marlow`, `juniper`, `kit`, `wren`, `rowan`, `tove`). The entry beats
  `leadModel` / `workerModel` (and `--model`, `--lead-model`, `--worker-model`) for that agent; without
  an `effort` it keeps its role's (`effort`, `leadEffort`). An agent's entry is read from the section of
  the engine that runs it: `claude` for Claude agents, `codex` for Codex agents.
- Claude agents take an alias (`opus`, `sonnet`, `haiku`, `fable`: the CLI resolves it to the current
  model of that family), a Claude model id (`claude-sonnet-5`, a Bedrock or Vertex id) or a Bedrock ARN,
  and efforts `low`..`max`; Codex agents take any non-Claude model name and efforts `none`, `minimal`,
  `low`, `medium`, `high`, `xhigh`. Anything else (an unknown agent, `sonet`, a Claude model under
  `codex`) stops the start with the reason.
- The console's `/model <agent> <model> [effort]` (`agent.model`, see docs/console.md) sets it while the
  Foreman runs and saves it here; `/model <agent> default` removes it. The agent's next turn runs it (a
  running turn finishes on its model); its nameplate, the banner and the feed ("Juniper switched to
  Sonnet (high, set by Alex)") follow at once. config.json is rewritten atomically (a temp file, then a
  rename) with every other key kept in its order and two-space JSON; only these entries change, so
  nothing from the flags or the environment is ever written. A config.json that is not valid JSON is
  left alone: the change then applies until the Foreman restarts, and the console says it was not saved.
- Usage limits follow the agent's model (see Usage limits): moved onto a model whose own limit is in
  force, it waits for that limit; moved off one, it runs at once. A turn already refused by its old
  model's limit continues right away on the new one. The lead's fallback stands in for the lead's own
  model, its entry here included: a lead moved onto a limited model runs `claude.leadFallback`
  meanwhile; moved off it, the fallback ends.
- `/model` on its own lists every agent's model, effort and where it comes from (`default`, `config`,
  or `override` = this entry).

### Permissions and subagents (`claude.permissions`, `claude.subagents`)

```json
{ "claude": {
    "permissions": {
      "mode": "auto",
      "allow": ["Bash(codex exec:*)"], "deny": [], "ask": [],
      "webTools": true,
      "protectCheckouts": true },
    "subagents": { "enabled": true, "agents": ["gate-verifier", "~/agents/reviewer.md"] } } }
```

- `mode: "policy"` (default): every tool call goes through AgentCraft's policy; what it cannot verify
  as safe asks you in-world.
- `mode: "auto"`: Claude Code's auto mode. A classifier decides what the policy would have asked
  about. A PreToolUse hook keeps the guardrails that apply whatever the classifier, your rules or a
  subagent do: everything the policy denies (git push, git safety settings, the lead editing),
  git internals (`.git`, `GIT_DIR`, `--git-dir`), and with `protectCheckouts` writes into your
  checkouts of registered repos or AgentCraft's own state; those still ask you. Classifier denials
  show on the agent's monitor.
- `allow` / `deny` / `ask`: Claude Code permission rules, in both modes, after the guardrails. An
  allow rule skips AgentCraft's policy for what it matches.
- `webTools`: WebFetch and WebSearch (asked per host in policy mode).
- `subagents`: agents may start Claude Code subagents (built-ins such as Explore, plus the agent files
  listed: names under `~/.claude/agents` or paths). Their tool calls go through the same policy and
  guardrails; they never get a worktree of their own. A `permissionMode` in an agent file is ignored.

### Agent context (`claude.context`)

Claude agents run with Claude Code's own settings off (`settingSources: []`, `strictMcpConfig: true`):
settings-file allow rules, repo hooks and a repo's `.mcp.json` would run ahead of, or outside, the
permission policy. What they do get is set here, and only here (the Claude engine adds it; Codex
agents in a mixed team are not affected):

```json
{ "claude": { "context": {
    "repoInstructions": true,
    "userInstructions": false,
    "files": ["~/.codex/AGENTS.md", "my-rules.md"],
    "maxChars": 24000,
    "skills": ["roadmap-keeper", "~/somewhere/my-skill"],
    "mcpServers": {
      "xcode": { "command": "xcodebuildmcp", "args": ["mcp"] },
      "engram": { "command": "engram", "args": ["mcp", "--tools=agent"] } },
    "mcpAllow": ["mcp__xcode__list_sims", "mcp__engram__*"] } } }
```

| key | default | |
| --- | --- | --- |
| `repoInstructions` | `true` | the repository's `CLAUDE.md`, `.claude/CLAUDE.md`, `AGENTS.md` and `.claude/rules/*.md` (by name), read from **your checkout** (the base of every worktree), never a worker's worktree: an agent cannot change what the next agent is told. In folder mode, your folder |
| `userInstructions` | `false` | your `~/.claude/CLAUDE.md` (written for interactive sessions, so opt-in) |
| `files` | `[]` | more instruction files: absolute, `~/...`, or relative to the AgentCraft home (where `config.json` is). A missing file is reported once at start |
| `maxChars` | `24000` | cap on the instructions block; past it the block is cut and the Foreman log says so |
| `skills` | `[]` | names under `~/.claude/skills` or paths. Copied into `<profile>/agent-plugin` with `allowed-tools` removed from `SKILL.md` (it would pre-approve tools); only these can be loaded with the Skill tool, and everything a skill then does goes through the normal permission checks |
| `mcpServers` | `{}` | stdio servers `{command, args?, env?}` for every agent. Names: lowercase letters, digits and `-`, not `agentcraft*` (the team tools server). A bad entry, or any other server type, stops the Foreman at start with the reason. A server named `engram` gets `--project <repo folder name>` unless its args set one, so a worktree's memories land in the repository's project |
| `mcpAllow` | `[]` | MCP tools that run without asking: full names `mcp__<server>__<tool>`, or `mcp__<server>__*` for all of a server's tools, of a server in `mcpServers`. Every other MCP tool asks |

Instructions are one block appended after the role prompt, which wins where they conflict. Each file
appears once: `@path` imports are expanded in place (as Claude Code does), and a file already
included, by an import or twice in the list, is not repeated. Headings name repository files by their
repo-relative path with `/` on every platform (`## Repository instructions (.claude/rules/style.md)`).
The skills and `mcpAllow` reach the permission policy and, in auto mode, the guardrail hook alike.
`claude.repoContext`, `claude.instructionsFile`, `claude.mcpServers` and `claude.mcpAllow` at the top
level are refused with a pointer to their place here.

While running, `<home>/<profile>/foreman.json` records `{pid, port, host, backend, profile, version, startedAt,
root, commit}` (`root`: the checkout it runs from; `commit`: its `git rev-parse HEAD` at start, absent without
git; the mod's Foreman launcher compares both to tell an older Foreman, mod/DEV.md "Foreman launcher")
so launch scripts can find it; `<home>/foreman.json` holds the same for the first live Foreman (when
it exits, another live profile takes its place). A second Foreman on a profile that is already
running is refused (two would both write its `state.json`).

## Engines: Claude and Codex

The team (`src/agents/team.ts`: task graph, worktrees, scheduling, CI, reviews, merges, steering,
restarts) runs each agent turn through an engine, chosen per agent:

- **Claude** (`src/agents/claude/engine.ts`): one Claude Agent SDK `query()` per turn; the team tools
  are an in-process MCP server; every tool call goes through `canUseTool` and the policy.
- **Codex** (`src/agents/codex/`): one `codex app-server` process per turn, JSON-RPC over stdio. The
  agent's thread is durable, so its next turn resumes it. Commands run with `approvalPolicy:
  "untrusted"`, so Codex asks before each one, and the ask goes through the same policy and in-game
  permission prompts (commands are unwrapped from Codex's `powershell -Command` / `bash -lc` wrapper
  first). Workers are sandboxed to their worktree, its git dir and the temp dir; the lead is
  read-only. The team tools are app-server dynamic tools. None of your own Codex setup reaches an
  agent thread: your MCP servers, plugins, apps, web search, image generation and notify hooks are
  switched off for it (your Codex app is untouched). Codex's own sub-agent tools cannot be switched
  off; agents are told not to use them, and every approval from a thread AgentCraft did not start is
  declined. The git safety environment is passed explicitly (`shell_environment_policy.set`), since
  Codex drops variables named like secrets (`GIT_CONFIG_KEY_0`).

  On Windows, the app-server uses `<home>/<profile>/codex-localappdata` as its own local app-data
  directory. This avoids a Codex sandbox setup bug that tries to repair permissions on locked
  desktop runtime executables. Agent shell commands retain the original `LOCALAPPDATA`; the
  sandbox, approval policy, login and saved Codex threads are unchanged.

  A worker's sandbox can write exactly what committing and merging on its own branch needs: the
  shared `objects/`, its own branches' refs and reflogs (`refs/heads/agentcraft/<agent>/`), and its
  worktree's own git dir (Codex protects the `.git` pointer's target unless it is granted exactly),
  validated against the registered repository before every turn. Never the shared `.git` as a
  whole: its `config` and `hooks` would let a worker run code in your own git, outside any sandbox,
  and its refs would let it move your branches. On Windows,
  worktrees run with the older grant may retain Codex deny ACLs; recreating the worktree after
  saving its changes clears those stale permissions. Do not reset permissions on the whole repo.
  Windows agents are also told to use `npm.cmd` / `npx.cmd`: their PowerShell launchers can fail
  on sandbox-inaccessible global installs or script execution policy, while the CMD launchers work.

The rest of this section describes the team with either engine ("Read/Grep/Glob" is how a Codex
agent reads with its shell).

## How the claude backend works

1. **Plan** (lead, read-only in your checkout): explores with Read/Grep/Glob, writes `Plan: ...` to
   shared memory, creates tasks with deps and assignees via `create_task`, may `ask_user`.
2. **Work** (worker, in its own worktree `<profile>/worktrees/<repo>/<agent>-<task>` on branch
   `agentcraft/<agent>/<task>-<slug>`): edits, runs tests, coordinates with `send_message`, finishes
   with `update_task(status "review")`. A worker that ends without it is nudged once.
3. **CI**: the repo's test command runs in the worktree; a failure goes back to the worker once.
4. **Review** (lead): gets the diff + CI result (and the task's history: who worked on it, what you
   already answered), then `request_merge` or asks for changes.
5. **Merge decision** (you): Merge / Request changes / Reject. Only an answered **Merge** merges.
   If the base moved on and the merge would conflict, the worker merges the base into its branch,
   resolves it, and the task comes back for review. That is what lets the lead plan tasks that
   touch the same files (a new CLI case, a help line) to run in parallel instead of in a chain.

A task that changed no files (a report, an investigation) has nothing to merge: `request_merge`
closes it as done (worktree abandoned, branch kept) instead of asking you to approve an empty
merge, and **Merge** on such a branch does the same. A goal whose tasks were all cancelled or
rejected becomes `cancelled` (it is active again if the lead adds a task to it).

Agent tools (in-process MCP server `agentcraft`): `send_message`, `ask_user` (blocks until you
answer), `write_memory`, `read_memory`, `update_task`, `report_status`, `list_tasks`, and for the
lead `create_task`, `request_merge`. Unread messages ride along on every tool result and on the
prompt of the agent's next turn. A message from you that arrives after an agent's last tool call
(e.g. while it writes its final summary) starts a follow-up turn as soon as that turn ends; one
sent to an off-shift agent is delivered when you `/resume` it.

The CLI process of every agent turn is spawned by the Foreman (the SDK's
`spawnClaudeCodeProcess`), so the Foreman knows its pid: an aborted turn (stop, pause, cancel,
timeout, Foreman shutdown) is closed, and its CLI and every process it started are killed if they
are still there a few seconds later (a process tree snapshot taken at abort time, plus a second
look after the CLI exited, also finds orphans the CLI left behind; a pid is only killed if its
creation time still matches, and never if the CLI's pid was reused). The next turn of that agent
(e.g. after `/pause` + `/resume`) waits for this clean-up, so two CLIs never share a session.

SDK stream -> world: Read/Grep/Glob -> `reading@library`, Edit/Write -> `editing@desk`, test
commands -> `testing@testbench`, other Bash -> `running@terminal`, `ask_user` -> `waiting_user@user`.

Sessions are persisted per (agent, task) and per (lead, goal). On restart, interrupted turns resume
with their session id **and their job kind** (a resumed plan still activates the goal; a resumed
review still ends in a merge decision); a question that was open across the restart resumes the
session with your answer. Start-up then reconciles every open state: a goal still planning with
nobody planning it is planned again (or activated if it has tasks), a task left `doing` with no
turn behind it goes back on the board (its session resumes), a task in `review` without a merge
decision gets CI + review again. SDK sessions are isolated from your own Claude Code settings
(`settingSources: []`).

A failed turn (API error, max turns, timeout) shows the agent as `error` and blocks its task with
the reason; `/task t3 retry` puts it back on the board.

### Steering

| | |
| --- | --- |
| `/pause @kit` | aborts Kit's turn, keeps the task; any open question of Kit's is withdrawn. `/resume @kit` continues the same session. |
| `/stop @kit` | Kit goes off shift (lounge, `active: false`): turn aborted, open questions and permission prompts withdrawn, `doing` tasks back on the board unassigned. Never scheduled again until `/resume @kit` or `/spawn @kit`; survives Foreman restarts. |
| `/spawn @wren [t3]` | brings an off-shift agent onto the team; with a task id, that task is assigned to it. |
| hand-off | when a task changes hands (stop, `/task t3 reassign @wren`) it is held off the board until the old turn is really over (CLI exited, its processes gone), then the old worktree's work is committed on its branch and the next worker's worktree starts **from that branch**, so nothing is lost. The next worker's prompt says it takes over (and from whom) and lists the questions you already answered on that task, so it does not ask again. This also works if the old directory is still busy: removing it is retried in the background and never blocks the hand-off. |
| stop is immediate | the aborted turn's CLI process is force-closed; anything a lingering session still tries (tool calls, permission requests, messages) is refused. |
| self-healing scheduler | if starting a task fails (a git error, a busy directory), the task stays on the board and the scheduler retries with backoff (2 s .. 60 s) instead of waiting for the next event. |

### Permissions (src/policy.ts)

| | |
| --- | --- |
| allowed | reads/edits inside the agent's worktree; safe dev commands (`npm test`, `node src/x.ts`, `git status/diff/add/commit/merge`, `ls`, `grep`, ...); `npx <tool>` for dev tools the worktree has installed (`node_modules/.bin`); scratch files in the OS temp dir |
| asks you (permission decision) | anything outside the worktree (absolute paths, `..`, `~`, `$HOME`, `%USERPROFILE%`, brace expansion like `{~,x}`, `cd` out of the worktree, redirections like `>C:/x`, paths from `$VARS` or `$(...)`, links that lead out, Glob patterns like `../../x/*`), network (`curl`, `npm install`/`view`/`outdated`, `npx` of a tool that is not installed, WebFetch, `git fetch`), dev servers (`vite`, `webpack serve`), git commands that change the repo shared with your checkout (`git config` writes, `git branch -f/-D/<new>`, `git tag`, `git stash`, `git update-ref`, `git checkout <branch>`, `git rebase <upstream> <other-branch>`, `--update-refs`, `--ignore-other-worktrees`, `git submodule update`, `filter-branch`), git pointed at another repository (`GIT_DIR`, `GIT_WORK_TREE`, `GIT_INDEX_FILE`, `GIT_COMMON_DIR`, ... in any form: prefix, `export`, `env`, `read`, `for`; `--git-dir`; git run after `cd`/`-C` out of the worktree), writing, moving or deleting a `.git` entry inside the worktree (its link to the repository), destructive commands (`rm -r`, `find -delete`, `git reset --hard`, `git clean`, `xargs rm`), env vars that make commands run code (`GIT_PAGER=...`, `NODE_OPTIONS=...`, `git -c core.pager=...`), inline code that writes/spawns/uses the network (`node -e`, `python -c`), process/system commands (`taskkill`, `reg`, `sudo`), GUIs and the browser (`git citool`, `git gui`, `git <sub> --help` on Windows), unknown binaries |
| always denied | `git push` however it is spelled, wrapped or hidden, when the policy can see it: `env`/`xargs`/`timeout`/`sudo`/`bash -c`/`cmd /c`/`eval`, inside `$(...)`, backticks or `<(...)`, `find -exec`, commands git runs for us (`rebase -x`, `bisect run`, `submodule foreach`, `filter-branch --tree-filter`, `difftool -x`), `echo "git push" \| bash`, here-docs/strings fed to a shell, `node -e "...execSync('git push')"`, a git subcommand from a variable or substitution (`git $x`), `git -c alias.p=push`, `git lfs push`, `git subtree push`; signing with your key (`git commit -S`, `git tag -s`, `-c commit.gpgsign=true`); changing or clearing the git safety variables (`env -i`, `GIT_CEILING_DIRECTORIES`); file edits by the lead; subagents. Where the policy cannot see a push (a test script, a node script), git itself refuses it (below). |

The Bash classifier is a small shell parser (quotes, redirections, heredocs and here-strings,
brace expansion, a virtual `cd`, wrapper commands, nested shells, `eval`). Every command
substitution (`$(...)`, backticks, `<(...)`, also inside double quotes, unquoted heredocs and
`$((...))`) is classified as a command of its own, and its output counts as unknown wherever a
path matters (except `$(pwd)`, `$(git rev-parse --show-toplevel)` and lists of worktree paths like
`$(git ls-files)`). `find` is checked per start point and per `-exec` command; `xargs` may read
from a list of worktree paths (`find`, `git ls-files`, `grep -l`), anything else it runs asks.
Commands that git runs for us (`git rebase -x/--exec`, `git bisect run`, `git submodule foreach`,
`git filter-branch --*-filter`, `git difftool -x`, `-c alias.x='!cmd'`) are classified exactly like
the same command typed directly. Anything it cannot verify asks. The lead works in your own
checkout, so it may only run read-only commands without asking (a redirection like `git log > x`
or `git diff --output=x` is a write). Programs the policy does not know, like an issue tracker's
CLI, ask every time unless listed in `--lead-read-commands`. The list is matched by bare program
name and applies to the lead only: a worker could put its own `bd` in front of the real one. Entries must be a bare program name plus plain words; writers, interpreters and network tools (`rm`, `git`, `bash`, `node`, `curl`, ...) are refused at startup.

"Always allow for this agent" stores every rule key the call needed, and each key is scoped so it
never covers more than the prompt said (the prompt shows the scope: `"Always allow for this
agent" covers: ...`):

| key | covers |
| --- | --- |
| `Bash:rm -r`, `Bash:find -delete`, `Bash:npm install`, `Bash:git reset --hard`, `Bash:git rebase`, ... | that action inside the worktree; paths outside, and commands git would run for it (`rebase -x`), still ask with their own keys |
| `Bash:git checkout:<target>` | switching the worktree to exactly that branch/commit |
| `Bash:outside:<cmd>:r\|w\|x:<dir>` | `<cmd>` reading / writing / running files directly in `<dir>` |
| `Bash:outside:<cmd>:rtree\|wtree:<path>` | a recursive read / change of exactly `<path>` (`rm -r`, `grep -r`, `find`, `mv` of a directory) |
| `Bash:net:curl:<hosts>`, `Bash:net:gh:pr view` | that tool to those hosts / that subcommand |
| `Bash:exact:<hash>` | only that exact command: anything whose arguments cannot be checked (xargs writes, `$VARS`, substitutions, unknown programs, process/system commands, inline code, shell scripts on stdin) |
| `Read:<dir>`, `Grep:tree:<path>`, `Write:<dir>`, `Write:.git:<file>`, `WebFetch:<host>` | the non-Bash tools |

`git push` is never allowed, whatever is stored. Keys from older versions of the Foreman
(`Bash:subst`, `Bash:find -exec`, `Bash:xargs rm`, `lead:<prefix>`, `Bash:git fetch`,
`Bash:git submodule`, `Bash:git lfs`, `Bash:git checkout`, `Write:.git`, ...) no longer match anything.

### Push, signing and other repositories are blocked at the git level too (src/gitsafety.ts)

The policy can only judge what it can see; a push could hide in a test script or a node script.
So every process an agent can influence (the agent's Claude CLI process, every command it runs,
and the Foreman's CI runs) gets:

- `GIT_ALLOW_PROTOCOL=agentcraft-none` plus env-scoped git config `protocol.allow=never` and an
  empty `url.<dead>.pushInsteadOf`: git refuses every transport, so push, send-pack, fetch and
  clone all fail, even with an explicit `pushurl`.
- `commit.gpgsign=false`, `tag.gpgsign=false` and a missing `gpg.program`/`gpg.ssh.program`:
  agents' commits are never signed with your key, and an explicit `-S` fails instead of signing.
- `GIT_CEILING_DIRECTORIES=<parent of the agent's cwd>`: git never walks up out of the worktree,
  so a worktree whose `.git` link was deleted stops working instead of reaching an enclosing
  repository. Inherited `GIT_DIR`/`GIT_WORK_TREE`/`GIT_INDEX_FILE`/... are removed.
- The agent's own git identity (`AgentCraft Kit <kit@agentcraft.local>`), never yours.

Env-scoped config outranks the repo's `.git/config` and your global config. Local git (status,
commit, branch, merge) is unaffected, and the Foreman's own git calls do not use this environment.
This is not a sandbox: code an agent runs could drop the variables on purpose (a script that
spawns git with an empty environment); the policy refuses every command it can see doing that
(`unset GIT_...`, `env -i`, `GIT_ALLOW_PROTOCOL=...`).

## Safety guarantees (src/repos.ts)

- Never pushes; there is no code path that runs `git push` (and agents' git cannot push, above).
- Your checked-out branch's working tree is touched only by an approved merge. The merge commit is
  built off-tree (`merge-tree` + `commit-tree`) and applied with `merge --ff-only`.
- The Foreman's own git writes in a worktree (committing leftover work at merge, abandon and
  hand-off) first check that the worktree's `.git` link still leads to this repository's own entry
  for that directory. If an agent rewrote or deleted it, nothing is committed, the diff is not
  shown, and an abandoned worktree is left in place for you to look at. They only ever move the
  agent's own `agentcraft/...` branch: if the agent checked out another branch, its work is
  committed as a snapshot onto its own branch and the other branch is not touched.
- The approved merge commit is yours: it uses your git identity (`user.name`/`user.email` as your
  git sees it in that repo) and is signed when your git config has `commit.gpgsign=true` (with your
  `gpg.format`/`user.signingkey`; `commit-tree` does not do that by itself). If signing fails, the
  merge is refused with the reason and the decision re-opens; nothing changes. `--no-sign-merges`
  turns signing off; the sim never signs. The agents' own commits on their branches (theirs
  and the Foreman's) use `AgentCraft <Name> <name@agentcraft.local>` and are never signed.
  With `--merge-style squash`, main gets one commit with the task's changes (your identity,
  signed as above, `Co-authored-by` the agents) instead of a merge commit plus the agents'
  commits - useful for repos that require signed commits or verified emails. Branches are kept.
- `/repo add <path>` takes any existing folder (see "Folder mode" below). A git repository root with
  at least one commit is used as it is. Anything else is a plain folder; in particular a folder inside
  another repository never registers (or touches) the enclosing repository.
- Merges are refused (and the decision re-opens with the reason) if the checkout that has the base
  branch checked out has uncommitted tracked changes. A merge that would conflict is not made either:
  with the claude backend the task goes back to its worker (`git merge <base>` in its worktree,
  resolve, test, commit), then through CI and review to a fresh merge decision; other backends
  re-open the decision with the conflicting files.
- If you have another branch checked out, a merge only moves the base branch ref.
- Agent branches are kept after merge/reject; merged worktree diffs stay viewable.
- `repo.dirty` follows your checkout: it is re-broadcast after a refused merge and polled every
  10 s (`--repo-poll-ms`), so the mod can show why a merge was refused.
- CI runs with the git safety env and a timeout that kills the whole process tree.
- The WebSocket only takes local, non-browser clients: any `Origin` header (every browser sends
  one; sandboxed iframes, `data:` and `file:` pages send `Origin: null`) and any Host header other
  than `127.0.0.1`/`localhost`/`[::1]` (DNS rebinding) are refused with HTTP 401. The mod and the
  CLI tools send no Origin. `--allow-browser-origins` turns this off for development.

## State layout

```
<AGENTCRAFT_HOME>/<profile>/
  state.json             agents, tasks, decisions, repos, goals, feed, messages, sessions (atomic writes)
  foreman.json           pid/port of the Foreman running this profile
  launcher.json          what the mod's Foreman launcher started here (pid, start time, checkout; mod/DEV.md)
  logs/<agent>.jsonl     agent logs (snapshots carry the last 60 lines per agent); rotated at 8 MB
                         into <agent>.1.jsonl (one old file kept); start-up reads only their ends
  memory/shared/*.md     shared notes (hand-editable)
  memory/agents/<id>/*.md
  worktrees/<repo>/<agent>-<task>/
```

## Protocol

`src/protocol.ts` (zod) is the source of truth; `docs/protocol.md` is generated from it with field
tables and a JSON example per message (`npm run gen:protocol-doc`; `npm run check:protocol-doc`
fails if it is stale). Highlights beyond the spec draft: `foreman.status` (backend/auth banner, with
`authMode`: `claude login`, `API key`, a cloud provider name or the Codex equivalents, and
`usage`: the spend mode plus, under the claude.ai login, the plan's rate-limit windows),
`ack`/`error` replies for messages with an `id`, `snapshot.logs`/`snapshot.goals`,
`Agent.active/paused/worktree/title`, task status `cancelled`, decision status `cancelled`.

## The sim backend

A deterministic 22-beat script (`src/agents/sim/scenario.ts`) on the pocket-notes demo repo: the
lead plans 9 tasks, five workers read, edit, test and message each other, CI fails and gets fixed,
a permission prompt, a product question, a blocked task (npm publish needs you) with a blocked
agent, an agent error that recovers (API overload), a cancelled stretch task, five merge decisions,
and a last question that decides how the goal ends (close the publish task -> goal done at 100%,
or keep it -> the goal stays open). Every agent state and station appears. Every edit, test run
and merge is real (real worktrees, real `npm test`, real merge commits), so the diffs you review
are genuine. Your answers change the outcome (Deny vs Allow, which `notes tags` behaviour gets
built, Request changes makes the worker revise). Progress is persisted per beat; a restarted
Foreman resumes the script. `/stop @agent` takes an agent off shift; the script waits for it until
`/resume`.

Static states for screenshot QA:

| | agents |
| --- | --- |
| `--showcase` | Marlow waiting_user@user, Juniper editing@desk, Kit testing@testbench, Wren idle@lounge, Rowan reading@library, Tove thinking@desk; open merge + question decisions |
| `--showcase late` | Marlow thinking@meeting, Juniper running@terminal, Kit done@lounge, Wren blocked@desk (t7), Rowan error@library, Tove editing@desk |

The repo id is the demo dir name: `sim-demo-showcase` / `sim-demo-showcase-late` for those profiles.

## Tests

```sh
npm test            # vitest: protocol, task graph, persistence, decisions, repos/merges, policy,
                    #         git push block, WS + full sim run, showcase states, claude
                    #         orchestration, restart recovery and steering (fake SDK)
npx tsc --noEmit
npm run check       # all of the above + protocol doc freshness
```

## Folder mode (no git needed)

`/repo add <path>` and `--repo` work on any existing folder, as when you start Claude Code in a new
directory and ask for a small program:

| The folder is | Mode | What happens |
|---|---|---|
| a git repository with at least one commit | `git` | agents branch from its current branch; an approved merge is a commit on it (everything above) |
| empty, or has files but no git | `folder` | no `.git` is ever created in it; an approved merge writes the files into it |
| a git repository with no commits yet | `folder` | your `.git` is not touched: no commit, branch or object is made; merged files appear as untracked files for you to commit |
| inside another git repository | `folder` | that repository is not touched; merged files appear as changes in it |

In folder mode the Foreman keeps a private git repository for the folder at
`<profile>/shadow/<id>.git`, with the folder as its work tree (`GIT_DIR` + `GIT_WORK_TREE` on each
call; nothing is written into any git config). Its `main` branch holds snapshots of the folder: one
before agents branch off and one right before a merge, so agents start from what is in the folder
now, and files you edited in the meantime are part of the base (the merge is three-way; your edits are
not overwritten). If you and an agent both add the same new file, that is a normal merge conflict:
the task goes back to its worker. Everything else (worktrees, diffs, review, CI, approval) is the same
as in git mode. `repo.dirty` is always false in folder mode, so a merge is never refused for your own edits.

Limits: `node_modules/`, `.venv/`, `venv/`, `__pycache__/`, `*.pyc`, `.DS_Store` and `Thumbs.db` are
never snapshotted, and a `.gitignore` in the folder is honoured. Your home directory, a drive root and
a folder with more than 20000 files (not counting those) are refused. The `git` program is still
required: the Foreman uses it internally.

## Troubleshooting

- **`port 7878 is already in use`**: another Foreman is running (`~/.agentcraft/foreman.json` and `~/.agentcraft/<profile>/foreman.json` have its pid) - or use `--port`.
- **`profile "claude" is in use by the Foreman pid N`**: that profile already has a running Foreman; stop it or use `--profile`.
- **`... is your home directory or a drive root`** / **`holds more than 20000 files`**: folder mode snapshots the whole folder, so `/repo add` the project's own folder, not a parent of many projects.
- **Banner says auth failed**: the parentheses show the auth mode that was tried (`API key`, a cloud provider or `claude login`). Set `ANTHROPIC_API_KEY` (or a cloud provider switch) and restart the Foreman. With `--use-claude-login`: run `tools\launch.ps1 -Login` / `node tools/launch-unix.mjs login` (or `claude` and `/login`); the launchers check the login before starting a Foreman and restart one of theirs that failed its check. The sim backend works without auth. Why the claude.ai login is opt-in: Anthropic does not allow third-party tools to offer it ([Agent SDK overview](https://code.claude.com/docs/en/agent-sdk/overview)); see `src/agents/claude/auth.ts`.
- **Merge refused: uncommitted changes**: commit or stash in your checkout, then choose Merge again (the decision re-opened).
- **Reset the demo repo**: `node sandbox/create-demo.mjs --force`.
