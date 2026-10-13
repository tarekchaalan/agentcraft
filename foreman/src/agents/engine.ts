// An engine runs one agent turn: the Claude Agent SDK (claude/engine.ts) or the Codex app-server
// (codex/engine.ts). Everything around a turn - task graph, worktrees, scheduling, CI, reviews,
// merges, steering, restarts - is the team's (team.ts) and the same for every engine, so a team
// can mix them (e.g. a Claude lead with Codex workers).
import type { ChildProcess } from 'node:child_process';
import type { EffortLevel } from '@anthropic-ai/claude-agent-sdk';
import type { PolicyContext, Verdict } from '../policy.js';
import type { Usage } from '../protocol.js';
import type { AgentTool, TurnHandle } from './tools.js';

export type EngineId = 'claude' | 'codex';
export const ENGINE_IDS: readonly EngineId[] = ['claude', 'codex'];
export type Role = 'lead' | 'worker';

export interface TurnStats {
  /** the engine's session (Claude session id, Codex thread id), to resume the next turn */
  sessionId?: string;
  resultText?: string;
  subtype?: string;
  isError: boolean;
  costUsd?: number;
  numTurns?: number;
  /** total tokens of the turn (engines that report usage, not cost) */
  tokens?: number;
  authFailed?: string;
  errors: string[];
  /** a usage/rate limit refused this turn (rate_limit_event "rejected" or an API rate_limit error) */
  limited?: boolean;
  /** the latest rate limit report seen in the turn */
  rateLimit?: RateLimitReport;
}

/** A plan usage report from an engine's stream (claude.ai subscription logins report these every turn). */
export interface RateLimitReport {
  status: 'allowed' | 'allowed_warning' | 'rejected';
  /** epoch ms */
  resetsAt?: number;
  type?: string;
  /** 0-1 */
  utilization?: number;
}

/** The user's (or the policy's) verdict on a tool call. */
export type PermissionAnswer = { allow: true } | { allow: false; message: string; interrupt?: boolean };

/**
 * Ask whether an agent may use a tool: the policy decides, or the user is asked (a permission
 * decision). `toolName`/`input` use the Claude tool vocabulary (Bash {command}, Edit {file_path}),
 * which the policy understands; other engines map their actions onto it.
 */
export type PermissionGate = (toolName: string, input: Record<string, unknown>, signal: AbortSignal, title?: string) => Promise<PermissionAnswer>;

export interface TurnSpec {
  agentId: string;
  role: Role;
  /** lead: the user's checkout (read-only); worker: its worktree */
  cwd: string;
  /**
   * the base checkout of the repository the agent works on (the lead's cwd; a worker's worktree is
   * made from it). Context read from here is the user's, never something an agent wrote.
   */
  repoPath: string;
  prompt: string;
  /** role, rules and worktree, added to the engine's own system prompt */
  instructions: string;
  /** the engine session to continue (from TurnStats.sessionId), if any */
  resume?: string;
  /**
   * the model and effort this turn runs instead of the engine's configured ones: the lead's
   * fallback while a usage limit of its own model is in force (claude.leadFallback, Claude engine)
   */
  modelOverride?: { model: string; effort: EffortLevel };
  /** environment for the agent's process and every command it runs (git safety, identity) */
  env: Record<string, string | undefined>;
  /** directories the agent may write besides cwd (a worktree's git dir, for commits) */
  writableRoots?: string[];
  abort: AbortController;
  turn: TurnHandle;
  permission: PermissionGate;
  /**
   * The policy's verdict on a tool call without asking anyone (what `permission` would do before
   * it turns to the user): engines with their own permission machinery (Claude Code's auto mode)
   * keep the policy's guardrails with it.
   */
  classify?: (toolName: string, input: Record<string, unknown>) => Verdict;
  /** roots nothing may write into without asking, whatever else allows it (registered checkouts, AgentCraft's state) */
  protectedRoots?: () => string[];
  tools: AgentTool[];
  /** the agent process the engine spawned, so the team can end its whole tree on abort */
  onProcess(child: ChildProcess): void;
  /** the session id, as soon as the engine knows it (persisted for resume) */
  onSession(sessionId: string): void;
  /** the model the turn really runs (e.g. "claude-opus-5-5"), as soon as the engine knows it */
  onModel?(model: string): void;
  /** a live usage report from the running turn, so the team can hold or throttle before it ends */
  onRateLimit?(report: RateLimitReport): void;
}

/** `mode`: how the engine authenticated, or tried to (e.g. "claude login", "API key", "Amazon Bedrock") */
export type AuthCheck = { ok: true; account: string; mode: string } | { ok: false; message: string; mode?: string };

export interface Engine {
  readonly id: EngineId;
  /** display name, e.g. "Claude" */
  readonly label: string;
  model(role: Role): string;
  /** how the engine will authenticate, as known before checkAuth (shown while checking) */
  authMode(): string;
  checkAuth(): Promise<AuthCheck>;
  runTurn(spec: TurnSpec): Promise<TurnStats>;
  /** what to tell the user when a turn failed authentication */
  authFailedMessage(detail: string): string;
  /**
   * Spend mode and plan rate-limit windows for `foreman.status.usage` (the team polls it, see
   * usage.ts). Optional: engines without it are never polled. Never throws: a failure is a
   * returned `error`.
   */
  usage?(): Promise<Usage>;
  /**
   * What the policy also needs to know for this engine's agents, merged into the team's policy
   * context (Claude: the skills and MCP tools enabled in claude.context, and the skills' files).
   */
  policyContext?(): Pick<PolicyContext, 'readDirs' | 'skills' | 'mcpAllow'>;
}
