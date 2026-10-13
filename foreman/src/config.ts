// Configuration: defaults < <AGENTCRAFT_HOME>/config.json < environment < CLI flags. A per-agent
// model (claude.agentModels / codex.agentModels, which the console's /model writes) beats the lead's
// and workers' models and effort for that agent.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { readJson, writeJsonAtomic } from './util/fsx.js';
import type { BackendName } from './protocol.js';
import { defaultUserName } from './user.js';
import type { EffortLevel } from '@anthropic-ai/claude-agent-sdk';
import { parseContextConfig, type AgentContextConfig } from './agents/claude/context.js';
import { DEFAULT_PERMISSIONS, type PermissionsConfig } from './agents/claude/permissions.js';
import { DEFAULT_SUBAGENTS, type SubagentsConfig } from './agents/claude/subagents.js';
import { CODEX_EFFORTS, modelProblem, normalizeModel } from './agents/models.js';
import { LEAD_ID, WORKER_IDS } from './cast.js';

export const FOREMAN_VERSION = '0.1.0';

/** Repo root of the AgentCraft project (foreman/src/config.ts -> ../..). */
export const PROJECT_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');

/**
 * What the lead runs while a usage limit of its own model is in force (claude.ai login): e.g. a
 * Fable lead continues on Opus when the Fable weekly limit is reached, instead of waiting for it.
 */
export interface LeadFallback {
  model: string;
  /** default: the lead's effort */
  effort?: EffortLevel;
}

/**
 * One agent's own model, and optionally its effort (config.json claude.agentModels.<agent> /
 * codex.agentModels.<agent>, set with the console's /model): it beats the lead's or workers' model
 * and effort for that agent. Without an effort the agent keeps its role's effort.
 */
export interface AgentModel<E extends string = string> {
  model: string;
  effort?: E;
}

/** Whether the lead's / workers' model is the built-in default or set (config.json, environment or a flag). */
export type ModelOrigin = 'default' | 'config';

export interface ClaudeConfig {
  leadModel: string;
  workerModel: string;
  effort: EffortLevel;
  leadEffort: EffortLevel;
  /** claude.agentModels: per-agent model and effort, beating leadModel/workerModel/effort for that agent */
  agentModels: Record<string, AgentModel<EffortLevel>>;
  /** where leadModel / workerModel came from (the console's /model shows it) */
  modelOrigin: { lead: ModelOrigin; worker: ModelOrigin };
  /** claude.leadFallback: the lead's model while its own model's usage limit is in force (default: none, the lead waits) */
  leadFallback?: LeadFallback;
  maxTurnsLead: number;
  maxTurnsWorker: number;
  /** max workers running a turn at the same time */
  maxConcurrent: number;
  /** max workers while the plan reports a usage warning (claude.ai login), until that window resets */
  throttleConcurrent: number;
  /** worker ids in the team (subset of the cast) */
  workers: string[];
  /** test command for CI after a worker finishes (default: detect, e.g. `npm test`) */
  ciCommand?: string;
  /** per-turn budget cap passed to the SDK */
  maxBudgetUsdPerTurn?: number;
  /** resume interrupted sessions on Foreman start */
  resumeOnStart: boolean;
  /** lead reviews each finished task before the merge decision reaches the user */
  leadReview: boolean;
  /**
   * Use the local `claude` CLI's claude.ai login instead of an API key / cloud provider. Personal use
   * only: Anthropic does not allow third-party tools to offer claude.ai login (see agents/claude/auth.ts).
   */
  useClaudeLogin: boolean;
  /** command prefixes the lead runs without asking, e.g. `bd show` */
  leadReadCommands: string[];
  /** poll the plan usage for the in-game display (claude.ai login: one `claude` process per poll) */
  usagePoll: boolean;
  /** permission mode, guardrails and Claude Code permission rules (config.json claude.permissions) */
  permissions: PermissionsConfig;
  /** Claude Code subagents for the agents (config.json claude.subagents) */
  subagents: SubagentsConfig;
  /** Claude Code context for the agents: instruction files, skills, MCP servers (config.json claude.context) */
  context: AgentContextConfig;
}

export type EngineName = 'claude' | 'codex';
export const ENGINE_NAMES: readonly EngineName[] = ['claude', 'codex'];

/** Codex engine (`codex app-server`): models and effort default to your Codex config. */
export interface CodexConfig {
  /** the codex CLI (default: `codex` on PATH, else the one inside the Codex desktop app) */
  path?: string;
  leadModel?: string;
  workerModel?: string;
  /** model_reasoning_effort for workers / the lead (low, medium, high, xhigh...) */
  effort?: string;
  leadEffort?: string;
  /** codex.agentModels: per-agent model and effort for Codex agents, beating the models and effort above */
  agentModels: Record<string, AgentModel>;
}

/** Which engine runs the lead, the workers, and per-agent exceptions (`--engines kit=codex`). */
export interface EngineChoice {
  lead: EngineName;
  worker: EngineName;
  byAgent: Record<string, EngineName>;
}

export type ShowcaseCheckpoint = 'showcase' | 'showcase-late';

export interface SimConfig {
  speed: number;
  seed: number;
  showcase: boolean;
  /** which static state --showcase holds: the busy mid-run state (default) or `--showcase late` */
  showcaseAt: ShowcaseCheckpoint;
  /** sim answers its own decisions (first option) after a short delay — for unattended runs/tests */
  autoAnswer: boolean;
  /** extra idle log lines while waiting on the user */
  ambient: boolean;
}

export interface Config {
  backend: BackendName;
  /** the person the team works for (prompts, feed, UI); default: the OS user name */
  userName: string;
  home: string;
  profile: string;
  /** profile directory: <home>/<profile> */
  dataDir: string;
  host: string;
  port: number;
  repos: string[];
  goal?: string;
  autostart: boolean;
  reset: boolean;
  notify: boolean;
  toastSilent: boolean;
  debug: boolean;
  quiet: boolean;
  projectRoot: string;
  /** reject WebSocket upgrades that carry a browser Origin (CSRF-style protection) */
  allowBrowserOrigins: boolean;
  /** how often the main checkouts are polled for head/dirty changes (ms) */
  repoPollMs: number;
  /** approved merges: a merge commit (keeps the agents' commits) or one squashed commit */
  mergeStyle: 'merge' | 'squash';
  /** sign approved merge commits when the repo's own git config says commit.gpgsign=true */
  signMerges: boolean;
  /** team settings (workers, CI, review, lead read commands) and the Claude engine's options */
  claude: ClaudeConfig;
  codex: CodexConfig;
  engines: EngineChoice;
  sim: SimConfig;
}

type Flags = Record<string, string | string[] | boolean>;

export function parseFlags(argv: string[]): { flags: Flags; positional: string[] } {
  const flags: Flags = {};
  const positional: string[] = [];
  const setFlag = (key: string, value: string | boolean): void => {
    const previous = flags[key];
    // Launchers pass one --repo per checkout. All other flags retain last-value wins.
    if (key === 'repo' && typeof value === 'string' && (typeof previous === 'string' || Array.isArray(previous))) {
      flags[key] = [...(Array.isArray(previous) ? previous : [previous]), value];
    } else flags[key] = value;
  };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i]!;
    if (a === '--') {
      positional.push(...argv.slice(i + 1));
      break;
    }
    if (!a.startsWith('--')) {
      positional.push(a);
      continue;
    }
    const eq = a.indexOf('=');
    if (eq > 0) {
      setFlag(a.slice(2, eq), a.slice(eq + 1));
      continue;
    }
    const key = a.slice(2);
    if (key.startsWith('no-')) {
      setFlag(key.slice(3), false);
      continue;
    }
    const next = argv[i + 1];
    if (next !== undefined && !next.startsWith('--')) {
      setFlag(key, next);
      i++;
    } else setFlag(key, true);
  }
  return { flags, positional };
}

function num(v: unknown, d: number): number {
  if (v === undefined || v === '' || v === true) return d;
  const n = Number(v);
  return Number.isFinite(n) ? n : d;
}

function bool(v: unknown, d: boolean): boolean {
  if (v === undefined) return d;
  if (typeof v === 'boolean') return v;
  return !/^(0|false|no|off)$/i.test(String(v));
}

function str(v: unknown): string | undefined {
  return typeof v === 'string' && v.length ? v : undefined;
}

/** Comma list (flag, env) or array (config.json). */
function list(v: unknown): string[] {
  const items = Array.isArray(v) ? v.map(String) : typeof v === 'string' ? v.split(',') : [];
  return items.map((s) => s.trim()).filter(Boolean);
}

/** Programs that can write, run other code or reach the network: never declarable as lead "read" commands. */
const NOT_READ_ONLY = new Set([
  'git', 'rm', 'mv', 'cp', 'tee', 'dd', 'sed', 'awk', 'find', 'xargs', 'env', 'sudo', 'sh', 'bash', 'zsh',
  'node', 'npm', 'npx', 'python', 'python3', 'pip', 'perl', 'ruby', 'curl', 'wget', 'ssh', 'scp', 'eval', 'exec',
  'cmd', 'powershell', 'pwsh', 'touch', 'mkdir', 'chmod', 'kill',
]);

/** Each entry is a bare program name plus plain words ("bd show"): no paths, shell syntax, or writers/interpreters. */
function readCommands(v: unknown): string[] {
  const entries = list(v);
  for (const e of entries) {
    const [head = '', ...words] = e.split(/\s+/);
    if (!/^[A-Za-z0-9_.+-]+$/.test(head) || !words.every((w) => /^[A-Za-z0-9_.:@+=-]+$/.test(w))) {
      throw new Error(`bad lead read command "${e}" (use a bare program name and plain words, like "bd show")`);
    }
    if (NOT_READ_ONLY.has(head.toLowerCase().replace(/\.(exe|cmd|bat)$/, ''))) {
      throw new Error(`lead read command "${e}" is not allowed: "${head}" can write files, run code or use the network`);
    }
  }
  return entries;
}

function stringList(v: unknown): string[] {
  return Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string' && x.trim().length > 0) : [];
}

function permissionsConfig(v: unknown): PermissionsConfig {
  const o = (v && typeof v === 'object' ? v : {}) as Record<string, unknown>;
  const mode = o.mode ?? DEFAULT_PERMISSIONS.mode;
  if (mode !== 'policy' && mode !== 'auto') throw new Error(`unknown permissions mode "${String(mode)}" (use policy or auto)`);
  return {
    mode,
    allow: stringList(o.allow),
    deny: stringList(o.deny),
    ask: stringList(o.ask),
    webTools: typeof o.webTools === 'boolean' ? o.webTools : DEFAULT_PERMISSIONS.webTools,
    protectCheckouts: typeof o.protectCheckouts === 'boolean' ? o.protectCheckouts : DEFAULT_PERMISSIONS.protectCheckouts,
  };
}

function subagentsConfig(v: unknown): SubagentsConfig {
  const o = (v && typeof v === 'object' ? v : {}) as Record<string, unknown>;
  return { enabled: typeof o.enabled === 'boolean' ? o.enabled : DEFAULT_SUBAGENTS.enabled, agents: stringList(o.agents) };
}

/**
 * Agent context lives under claude.context only. These top-level keys (an earlier, separate config
 * surface) are refused with their new place, rather than silently giving agents less than configured.
 */
const MOVED_CLAUDE_KEYS: Record<string, string> = {
  repoContext: 'claude.context.repoInstructions (on by default)',
  instructionsFile: 'claude.context.files',
  mcpServers: 'claude.context.mcpServers',
  mcpAllow: 'claude.context.mcpAllow',
};

function contextConfig(fileClaude: Record<string, unknown>, home: string): AgentContextConfig {
  for (const [key, to] of Object.entries(MOVED_CLAUDE_KEYS)) {
    if (key in fileClaude) throw new Error(`config.json claude.${key} is not a setting: use ${to}`);
  }
  return parseContextConfig(fileClaude.context, home);
}

function mergeStyle(v: unknown): 'merge' | 'squash' {
  if (v === undefined || v === 'merge') return 'merge';
  if (v === 'squash') return 'squash';
  throw new Error(`unknown merge style "${String(v)}" (use merge or squash)`);
}

const EFFORTS: EffortLevel[] = ['low', 'medium', 'high', 'xhigh', 'max'];
function effort(v: unknown, d: EffortLevel): EffortLevel {
  if (v === undefined) return d;
  if (typeof v === 'string' && (EFFORTS as string[]).includes(v)) return v as EffortLevel;
  throw new Error(`unknown effort "${String(v)}" (use ${EFFORTS.join(', ')})`);
}

/**
 * claude.leadFallback {model, effort?} < AGENTCRAFT_LEAD_FALLBACK_MODEL / _EFFORT < --lead-fallback-model /
 * --lead-fallback-effort, each field on its own. `--no-lead-fallback-model` turns off a fallback set elsewhere.
 */
function leadFallback(flags: Flags, env: NodeJS.ProcessEnv, file: unknown): LeadFallback | undefined {
  if (file !== undefined && (file === null || typeof file !== 'object' || Array.isArray(file))) {
    throw new Error('config.json claude.leadFallback must be an object like {"model": "claude-opus-5-5", "effort": "xhigh"}');
  }
  const f = (file ?? {}) as Record<string, unknown>;
  if (f.model !== undefined && (typeof f.model !== 'string' || !f.model.trim())) throw new Error('config.json claude.leadFallback.model must be a model id or alias, e.g. "claude-opus-5-5"');
  const off = flags['lead-fallback-model'] === false;
  const model = off ? undefined : (str(flags['lead-fallback-model']) ?? str(env.AGENTCRAFT_LEAD_FALLBACK_MODEL) ?? str(f.model))?.trim();
  // `--no-lead-fallback-effort`: the lead's own effort
  const effortFlag = flags['lead-fallback-effort'];
  const effortRaw = effortFlag === false ? undefined : (effortFlag ?? str(env.AGENTCRAFT_LEAD_FALLBACK_EFFORT) ?? f.effort);
  // validated even when no model is set, so a typo never goes unnoticed
  const fallbackEffort = effortRaw === undefined ? undefined : effort(effortRaw, 'medium');
  if (!model) {
    if (fallbackEffort && !off) {
      throw new Error('a lead fallback effort needs a fallback model (--lead-fallback-model, AGENTCRAFT_LEAD_FALLBACK_MODEL or config.json claude.leadFallback.model)');
    }
    return undefined;
  }
  return { model, ...(fallbackEffort ? { effort: fallbackEffort } : {}) };
}

/** Every agent id (the cast is fixed: the lead and five workers). */
export const AGENT_IDS: readonly string[] = [LEAD_ID, ...WORKER_IDS];

const isObject = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v);

/**
 * claude.agentModels / codex.agentModels: {"juniper": {"model": "sonnet", "effort": "high"}}. Each
 * key is an agent id; each model must be one its engine runs (a Claude alias or id under claude, a
 * non-Claude model under codex); an effort must be one the engine knows. A mistake stops the start,
 * like any other bad setting, rather than an agent failing its first turn.
 */
function agentModelsConfig<E extends string>(v: unknown, engine: 'claude' | 'codex', efforts: readonly E[]): Record<string, AgentModel<E>> {
  if (v === undefined) return {};
  const where = `config.json ${engine}.agentModels`;
  if (!isObject(v)) throw new Error(`${where} must be an object like {"juniper": {"model": "${engine === 'claude' ? 'sonnet' : 'gpt-5.1-codex'}", "effort": "high"}}`);
  const out: Record<string, AgentModel<E>> = {};
  for (const [agent, entry] of Object.entries(v)) {
    if (!AGENT_IDS.includes(agent)) throw new Error(`${where}: no agent "${agent}" (use ${AGENT_IDS.join(', ')})`);
    if (!isObject(entry) || typeof entry.model !== 'string' || !entry.model.trim()) throw new Error(`${where}.${agent} must be like {"model": "${engine === 'claude' ? 'sonnet' : 'gpt-5.1-codex'}", "effort": "high"}`);
    const model = entry.model.trim();
    const problem = modelProblem(engine, model);
    if (problem === 'invalid') throw new Error(`${where}.${agent}.model "${model}" is not a model name`);
    if (problem === 'not-claude') throw new Error(`${where}.${agent}.model "${model}" is not a Claude model (use opus, sonnet, haiku, fable or a claude-... id)`);
    if (problem === 'claude') throw new Error(`${where}.${agent}.model "${model}" is a Claude model: Codex agents run Codex models (put it under claude.agentModels)`);
    const effort = entry.effort;
    if (effort !== undefined && (typeof effort !== 'string' || !(efforts as readonly string[]).includes(effort))) {
      throw new Error(`${where}.${agent}.effort "${String(effort)}" is not an effort (use ${efforts.join(', ')})`);
    }
    // a hand-written "Sonnet" runs as the alias "sonnet"
    out[agent] = { model: normalizeModel(engine, model), ...(effort !== undefined ? { effort: effort as E } : {}) };
  }
  return out;
}

/** A Claude agent's model and effort: its own (claude.agentModels), else its role's. */
export function claudeModelFor(cfg: ClaudeConfig, agentId: string | undefined, role: 'lead' | 'worker'): { model: string; effort: EffortLevel } {
  const own = agentId ? cfg.agentModels[agentId] : undefined;
  return { model: own?.model ?? (role === 'lead' ? cfg.leadModel : cfg.workerModel), effort: own?.effort ?? (role === 'lead' ? cfg.leadEffort : cfg.effort) };
}

/** One agent's model change for config.json: its new model, or undefined to remove its own model. */
export interface AgentModelChange {
  section: 'claude' | 'codex';
  agentId: string;
  value: AgentModel | undefined;
}

/**
 * Write per-agent models into <home>/config.json (`<section>.agentModels.<agent>`), keeping every
 * other key and its order; the file is rewritten atomically (a temp file, then a rename) with two-space
 * JSON. Only the given agents' entries change: nothing else from the running config (flags, the
 * environment, anything secret) is ever written. An empty agentModels is removed again; with nothing
 * to change (removing an entry that is not there) the file is not touched. Throws when the file is not
 * a JSON object (it is left as it is then). Returns the file.
 */
export function saveAgentModels(home: string, changes: AgentModelChange[]): string {
  const link = path.join(home, 'config.json');
  const exists = fs.existsSync(link);
  // a config.json that is a symlink stays one: its target is rewritten
  const file = exists ? fs.realpathSync(link) : link;
  const raw = exists ? fs.readFileSync(file, 'utf8') : '';
  let doc: unknown = {};
  if (raw.trim()) {
    try {
      doc = JSON.parse(raw);
    } catch (e) {
      throw new Error(`config.json is not valid JSON (${(e as Error).message}); fix it, then try again`);
    }
  }
  if (!isObject(doc)) throw new Error('config.json is not a JSON object');
  let dirty = false;
  for (const c of changes) {
    let section = doc[c.section];
    if (section === undefined) {
      if (!c.value) continue;
      section = doc[c.section] = {};
    }
    if (!isObject(section)) throw new Error(`config.json "${c.section}" is not an object`);
    let models = section.agentModels;
    if (models === undefined) {
      if (!c.value) continue;
      models = section.agentModels = {};
    }
    if (!isObject(models)) throw new Error(`config.json ${c.section}.agentModels is not an object`);
    if (c.value) models[c.agentId] = { model: c.value.model, ...(c.value.effort ? { effort: c.value.effort } : {}) };
    else if (c.agentId in models) delete models[c.agentId];
    else continue;
    dirty = true;
    if (!Object.keys(models).length) delete section.agentModels;
  }
  if (!dirty) return file;
  const mode = exists ? fs.statSync(file).mode & 0o777 : undefined;
  writeJsonAtomic(file, doc);
  // the rename leaves the umask's mode: keep the file's own (POSIX)
  if (mode !== undefined && process.platform !== 'win32') fs.chmodSync(file, mode);
  return file;
}

/** Every flag loadConfig reads (the `no-` prefix is stripped by parseFlags). */
export const KNOWN_FLAGS = new Set([
  'home', 'backend', 'profile', 'user-name', 'use-claude-login', 'repo', 'workers', 'model', 'port', 'goal', 'autostart', 'reset', 'notify',
  'toast-silent', 'debug', 'quiet', 'allow-browser-origins', 'repo-poll-ms', 'merge-style', 'sign-merges',
  'lead-model', 'worker-model', 'effort', 'lead-effort', 'lead-fallback-model', 'lead-fallback-effort', 'max-turns', 'max-turns-lead', 'max-turns-worker',
  'max-concurrent', 'throttle-concurrent', 'ci', 'max-budget', 'resume', 'lead-review', 'speed', 'seed', 'showcase', 'auto-answer',
  'ambient', 'lead-read-commands', 'lead-engine', 'worker-engine', 'engines', 'codex-path', 'codex-model', 'codex-lead-model',
  'codex-worker-model', 'codex-effort', 'codex-lead-effort', 'usage',
]);

function engineName(v: unknown, d: EngineName, what: string): EngineName {
  if (v === undefined || v === '') return d;
  if (typeof v === 'string' && (ENGINE_NAMES as string[]).includes(v)) return v as EngineName;
  throw new Error(`unknown ${what} "${String(v)}" (use ${ENGINE_NAMES.join(' or ')})`);
}

/** "kit=codex,wren=claude" (flag/env) or {"kit": "codex"} (config.json) */
function engineMap(v: unknown): Record<string, EngineName> {
  const pairs: Array<[string, unknown]> =
    v && typeof v === 'object' && !Array.isArray(v) ? Object.entries(v as Record<string, unknown>) : list(v).map((e) => e.split('=').map((x) => x.trim()) as [string, string]);
  const out: Record<string, EngineName> = {};
  for (const [agent, engine] of pairs) {
    if (!agent || !/^[a-z0-9_-]+$/i.test(agent)) throw new Error(`bad --engines entry "${agent}=${String(engine)}" (use agent=engine, e.g. kit=codex)`);
    if (engine === undefined || engine === '') throw new Error(`no engine for ${agent} in --engines (use agent=engine, e.g. ${agent}=codex)`);
    out[agent.toLowerCase()] = engineName(engine, 'claude', `engine for ${agent}`);
  }
  return out;
}

/**
 * Unknown flags and stray positionals are errors, not silently ignored: a mistyped or mangled flag
 * (e.g. PowerShell passing `--workers,kit,--model,sonnet` as ONE argument) would otherwise start a
 * real claude team on the expensive defaults (opus lead, medium effort, three workers).
 */
function checkArgs(flags: Flags, positional: string[]): void {
  const unknown = Object.keys(flags).filter((k) => !KNOWN_FLAGS.has(k));
  if (unknown.length) {
    throw new Error(`unknown option${unknown.length > 1 ? 's' : ''} ${unknown.map((k) => `"--${k}"`).join(', ')} (see --help)`);
  }
  if (positional.length) throw new Error(`unexpected argument "${positional[0]}" (options start with --; see --help)`);
}

export function loadConfig(argv: string[], env: NodeJS.ProcessEnv = process.env): Config {
  const { flags, positional } = parseFlags(argv);
  checkArgs(flags, positional);
  const home = path.resolve(str(flags.home) ?? env.AGENTCRAFT_HOME ?? path.join(os.homedir(), '.agentcraft'));
  const file = readJson<Record<string, unknown>>(path.join(home, 'config.json')) ?? {};
  const fileClaude = (file.claude ?? {}) as Record<string, unknown>;
  const fileSim = (file.sim ?? {}) as Record<string, unknown>;
  const fileCodex = (file.codex ?? {}) as Record<string, unknown>;
  const pick = (k: string, envKey?: string): unknown => flags[k] ?? (envKey ? env[envKey] : undefined) ?? file[k];

  const backendRaw = String(pick('backend', 'AGENTCRAFT_BACKEND') ?? 'claude');
  if (backendRaw !== 'sim' && backendRaw !== 'claude' && backendRaw !== 'codex') throw new Error(`unknown backend "${backendRaw}" (use sim, claude or codex)`);
  const backend = backendRaw as BackendName;
  const profile = str(pick('profile', 'AGENTCRAFT_PROFILE')) ?? backend;
  if (!/^[a-zA-Z0-9_-]+$/.test(profile)) throw new Error(`bad profile name "${profile}"`);

  const repoFlag = flags.repo;
  const repos: string[] = [];
  if (typeof repoFlag === 'string' || Array.isArray(repoFlag)) {
    const values = Array.isArray(repoFlag) ? repoFlag : [repoFlag];
    repos.push(...values.flatMap((value) => value.split(',').map((s) => s.trim()).filter(Boolean)));
  } else if (Array.isArray(file.repos)) repos.push(...(file.repos as string[]));

  const workersRaw = str(flags.workers) ?? env.AGENTCRAFT_WORKERS ?? (fileClaude.workers as string[] | string | undefined);
  const workers = Array.isArray(workersRaw)
    ? workersRaw
    : typeof workersRaw === 'string'
      ? /^\d+$/.test(workersRaw)
        ? ['juniper', 'kit', 'wren', 'rowan', 'tove'].slice(0, Math.max(1, Math.min(5, Number(workersRaw))))
        : workersRaw.split(',').map((s) => s.trim()).filter(Boolean)
      : ['juniper', 'kit', 'wren'];

  const model = str(flags.model);
  const leadModel = str(flags['lead-model']) ?? model ?? str(env.AGENTCRAFT_LEAD_MODEL) ?? str(fileClaude.leadModel);
  const workerModel = str(flags['worker-model']) ?? model ?? str(env.AGENTCRAFT_WORKER_MODEL) ?? str(fileClaude.workerModel);
  const fallback = leadFallback(flags, env, fileClaude.leadFallback);
  const cfg: Config = {
    backend,
    userName: (str(pick('user-name', 'AGENTCRAFT_USER_NAME')) ?? str(file.userName))?.trim().slice(0, 40) || defaultUserName(),
    home,
    profile,
    dataDir: path.join(home, profile),
    host: '127.0.0.1',
    port: num(pick('port', 'AGENTCRAFT_PORT'), 7878),
    repos,
    goal: str(flags.goal),
    autostart: bool(flags.autostart, false) || !!str(flags.goal),
    reset: bool(flags.reset, false),
    notify: bool(pick('notify', 'AGENTCRAFT_NOTIFY'), backend !== 'sim'),
    toastSilent: bool(pick('toast-silent', 'AGENTCRAFT_TOAST_SILENT'), false),
    debug: bool(pick('debug', 'AGENTCRAFT_DEBUG'), false),
    quiet: bool(flags.quiet, false),
    projectRoot: PROJECT_ROOT,
    allowBrowserOrigins: bool(pick('allow-browser-origins'), false),
    repoPollMs: Math.max(500, num(pick('repo-poll-ms'), 10_000)),
    mergeStyle: mergeStyle(pick('merge-style', 'AGENTCRAFT_MERGE_STYLE')),
    // the sim answers merges unattended (screenshot QA, --auto-answer): never sign there
    signMerges: bool(pick('sign-merges', 'AGENTCRAFT_SIGN_MERGES'), backend !== 'sim'),
    claude: {
      leadModel: leadModel ?? 'opus',
      workerModel: workerModel ?? 'sonnet',
      effort: effort(flags.effort ?? fileClaude.effort, 'medium'),
      leadEffort: effort(flags['lead-effort'] ?? flags.effort ?? fileClaude.leadEffort, 'medium'),
      agentModels: agentModelsConfig(fileClaude.agentModels, 'claude', EFFORTS),
      modelOrigin: { lead: leadModel ? 'config' : 'default', worker: workerModel ? 'config' : 'default' },
      ...(fallback ? { leadFallback: fallback } : {}),
      maxTurnsLead: num(flags['max-turns-lead'] ?? flags['max-turns'] ?? fileClaude.maxTurnsLead, 40),
      maxTurnsWorker: num(flags['max-turns-worker'] ?? flags['max-turns'] ?? fileClaude.maxTurnsWorker, 80),
      maxConcurrent: Math.max(1, num(flags['max-concurrent'] ?? fileClaude.maxConcurrent, 3)),
      throttleConcurrent: Math.max(1, num(flags['throttle-concurrent'] ?? fileClaude.throttleConcurrent, 1)),
      workers,
      ciCommand: str(flags.ci) ?? str(fileClaude.ciCommand),
      maxBudgetUsdPerTurn: flags['max-budget'] !== undefined ? num(flags['max-budget'], 0) || undefined : (fileClaude.maxBudgetUsdPerTurn as number | undefined),
      resumeOnStart: bool(flags.resume ?? fileClaude.resumeOnStart, true),
      leadReview: bool(flags['lead-review'] ?? fileClaude.leadReview, true),
      useClaudeLogin: bool(flags['use-claude-login'] ?? env.AGENTCRAFT_USE_CLAUDE_LOGIN ?? fileClaude.useClaudeLogin, false),
      leadReadCommands: readCommands(flags['lead-read-commands'] ?? env.AGENTCRAFT_LEAD_READ_COMMANDS ?? fileClaude.leadReadCommands),
      usagePoll: bool(flags.usage ?? fileClaude.usagePoll, true),
      permissions: permissionsConfig(fileClaude.permissions),
      subagents: subagentsConfig(fileClaude.subagents),
      context: contextConfig(fileClaude, home),
    },
    codex: {
      path: str(flags['codex-path']) ?? str(env.AGENTCRAFT_CODEX_PATH) ?? str(fileCodex.path),
      leadModel: str(flags['codex-lead-model']) ?? str(flags['codex-model']) ?? str(fileCodex.leadModel) ?? str(fileCodex.model),
      workerModel: str(flags['codex-worker-model']) ?? str(flags['codex-model']) ?? str(fileCodex.workerModel) ?? str(fileCodex.model),
      effort: str(flags['codex-effort']) ?? str(fileCodex.effort),
      leadEffort: str(flags['codex-lead-effort']) ?? str(flags['codex-effort']) ?? str(fileCodex.leadEffort) ?? str(fileCodex.effort),
      agentModels: agentModelsConfig(fileCodex.agentModels, 'codex', CODEX_EFFORTS),
    },
    engines: {
      lead: engineName(pick('lead-engine', 'AGENTCRAFT_LEAD_ENGINE') ?? (file.engines as Record<string, unknown> | undefined)?.lead, backend === 'codex' ? 'codex' : 'claude', 'lead engine'),
      worker: engineName(pick('worker-engine', 'AGENTCRAFT_WORKER_ENGINE') ?? (file.engines as Record<string, unknown> | undefined)?.worker, backend === 'codex' ? 'codex' : 'claude', 'worker engine'),
      byAgent: engineMap(flags.engines ?? env.AGENTCRAFT_ENGINES ?? (file.engines as Record<string, unknown> | undefined)?.byAgent),
    },
    sim: {
      speed: Math.max(0.05, num(flags.speed ?? env.AGENTCRAFT_SIM_SPEED ?? fileSim.speed, 1)),
      seed: num(flags.seed ?? fileSim.seed, 7),
      showcase: bool(flags.showcase, false),
      showcaseAt: flags.showcase === 'late' ? 'showcase-late' : 'showcase',
      autoAnswer: bool(flags['auto-answer'] ?? fileSim.autoAnswer, false),
      ambient: bool(flags.ambient ?? fileSim.ambient, true),
    },
  };
  if (cfg.sim.showcase) cfg.autostart = true;
  return cfg;
}

export const HELP = `AgentCraft Foreman ${FOREMAN_VERSION}

usage: npm run start -- [options]

  --backend sim|claude|codex  agent backend (default: claude; codex = an all-Codex team)
  --repo <path>[,<path>]   work in these existing folder(s): a git repo, or any folder, empty or not;
                           repeatable (sim: defaults to a fresh sandbox/sim-demo)
  --goal "<text>"          submit a goal right away
  --port <n>               WebSocket port (default 7878, env AGENTCRAFT_PORT)
  --home <dir>             state root (default ~/.agentcraft, env AGENTCRAFT_HOME)
  --user-name <name>       your name, as the agents address you (default: your OS user name,
                           env AGENTCRAFT_USER_NAME, config.json "userName")
  --profile <name>         state profile under home (default: backend name)
  --reset                  wipe this profile's state first (sim: also recreates the demo repo)
  --notify / --no-notify   desktop notification when a decision waits (default: on for claude, off for sim)
  --toast-silent           toasts without sound
  --repo-poll-ms <n>       how often repo checkouts are checked for head/dirty changes (default 10000)
  --merge-style merge|squash  approved merges: merge commit keeping the agents' commits (default),
                           or one squashed commit authored by you
  --no-sign-merges         never sign approved merge commits (default: signed when your git
                           config has commit.gpgsign=true; claude backend only)
  --debug                  verbose logging

 sim backend
  --speed <x>              speed multiplier (default 1)
  --seed <n>               scenario seed (default 7)
  --autostart              start the scripted scenario immediately (otherwise: on first goal.submit)
  --showcase               run to the showcase checkpoint instantly and hold that static state
  --showcase late          hold the later state instead (blocked, error, done and running agents)
  --auto-answer            answer the scenario's own decisions (unattended runs)
  --no-ambient             no idle chatter while waiting on you

 claude backend
  auth: ANTHROPIC_API_KEY, or a cloud provider (CLAUDE_CODE_USE_BEDROCK / _VERTEX / _FOUNDRY)
  --use-claude-login       use your local \`claude\` CLI login instead (personal use only; env
                           AGENTCRAFT_USE_CLAUDE_LOGIN=1, config.json claude.useClaudeLogin)
  --model <m>              model for lead and workers (default lead: opus, workers: sonnet)
  --lead-model <m> / --worker-model <m>
  --effort low|medium|high|xhigh|max   (default medium)
  --lead-fallback-model <m>  the lead's model while a usage limit of its own model is in force
                           (claude.ai login, e.g. a Fable lead at the Fable limit), instead of
                           waiting for the reset; back on --lead-model when it resets (default: none;
                           env AGENTCRAFT_LEAD_FALLBACK_MODEL, config.json claude.leadFallback.model;
                           --no-lead-fallback-model turns off one set there)
  --lead-fallback-effort <e>  its effort (default: the lead's; env AGENTCRAFT_LEAD_FALLBACK_EFFORT,
                           config.json claude.leadFallback.effort)
  one agent's own model:   config.json claude.agentModels, e.g. {"juniper": {"model": "sonnet",
                           "effort": "high"}}: beats the models and effort above for that agent;
                           the console's /model <agent> <model> [effort] sets it (and saves it)
  --max-turns <n>          turn cap per session run (default lead 40 / worker 80)
  --workers <n|ids>        team size or comma list (default juniper,kit,wren)
  --max-concurrent <n>     workers running at once (default 3)
  --throttle-concurrent <n>  workers at once while your plan reports a usage warning (default 1);
                           at the limit itself every agent waits until it resets, then resumes
  --max-budget <usd>       per-turn USD cap
  --ci "<cmd>"             test command run after each task (default: detected, e.g. npm test)
  --lead-read-commands "<cmd>,..."
                           read commands the lead runs without asking, by prefix, e.g.
                           "bd show,gh issue view" (env AGENTCRAFT_LEAD_READ_COMMANDS)
  --no-lead-review         skip the lead's review turn before merge decisions
  --no-resume              do not resume interrupted sessions on start
  --no-usage               do not poll the plan usage shown in-game (with --use-claude-login each
                           poll runs a \`claude\` process; config.json claude.usagePoll)

 codex engine (--backend codex, or mixed teams)
  auth: your Codex login (\`codex login\`: ChatGPT or an OpenAI API key)
  --lead-engine claude|codex / --worker-engine claude|codex
                           mix engines (default: the backend's), e.g. a Claude lead with Codex workers
  --engines <agent=engine,...>  per agent, e.g. kit=codex,wren=claude (env AGENTCRAFT_ENGINES)
  --codex-path <path>      the codex CLI (default: on PATH, else the Codex desktop app's)
  --codex-model <m>        model for Codex agents (default: your Codex config's model)
  --codex-lead-model <m> / --codex-worker-model <m>
  --codex-effort <e>       reasoning effort (low|medium|high|xhigh...; default: your Codex config's)
  one Codex agent's own model: config.json codex.agentModels (like claude.agentModels; /model sets it)
  Codex agents get none of your own MCP servers, plugins, web search or apps; every command and
  out-of-worktree edit goes through the same policy and in-game permission prompts.
`;
