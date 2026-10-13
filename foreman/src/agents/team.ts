// The agent team: a lead and workers doing real work, each turn run by an engine (engine.ts):
// the Claude Agent SDK or the Codex app-server, chosen per agent (a team can mix them).
//
// Each agent processes a queue of jobs, one engine turn per job:
//   plan    lead explores the repo (read-only), writes the plan, creates tasks
//   work    worker implements a task in its own git worktree
//   review  lead reviews a finished task (diff + CI) -> request_merge or changes
//   followup resume an agent's session with new input (user message, answer, feedback)
// Session ids are persisted per (agent, task|goal). A job interrupted by a Foreman restart is
// resumed with the same session AND the same kind, so what happens after the turn (a planning
// goal becomes active, a finished task goes to CI + review) is never lost. start() reconciles
// every non-terminal state (planning goals, doing tasks, review tasks) with what is running.
//
// Steering: pause = abort the turn, keep the task, resume later; stop = off shift: abort the
// turn, withdraw the agent's open questions, hand its tasks back to the board, never scheduled
// again until resume/spawn (persisted across restarts).
//
// Hand-off: a task that changes hands (stop, reassign) is held off the board until the old turn
// is really over - its CLI process has exited and every process it started is gone (they keep
// the worktree busy on Windows and could still write to it) - and the old worktree's work is
// committed on its branch. The next worker's worktree then starts from that branch.
import type { ChildProcess } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import type { EffortLevel } from '@anthropic-ai/claude-agent-sdk';
import type { AgentModel, AgentModelChange, ClaudeConfig, LeadFallback } from '../config.js';
import { claudeModelFor, FOREMAN_VERSION, saveAgentModels } from '../config.js';
import { ClientError, type AgentModelResult, type Backend, type Foreman, type ModelChange } from '../foreman.js';
import { withGitSafety } from '../gitsafety.js';
import { agentGitIdentity } from '../util/git.js';
import { classifyToolUse, describeRuleKey, describeToolCall, type PolicyContext } from '../policy.js';
import type { AgentModelInfo, BackendName, Decision, Goal, Task, Usage } from '../protocol.js';
import { MERGE_OPTIONS, PERMISSION_OPTIONS } from '../protocol.js';
import type { TestResult } from '../repos.js';
import { renderDiffText } from '../diff.js';
import { formatInbox } from '../bus.js';
import { descendantsOf, killSnapshot, killTree, orphansOf, processTable, type ProcEntry } from '../util/proc.js';
import { truncate } from '../util/text.js';
import { leadSystemPrompt, planPrompt, RESUME_PROMPT, reviewPrompt, workerSystemPrompt, workPrompt } from './prompts.js';
import { fetchPulls, githubOrigin, prRefs, pullBriefs, type PullRequest } from '../pulls.js';
import type { AuthCheck, Engine, EngineId, PermissionGate, RateLimitReport, Role, TurnSpec, TurnStats } from './engine.js';
import { limitFamilyFromText, limitFromText, limitModelFamily, modelFamily, usageWindowFamily } from './limits.js';
import { agentTools, type ToolHooks, type TurnHandle } from './tools.js';
import { effortsFor, modelLabel, modelProblem, normalizeModel } from './models.js';
import { UsagePoller } from './usage.js';
import { userName } from '../user.js';

// policy: our own team tools are trusted (the Claude engine exposes them as this MCP server)
const TEAM_MCP_SERVER = 'agentcraft';

type JobKind = 'plan' | 'work' | 'review' | 'followup';
type AbortReason = 'pause' | 'stop' | 'shutdown' | 'cancel' | 'timeout';

interface Job {
  kind: JobKind;
  agentId: string;
  prompt: string;
  sessionKey: string;
  taskId?: string;
  goalId?: string;
  /** start a new session even if one exists for the key */
  fresh?: boolean;
  /** nudges already sent for this task (worker ended without update_task) */
  nudges?: number;
  /** continuing an interrupted job (restart, pause): log the prompt */
  resumed?: boolean;
}

interface Inflight {
  kind: JobKind;
  sessionKey: string;
  taskId?: string;
  goalId?: string;
  startedAt: number;
}

interface ClaudeState {
  inflight: Record<string, Inflight>;
  ciFixes: Record<string, number>;
  /** agents the user stopped (off shift until resume/spawn) */
  stopped: string[];
  /**
   * an account-wide usage limit was hit (the plan's 5-hour, weekly or credit limit, or one that names
   * no model): no turn starts until `until` (epoch ms). A model's own limit is never kept here (see
   * modelLimits; loadLimits moves one that an older Foreman saved here).
   */
  limit?: { until: number; type?: string };
  /**
   * a model family's own usage limit, by family ("fable": the Fable weekly limit, rate-limit type
   * seven_day_overage_included; "opus", "sonnet"): only the agents whose next turn runs that family
   * wait until it resets (a lead on it with claude.leadFallback runs the fallback instead); everyone
   * else keeps working.
   */
  modelLimits?: Record<string, ModelLimit>;
  /** the plan warned about usage: fewer workers at once until `until` */
  throttle?: { until: number; type?: string };
  /**
   * the lead's own model (`model`: claude.agentModels.marlow, else claude.leadModel) hit a usage limit of that model's family
   * (`family`, e.g. "fable"): the lead runs claude.leadFallback until `until` (that limit's own,
   * modelLimits[family]), then its own model again; nobody else waits. `guessed`: the limit did not
   * say when it resets (a usage poll may).
   */
  leadFallback?: { until: number; model: string; family: string; type?: string; guessed?: boolean };
}

/** A model family's own usage limit (ClaudeState.modelLimits). */
interface ModelLimit {
  /** epoch ms */
  until: number;
  /** the rate-limit type it was reported with (none when only the usage poll saw it) */
  type?: string;
  /** no reset time was given: `until` is a wait that doubles per hit (a usage poll may give the real one) */
  guessed?: boolean;
  /** only the usage poll saw it (the model's weekly window full), no refused turn: a later poll that shows room lifts it */
  fromUsage?: boolean;
}

/** Which turn a usage limit came from (the lead's fallback applies only to the lead on its own model). */
interface TurnOrigin {
  agentId: string;
  /** the turn ran the lead's fallback */
  fallback: boolean;
  /** the model the turn was started with (configured, or the lead's fallback) */
  model?: string;
  /** the model the turn reported (e.g. "claude-fable-5-1"), if it did */
  reported?: string;
}

/** The model a turn runs; `override`: the lead's fallback model and effort, which the engine runs instead of its own. */
interface TurnModel {
  model: string;
  override?: NonNullable<TurnSpec['modelOverride']>;
}

interface Running {
  abort: AbortController;
  job: Job;
  reason?: AbortReason;
  /** the agent's CLI process (the engine spawns it and reports it, so we know its pid) */
  child?: ChildProcess;
  /** settles when runJob is completely done with this turn */
  done?: Promise<void>;
  /** when we spawned the CLI (epoch ms) */
  spawnedAt?: number;
  /** snapshot of the CLI's process tree taken when the turn was aborted (undefined: unreadable) */
  tree?: Promise<ProcEntry[] | undefined>;
  /** the (single) clean-up of this turn's processes, once started */
  reaping?: Promise<void>;
  /** the turn runs the lead's fallback model */
  fallback?: boolean;
  /** the agent's model epoch when the turn started (TeamBackend.modelEpochs): /model changed its model during the turn when they differ */
  modelEpoch?: number;
}

const sleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms));

/** "14:05" today, "Tue 14:05" on another day. */
function clock(ms: number): string {
  const d = new Date(ms);
  const hm = d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  return d.toDateString() === new Date().toDateString() ? hm : `${d.toLocaleDateString([], { weekday: 'short' })} ${hm}`;
}

const capital = (s: string) => s.charAt(0).toUpperCase() + s.slice(1);

/** " (seven day overage included)" for a rate-limit type, "" for none */
const typeText = (type: string | undefined) => (type ? ` (${type.replace(/_/g, ' ')})` : '');

function alive(child: ChildProcess | undefined): child is ChildProcess {
  return !!child && child.exitCode === null && child.signalCode === null;
}

const TURN_TIMEOUT_MS = 45 * 60_000;
/** wait after a usage limit that did not say when it resets (doubles per hit, up to LIMIT_BACKOFF_MAX_MS) */
const LIMIT_BACKOFF_MS = 5 * 60_000;
const LIMIT_BACKOFF_MAX_MS = 60 * 60_000;
/** throttle length when a usage warning has no reset time */
const THROTTLE_DEFAULT_MS = 30 * 60_000;
const LEAD = 'marlow';

/**
 * Environment for an agent's CLI process (and every command it runs): git refuses all
 * transports (no push, ever) and never signs; git does not walk up out of the agent's cwd; the
 * agent's commits carry its own placeholder identity ("AgentCraft Kit <kit@agentcraft.local>"),
 * never the user's; and each Bash call starts in the agent's own cwd, so a `cd` in one command
 * cannot carry the next one out of the worktree.
 */
export function agentEnv(base: NodeJS.ProcessEnv = process.env, who: { agentId?: string; cwd?: string } = {}): Record<string, string | undefined> {
  return withGitSafety(
    base,
    {
      CLAUDE_AGENT_SDK_CLIENT_APP: `agentcraft-foreman/${FOREMAN_VERSION}`,
      CLAUDE_BASH_MAINTAIN_PROJECT_WORKING_DIR: '1',
      ...(who.agentId ? (agentGitIdentity(who.agentId) as Record<string, string>) : {}),
    },
    who.cwd ? { ceiling: path.dirname(path.resolve(who.cwd)) } : {},
  );
}

/** Which engine runs which agent: the lead's, the workers', and per-agent exceptions. */
export interface TeamEngines {
  lead: Engine;
  worker: Engine;
  byAgent?: Record<string, Engine>;
}

export interface TeamOptions {
  /** the backend name reported to clients ("claude" or "codex": the workers' engine) */
  name: BackendName;
  engines: TeamEngines;
  /** skip the startup auth probe (tests) */
  skipAuthCheck?: boolean;
  /** injectable for tests: pull request intake (default: git + gh, see pulls.ts) */
  pullFetcher?: PullFetcher;
}

export interface PullFetcher {
  origin(repoPath: string): Promise<string | undefined>;
  fetch(repoPath: string, numbers: number[]): Promise<{ pulls: PullRequest[]; errors: string[] }>;
}

const defaultPullFetcher: PullFetcher = { origin: (p) => githubOrigin(p), fetch: (p, n) => fetchPulls(p, n) };

export class TeamBackend implements Backend {
  readonly name: BackendName;
  private queues = new Map<string, Job[]>();
  private running = new Map<string, Running>();
  private pausedJobs = new Map<string, Job>();
  private tickTimer: NodeJS.Timeout | undefined;
  private authFailed = false;
  private stopping = false;
  private waitingUser = new Set<string>();
  private hooks: ToolHooks;
  private lastCost = new Map<string, number>();
  private turnPromises = new Set<Promise<void>>();
  /** tasks whose CI + review hand-off is in progress */
  private reviewing = new Set<string>();
  /** the most recent turn per agent (kept after it ends, for quiesce) */
  private lastTurn = new Map<string, Running>();
  /** tasks changing hands: off the board until the old turn is over and its work committed */
  private handoffs = new Map<string, Promise<void>>();
  /** scheduler retry after an error (backoff) */
  private retryTimer: NodeJS.Timeout | undefined;
  private retryDelayMs = 2000;
  /** fires when a usage limit / throttle window ends */
  private limitTimer: NodeJS.Timeout | undefined;
  private limitBackoffMs = LIMIT_BACKOFF_MS;
  /** per model family: the wait after a limit of its own that did not say when it resets (doubles per hit, reset when a turn on it succeeds) */
  private familyBackoffMs = new Map<string, number>();
  /** the model the lead's own (not fallback) turns reported, for a lead model that names no family ("default") */
  private leadOwnReported: string | undefined;
  /** the last rate-limit status each plan window reported (a change refreshes the usage display) */
  private rateLimitStatus = new Map<string, RateLimitReport['status']>();
  private readonly pullFetcher: PullFetcher;
  /** the model each agent's last turn really ran (shown on its nameplate) */
  private reportedModels = new Map<string, { engine: EngineId; label: string }>();
  /** keeps foreman.status.usage fresh, for an engine that reports plan usage (see usage.ts) */
  private usagePoller: UsagePoller | undefined;
  /** bumped per agent when /model changes its model or effort (a turn started before shows the new one when it ends) */
  private modelEpochs = new Map<string, number>();

  constructor(
    protected fm: Foreman,
    protected cfg: ClaudeConfig,
    private opts: TeamOptions,
  ) {
    this.name = opts.name;
    this.pullFetcher = opts.pullFetcher ?? defaultPullFetcher;
    this.hooks = {
      onReview: () => {
        /* handled after the worker's turn ends (CI then review) */
      },
      onChangesRequested: (taskId, feedback) => this.sendBackToWorker(taskId, `Marlow reviewed your work on ${taskId} and asks for changes:\n${feedback}\n\nMake the changes, re-run the tests, then update_task("${taskId}", status "review", summary).`),
      onTasksChanged: () => this.tick(),
      onMergeRequested: (taskId) => this.fm.log.info(`merge decision opened for ${taskId}`),
      onWaiting: (agentId, waiting) => {
        if (waiting) this.waitingUser.add(agentId);
        else this.waitingUser.delete(agentId);
      },
    };
  }

  private get st(): ClaudeState {
    const b = this.fm.store.data.backend;
    let s = b.claude as ClaudeState | undefined;
    if (!s) {
      s = { inflight: {}, ciFixes: {}, stopped: [] };
      b.claude = s;
    }
    s.inflight ??= {};
    s.ciFixes ??= {};
    s.stopped ??= [];
    return s;
  }

  get team(): string[] {
    return this.cfg.workers.filter((w) => this.fm.agent(w));
  }

  /** The engine that runs an agent's turns. */
  engineFor(agentId: string): Engine {
    const e = this.opts.engines;
    return e.byAgent?.[agentId] ?? (agentId === LEAD ? e.lead : e.worker);
  }

  /**
   * Every agent's nameplate shows its engine and model: the configured model (the lead's fallback
   * while it runs it) until a turn reports the real one (onModel). Off-shift agents too: it is what
   * they would run when spawned.
   */
  private showEngines(): void {
    for (const a of this.fm.agents()) {
      const engine = this.engineFor(a.id);
      const reported = this.reportedModels.get(a.id);
      const model = (reported?.engine === engine.id ? reported.label : undefined) ?? modelLabel(this.turnModel(a.id, a.id === LEAD ? 'lead' : 'worker', engine).model) ?? engine.label;
      this.fm.setAgent(a.id, { engine: engine.id, model });
    }
  }

  /** Every engine on the team (each is checked at start). */
  private enginesInUse(): Engine[] {
    return [...new Set([LEAD, ...this.team].map((id) => this.engineFor(id)))];
  }

  /**
   * e.g. "Claude (lead opus, workers sonnet)" or "Claude lead opus · Codex workers gpt-5"; on the
   * lead's fallback "Claude (lead Opus 5.5 while Fable is limited, workers sonnet)"
   */
  private teamLabel(): string {
    const lead = this.engineFor(LEAD);
    const workers = [...new Set(this.team.map((w) => this.engineFor(w)))];
    // aliases as configured ("opus"), full model ids as display names ("claude-opus-5-5" -> "Opus 5.5")
    const name = (model: string) => (/^[a-z]+$/.test(model) ? model : (modelLabel(model) ?? model));
    const fb = this.onFallback() ? this.st.leadFallback : undefined;
    const leadModel = fb ? `${name(this.turnModel(LEAD, 'lead', lead).model)} while ${capital(fb.family)} is limited` : name(lead.model('lead', LEAD));
    // the workers' model on an engine: one for all of them, else each one's (/model gave some their own)
    const m = (e: Engine) => {
      const on = this.team.filter((w) => this.engineFor(w) === e);
      const models = on.map((w) => name(e.model('worker', w)));
      return new Set(models).size > 1 ? on.map((w, i) => `${this.fm.nameOf(w)} ${models[i]}`).join(', ') : (models[0] ?? name(e.model('worker')));
    };
    if (!workers.length || (workers.length === 1 && workers[0] === lead)) return `${lead.label} (lead ${leadModel}, workers ${m(lead)})`;
    if (workers.length === 1) return `${lead.label} lead ${leadModel} · ${workers[0]!.label} workers ${m(workers[0]!)}`;
    return `${lead.label} lead ${leadModel} · ${this.team.map((w) => `${this.fm.nameOf(w)} ${this.engineFor(w).label}`).join(', ')}`;
  }

  private isStopped(agentId: string): boolean {
    return this.st.stopped.includes(agentId);
  }

  private setStopped(agentId: string, stopped: boolean): void {
    const s = this.st;
    s.stopped = s.stopped.filter((x) => x !== agentId);
    if (stopped) s.stopped.push(agentId);
    this.fm.store.markDirty();
  }

  // ---- lifecycle ----------------------------------------------------------------------------

  async start(): Promise<void> {
    for (const a of this.fm.agents()) {
      const onTeam = (a.id === LEAD || this.team.includes(a.id)) && !this.isStopped(a.id);
      this.fm.setAgent(a.id, { active: onTeam });
      if (!onTeam) this.fm.setAgent(a.id, { state: 'idle', station: 'lounge', activity: this.isStopped(a.id) ? 'stopped - off shift' : 'off shift' });
      else if (a.activity === 'off shift' || a.activity.startsWith('stopped')) this.fm.setAgent(a.id, { activity: 'ready' });
    }
    // the spend survives restarts: every session's cost is persisted, so the total is their sum
    const spent = Object.values(this.fm.store.data.sessions).reduce((sum, s) => sum + (s.costUsd || 0), 0);
    if (spent > 0) this.fm.setStatus({ costUsd: Math.round(spent * 1000) / 1000 });
    this.startLeadFallback();
    // before anything is queued: a model's limit saved before the restart holds only that model's agents
    this.loadLimits();
    this.showEngines();
    const authed = await this.checkAuth();
    this.showEngines(); // the auth check may have learned the configured model
    if (authed) this.startUsagePoller();
    if (!this.cfg.resumeOnStart) {
      this.st.inflight = {};
    } else {
      this.recover();
    }
    this.armLimitTimer();
    // the user's messages that no agent read before the restart
    for (const id of [LEAD, ...this.team]) this.deliverPending(id);
    void this.fm.repos.sweepPendingRemovals().catch((e) => this.fm.log.debug(`sweep: ${(e as Error).message}`));
    this.tick();
  }

  async checkAuth(): Promise<boolean> {
    if (this.opts.skipAuthCheck) {
      this.fm.setStatus({ auth: 'ok', message: this.teamLabel() });
      this.showBanner();
      return true;
    }
    const engines = this.enginesInUse();
    // with several engines, each account / auth mode is prefixed with its engine's label
    const tag = (engine: Engine, s: string) => (engines.length > 1 ? `${engine.label}: ${s}` : s);
    this.fm.setStatus({ auth: 'checking', authMode: engines.map((e) => tag(e, e.authMode())).join(' · '), message: `Checking ${engines.map((e) => e.label).join(' and ')} access...` });
    const accounts: string[] = [];
    const modes: string[] = [];
    for (const engine of engines) {
      const r = await engine.checkAuth().catch((e: Error): AuthCheck => ({ ok: false, message: `${engine.label} check failed: ${e.message}`, mode: engine.authMode() }));
      if (!r.ok) {
        this.markAuthFailed(r.message, tag(engine, r.mode ?? engine.authMode()));
        return false;
      }
      accounts.push(tag(engine, r.account));
      modes.push(tag(engine, r.mode));
      this.fm.log.info(`${engine.id} auth ok via ${r.mode} (${r.account})`);
    }
    this.authFailed = false;
    this.fm.setStatus({ auth: 'ok', account: accounts.join(' · '), authMode: modes.join(' · '), message: this.teamLabel() });
    this.showBanner(); // who waits for a model's own limit, if anyone
    return true;
  }

  /**
   * Plan usage for the in-game display, from the engine that reports it (Claude; Codex and the sim
   * do not), unless --no-usage. One `usage` on the wire: a second such engine would need its window
   * labels prefixed with the engine's, like `account`.
   */
  private startUsagePoller(): void {
    if (!this.cfg.usagePoll || this.stopping) return;
    const engine = this.enginesInUse().find((e) => e.usage);
    if (!engine?.usage) return;
    this.usagePoller ??= new UsagePoller({
      poll: () => engine.usage!(),
      publish: (usage) => {
        this.fm.setStatus({ usage });
        this.applyUsage(usage);
      },
      busy: () => this.usageBusy(),
      log: this.fm.log,
    });
    this.usagePoller.start();
  }

  /** The busy cadence: a turn of an engine that reports plan usage runs (a Codex turn spends no Claude plan). */
  private usageBusy(): boolean {
    for (const id of this.running.keys()) if (this.engineFor(id).usage) return true;
    return false;
  }

  /** `authMode`: the auth mode that was attempted (the auth check); a failed turn keeps the current one */
  private markAuthFailed(message: string, authMode?: string): void {
    this.authFailed = true;
    this.fm.setStatus({ auth: 'failed', message, ...(authMode ? { authMode } : {}) });
    this.fm.log.error(message);
    this.fm.bus.feed('error', message);
    this.fm.notify('warn', message);
    if (process.stdout.isTTY) process.stdout.write('\x07');
  }

  private openQuestion(agentId: string): Decision | undefined {
    return this.fm.decisions.open().find((d) => d.kind === 'question' && d.agentId === agentId);
  }

  /** A job matching `pred` is queued, running or paused (waiting for /resume) for this agent. */
  private hasQueued(agentId: string, pred: (j: Job) => boolean): boolean {
    const running = this.running.get(agentId);
    const paused = this.pausedJobs.get(agentId);
    return (this.queues.get(agentId) ?? []).some(pred) || (running ? pred(running.job) : false) || (paused ? pred(paused) : false);
  }

  /**
   * After a restart: re-attach or re-queue everything that was in flight, then reconcile every
   * non-terminal state with what is actually running, so nothing waits forever.
   */
  private recover(): void {
    const st = this.st;
    // permission prompts from a dead process are moot; the resumed agent retries the tool
    for (const d of this.fm.decisions.open().filter((x) => x.kind === 'permission')) this.fm.decisions.cancel(d.id, 'Foreman restarted');
    for (const [agentId, inf] of Object.entries(st.inflight)) {
      // a turn for a task that is gone or cancelled has nothing to resume
      const task = inf.taskId ? this.fm.tasks.get(inf.taskId) : undefined;
      if (this.isStopped(agentId) || !this.fm.agent(agentId) || (inf.taskId && (!task || task.status === 'cancelled'))) {
        delete st.inflight[agentId];
        continue;
      }
      const openQ = this.openQuestion(agentId);
      if (openQ) {
        this.fm.log.info(`recover: ${agentId} is waiting on ${openQ.id}; will resume after the answer`);
        this.fm.setAgent(agentId, { state: 'waiting_user', station: 'user', activity: 'waiting for your answer' });
        continue;
      }
      const session = this.fm.store.data.sessions[inf.sessionKey];
      if (session?.sessionId) {
        this.fm.log.info(`recover: resuming ${agentId} (${inf.kind}${inf.taskId ? ` ${inf.taskId}` : ''})`);
        this.enqueue({ kind: inf.kind, agentId, sessionKey: inf.sessionKey, prompt: RESUME_PROMPT, resumed: true, ...(inf.taskId ? { taskId: inf.taskId } : {}), ...(inf.goalId ? { goalId: inf.goalId } : {}) });
      } else {
        // the turn died before it had a session: the reconciliation below starts it again
        delete st.inflight[agentId];
      }
    }
    this.reconcile();
    this.fm.store.markDirty();
  }

  /** Bring planning goals, doing tasks and review tasks back in line with running/queued jobs. */
  private reconcile(): void {
    const st = this.st;
    // goals still planning with nobody planning them
    for (const g of this.fm.goals().filter((x) => x.status === 'planning')) {
      const leadOnIt = st.inflight[LEAD]?.goalId === g.id || this.hasQueued(LEAD, (j) => j.goalId === g.id) || this.openQuestion(LEAD) !== undefined;
      if (leadOnIt || this.isStopped(LEAD)) continue;
      if (this.fm.tasks.forGoal(g.id).length) this.promoteGoal(g, 'recovered');
      else {
        const repo = g.repoId ? this.fm.repos.get(g.repoId) : undefined;
        if (!repo) continue;
        this.fm.log.info(`recover: re-planning ${g.id}`);
        this.enqueue({ kind: 'plan', agentId: LEAD, goalId: g.id, sessionKey: `${LEAD}:${g.id}`, fresh: !this.fm.store.data.sessions[`${LEAD}:${g.id}`]?.sessionId, prompt: planPrompt(this.fm, g, repo) });
      }
    }
    // doing tasks whose worker is not working on them: back on the board (the session resumes)
    for (const t of this.fm.tasks.list()) {
      if (t.status !== 'doing' || !t.assignee || t.assignee === LEAD) continue;
      const w = t.assignee;
      if (st.inflight[w]?.taskId === t.id || this.hasQueued(w, (j) => j.taskId === t.id)) continue;
      if (this.openQuestion(w)?.taskId === t.id) continue; // resumes with the answer
      this.fm.log.info(`recover: ${t.id} was doing without a running turn; re-queued`);
      this.fm.tasks.setStatus(t.id, 'todo', { force: true });
      if (this.isStopped(w)) this.fm.tasks.update(t.id, { assignee: null });
    }
    this.sweepReviews();
  }

  /** Tasks in review with no merge decision and no review job: CI + review (again). */
  private sweepReviews(): void {
    const st = this.st;
    for (const t of this.fm.tasks.list()) {
      if (t.status !== 'review' || !t.worktree) continue;
      if (this.fm.decisions.open().some((d) => d.taskId === t.id)) continue;
      if (Object.values(st.inflight).some((i) => i.taskId === t.id)) continue;
      if (this.reviewing.has(t.id) || this.hasQueued(LEAD, (j) => j.taskId === t.id) || (t.assignee && this.hasQueued(t.assignee, (j) => j.taskId === t.id))) continue;
      void this.afterWorkerDone(t.id);
    }
  }

  async stop(): Promise<void> {
    this.stopping = true;
    if (this.tickTimer) clearTimeout(this.tickTimer);
    if (this.limitTimer) clearTimeout(this.limitTimer);
    if (this.retryTimer) clearTimeout(this.retryTimer);
    this.usagePoller?.stop();
    const turns = [...this.running.values()];
    for (const r of turns) this.abortTurn(r, 'shutdown');
    await Promise.race([Promise.allSettled([...this.turnPromises]), sleep(4000)]);
    // nothing an agent started outlives the Foreman (sessions resume on the next start)
    await Promise.race([Promise.allSettled(turns.map((r) => this.reap(r, 1500))), sleep(3000)]);
    // inflight entries stay persisted so the next start resumes them
    this.fm.store.markDirty();
  }

  // ---- turn teardown ------------------------------------------------------------------------

  /** Abort a turn: remember why, snapshot its process tree while the CLI is still alive, close it. */
  private abortTurn(r: Running, reason: AbortReason): void {
    r.reason = reason;
    const pid = r.child?.pid;
    if (pid && alive(r.child) && !r.tree) r.tree = processTable().then((t) => (t ? descendantsOf(t, pid) : undefined)).catch(() => undefined);
    r.abort.abort();
  }

  /**
   * Make sure an aborted turn's CLI process and everything it started are gone. The SDK's close()
   * gives the CLI ~2 s to exit; after that its tree is killed. Processes that outlived the CLI
   * (orphans on Windows) come from the snapshot taken at abort time plus, read after the CLI
   * exited, every newer process whose parent chain leads to the CLI's pid (unless that pid was
   * reused). Only processes that are still the same ones (pid + creation time) are killed.
   */
  private reap(r: Running, graceMs = 4000): Promise<void> {
    r.reaping ??= this.doReap(r, graceMs).catch((e) => this.fm.log.warn(`clean-up of ${r.job.agentId}'s turn: ${(e as Error).message}`));
    return r.reaping;
  }

  private async doReap(r: Running, graceMs: number): Promise<void> {
    const child = r.child;
    if (!child?.pid) return;
    if (alive(child)) {
      await Promise.race([new Promise<void>((res) => child.once('exit', () => res())), sleep(graceMs)]);
      if (alive(child)) {
        killTree(child);
        await Promise.race([new Promise<void>((res) => child.once('exit', () => res())), sleep(2000)]);
      }
    }
    const snapshot = r.tree ? await r.tree : undefined;
    const table = await processTable();
    if (!table) {
      this.fm.log.warn(`could not read the process table to check for processes left by ${r.job.agentId}'s stopped turn`);
      return;
    }
    const targets = new Map<number, ProcEntry>();
    for (const e of [...(snapshot ?? []), ...orphansOf(table, child.pid, r.spawnedAt ?? 0)]) targets.set(e.pid, e);
    const killed = (await killSnapshot([...targets.values()], table)) ?? [];
    if (killed.length) this.fm.log.info(`killed ${killed.length} leftover process(es) of ${r.job.agentId}'s stopped turn (pids ${killed.join(', ')})`);
  }

  /** Wait until an agent's current/last turn is completely over (CLI exited, its processes gone). */
  private async quiesce(agentId: string, maxMs = 15_000): Promise<void> {
    const r = this.running.get(agentId) ?? this.lastTurn.get(agentId);
    if (!r) return;
    if (r.done) await Promise.race([r.done.catch(() => undefined), sleep(maxMs)]);
    await this.reap(r);
  }

  /**
   * A task leaves `fromAgent` (stop, reassign): hold it off the board until that agent's turn is
   * really over, then commit its work on its branch (the next worker starts from that branch).
   */
  private handOff(taskId: string, fromAgent: string, why: string): void {
    if (this.handoffs.has(taskId)) return;
    const p = (async () => {
      try {
        await this.quiesce(fromAgent);
        const t = this.fm.tasks.get(taskId);
        const wt = t?.worktree && t.repoId ? this.fm.repos.findWorktree(t.repoId, t.worktree) : undefined;
        if (t && wt && wt.agentId === fromAgent && wt.status === 'active') {
          await this.fm.repos.abandon(t.repoId!, wt.id, `agentcraft: ${t.id} work in progress (${why})`);
        }
      } catch (e) {
        this.fm.log.warn(`hand-off of ${taskId} from ${fromAgent}: ${(e as Error).message}`);
      } finally {
        this.handoffs.delete(taskId);
        this.tick();
      }
    })();
    this.handoffs.set(taskId, p);
  }

  // ---- usage limits -------------------------------------------------------------------------
  //
  // An account-wide limit (the plan's 5-hour, weekly or credit limit, or a limit that names no
  // model) holds everyone: st.limit. A model family's own limit (its weekly window: the Fable limit,
  // the Opus or Sonnet limit) holds only the agents whose next turn runs that family:
  // st.modelLimits[family]. A lead on that family with claude.leadFallback runs the fallback instead.

  /** An account-wide usage limit is in force: no turn starts (queued jobs wait, then resume). */
  private limited(now = Date.now()): boolean {
    return !!this.st.limit && now < this.st.limit.until;
  }

  /** A model family's own limit in force now, if any. */
  private modelLimit(family: string | undefined, now = Date.now()): ModelLimit | undefined {
    const l = family ? this.st.modelLimits?.[family] : undefined;
    return l && now < l.until ? l : undefined;
  }

  /**
   * The model family an agent's next turn runs ("fable", "opus"...): its model (the lead's fallback
   * while it runs it), else, for a model that names none ("default"), what its turns reported.
   * Undefined for a Codex agent: these are the Claude plan's limits.
   */
  private familyOf(agentId: string): string | undefined {
    const engine = this.engineFor(agentId);
    if (engine.id !== 'claude') return undefined;
    const family = modelFamily(this.turnModel(agentId, agentId === LEAD ? 'lead' : 'worker', engine).model);
    const reported = this.reportedModels.get(agentId);
    return family ?? (reported?.engine === engine.id ? modelFamily(reported.label) : undefined);
  }

  /** The usage limit an agent waits for before its next turn: the account's, or its model family's own (`family`). */
  private holdFor(agentId: string, now = Date.now()): { until: number; type?: string; family?: string } | undefined {
    if (this.limited(now)) return this.st.limit;
    const family = this.familyOf(agentId);
    const l = this.modelLimit(family, now);
    return l && family ? { until: l.until, ...(l.type ? { type: l.type } : {}), family } : undefined;
  }

  /** "usage limit - resumes 14:05", "Fable limit - resumes 14:05" */
  private holdActivity(hold: { until: number; family?: string } | undefined): string {
    return `${hold?.family ? `${capital(hold.family)} limit` : 'usage limit'} - resumes ${hold ? clock(hold.until) : 'later'}`;
  }

  /** The agents on shift. */
  private onShift(): string[] {
    return [LEAD, ...this.team].filter((id) => this.fm.agent(id)?.active && !this.isStopped(id));
  }

  /** The agents on shift whose next turn runs `family`: the ones its limit holds. */
  private onFamily(family: string): string[] {
    return this.onShift().filter((id) => this.familyOf(id) === family);
  }

  /** "Marlow", "Marlow and Kit", "Marlow, Kit and Wren" */
  private names(ids: string[]): string {
    const n = ids.map((id) => this.fm.nameOf(id));
    return n.length > 1 ? `${n.slice(0, -1).join(', ')} and ${n[n.length - 1]}` : (n[0] ?? '');
  }

  /** Workers allowed at once: fewer while the plan reports a usage warning. */
  private maxWorkers(now = Date.now()): number {
    const t = this.st.throttle;
    return t && now < t.until ? Math.min(this.cfg.maxConcurrent, this.cfg.throttleConcurrent) : this.cfg.maxConcurrent;
  }

  /** A live usage report from a running turn (`from`: whose turn, for the lead's fallback). */
  protected onRateLimit(r: RateLimitReport, from?: TurnOrigin): void {
    // foreman.status.usage has one source, the usage poller: when a window's limit status changes
    // (a warning, the limit itself, allowed again) it fetches fresh numbers now instead of at its
    // next tick. Only changes: these reports come with every turn, and each poll is a CLI process.
    const window = r.type ?? '?';
    const before = this.rateLimitStatus.get(window);
    if (before !== r.status) {
      this.rateLimitStatus.set(window, r.status);
      if (before !== undefined || r.status !== 'allowed') this.usagePoller?.trigger();
    }
    if (r.status === 'rejected') {
      // a model's own limit holds that model's agents (a lead on it with a fallback switches models;
      // the turn ends refused and runJob continues it on the fallback); any other holds everyone
      this.limitReached(r, undefined, from, false);
    } else if (r.status === 'allowed_warning') {
      const until = r.resetsAt ?? Date.now() + THROTTLE_DEFAULT_MS;
      const prev = this.st.throttle;
      if (prev && prev.until >= until) return;
      this.st.throttle = { until, ...(r.type ? { type: r.type } : {}) };
      this.fm.store.markDirty();
      if (this.maxWorkers() < this.cfg.maxConcurrent) {
        const pct = r.utilization !== undefined ? ` (${Math.round(r.utilization * 100)}%)` : '';
        this.fm.bus.feed('system', `Usage warning${r.type ? ` (${r.type.replace(/_/g, ' ')})` : ''}${pct}: ${this.maxWorkers()} worker(s) at a time until ${clock(until)}`);
      }
      this.armLimitTimer();
    }
  }

  /** Stop starting turns until the limit resets (or a backoff when it did not say when). */
  private setLimit(resetsAt: number | undefined, type: string | undefined): void {
    const until = resetsAt ?? Date.now() + this.limitBackoffMs;
    if (!resetsAt) this.limitBackoffMs = Math.min(LIMIT_BACKOFF_MAX_MS, this.limitBackoffMs * 2);
    const prev = this.st.limit;
    if (prev && prev.until >= until) return;
    this.st.limit = { until, ...(type ? { type } : {}) };
    this.fm.store.markDirty();
    const what = `Usage limit reached${type ? ` (${type.replace(/_/g, ' ')})` : ''}`;
    this.fm.setStatus({ message: `${what}: agents wait until ${clock(until)}, then resume` });
    if (!prev || Date.now() >= prev.until) {
      this.fm.bus.feed('error', `${what}. Nobody starts a new turn until ${clock(until)}; interrupted work resumes then.`);
      this.fm.notify('warn', `${what}: AgentCraft resumes at ${clock(until)}`);
    }
    this.armLimitTimer();
  }

  /**
   * A usage limit refused a turn: `report` as the engine reported it (live, or the turn's last one),
   * `text` the turn's error. A model's own limit holds the agents on that model (a lead on it with a
   * fallback switches models); anything else holds everyone. A typed report decides which limit it
   * is, the message only when there is none. `ended`: the turn is over (a live report of it may have
   * recorded this limit already).
   */
  private limitReached(report: RateLimitReport | undefined, text: string | undefined, from: TurnOrigin | undefined, ended: boolean): void {
    const rejected = report?.status === 'rejected' ? report : undefined;
    const family = rejected?.type ? limitModelFamily(rejected.type) : limitFamilyFromText(text ?? '');
    // the model the refused turn ran: as it reported it, else as it was started
    const ran = from ? (modelFamily(from.reported) ?? modelFamily(from.model)) : family;
    if (family && ran === family) {
      this.setModelLimit(family, { ...(rejected?.resetsAt ? { resetsAt: rejected.resetsAt } : {}), ...(rejected?.type ? { type: rejected.type } : {}) });
      return;
    }
    if (ended && this.limited()) return; // its live report held everyone already
    // a model's limit on a turn of another model (or of one that names none) says nothing about
    // which agents it holds: it holds everyone, like an account-wide limit
    if (family) this.fm.log.warn(`${capital(family)} limit reported on ${from?.agentId ?? 'a'} turn of ${from?.reported ?? from?.model ?? 'an unknown model'}: everyone waits for it`);
    this.setLimit(report?.resetsAt, report?.type);
  }

  /**
   * A model family's own limit is reached (a refused turn, or the usage poll shows its weekly window
   * full): the agents on that family wait until it resets, a lead on it with a fallback switches to
   * the fallback, everyone else keeps working. Without a reset time it lasts until the model's weekly
   * window resets in the usage poll, else a wait from 5 minutes doubling to an hour.
   */
  private setModelLimit(family: string, limit: { resetsAt?: number; type?: string; fromUsage?: boolean }): void {
    const now = Date.now();
    const prev = this.modelLimit(family, now);
    if (prev) {
      // in force already: a real reset time replaces a guessed one, otherwise it only gets longer; a
      // refused turn confirms a limit only the usage poll had seen (a later poll cannot lift it then)
      const longer = limit.resetsAt !== undefined && (!!prev.guessed || limit.resetsAt > prev.until);
      const confirmed = !!prev.fromUsage && !limit.fromUsage;
      if (!longer && !confirmed) return;
      if (confirmed) delete prev.fromUsage;
      if (limit.type) prev.type = limit.type;
      this.fm.store.markDirty();
      if (!longer) return;
      prev.until = limit.resetsAt!;
      delete prev.guessed;
      this.fm.log.info(`${capital(family)} limit lasts until ${new Date(prev.until).toISOString()}`);
      this.syncLeadFallback(family);
      this.showBanner();
      this.armLimitTimer();
      return;
    }
    const window = limit.resetsAt ? undefined : this.usageReset(family, now);
    const wait = this.familyBackoffMs.get(family) ?? LIMIT_BACKOFF_MS;
    const until = limit.resetsAt ?? window ?? now + wait;
    const guessed = !limit.resetsAt && !window;
    if (guessed) this.familyBackoffMs.set(family, Math.min(LIMIT_BACKOFF_MAX_MS, wait * 2));
    this.st.modelLimits = { ...this.st.modelLimits, [family]: { until, ...(limit.type ? { type: limit.type } : {}), ...(guessed ? { guessed: true } : {}), ...(limit.fromUsage ? { fromUsage: true } : {}) } };
    this.fm.store.markDirty();
    // the lead first: on its fallback it is not one of the agents that wait
    this.syncLeadFallback(family);
    const held = this.onFamily(family);
    const model = capital(family);
    const what = `${model} limit reached${limit.fromUsage ? ` (weekly ${model} usage at 100%)` : typeText(limit.type)}`;
    this.fm.log.warn(
      `${what}: ${held.length ? `${held.join(', ')} wait` : `no agent on shift runs ${model}`} until ${new Date(until).toISOString()}` +
        `${guessed ? ' (no reset time given)' : ''}; agents on other models keep working`,
    );
    if (held.length) {
      const others = this.onShift().length > held.length;
      this.fm.bus.feed('error', `${what}. ${this.names(held)} (on ${model}) start${held.length === 1 ? 's' : ''} no new turn until ${clock(until)}; ${others ? 'the others keep working' : 'interrupted work resumes then'}.`);
      this.fm.notify('warn', `${model} limit reached: ${this.names(held)} ${held.length === 1 ? 'waits' : 'wait'} until ${clock(until)}`);
    }
    this.showBanner();
    this.armLimitTimer();
  }

  /** A model's own limit is over (it reset, or the usage poll shows room): its agents resume, a lead on its fallback for it returns to its own model. */
  private liftModelLimit(family: string, why: string): void {
    if (!this.st.modelLimits?.[family]) return;
    // a lead that ran its fallback for this limit did not wait (at the reset onFallback() is over
    // already, so its next turn counts as this family's)
    const f = this.st.leadFallback;
    const leadSwitched = !!f && f.family === family && f.model === this.leadModel() && !!this.fallbackConfig();
    const held = this.onFamily(family).filter((id) => !(id === LEAD && leadSwitched));
    delete this.st.modelLimits[family];
    this.fm.store.markDirty();
    this.fm.log.info(`${capital(family)} limit over (${why})${held.length ? `: ${held.join(', ')} resume` : ''}`);
    if (held.length) this.fm.bus.feed('system', `${capital(family)} limit reset: ${this.names(held)} ${held.length === 1 ? 'resumes' : 'resume'}`);
    if (this.st.leadFallback?.family === family) this.endLeadFallback();
    this.showBanner();
    for (const id of this.queues.keys()) this.pump(id);
    this.tick();
  }

  /** Wake up when the earliest limit/throttle/lead-fallback window ends. */
  private armLimitTimer(): void {
    if (this.limitTimer) clearTimeout(this.limitTimer);
    this.limitTimer = undefined;
    const now = Date.now();
    const ends = [this.st.limit?.until, ...Object.values(this.st.modelLimits ?? {}).map((l) => l.until), this.st.throttle?.until, this.st.leadFallback?.until].filter(
      (t): t is number => typeof t === 'number' && t > now,
    );
    if (!ends.length) {
      this.liftExpired();
      return;
    }
    // setTimeout caps at ~24.8 days; re-arm in steps
    const delay = Math.min(Math.min(...ends) - now + 1000, 2 ** 31 - 1);
    this.limitTimer = setTimeout(() => {
      this.limitTimer = undefined;
      this.liftExpired();
      this.armLimitTimer();
    }, delay);
    this.limitTimer.unref?.();
  }

  private liftExpired(): void {
    const now = Date.now();
    let changed = false;
    if (this.st.limit && now >= this.st.limit.until) {
      delete this.st.limit;
      changed = true;
      this.showBanner(); // the team label, or who still waits for a model's own limit
      this.fm.bus.feed('system', 'Usage limit reset: the team picks up where it stopped');
      for (const id of this.queues.keys()) this.pump(id);
    }
    for (const [family, l] of Object.entries(this.st.modelLimits ?? {})) {
      if (now < l.until) continue;
      this.liftModelLimit(family, 'it reset');
      changed = true;
    }
    if (this.st.throttle && now >= this.st.throttle.until) {
      delete this.st.throttle;
      changed = true;
    }
    if (this.st.leadFallback && now >= this.st.leadFallback.until) {
      this.endLeadFallback();
      changed = true;
    }
    if (changed) {
      this.fm.store.markDirty();
      this.tick();
    }
  }

  // ---- the lead's fallback model (claude.leadFallback) ---------------------------------------

  /** claude.leadFallback, when it can apply: the lead runs on the Claude engine (the plan limits are Claude's). */
  private fallbackConfig(): LeadFallback | undefined {
    return this.cfg.leadFallback && this.engineFor(LEAD).id === 'claude' ? this.cfg.leadFallback : undefined;
  }

  /**
   * The lead's own model and effort on the Claude engine, which claude.leadFallback stands in for: its
   * own (claude.agentModels, /model), else claude.leadModel / leadEffort. (The fallback applies only to
   * a Claude lead, see fallbackConfig.)
   */
  private leadModel(): string {
    return claudeModelFor(this.cfg, LEAD, 'lead').model;
  }

  private leadEffort(): EffortLevel {
    return claudeModelFor(this.cfg, LEAD, 'lead').effort;
  }

  /** The lead runs its fallback now: a limit of its own model is in force (and the config still asks for it). */
  private onFallback(now = Date.now()): boolean {
    const f = this.st.leadFallback;
    return !!f && now < f.until && !!this.fallbackConfig() && f.model === this.leadModel();
  }

  /** The model an agent's next turn runs (its own, else its role's); for the lead on its fallback, the model and effort the engine runs instead. */
  private turnModel(agentId: string, role: Role, engine: Engine): TurnModel {
    const fb = agentId === LEAD ? this.fallbackConfig() : undefined;
    if (fb && this.onFallback()) return { model: fb.model, override: { model: fb.model, effort: fb.effort ?? this.leadEffort() } };
    return { model: engine.model(role, agentId) };
  }

  /** "Opus 5.5 (xhigh)" */
  private fallbackLabel(fb: LeadFallback): string {
    return `${modelLabel(fb.model) ?? fb.model} (${fb.effort ?? this.leadEffort()})`;
  }

  /**
   * The banner line: who waits for a model's own limit (and that the others keep working), else the
   * team label. A failed auth's message and an account-wide limit's (it holds everyone) stay.
   */
  private showBanner(): void {
    if (this.authFailed || this.limited() || this.fm.status.auth !== 'ok') return;
    const now = Date.now();
    const holds = Object.entries(this.st.modelLimits ?? {})
      .filter(([, l]) => now < l.until)
      .map(([family, l]) => ({ family, l, held: this.onFamily(family) }))
      .filter((x) => x.held.length);
    if (!holds.length) {
      this.fm.setStatus({ message: this.teamLabel() });
      return;
    }
    const waiting = new Set(holds.flatMap((x) => x.held));
    const others = this.onShift().some((id) => !waiting.has(id));
    const lines = holds.map(({ family, l, held }) => {
      const one = held.length === 1;
      return `${capital(family)} limit reached${typeText(l.type)}: ${this.names(held)} ${one ? 'waits' : 'wait'} until ${clock(l.until)}, then ${one ? 'resumes' : 'resume'}`;
    });
    this.fm.setStatus({ message: `${lines.join('; ')}${others ? '; the others keep working' : ''}` });
  }

  /** When the usage poller last saw a model family's weekly window ("weekly:fable") reset, if that is still ahead. */
  private usageReset(family: string, now = Date.now()): number | undefined {
    const w = this.fm.status.usage?.windows.find((x) => x.id === `weekly:${family}` || x.id.startsWith(`weekly:${family}-`));
    const at = w?.resetsAt ? Date.parse(w.resetsAt) : Number.NaN;
    return Number.isFinite(at) && at > now ? at : undefined;
  }

  /** The family of the lead's own model: as configured, else as its own turns reported it (an alias such as "default"). */
  private leadOwnFamily(): string | undefined {
    return modelFamily(this.leadModel()) ?? modelFamily(this.leadOwnReported);
  }

  /**
   * A limit of a model family's own is in force (modelLimits). When it is the lead's own model's
   * (from the lead's turn, a worker's turn, the usage poll or the state saved before a restart) and
   * claude.leadFallback names a model outside it, the lead runs the fallback for as long as the limit
   * lasts instead of waiting; on its fallback already, the fallback follows the limit's reset time.
   */
  private syncLeadFallback(family: string): void {
    const fb = this.fallbackConfig();
    const l = this.modelLimit(family);
    if (!fb || !l || family !== this.leadOwnFamily() || modelFamily(fb.model) === family) return;
    const prev = this.onFallback() ? this.st.leadFallback : undefined;
    if (prev) {
      if (prev.until === l.until && !!prev.guessed === !!l.guessed) return;
      prev.until = l.until;
      if (l.guessed) prev.guessed = true;
      else delete prev.guessed;
      this.fm.store.markDirty();
      this.fm.log.info(`${capital(family)} limit until ${new Date(l.until).toISOString()}: ${this.fm.nameOf(LEAD)} returns to ${prev.model} then`);
      this.showBanner();
      this.armLimitTimer();
      return;
    }
    const until = l.until;
    this.st.leadFallback = { until, model: this.leadModel(), family, ...(l.type ? { type: l.type } : {}), ...(l.guessed ? { guessed: true } : {}) };
    this.fm.store.markDirty();
    const what = `${capital(family)} limit reached`;
    this.fm.log.warn(
      `${what}: ${this.fm.nameOf(LEAD)} switches from ${this.leadModel()} to ${fb.model} (${fb.effort ?? this.leadEffort()}) until ${new Date(until).toISOString()}` +
        `${l.guessed ? ' (the limit gave no reset time; it tries its own model again then)' : ''}`,
    );
    this.fm.bus.feed('system', `${this.fm.nameOf(LEAD)} switched to ${this.fallbackLabel(fb)}: ${what}, back at ${clock(until)}`, { agentId: LEAD });
    // the nameplate shows the fallback now, then what its first turn reports; a turn still running
    // on the lead's own model keeps its nameplate until it ends (runJob shows the fallback then)
    const turn = this.running.get(LEAD);
    if (!turn || turn.fallback) {
      this.reportedModels.delete(LEAD);
      this.showEngines();
    }
    this.showBanner();
    this.armLimitTimer();
  }

  /** The lead's own model's limit has reset (or the config no longer asks for a fallback): its next turn runs it again. */
  private endLeadFallback(): void {
    const f = this.st.leadFallback;
    if (!f) return;
    delete this.st.leadFallback;
    this.fm.store.markDirty();
    if (f.model !== this.leadModel() || !this.fallbackConfig()) return;
    const own = `${modelLabel(this.leadModel()) ?? this.leadModel()} (${this.leadEffort()})`;
    this.fm.log.info(`${capital(f.family)} limit reset: ${this.fm.nameOf(LEAD)} is back on ${this.leadModel()} from its next turn`);
    this.fm.bus.feed('system', `${this.fm.nameOf(LEAD)} is back on ${own}: the ${capital(f.family)} limit reset`, { agentId: LEAD });
    // a fallback turn still running keeps its nameplate until it ends (runJob shows the own model then)
    if (!this.running.get(LEAD)?.fallback) {
      this.reportedModels.delete(LEAD);
      this.showEngines();
    }
    this.showBanner();
  }

  /** At start: a fallback persisted before a restart applies only if the config still asks for it, for the same lead model. */
  private startLeadFallback(): void {
    const fb = this.fallbackConfig();
    if (this.cfg.leadFallback && !fb) this.fm.log.debug('claude.leadFallback: the lead runs on Codex, so it has no fallback model');
    else if (fb && modelFamily(fb.model) && modelFamily(fb.model) === modelFamily(this.leadModel())) {
      this.fm.log.warn(`claude.leadFallback: ${fb.model} shares ${this.leadModel()}'s usage limit, so the lead never switches to it`);
    } else if (fb) this.fm.log.info(`lead fallback: ${fb.model} (${fb.effort ?? this.leadEffort()}) while ${this.leadModel()} is at a usage limit of its own`);
    const f = this.st.leadFallback;
    if (!f) return;
    if (!fb || f.model !== this.leadModel()) {
      this.fm.log.info(`lead fallback from before the restart dropped (the config no longer asks for it for ${f.model})`);
      delete this.st.leadFallback;
      this.fm.store.markDirty();
    } else if (this.onFallback()) {
      this.fm.log.info(`${capital(f.family)} limit: ${this.fm.nameOf(LEAD)} stays on ${fb.model} until ${new Date(f.until).toISOString()}`);
    }
  }

  /**
   * At start (after startLeadFallback), the limits saved before the restart. A Foreman from before
   * per-model holds saved a model's own limit (e.g. the Fable limit, seven_day_overage_included) as
   * the account-wide `limit`, which held everyone: it becomes that model's limit. A lead fallback in
   * force is its model's limit too. Limits that ended while the Foreman was down go. A lead whose own
   * model is limited switches to its fallback now, before its first turn.
   */
  private loadLimits(): void {
    const st = this.st;
    const now = Date.now();
    const old = st.limit;
    const family = limitModelFamily(old?.type);
    if (old && family) {
      delete st.limit;
      this.fm.store.markDirty();
      if (now < old.until) {
        const prev = st.modelLimits?.[family];
        if (!prev || prev.until < old.until) st.modelLimits = { ...st.modelLimits, [family]: { until: old.until, type: old.type! } };
        this.fm.log.warn(
          `the usage limit saved before the restart (${old.type}, until ${new Date(old.until).toISOString()}) is the ${capital(family)} limit: ` +
            `it holds only the agents on ${capital(family)} now, not the whole team`,
        );
      } else this.fm.log.info(`the usage limit saved before the restart (${old.type}) is over`);
    }
    const f = st.leadFallback;
    if (f && now < f.until && !this.modelLimit(f.family, now)) {
      st.modelLimits = { ...st.modelLimits, [f.family]: { until: f.until, ...(f.type ? { type: f.type } : {}), ...(f.guessed ? { guessed: true } : {}) } };
      this.fm.store.markDirty();
    }
    if (!st.modelLimits) return;
    for (const [fam, l] of Object.entries(st.modelLimits)) {
      if (now >= l.until) delete st.modelLimits[fam];
    }
    if (!Object.keys(st.modelLimits).length) delete st.modelLimits;
    this.fm.store.markDirty();
    for (const [fam, l] of Object.entries(st.modelLimits ?? {})) {
      this.fm.log.info(`${capital(fam)} limit until ${new Date(l.until).toISOString()}: agents on ${capital(fam)} wait for it, the others work`);
      this.syncLeadFallback(fam);
    }
  }

  /**
   * A usage poll came in. A model's weekly window at 100% (and not marked inactive) is that model's
   * limit before any turn is refused: its agents wait, a lead on it switches to its fallback. A limit
   * only the poll had seen lifts when its window has room again. A limit that gave no reset time
   * takes its window's reset.
   */
  private applyUsage(usage: Usage): void {
    if (usage.error || usage.stale) return;
    const now = Date.now();
    for (const w of usage.windows) {
      const family = usageWindowFamily(w.id);
      if (!family) continue;
      const at = w.resetsAt ? Date.parse(w.resetsAt) : Number.NaN;
      const full = w.utilization !== null && w.utilization >= 100 && w.active !== false && Number.isFinite(at) && at > now;
      if (full) this.setModelLimit(family, { resetsAt: at, fromUsage: true });
      else if (this.modelLimit(family, now)?.fromUsage) this.liftModelLimit(family, `the usage poll shows ${w.id} at ${w.utilization ?? '?'}%`);
    }
    for (const [family, l] of Object.entries(this.st.modelLimits ?? {})) {
      if (!l.guessed || now >= l.until) continue;
      const at = this.usageReset(family, now);
      if (!at) continue;
      l.until = at;
      delete l.guessed;
      this.fm.store.markDirty();
      this.fm.log.info(`${capital(family)} limit resets ${new Date(at).toISOString()} (usage)`);
      this.syncLeadFallback(family);
      this.showBanner();
      this.armLimitTimer();
    }
  }

  /**
   * The lead's turn on its own model was refused by a usage limit and the lead now runs its fallback
   * (limitReached switched it, or an earlier limit of its model did): the same job continues right
   * away on the fallback (its session resumed), instead of waiting for the reset (holdForLimit).
   * False when that does not apply.
   */
  private continueOnFallback(job: Job, from: TurnOrigin): boolean {
    // a limit that holds the fallback too (the account's, or the fallback model's own) wins: the job waits for it
    if (from.agentId !== LEAD || from.fallback || !this.onFallback() || this.holdFor(LEAD)) return false;
    const fb = this.fallbackConfig();
    const f = this.st.leadFallback;
    if (!fb || !f) return false;
    const model = modelLabel(fb.model) ?? fb.model;
    const next = this.continuation(
      job,
      `The ${capital(f.family)} usage limit stopped your last turn; you continue on ${model} until it resets. Re-check where you were (the task board, your messages) and continue your current job.`,
    );
    this.fm.setAgent(LEAD, { state: 'thinking', activity: `continuing on ${model}` });
    this.fm.agentLog(LEAD, 'text', `${capital(f.family)} limit: this ${job.kind} continues on ${this.fallbackLabel(fb)}`);
    const q = this.queues.get(LEAD) ?? [];
    q.unshift(next);
    this.queues.set(LEAD, q);
    return true;
  }

  /**
   * A usage limit of the model this turn ran refused it, but /model gave the agent another model while
   * the turn ran, one no limit holds: the same job continues at once on it, instead of waiting for (or,
   * as a refusal no limit covers, holding everyone until) a limit that no longer applies to the agent.
   */
  private continueOnNewModel(job: Job, from: TurnOrigin): boolean {
    const agentId = job.agentId;
    const ran = modelFamily(from.reported) ?? modelFamily(from.model);
    if (!ran || !this.modelLimit(ran) || this.familyOf(agentId) === ran || this.holdFor(agentId)) return false;
    const model = modelLabel(this.turnModel(agentId, agentId === LEAD ? 'lead' : 'worker', this.engineFor(agentId)).model) ?? 'its new model';
    const next = this.continuation(
      job,
      `The ${capital(ran)} usage limit stopped your last turn; ${userName()} has switched you to ${model}, so you continue now. Re-check where you were (your worktree, the task board) and continue your current job.`,
    );
    this.fm.setAgent(agentId, { state: 'thinking', activity: `continuing on ${model}` });
    this.fm.agentLog(agentId, 'text', `${capital(ran)} limit: this ${job.kind} continues on ${model}`);
    const q = this.queues.get(agentId) ?? [];
    q.unshift(next);
    this.queues.set(agentId, q);
    return true;
  }

  /** A job again after a usage limit refused its turn: its session resumed with `prompt`, or (no session yet) the same job from the start. */
  private continuation(job: Job, prompt: string): Job {
    return this.fm.store.data.sessions[job.sessionKey]?.sessionId ? { ...job, fresh: false, resumed: true, prompt } : job;
  }

  /**
   * A turn ended because of a usage limit (limitReached recorded it: the account's, or the model's
   * own this agent runs): keep its task where it is and run the same job again (resuming its session)
   * once the limit resets, instead of marking the task failed/blocked.
   */
  private holdForLimit(job: Job, stats: TurnStats): void {
    // a refusal that no limit in force covers for this agent still waits, as before (never retried at once)
    if (!this.holdFor(job.agentId)) this.setLimit(stats.rateLimit?.resetsAt, stats.rateLimit?.type);
    const next = this.continuation(job, 'A usage limit stopped your last turn; it has reset now. Re-check where you were (your worktree, the task board) and continue your current job.');
    const hold = this.holdFor(job.agentId);
    const what = hold?.family ? `${capital(hold.family)} limit` : 'usage limit';
    this.fm.setAgent(job.agentId, { state: 'blocked', activity: this.holdActivity(hold) });
    this.fm.agentLog(job.agentId, 'error', `${what}: this ${job.kind} resumes when it resets${hold ? ` (${clock(hold.until)})` : ''}`);
    const q = this.queues.get(job.agentId) ?? [];
    q.unshift(next);
    this.queues.set(job.agentId, q);
  }

  /** Scheduler failed (git error, busy directory...): try again later instead of stalling. */
  private retryLater(): void {
    if (this.retryTimer || this.stopping) return;
    const delay = this.retryDelayMs;
    this.retryDelayMs = Math.min(60_000, this.retryDelayMs * 2);
    this.retryTimer = setTimeout(() => {
      this.retryTimer = undefined;
      this.tick();
    }, delay);
    this.retryTimer.unref?.();
  }

  // ---- goals & scheduling -------------------------------------------------------------------

  async submitGoal(goal: Goal): Promise<void> {
    if (this.authFailed) {
      this.fm.setGoal(goal.id, { status: 'failed' });
      throw new ClientError(`Claude is not available: ${this.fm.status.message ?? 'auth failed'}`);
    }
    const repo = this.fm.repos.require(goal.repoId!);
    if (this.isStopped(LEAD)) {
      this.setStopped(LEAD, false);
      this.fm.bus.feed('system', 'Marlow is back on shift for the new goal', { agentId: LEAD });
    }
    for (const w of [LEAD, ...this.team]) if (!this.isStopped(w)) this.fm.setAgent(w, { active: true });
    this.fm.setAgent(LEAD, { state: 'thinking', station: 'meeting', activity: 'reading the goal', repoId: repo.id });
    // folder mode: no pull request intake (fetching would add refs to the user's own or an enclosing repository)
    const pulls = this.fm.repos.isFolder(repo) ? [] : await this.intakePulls(goal, repo.path);
    this.enqueue({ kind: 'plan', agentId: LEAD, goalId: goal.id, sessionKey: `${LEAD}:${goal.id}`, fresh: true, prompt: planPrompt(this.fm, goal, repo, pulls) });
  }

  /**
   * A goal that mentions pull requests ("#12") on a GitHub repository: fetch them before the lead
   * plans (see pulls.ts), announce them in the feed and keep a brief in shared memory.
   */
  private async intakePulls(goal: Goal, repoPath: string): Promise<PullRequest[]> {
    const refs = prRefs(goal.text);
    if (!refs.length || !(await this.pullFetcher.origin(repoPath))) return [];
    this.fm.setAgent(LEAD, { state: 'reading', station: 'library', activity: `fetching ${refs.length} pull request${refs.length === 1 ? '' : 's'}` });
    this.fm.bus.feed('system', `Fetching ${refs.length} pull request${refs.length === 1 ? '' : 's'} from GitHub`, { agentId: LEAD });
    const { pulls, errors } = await this.pullFetcher.fetch(repoPath, refs);
    for (const p of pulls) this.fm.bus.feed('task', `PR #${p.number} by @${p.author}: ${p.title}`, { agentId: LEAD });
    for (const e of errors) this.fm.bus.feed('error', `PR ${e}`, { agentId: LEAD });
    if (pulls.length) {
      this.fm.memory.write({ scope: 'shared', title: `Pull requests for ${goal.id}`, body: pullBriefs(pulls), author: LEAD, mode: 'replace' });
    }
    this.fm.setAgent(LEAD, { state: 'thinking', station: 'meeting', activity: 'reading the goal' });
    return pulls;
  }

  private promoteGoal(goal: Goal, why: string): void {
    if (goal.status !== 'planning') return;
    const n = this.fm.tasks.forGoal(goal.id).length;
    this.fm.setGoal(goal.id, { status: 'active' });
    this.fm.bus.feed('plan', `Marlow planned the goal into ${n} task${n === 1 ? '' : 's'}${why === 'recovered' ? ' (picked up after a restart)' : ''}`, { agentId: LEAD });
    this.tick();
  }

  tick(): void {
    if (this.tickTimer || this.stopping) return;
    this.tickTimer = setTimeout(() => {
      this.tickTimer = undefined;
      this.schedule().catch((e) => {
        this.fm.log.error(`scheduler: ${(e as Error).stack ?? e}`);
        this.retryLater();
      });
    }, 50);
    this.tickTimer.unref?.();
  }

  private workersRunning(): number {
    return [...this.running.keys()].filter((id) => id !== LEAD).length;
  }

  private isFree(w: string): boolean {
    const a = this.fm.agent(w);
    if (!a || !a.active || a.paused || this.isStopped(w) || !this.team.includes(w)) return false;
    if (this.running.has(w) || (this.queues.get(w)?.length ?? 0) > 0) return false;
    // its model is at a limit of its own: no new task for it (a worker on another model takes it)
    if (this.holdFor(w)) return false;
    return !this.fm.tasks.list().some((t) => t.assignee === w && t.status === 'doing');
  }

  private async schedule(): Promise<void> {
    if (this.authFailed || this.stopping || this.limited()) return;
    for (const goal of this.fm.goals().filter((g) => g.status === 'active')) {
      for (const t of this.fm.tasks.ready(goal.id)) {
        if (this.workersRunning() >= this.maxWorkers()) return;
        if (this.handoffs.has(t.id)) continue; // the previous worker's turn is still winding down
        let w: string | undefined;
        if (t.assignee && this.team.includes(t.assignee) && !this.isStopped(t.assignee)) {
          if (!this.isFree(t.assignee)) continue; // wait for the intended worker
          w = t.assignee;
        } else {
          w = this.team.find((x) => this.isFree(x));
        }
        if (!w) continue;
        try {
          await this.startWork(w, t, goal);
          this.retryDelayMs = 2000;
        } catch (e) {
          // the task stays on the board (assigned to w); try again shortly
          this.fm.log.error(`could not start ${t.id} for ${w}: ${(e as Error).message}`);
          this.retryLater();
        }
      }
    }
    // pump queues that were held back by the concurrency cap
    for (const id of this.queues.keys()) this.pump(id);
  }

  private async startWork(agentId: string, t: Task, goal: Goal): Promise<void> {
    if (!t.repoId) t.repoId = goal.repoId;
    this.fm.tasks.update(t.id, { assignee: agentId });
    // a task another worker started (stopped / reassigned): continue from that worker's branch,
    // whether or not its worktree was already wound down (abandoned)
    let startPoint: string | undefined;
    let continuesFrom: string | undefined;
    const prev = t.worktree ? this.fm.repos.findWorktree(t.repoId!, t.worktree) : undefined;
    if (prev && prev.agentId !== agentId && prev.status !== 'merged') {
      if (prev.status === 'active') {
        // nobody prepared the hand-off (e.g. reconciled after a restart): finish it here
        if (this.lastTurn.get(prev.agentId)?.job.taskId === t.id) await this.quiesce(prev.agentId);
        await this.fm.repos.abandon(t.repoId!, prev.id, `agentcraft: ${t.id} work in progress (handed to ${this.fm.nameOf(agentId)})`).catch((e) => this.fm.log.warn(`abandon ${prev.id}: ${(e as Error).message}`));
      }
      if ((await this.fm.repos.commitsAhead(t.repoId!, prev.branch, prev.base)) > 0) {
        startPoint = prev.branch;
        continuesFrom = prev.agentId;
        this.fm.bus.feed('task', `${this.fm.nameOf(agentId)} continues ${t.id} from ${this.fm.nameOf(prev.agentId)}'s branch`, { agentId });
      }
    }
    // a pull request task starts from the contributor's commits (fetched at goal intake)
    if (!startPoint && t.startBranch) startPoint = t.startBranch;
    const wt = await this.fm.repos.createWorktree(t.repoId!, agentId, t, startPoint ? { startPoint } : {});
    this.fm.tasks.update(t.id, { branch: wt.branch, worktree: wt.id });
    this.fm.tasks.setStatus(t.id, 'doing');
    this.fm.setAgent(agentId, { taskId: t.id, repoId: t.repoId!, worktree: wt.id, state: 'thinking', station: 'desk', activity: `starting ${t.id}` });
    this.fm.bus.feed('task', `${this.fm.nameOf(agentId)} started ${t.id}: ${t.title}`, { agentId });
    const inbox = formatInbox(this.fm.bus.inbox(agentId, { markRead: true }), (id) => this.fm.nameOf(id));
    this.enqueue({ kind: 'work', agentId, taskId: t.id, goalId: goal.id, sessionKey: `${agentId}:${t.id}`, fresh: !this.fm.store.data.sessions[`${agentId}:${t.id}`]?.sessionId, prompt: workPrompt(this.fm, t, goal, wt, inbox, continuesFrom) });
  }

  // ---- job queue ----------------------------------------------------------------------------

  private enqueue(job: Job): void {
    const q = this.queues.get(job.agentId) ?? [];
    q.push(job);
    this.queues.set(job.agentId, q);
    this.pump(job.agentId);
    // the job waits for a usage limit (the account's, or its model's own): the agent says which
    if (!this.running.has(job.agentId)) this.showHold(job.agentId);
  }

  /** An agent with a job that waits for a usage limit shows it ("Fable limit - resumes 14:05"). */
  private showHold(agentId: string): void {
    const a = this.fm.agent(agentId);
    if (this.stopping || !a?.active || a.paused || this.isStopped(agentId)) return;
    const hold = this.holdFor(agentId);
    if (hold) this.fm.setAgent(agentId, { state: 'blocked', activity: this.holdActivity(hold) });
  }

  private pump(agentId: string): void {
    // a usage limit holds the agent: the account's (everyone), or its model family's own
    if (this.stopping || this.authFailed || this.holdFor(agentId)) return;
    if (this.running.has(agentId)) return;
    const a = this.fm.agent(agentId);
    if (!a || a.paused || !a.active || this.isStopped(agentId)) return;
    const q = this.queues.get(agentId);
    if (!q?.length) return;
    if (agentId !== LEAD && this.workersRunning() >= this.maxWorkers()) return;
    const job = q.shift()!;
    const p = this.runJob(job).finally(() => {
      this.turnPromises.delete(p);
    });
    this.turnPromises.add(p);
    // runJob registers its Running entry synchronously, before its first await
    const entry = this.running.get(agentId);
    if (entry) {
      entry.done = p;
      this.lastTurn.set(agentId, entry);
    }
  }

  /** Where a job runs, and the base checkout of its repository (context is read from there, never a worktree). */
  private cwdFor(job: Job): { cwd: string; role: 'lead' | 'worker'; repoPath: string } {
    if (job.agentId === LEAD) {
      const goal = job.goalId ? this.fm.goal(job.goalId) : this.fm.currentGoal();
      const repo = goal?.repoId ? this.fm.repos.get(goal.repoId) : this.fm.repos.defaultRepo();
      if (!repo) throw new Error('no repo for the lead');
      return { cwd: repo.path, role: 'lead', repoPath: repo.path };
    }
    const t = job.taskId ? this.fm.tasks.get(job.taskId) : undefined;
    if (!t?.worktree || !t.repoId) throw new Error(`job for ${job.agentId} has no worktree`);
    const wt = this.fm.repos.requireWorktree(t.repoId, t.worktree);
    // its directory is gone: the CLI would fail to start in it (the SDK reports that as a
    // "native binary failed to launch" error)
    if (wt.status !== 'active') throw new Error(`worktree ${wt.id} is ${wt.status}; ${t.id} has no worktree to work in`);
    return { cwd: wt.path, role: 'worker', repoPath: this.fm.repos.require(t.repoId).path };
  }

  /** What the AgentCraft policy needs to judge this agent's tool calls. */
  private policyContext(agentId: string, role: Role, cwd: string): PolicyContext {
    // what the agent's engine enabled on top (Claude: claude.context skills, their files and MCP
    // tools), so the policy, and auto mode's guardrail hook through `classify`, both see it
    const engineCtx = this.engineFor(agentId).policyContext?.();
    return {
      ...engineCtx,
      role,
      cwd,
      readDirs: [this.fm.memory.dir, ...(engineCtx?.readDirs ?? [])],
      alwaysAllow: this.fm.store.data.permissionRules[agentId] ?? [],
      mcpServer: TEAM_MCP_SERVER,
      leadReadCommands: this.cfg.leadReadCommands,
      ...(this.cfg.subagents.enabled ? { subagents: true } : {}),
    };
  }

  /** Auto mode's guardrails cover the user's checkouts and AgentCraft's own state (other agents' worktrees). */
  private protectedRoots(): string[] {
    return this.cfg.permissions.protectCheckouts ? [...this.fm.repos.list().map((r) => r.path), this.fm.config.home] : [];
  }

  /** The policy, and the user for whatever it cannot allow by itself (a permission decision). */
  private permissionGate(agentId: string, role: Role, cwd: string, turn: TurnHandle): PermissionGate {
    return async (toolName, input, signal, title) => {
      // a stopped/paused/cancelled turn runs nothing more, even if its CLI has not exited yet
      if (turn.signal.aborted) return { allow: false, message: `Your turn was stopped by ${userName()}.`, interrupt: true };
      const verdict = classifyToolUse(toolName, input, this.policyContext(agentId, role, cwd));
      if (verdict.action === 'allow') return { allow: true };
      if (verdict.action === 'deny') {
        this.fm.agentLog(agentId, 'error', `blocked: ${describeToolCall(toolName, input)} (${verdict.reason})`);
        return { allow: false, message: verdict.reason };
      }
      const prev = this.fm.agent(agentId);
      const prevState = prev ? { state: prev.state, station: prev.station, activity: prev.activity } : undefined;
      const t = prev?.taskId;
      const d = this.fm.createDecision({
        agentId,
        kind: 'permission',
        tool: toolName,
        question: `${this.fm.nameOf(agentId)} wants to run ${truncate(describeToolCall(toolName, input), 160)}`,
        options: [...PERMISSION_OPTIONS],
        context: `${verdict.reason}\ncwd: ${cwd}\n"${PERMISSION_OPTIONS[1]}" covers: ${[...new Set(verdict.ruleKeys.map(describeRuleKey))].join('; ')}${title ? `\n${title}` : ''}`,
        ...(t ? { taskId: t } : {}),
      });
      this.fm.setAgent(agentId, { state: 'waiting_user', station: 'user', activity: 'asking permission' });
      this.fm.agentLog(agentId, 'tool', `permission? ${describeToolCall(toolName, input)}`);
      const onAbort = () => this.fm.decisions.cancel(d.id, 'turn stopped');
      if (signal.aborted) onAbort();
      else signal.addEventListener('abort', onAbort, { once: true });
      const res = await this.fm.decisions.wait(d.id);
      signal.removeEventListener('abort', onAbort);
      if (signal.aborted) return { allow: false, message: 'The turn was stopped.' };
      if (prevState) this.fm.setAgent(agentId, prevState);
      const opt = res.answer?.option;
      if (res.status === 'answered' && (opt === PERMISSION_OPTIONS[0] || opt === PERMISSION_OPTIONS[1])) {
        if (opt === PERMISSION_OPTIONS[1]) {
          // every key the call needed: each is scoped (see policy.ts), so this grants exactly
          // what the prompt listed
          const rules = (this.fm.store.data.permissionRules[agentId] ??= []);
          for (const k of verdict.ruleKeys) if (!rules.includes(k)) rules.push(k);
          this.fm.store.markDirty();
        }
        this.fm.agentLog(agentId, 'result', `${userName()} allowed: ${describeToolCall(toolName, input)}`);
        return { allow: true };
      }
      this.fm.agentLog(agentId, 'error', `${res.status === 'cancelled' ? 'Permission request withdrawn' : `${userName()} denied`}: ${describeToolCall(toolName, input)}`);
      return { allow: false, message: `${userName()} denied this${res.answer?.text ? `: ${res.answer.text}` : ''}. Find another way or ask_user.` };
    };
  }

  /**
   * Where a sandboxed worker (Codex) may write besides its worktree: exactly what committing and
   * merging on its own branch needs, and the temp dir (scratch files and test runs; the policy
   * allows it too). Never the shared git dir as a whole: its config and hooks would let a worker
   * run code in the user's own git, outside every sandbox, and its refs would let it move the
   * user's branches. Verified against the real Codex Windows sandbox: commit and `git merge`
   * work; writing .git/config, .git/hooks or refs/heads/main is denied.
   *  - objects/                        new commits, trees, blobs (content-addressed, harmless)
   *  - refs/heads/<branch dir>/        this agent's branches only (agentcraft/<agent>/...)
   *  - logs/refs/heads/<branch dir>/   their reflogs
   *  - the worktree's own git dir      its HEAD, index, ORIG_HEAD, MERGE_HEAD (Codex protects the
   *                                    .git pointer's target unless it is granted exactly)
   */
  private async writableRoots(role: Role, job: Job): Promise<string[]> {
    if (role !== 'worker' || !job.taskId) return [];
    const t = this.fm.tasks.require(job.taskId);
    const repo = this.fm.repos.require(t.repoId!);
    const worktree = this.fm.repos.requireWorktree(repo.id, t.worktree!);
    const verified = await this.fm.repos.verifyWorktreeGit(repo, worktree);
    if (!verified.ok) throw new Error(`Cannot grant worktree Git access: ${verified.reason}`);
    const roots = [path.join(verified.commonDir, 'objects'), verified.gitDir, os.tmpdir()];
    const branchDir = path.posix.dirname(worktree.branch);
    if (branchDir !== '.' && !branchDir.split('/').includes('..')) {
      for (const d of [path.join(verified.commonDir, 'refs', 'heads', ...branchDir.split('/')), path.join(verified.commonDir, 'logs', 'refs', 'heads', ...branchDir.split('/'))]) {
        fs.mkdirSync(d, { recursive: true });
        roots.push(d);
      }
    }
    return [...new Set(roots)];
  }

  private async runJob(job: Job): Promise<void> {
    const agentId = job.agentId;
    const abort = new AbortController();
    const entry: Running = { abort, job, modelEpoch: this.modelEpochs.get(agentId) };
    /** /model gave the agent another model (or effort) since this turn started: the turn keeps its own, the nameplate shows the next one */
    const modelChanged = () => entry.modelEpoch !== this.modelEpochs.get(agentId);
    const turn: TurnHandle = { signal: abort.signal, reason: () => entry.reason };
    // (pump records this turn as lastTurn right after this synchronous part: this is the previous one)
    const previous = this.lastTurn.get(agentId);
    this.running.set(agentId, entry);
    this.usagePoller?.reschedule(); // a turn runs: the busy cadence
    let stats: TurnStats | undefined;
    let cwd = '';
    /** the model this turn runs (the lead's fallback, or the engine's configured model) */
    let sel: TurnModel | undefined;
    /** the model the turn reported running */
    let reported: string | undefined;
    try {
      // a paused/stopped turn of this agent may still be winding down: never run two CLIs on one
      // agent (they could share a session)
      if (previous?.reaping) await Promise.race([previous.reaping, sleep(10_000)]);
      const where = this.cwdFor(job);
      cwd = where.cwd;
      const role = where.role;
      const engine = this.engineFor(agentId);
      const session = this.fm.store.data.sessions[job.sessionKey];
      // a session belongs to the engine that made it (records from before engines were Claude's)
      const resume = !job.fresh && session?.sessionId && (session.engine ?? 'claude') === engine.id ? session.sessionId : undefined;
      this.st.inflight[agentId] = { kind: job.kind, sessionKey: job.sessionKey, startedAt: Date.now(), ...(job.taskId ? { taskId: job.taskId } : {}), ...(job.goalId ? { goalId: job.goalId } : {}) };
      this.fm.store.markDirty();

      let systemAppend: string;
      if (role === 'lead') systemAppend = leadSystemPrompt(this.fm, this.team);
      else {
        const t = this.fm.tasks.require(job.taskId!);
        systemAppend = workerSystemPrompt(this.fm, agentId, this.fm.repos.requireWorktree(t.repoId!, t.worktree!));
      }
      const turnSel = this.turnModel(agentId, role, engine);
      sel = turnSel;
      if (turnSel.override) entry.fallback = true;
      const model = turnSel.model;
      const limitedFamily = this.st.leadFallback?.family;
      const fallbackNote = turnSel.override ? `, ${turnSel.override.effort}: fallback${limitedFamily ? ` while ${capital(limitedFamily)} is limited` : ''}` : '';
      this.fm.agentLog(agentId, 'text', `${resume ? 'Resuming' : 'Starting'} ${job.kind}${job.taskId ? ` ${job.taskId}` : ''} (${engine.id === 'claude' ? model : `${engine.label} ${model}`}${fallbackNote})`);
      if (job.kind === 'followup' || job.resumed) this.fm.agentLog(agentId, 'text', truncate(job.prompt, 400));
      const timer = setTimeout(() => this.abortTurn(entry, 'timeout'), TURN_TIMEOUT_MS);
      timer.unref?.();
      // messages that arrived while the agent was not in a turn ride along with this prompt
      const unread = this.fm.bus.inbox(agentId, { markRead: true });
      const prompt = unread.length ? `${job.prompt}\n\n[New messages]\n${formatInbox(unread, (id) => this.fm.nameOf(id))}` : job.prompt;
      try {
        stats = await engine.runTurn({
          agentId,
          role,
          cwd,
          repoPath: where.repoPath,
          prompt,
          instructions: systemAppend,
          ...(resume ? { resume } : {}),
          ...(turnSel.override ? { modelOverride: turnSel.override } : {}),
          env: agentEnv(process.env, { agentId, cwd }),
          writableRoots: await this.writableRoots(role, job),
          abort,
          turn,
          permission: this.permissionGate(agentId, role, cwd, turn),
          classify: (tool, input) => classifyToolUse(tool, input, this.policyContext(agentId, role, cwd)),
          protectedRoots: () => this.protectedRoots(),
          tools: agentTools(this.fm, agentId, role, this.hooks, turn),
          // a stopped turn's whole process tree is ended before its worktree is handed on
          onProcess: (child) => {
            entry.child = child;
            entry.spawnedAt = Date.now();
          },
          onSession: (id) => {
            if (this.fm.store.data.sessions[job.sessionKey]?.sessionId !== id) this.recordSession(job.sessionKey, id, model, engine.id);
          },
          onModel: (m) => {
            reported = m;
            // the nameplate already shows the model /model gave it for its next turn
            if (modelChanged()) return;
            // the lead's own model, when its name says nothing about its family ("default")
            if (agentId === LEAD && !turnSel.override && engine.id === 'claude') this.leadOwnReported = m;
            const label = modelLabel(m);
            if (!label) return;
            this.reportedModels.set(agentId, { engine: engine.id, label });
            this.fm.setAgent(agentId, { engine: engine.id, model: label });
          },
          onRateLimit: (r) => this.onRateLimit(r, { agentId, fallback: !!turnSel.override, model, ...(reported ? { reported } : {}) }),
        });
      } finally {
        clearTimeout(timer);
      }
      if (stats.sessionId) this.recordSession(job.sessionKey, stats.sessionId, model, engine.id, stats);
      if (stats.authFailed) this.markAuthFailed(engine.authFailedMessage(stats.authFailed));
    } catch (e) {
      const aborted = abort.signal.aborted;
      if (!aborted) {
        const msg = (e as Error).message ?? String(e);
        this.fm.log.error(`${agentId} ${job.kind} failed: ${msg}`);
        this.fm.agentLog(agentId, 'error', `session error: ${truncate(msg, 400)}`);
        if (/auth|login|credential|401/i.test(msg)) this.markAuthFailed(this.engineFor(agentId).authFailedMessage(truncate(msg, 160)));
        stats = { isError: true, errors: [msg] };
        const l = limitFromText(msg);
        if (l.limited) {
          stats.limited = true;
          if (l.resetsAt) stats.rateLimit = { status: 'rejected', resetsAt: l.resetsAt };
        }
      }
    } finally {
      this.running.delete(agentId);
    }

    const reason = entry.reason;
    if (reason === 'shutdown') return; // inflight stays persisted -> resumed next start (stop() reaps)
    // an aborted turn's CLI and its processes must not linger (they would keep working); started
    // before anything is re-queued, so the next turn of this agent waits for it
    if (reason) void this.reap(entry);
    delete this.st.inflight[agentId];
    this.fm.store.markDirty();
    // the lead's own model's limit reset while this fallback turn ran (its nameplate shows its own
    // model again), or began while this turn on its own model ran (the fallback, from now on), or
    // /model gave the agent another model while this turn ran (its next turn runs that one)
    const nextModel = () => this.turnModel(agentId, agentId === LEAD ? 'lead' : 'worker', this.engineFor(agentId)).model;
    if ((sel?.override && !this.onFallback()) || (agentId === LEAD && sel && !sel.override && this.onFallback()) || (modelChanged() && sel && nextModel() !== sel.model)) {
      this.reportedModels.delete(agentId);
      this.showEngines();
    }
    if (reason === 'pause') {
      const next: Job = { ...job, fresh: false, resumed: true, prompt: `${userName()} paused you and has now resumed you. Any question you had open was withdrawn; ask again if you still need it. Continue your current job.` };
      // resume may already have arrived while the aborted turn was unwinding
      if (this.fm.agent(agentId)?.paused) {
        this.pausedJobs.set(agentId, next);
        this.fm.setAgent(agentId, { state: 'idle', activity: 'paused' });
      } else this.enqueue(next);
    } else if (reason === 'stop') {
      if (this.isStopped(agentId)) this.fm.setAgent(agentId, { state: 'idle', station: 'lounge', activity: 'stopped - off shift', taskId: null, worktree: null });
    } else if (reason === 'cancel') {
      const a = this.fm.agent(agentId);
      if (a?.taskId === job.taskId) this.fm.setAgent(agentId, { state: 'idle', station: 'lounge', activity: 'task cancelled', taskId: null, worktree: null });
    } else if (stats?.isError && stats.limited) {
      // a model's own limit holds the agents on that model (the lead, with a fallback, continues on it
      // now); any other limit holds everyone until it resets
      const origin: TurnOrigin = { agentId, fallback: !!sel?.override, ...(sel ? { model: sel.model } : {}), ...(reported ? { reported } : {}) };
      this.limitReached(stats.rateLimit, [stats.resultText ?? '', ...stats.errors].join(' '), origin, true);
      if (!sel || !(this.continueOnFallback(job, origin) || (modelChanged() && this.continueOnNewModel(job, origin)))) this.holdForLimit(job, stats);
    } else if (reason === 'timeout') {
      this.fm.agentLog(agentId, 'error', `turn timed out after ${TURN_TIMEOUT_MS / 60_000} min`);
      await this.afterTurn(job, { isError: true, subtype: 'timeout', errors: ['turn timed out'] }).catch((e) => this.fm.log.error(`afterTurn ${agentId}: ${(e as Error).stack ?? e}`));
    } else {
      if (stats && !stats.isError) {
        this.limitBackoffMs = LIMIT_BACKOFF_MS;
        // its model worked again: a later limit of it without a reset time starts from the shortest wait
        const family = modelFamily(reported) ?? modelFamily(sel?.model);
        if (family) this.familyBackoffMs.delete(family);
      }
      await this.afterTurn(job, stats).catch((e) => this.fm.log.error(`afterTurn ${agentId}: ${(e as Error).stack ?? e}`));
    }
    // the turn used some of the plan: fresh numbers (unless the poller is backing off)
    this.usagePoller?.trigger();
    this.pump(agentId);
    // the user's messages that came after the agent's last tool call: answer them now
    if (reason !== 'stop' && reason !== 'pause') this.deliverPending(agentId);
    this.tick();
  }

  /**
   * Messages from the user to this agent that nobody has read yet (they arrived after its last
   * agentcraft tool call, or while it was off shift): start a follow-up turn for them.
   */
  private deliverPending(agentId: string): void {
    if (this.stopping || this.isStopped(agentId) || this.running.has(agentId) || this.pausedJobs.has(agentId) || (this.queues.get(agentId)?.length ?? 0) > 0) return;
    const a = this.fm.agent(agentId);
    if (!a?.active || a.paused) return;
    const fromUser = this.fm.bus.inbox(agentId).filter((m) => m.from === 'user' && m.to === agentId);
    if (!fromUser.length) return;
    this.fm.log.info(`delivering ${fromUser.length} message(s) from ${userName()} to ${agentId} that arrived after its last turn`);
    this.onUserMessage(agentId, fromUser[fromUser.length - 1]!.text);
  }

  private recordSession(key: string, sessionId: string, model: string, engine: EngineId, stats?: TurnStats): void {
    const s = (this.fm.store.data.sessions[key] ??= { turns: 0, costUsd: 0, updatedAt: Date.now() });
    s.sessionId = sessionId;
    s.model = model;
    s.engine = engine;
    s.updatedAt = Date.now();
    if (stats) {
      s.turns += stats.numTurns ?? 0;
      if (typeof stats.costUsd === 'number') {
        // total_cost_usd is cumulative per session (resumes continue from the saved total)
        const prev = this.lastCost.get(sessionId) ?? s.costUsd;
        const delta = Math.max(0, stats.costUsd - prev);
        this.lastCost.set(sessionId, stats.costUsd);
        s.costUsd = Math.max(s.costUsd, stats.costUsd);
        this.fm.setStatus({ costUsd: Math.round(((this.fm.status.costUsd ?? 0) + delta) * 1000) / 1000 });
      }
      s.lastResult = stats.subtype;
    }
    this.fm.store.markDirty();
  }

  // ---- after a turn -------------------------------------------------------------------------

  private failure(stats: TurnStats | undefined): string {
    return truncate(stats?.subtype && stats.subtype !== 'success' ? stats.subtype.replace(/^error_/, '').replace(/_/g, ' ') : (stats?.errors[0] ?? 'error'), 36);
  }

  private async afterTurn(job: Job, stats: TurnStats | undefined): Promise<void> {
    const failed = !stats || stats.isError;
    if (job.agentId === LEAD) {
      this.fm.setAgent(LEAD, failed ? { state: 'error', station: 'meeting', activity: `turn failed: ${this.failure(stats)}` } : { state: 'idle', station: 'meeting', activity: 'watching the task wall' });
      // any lead turn for a goal that is still planning (plan, or a plan resumed after a
      // restart / an answer) settles the goal: tasks -> active
      const goal = job.goalId ? this.fm.goal(job.goalId) : undefined;
      if (goal && goal.status === 'planning') {
        const n = this.fm.tasks.forGoal(goal.id).length;
        if (n > 0) this.promoteGoal(goal, 'planned');
        else if (failed) {
          this.fm.setGoal(goal.id, { status: 'failed' });
          this.fm.bus.feed('error', `Marlow's planning turn ended without tasks${stats?.errors.length ? `: ${stats.errors.join('; ')}` : ''}`, { agentId: LEAD });
        } else if (job.kind === 'plan') {
          // nothing to do (e.g. the user said "ignore it"): close the goal instead of leaving it
          // "active" at 0% forever; a task the lead adds to it later makes it active again
          this.fm.setGoal(goal.id, { status: 'cancelled', progress: 0 });
          this.fm.bus.feed('goal', `Marlow planned no tasks: goal closed (${truncate(goal.text, 80)})`, { agentId: LEAD });
        }
      }
      if (job.kind === 'review' && job.taskId) {
        const t = this.fm.tasks.get(job.taskId);
        // pending, not open: a merge the user just approved may still be running, and the task
        // only leaves review once it is done
        const hasDecision = this.fm.decisions.pending().some((d) => d.kind === 'merge' && d.taskId === job.taskId);
        if (t && t.status === 'review' && !hasDecision) {
          // lead gave no verdict: still surface the merge to the user (never auto-merge)
          this.openMergeDecision(t, `Marlow's review: ${truncate(stats?.resultText ?? '(no verdict)', 300)}`);
        }
      }
      return;
    }
    // worker
    const t = job.taskId ? this.fm.tasks.get(job.taskId) : undefined;
    if (!t) {
      this.fm.setAgent(job.agentId, failed ? { state: 'error', station: 'desk', activity: `turn failed: ${this.failure(stats)}` } : { state: 'idle', station: 'lounge', activity: 'idle' });
      return;
    }
    if (t.status === 'review') {
      await this.afterWorkerDone(t.id);
      return;
    }
    if (t.status === 'doing') {
      const nudges = job.nudges ?? 0;
      if (!failed && nudges < 1) {
        this.enqueue({ ...job, kind: 'followup', fresh: false, nudges: nudges + 1, prompt: `You ended your turn but ${t.id} is still "doing". If the work is complete, call update_task("${t.id}", status "review", summary). If you are stuck, call update_task with status "blocked" and blocked_reason. Otherwise continue.` });
        return;
      }
      const wt = t.worktree && t.repoId ? this.fm.repos.findWorktree(t.repoId, t.worktree) : undefined;
      if (wt) await this.fm.repos.refresh(t.repoId!);
      if (!failed && wt && wt.files > 0) {
        this.fm.tasks.setStatus(t.id, 'review', { summary: truncate(stats?.resultText ?? 'work complete', 400) });
        await this.afterWorkerDone(t.id);
      } else {
        this.fm.tasks.setStatus(t.id, 'blocked', { reason: failed ? `session ended: ${stats?.subtype ?? stats?.errors.join('; ') ?? 'error'}` : 'worker stopped without changes', force: true });
        // a failed turn is an error (red); a worker that gave up is blocked
        this.fm.setAgent(job.agentId, failed ? { state: 'error', station: 'desk', activity: `${t.id}: ${this.failure(stats)}` } : { state: 'blocked', station: 'desk', activity: `${t.id} blocked` });
        this.fm.bus.send(job.agentId, LEAD, `${t.id} is blocked: ${this.fm.tasks.get(t.id)?.blockedReason}`);
        this.fm.notify('warn', `${this.fm.nameOf(job.agentId)}: ${t.id} ${failed ? 'failed' : 'is blocked'} (${this.fm.tasks.get(t.id)?.blockedReason ?? ''}) - /task ${t.id} retry when ready`);
      }
      return;
    }
    if (t.status === 'blocked') {
      this.fm.setAgent(job.agentId, { state: 'blocked', station: 'desk', activity: `${t.id} blocked` });
      this.fm.notify('warn', `${this.fm.nameOf(job.agentId)} is blocked on ${t.id}: ${t.blockedReason ?? ''}`);
      return;
    }
    this.fm.setAgent(job.agentId, { state: 'idle', station: 'lounge', activity: 'idle' });
  }

  /** A worker finished a task: CI in the worktree, then lead review (or a merge decision). */
  private async afterWorkerDone(taskId: string): Promise<void> {
    if (this.reviewing.has(taskId)) return; // CI already running for it
    this.reviewing.add(taskId);
    try {
      await this.ciThenReview(taskId);
    } finally {
      this.reviewing.delete(taskId);
    }
  }

  private async ciThenReview(taskId: string): Promise<void> {
    const t = this.fm.tasks.get(taskId);
    if (!t || t.status !== 'review' || !t.repoId || !t.worktree) return;
    const worker = t.assignee;
    if (worker) this.fm.setAgent(worker, { state: 'idle', station: 'lounge', activity: `${t.id} in review` });
    let ci: TestResult | undefined;
    try {
      this.fm.tasks.update(t.id, { ci: 'running' });
      this.fm.repos.setCi(t.repoId, 'running');
      if (worker) this.fm.agentLog(worker, 'tool', `CI: ${this.cfg.ciCommand ?? this.fm.repos.detectTestCommand(this.fm.repos.requireWorktree(t.repoId, t.worktree).path) ?? '(no tests)'}`);
      ci = await this.fm.repos.runTests(t.repoId, t.worktree, this.cfg.ciCommand);
      this.fm.tasks.update(t.id, { ci: ci.pass ? 'pass' : 'fail' });
      this.fm.repos.setCi(t.repoId, ci.pass ? 'pass' : 'fail');
      if (worker) this.fm.agentLog(worker, ci.pass ? 'result' : 'error', `CI ${ci.pass ? 'passed' : 'FAILED'} (${(ci.durationMs / 1000).toFixed(1)}s)\n${ci.output.split('\n').slice(-6).join('\n')}`);
      this.fm.bus.feed('ci', `${t.id}: tests ${ci.pass ? 'pass' : 'fail'} (${ci.command})`, { ...(worker ? { agentId: worker } : {}) });
    } catch (e) {
      this.fm.log.warn(`CI for ${t.id}: ${(e as Error).message}`);
    }
    await this.fm.repos.refresh(t.repoId);
    if (ci && !ci.pass && (this.st.ciFixes[t.id] ?? 0) < 1 && worker && !this.isStopped(worker)) {
      this.st.ciFixes[t.id] = (this.st.ciFixes[t.id] ?? 0) + 1;
      this.sendBackToWorker(t.id, `CI failed for ${t.id} (${ci.command}):\n${ci.output}\n\nFix the failures, re-run the tests, then update_task("${t.id}", status "review", summary).`);
      return;
    }
    if (this.cfg.leadReview && !this.isStopped(LEAD)) {
      const diff = await this.fm.repos.diff(t.repoId, t.worktree);
      this.fm.setAgent(LEAD, { state: 'reading', station: 'mergestation', activity: `reviewing ${t.id}` });
      this.enqueue({ kind: 'review', agentId: LEAD, taskId: t.id, ...(t.goalId ? { goalId: t.goalId } : {}), sessionKey: `${LEAD}:${t.goalId ?? 'adhoc'}`, prompt: reviewPrompt(this.fm, this.fm.tasks.require(t.id), renderDiffText(diff.files), diff.stats, ci) });
    } else {
      this.openMergeDecision(t, t.summary ?? 'Work complete.');
    }
  }

  private openMergeDecision(t: Task, summary: string): void {
    const wt = this.fm.repos.requireWorktree(t.repoId!, t.worktree!);
    if (wt.status !== 'active') {
      this.fm.log.warn(`no merge decision for ${t.id}: worktree ${wt.id} is ${wt.status}`);
      return;
    }
    this.fm.createDecision({
      agentId: LEAD,
      kind: 'merge',
      question: `Merge ${t.id} "${t.title}" (${wt.branch}) into ${wt.base}?`,
      options: [...MERGE_OPTIONS],
      context: `${summary}\n${wt.files} files, +${wt.additions} -${wt.deletions} | tests: ${t.ci}`,
      taskId: t.id,
      repoId: t.repoId!,
      worktree: wt.id,
    });
    if (!this.isStopped(LEAD)) this.fm.setAgent(LEAD, { state: 'idle', station: 'mergestation', activity: `awaiting your review of ${t.id}` });
  }

  /** Resume the worker's task session with feedback (lead/user changes, CI failure). */
  private sendBackToWorker(taskId: string, prompt: string): void {
    const t = this.fm.tasks.get(taskId);
    if (!t?.assignee) return;
    if (this.isStopped(t.assignee)) {
      // nobody to send it back to: put it on the board for the next free worker
      this.fm.tasks.setStatus(t.id, 'todo', { force: true, summary: truncate(prompt, 400) });
      this.fm.tasks.update(t.id, { assignee: null });
      this.tick();
      return;
    }
    if (t.status !== 'doing') this.fm.tasks.setStatus(t.id, 'doing', { force: true });
    this.fm.setAgent(t.assignee, { taskId: t.id, state: 'thinking', station: 'desk', activity: `revising ${t.id}`, ...(t.repoId ? { repoId: t.repoId } : {}), ...(t.worktree ? { worktree: t.worktree } : {}) });
    this.enqueue({ kind: 'followup', agentId: t.assignee, taskId: t.id, ...(t.goalId ? { goalId: t.goalId } : {}), sessionKey: `${t.assignee}:${t.id}`, prompt });
  }

  // ---- models (agent.model, the console's /model) --------------------------------------------
  //
  // An agent's own model and effort live in its engine's agentModels (config.json claude.agentModels
  // or codex.agentModels): the engine reads them at every turn, so a change applies from the agent's
  // next turn and a running turn finishes on the model it started with. The nameplate, the banner and
  // who a model's usage limit holds follow at once.

  /** What an agent runs, as agent.model reports it. */
  private modelInfo(agentId: string): AgentModelInfo {
    const engine = this.engineFor(agentId);
    const role: Role = agentId === LEAD ? 'lead' : 'worker';
    const model = engine.model(role, agentId);
    const fb = agentId === LEAD && this.onFallback() ? this.fallbackConfig() : undefined;
    const reported = this.reportedModels.get(agentId);
    // the real model once a turn of it reported one (not a fallback turn's)
    const label = (!fb && reported?.engine === engine.id ? reported.label : undefined) ?? modelLabel(model) ?? `${engine.label} default`;
    const effort = engine.effort(role, agentId);
    return {
      agentId,
      name: this.fm.nameOf(agentId),
      engine: engine.id,
      model,
      label,
      ...(effort ? { effort } : {}),
      source: engine.modelSource(role, agentId),
      ...(fb ? { fallback: this.fallbackLabel(fb) } : {}),
    };
  }

  /** "Sonnet 5 (high)", "GPT-5.1 Codex" (no effort: the engine's default) */
  private modelText(i: AgentModelInfo): string {
    return `${i.label}${i.effort ? ` (${i.effort})` : ''}`;
  }

  /** Why an engine cannot take this model or effort (undefined: it can). */
  private modelChangeProblem(engine: Engine, model: string, effort: string | undefined): string | undefined {
    const problem = modelProblem(engine.id, model);
    if (problem === 'invalid') return `"${model}" is not a model name`;
    if (problem === 'not-claude') return `${model} is not a Claude model (use opus, sonnet, haiku, fable or a claude-... model id)`;
    if (problem === 'claude') return `${model} is a Claude model (use a Codex model such as gpt-5.1-codex, or default)`;
    const efforts = effortsFor(engine.id);
    if (effort && !efforts.includes(effort)) return `there is no "${effort}" effort (use ${efforts.join(', ')})`;
    return undefined;
  }

  agentModels(agentIds: string[], change: ModelChange | undefined, group: boolean): AgentModelResult {
    if (!change) {
      const agents = agentIds.map((id) => this.modelInfo(id));
      const text = agents.map((a) => `${a.name} ${this.modelText(a)}${a.fallback ? `, on ${a.fallback} while its own model is limited` : ''} (${a.source})`).join(', ');
      return { agents, text };
    }
    return this.changeModels(agentIds, change, group);
  }

  /**
   * Give agents their own model and effort, or with "default" take it away (back to the lead's /
   * workers' model and effort): validated per engine (a group skips the agents whose engine cannot
   * run it), saved in config.json, then applied in memory (also when the save failed: the result says
   * so). A lead switched onto a model whose own usage limit is in force runs its fallback (or waits);
   * an agent switched away from a limited model runs at once.
   */
  private changeModels(agentIds: string[], change: ModelChange, group: boolean): AgentModelResult {
    const reset = change.model.trim().toLowerCase() === 'default';
    const effort = change.effort?.trim().toLowerCase() || undefined;
    if (reset && effort) throw new ClientError('"default" goes back to the configured model and effort: give no effort with it');
    const plan: Array<{ id: string; engine: Engine; next: AgentModel | undefined }> = [];
    /** agents whose engine cannot run it, by reason ("Claude: gpt-5 is not a Claude model ...") */
    const refused = new Map<string, string[]>();
    for (const id of agentIds) {
      const engine = this.engineFor(id);
      const problem = reset ? undefined : this.modelChangeProblem(engine, change.model, effort);
      if (problem) {
        const why = `${engine.label}: ${problem}`;
        refused.set(why, [...(refused.get(why) ?? []), id]);
        continue;
      }
      plan.push({ id, engine, next: reset ? undefined : { model: normalizeModel(engine.id, change.model), ...(effort ? { effort } : {}) } });
    }
    const runsOn = (ids: string[], why: string) => `${this.names(ids)} ${ids.length === 1 ? 'runs' : 'run'} on ${why}`;
    // one agent named, or nobody can run it: refused ("Juniper, Kit and Wren run on Claude: ...")
    if (!plan.length || (!group && refused.size)) throw new ClientError([...refused].map(([why, ids]) => runsOn(ids, why)).join('; '));
    const skipped = [...refused].flatMap(([why, ids]) => ids.map((agentId) => ({ agentId, reason: runsOn([agentId], why) })));
    const before = new Map(plan.map((p) => [p.id, this.modelInfo(p.id)]));

    // config.json first (only the entries that change), then the engines' live settings
    const same = (a: AgentModel | undefined, b: AgentModel | undefined) => a?.model === b?.model && a?.effort === b?.effort;
    const writes: AgentModelChange[] = plan.filter((p) => !same(p.engine.agentModels[p.id], p.next)).map((p) => ({ section: p.engine.id, agentId: p.id, value: p.next }));
    let saveError: string | undefined;
    if (writes.length) {
      try {
        const file = saveAgentModels(this.fm.config.home, writes);
        this.fm.log.info(`/model: saved ${writes.map((w) => `${w.section}.agentModels.${w.agentId} = ${w.value ? JSON.stringify(w.value) : '(removed)'}`).join(', ')} in ${file}`);
      } catch (e) {
        saveError = (e as Error).message;
        this.fm.log.warn(`/model: could not save the change in config.json (${saveError}); it applies until the Foreman restarts`);
      }
    }
    for (const p of plan) {
      if (p.next) p.engine.agentModels[p.id] = p.next;
      else delete p.engine.agentModels[p.id];
    }

    // what changed for each agent: its next turn runs another model or effort
    const changed: string[] = [];
    let leadModelChanged = false;
    for (const p of plan) {
      const was = before.get(p.id)!;
      const role: Role = p.id === LEAD ? 'lead' : 'worker';
      const model = p.engine.model(role, p.id);
      if (model === was.model && p.engine.effort(role, p.id) === was.effort) continue;
      changed.push(p.id);
      this.modelEpochs.set(p.id, (this.modelEpochs.get(p.id) ?? 0) + 1);
      if (model !== was.model) {
        // what its last turn reported was the old model
        this.reportedModels.delete(p.id);
        if (p.id === LEAD) {
          this.leadOwnReported = undefined;
          leadModelChanged = true;
        }
      }
    }
    const after = new Map(plan.map((p) => [p.id, this.modelInfo(p.id)]));
    // one feed line per new model: "Juniper switched to Sonnet 5 (high, set by Alex)"
    const byModel = new Map<string, string[]>();
    for (const id of changed) {
      const key = this.modelText(after.get(id)!);
      byModel.set(key, [...(byModel.get(key) ?? []), id]);
    }
    for (const members of byModel.values()) {
      const i = after.get(members[0]!)!;
      const by = `${i.effort ? `${i.effort}, ` : ''}set by ${userName()}`;
      this.fm.bus.feed('system', `${this.names(members)} switched ${reset ? 'back to the configured model, ' : 'to '}${i.label} (${by})`, members.length === 1 ? { agentId: members[0]! } : {});
    }
    this.fm.log.info(`/model: ${plan.map((p) => `${p.id} ${before.get(p.id)!.model} -> ${after.get(p.id)!.model} (${after.get(p.id)!.effort ?? 'default effort'})`).join(', ')}${changed.length ? '' : ' (unchanged)'}`);

    // the lead's fallback stood in for its previous model; the new one's own limit may be in force
    if (leadModelChanged) this.leadModelChanged();
    this.showEngines();
    this.showBanner();
    for (const id of changed) {
      // a running turn finishes on its model; the next one runs the new one
      if (this.running.has(id)) continue;
      this.pump(id); // a job held by its old model's limit runs now
      if (this.running.has(id)) continue;
      const a = this.fm.agent(id);
      if (this.holdFor(id)) this.showHold(id);
      else if (a?.state === 'blocked' && / limit - resumes /.test(a.activity)) this.fm.setAgent(id, { state: 'idle', activity: 'ready' });
    }
    this.tick();

    // the line for the player: "Juniper now runs Sonnet (high) from the next turn; saved"
    const parts: string[] = [];
    const ids = plan.map((p) => p.id);
    for (const members of byModel.values()) {
      const i = after.get(members[0]!)!;
      const one = members.length === 1;
      parts.push(
        reset
          ? `${this.names(members)} ${one ? 'is' : 'are'} back on the configured model, ${this.modelText(i)}, from the next turn`
          : `${this.names(members)} now ${one ? 'runs' : 'run'} ${this.modelText(i)} from the next turn`,
      );
    }
    const unchanged = ids.filter((id) => !changed.includes(id));
    if (unchanged.length) {
      const i = after.get(unchanged[0]!)!;
      const sameModel = unchanged.every((id) => this.modelText(after.get(id)!) === this.modelText(i));
      parts.push(`${this.names(unchanged)} already ${unchanged.length === 1 ? 'runs' : 'run'} ${sameModel ? this.modelText(i) : 'that model'}`);
    }
    // as they are now: the lead may run its fallback for the new model's limit, a limit may hold some
    const now = new Map(ids.map((id) => [id, this.modelInfo(id)]));
    const waits = new Map<string, string[]>();
    for (const id of ids) {
      const i = now.get(id)!;
      const f = this.st.leadFallback;
      const hold = this.holdFor(id);
      if (i.fallback && f) parts.push(`${i.name} runs ${i.fallback} meanwhile, while the ${capital(f.family)} limit lasts (until ${clock(f.until)})`);
      else if (hold) {
        const what = `the ${hold.family ? `${capital(hold.family)} limit` : 'usage limit'} (until ${clock(hold.until)})`;
        waits.set(what, [...(waits.get(what) ?? []), id]);
      }
    }
    for (const [what, members] of waits) parts.push(`${this.names(members)} ${members.length === 1 ? 'waits' : 'wait'} for ${what}`);
    // "Kit unchanged (on Codex)"
    for (const [why, members] of refused) parts.push(`${this.names(members)} unchanged (on ${why.slice(0, why.indexOf(':'))})`);
    // config.json already held it when nothing had to be written
    if (writes.length) parts.push(saveError ? `NOT saved: ${saveError}` : 'saved');
    const text = parts.join('; ');
    return {
      agents: [...ids.map((id) => now.get(id)!), ...skipped.map((s) => this.modelInfo(s.agentId))],
      text,
      changed,
      ...(skipped.length ? { skipped } : {}),
      saved: !saveError,
      ...(saveError ? { saveError } : {}),
    };
  }

  /** /model changed the lead's own model: a fallback for its previous model no longer applies; the new model's own limit, if in force, applies now. */
  private leadModelChanged(): void {
    const f = this.st.leadFallback;
    if (f && f.model !== this.leadModel()) {
      delete this.st.leadFallback;
      this.fm.store.markDirty();
      this.fm.log.info(`${this.fm.nameOf(LEAD)} now runs ${this.leadModel()}: the fallback for ${f.model} (${capital(f.family)} limit) no longer applies`);
    }
    const family = this.leadOwnFamily();
    if (family && this.modelLimit(family)) this.syncLeadFallback(family);
    this.armLimitTimer();
  }

  // ---- user intents -------------------------------------------------------------------------

  /** `note`: extra instructions for the agent only (not shown in the feed). */
  onUserMessage(to: string, text: string, note?: string): void {
    const id = to === 'all' ? LEAD : to;
    const a = this.fm.agent(id);
    if (!a) return;
    if (this.isStopped(id)) {
      // stays unread; delivered when the user resumes the agent (deliverPending)
      this.fm.bus.send(id, 'user', `(${this.fm.nameOf(id)} is off shift - /resume @${id} to bring them back; your message is queued.)`);
      return;
    }
    // in a turn: delivered with its next agentcraft tool result, or right after the turn ends
    // (deliverPending). Paused mid-turn: delivered with the resumed job's prompt.
    if (this.running.has(id) || this.pausedJobs.has(id)) return;
    // every unread message from the user to this agent goes into one follow-up
    const mine = this.fm.bus.inbox(id).filter((m) => m.from === 'user' && (m.to === id || (to === 'all' && m.to === 'all')));
    const body = mine.length ? mine.map((m) => m.text).join('\n\n') : text;
    const consume = () => this.fm.bus.markRead(id, mine.map((m) => m.id));
    const prompt = `Message from ${userName()}: ${body}\n\n${note ? `${note}\n\n` : ''}Respond briefly with send_message(to "user") and act on it if needed (lead: create or update tasks; worker: adjust your work).`;
    if (id === LEAD) {
      const goal = this.fm.currentGoal();
      if (!goal) {
        consume();
        this.fm.bus.send(LEAD, 'user', 'No goal yet - type one in the console and I will plan it.');
        return;
      }
      consume();
      this.enqueue({ kind: 'followup', agentId: LEAD, goalId: goal.id, sessionKey: `${LEAD}:${goal.id}`, prompt });
      return;
    }
    const t = this.fm.tasks.list().filter((x) => x.assignee === id && (x.status === 'doing' || x.status === 'review')).pop();
    consume();
    if (!t) {
      this.fm.bus.send(id, 'user', 'I am not on a task right now - Marlow will pick that up.');
      this.fm.bus.send('user', LEAD, `(for ${this.fm.nameOf(id)}) ${body}`);
      const name = this.fm.nameOf(id);
      this.onUserMessage(
        LEAD,
        `(originally for ${name}) ${body}`,
        `${name} is not on a task, and workers only read messages while they work on one. If this needs ${name} to do something, create a task for it with create_task (assignee "${id}"); a send_message alone will not reach ${name}.`,
      );
      return;
    }
    this.enqueue({ kind: 'followup', agentId: id, taskId: t.id, ...(t.goalId ? { goalId: t.goalId } : {}), sessionKey: `${id}:${t.id}`, prompt });
  }

  onDecisionSettled(d: Decision): void {
    if (d.kind === 'question') {
      // in-process ask_user waiters resolve by themselves; after a restart nobody waits -> resume
      if (!this.waitingUser.has(d.agentId) && !this.running.has(d.agentId) && !this.isStopped(d.agentId)) {
        const ans = [d.answer?.option, d.answer?.text].filter(Boolean).join(' — ') || '(cancelled)';
        const inf = this.st.inflight[d.agentId];
        const t = d.taskId ? this.fm.tasks.get(d.taskId) : undefined;
        const goalId = inf?.goalId ?? t?.goalId ?? (d.agentId === LEAD ? this.fm.currentGoal()?.id : undefined);
        const sessionKey = inf?.sessionKey ?? (d.agentId === LEAD ? `${LEAD}:${goalId ?? 'adhoc'}` : t ? `${d.agentId}:${t.id}` : undefined);
        if (sessionKey) {
          // keep the interrupted job's kind, so its after-turn step (e.g. plan -> active) still runs
          this.enqueue({
            kind: inf?.kind ?? 'followup',
            agentId: d.agentId,
            sessionKey,
            resumed: true,
            ...(t ? { taskId: t.id } : inf?.taskId ? { taskId: inf.taskId } : {}),
            ...(goalId ? { goalId } : {}),
            prompt: `Earlier you asked ${userName()}: "${d.question}". ${userName()} answered: ${ans}. (Your ask_user call was interrupted by an orchestrator restart.) Continue.`,
          });
        }
      }
      return;
    }
    if (d.kind === 'merge' && d.taskId) {
      const t = this.fm.tasks.get(d.taskId);
      if (!t) return;
      if (d.answer?.option === 'Merge' && t.status === 'done') {
        if (t.assignee && this.fm.agent(t.assignee)?.taskId === t.id) this.fm.setAgent(t.assignee, { state: 'idle', station: 'lounge', activity: `${t.id} merged`, taskId: null, worktree: null });
        if (!this.isStopped(LEAD)) this.fm.setAgent(LEAD, { state: 'idle', station: 'meeting', activity: 'watching the task wall' });
        const g = t.goalId ? this.fm.goal(t.goalId) : undefined;
        if (g && this.fm.tasks.goalComplete(g.id)) {
          this.fm.bus.send(LEAD, 'user', `Everything for "${truncate(g.text, 80)}" is merged. Nice working with you.`);
          for (const w of this.team) if (!this.isStopped(w)) this.fm.setAgent(w, { state: 'done', station: 'lounge', activity: 'goal done' });
          if (!this.isStopped(LEAD)) this.fm.setAgent(LEAD, { state: 'done', station: 'meeting', activity: 'goal done' });
        }
        this.tick();
      } else if (d.answer?.option === 'Request changes') {
        this.sendBackToWorker(t.id, `${userName()} reviewed ${t.id} and requested changes:\n${d.answer.text ?? '(no details given - ask_user if unclear)'}\n\nMake the changes, re-run the tests, then update_task("${t.id}", status "review", summary).`);
      } else if (d.answer?.option === 'Reject') {
        if (t.assignee && this.fm.agent(t.assignee)?.taskId === t.id) this.fm.setAgent(t.assignee, { state: 'idle', station: 'lounge', activity: `${t.id} rejected`, taskId: null, worktree: null });
        this.tick();
      }
    }
  }

  onMergeConflict(task: Task, info: { base: string; branch: string; files: string[]; reason: string }): boolean {
    if (!task.assignee || this.isStopped(task.assignee)) return false; // the user decides (decision stays open)
    const files = info.files.length ? info.files.join(', ') : '(see git status)';
    this.sendBackToWorker(
      task.id,
      `${userName()} approved merging ${task.id}, but ${info.branch} now conflicts with ${info.base} (other work was merged into ${info.base} after you started) in: ${files}.\n` +
        `In your worktree run \`git merge ${info.base}\`, resolve every conflict so that both sides' changes are kept, run the tests, and commit the merge (git commit --no-edit). ` +
        `Do not rebase, reset or check out other branches. Then update_task("${task.id}", status "review", summary).`,
    );
    return true;
  }

  onTaskAction(task: Task, action: 'reassign' | 'cancel' | 'retry' | 'prioritize'): void {
    if (action === 'cancel' || action === 'reassign') {
      for (const [id, r] of this.running) {
        if (r.job.taskId === task.id && id !== LEAD && (action === 'cancel' || task.assignee !== id)) this.abortTurn(r, 'cancel');
      }
      for (const [id, q] of this.queues) this.queues.set(id, q.filter((j) => j.taskId !== task.id || (action === 'reassign' && task.assignee === id)));
      // a paused or persisted continuation of the task would bring it back on resume / restart
      for (const [id, job] of this.pausedJobs) {
        if (job.taskId === task.id && (action === 'cancel' || (id !== LEAD && task.assignee !== id))) this.pausedJobs.delete(id);
      }
      for (const [id, job] of Object.entries(this.st.inflight)) {
        if (job.taskId === task.id && (action === 'cancel' || (id !== LEAD && task.assignee !== id))) delete this.st.inflight[id];
      }
      this.fm.store.markDirty();
      // reassigned: the new worker continues from the old worker's branch once that turn is over
      if (action === 'reassign' && task.repoId && task.worktree) {
        const wt = this.fm.repos.findWorktree(task.repoId, task.worktree);
        if (wt && wt.status === 'active' && wt.agentId !== task.assignee) this.handOff(task.id, wt.agentId, `reassigned to ${this.fm.nameOf(task.assignee ?? 'user')}`);
      }
    }
    this.tick();
  }

  /** Withdraw an agent's open questions and permission prompts (not merge decisions: those are the user's). */
  private withdrawDecisions(agentId: string, why: string): void {
    for (const d of this.fm.decisions.open().filter((x) => x.agentId === agentId && x.kind !== 'merge')) this.fm.decisions.cancel(d.id, why);
  }

  async onAgentAction(agentId: string, action: 'pause' | 'resume' | 'stop' | 'spawn'): Promise<void> {
    const r = this.running.get(agentId);
    const name = this.fm.nameOf(agentId);
    if (action === 'pause') {
      if (r) this.abortTurn(r, 'pause');
      this.fm.setAgent(agentId, { state: 'idle', activity: 'paused' });
    } else if (action === 'resume' || action === 'spawn') {
      const wasStopped = this.isStopped(agentId);
      if (action === 'spawn' && agentId !== LEAD && !this.cfg.workers.includes(agentId)) this.cfg.workers.push(agentId);
      if (wasStopped || action === 'spawn') {
        this.setStopped(agentId, false);
        this.fm.setAgent(agentId, { active: true, paused: false, state: 'idle', station: 'lounge', activity: 'ready' });
        if (wasStopped) this.fm.bus.feed('system', `${name} is back on shift`, { agentId });
      }
      const job = this.pausedJobs.get(agentId);
      this.pausedJobs.delete(agentId);
      if (job) this.enqueue(job);
      else this.pump(agentId);
      if (agentId === LEAD && wasStopped) this.reconcile();
      // messages the user sent while the agent was off shift or paused
      this.deliverPending(agentId);
    } else if (action === 'stop') {
      this.setStopped(agentId, true);
      if (r) this.abortTurn(r, 'stop');
      this.queues.delete(agentId);
      this.pausedJobs.delete(agentId);
      delete this.st.inflight[agentId];
      this.withdrawDecisions(agentId, `${name} was stopped`);
      if (agentId !== LEAD) {
        for (const t of this.fm.tasks.list().filter((x) => x.assignee === agentId && x.status === 'doing')) {
          // back on the board, but held until the agent's turn is really over and its work is committed;
          // the next worker then continues from this branch
          this.fm.tasks.setStatus(t.id, 'todo', { force: true });
          this.fm.tasks.update(t.id, { assignee: null });
          this.fm.bus.feed('task', `${t.id} is back on the board (${name} was stopped)`, { agentId: 'user' });
          this.handOff(t.id, agentId, `${name} was stopped`);
        }
      }
      this.fm.setAgent(agentId, { active: false, paused: false, state: 'idle', station: 'lounge', activity: 'stopped - off shift', taskId: null, worktree: null });
    }
    // who waits for a model's own limit may have changed (an agent on that model went off / on shift)
    if (Object.values(this.st.modelLimits ?? {}).some((l) => Date.now() < l.until)) this.showBanner();
    this.tick();
  }
}
