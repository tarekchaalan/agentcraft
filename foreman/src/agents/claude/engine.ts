// The Claude engine: one Claude Agent SDK `query()` per turn. We spawn the CLI ourselves (same as
// the SDK's local spawn) so its pid is known and a stopped turn's whole process tree can be ended.
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { query, type AgentDefinition, type CanUseTool, type Options, type Query } from '@anthropic-ai/claude-agent-sdk';
import type { ClaudeConfig } from '../../config.js';
import type { Foreman } from '../../foreman.js';
import type { Usage } from '../../protocol.js';
import { userName } from '../../user.js';
import { truncate } from '../../util/text.js';
import type { AuthCheck, Engine, Role, TurnSpec, TurnStats } from '../engine.js';
import { detectApiAuth, NO_API_AUTH_MESSAGE, withAuthMode } from './auth.js';
import { buildSkillsPlugin, expandHome, instructionsBlock, mcpServersFor } from './context.js';
import { guardrailHook } from './permissions.js';
import { StreamMapper } from './stream.js';
import { loadSubagents } from './subagents.js';
import { MCP_SERVER, mcpServer } from './tools.js';
import { mapUsage, USAGE_ERRORS, USAGE_METHODS, USAGE_TIMEOUT_MS, usageSummary } from './usage.js';

/** The prompt of a throwaway query (auth probe, usage): it never sends a turn. */
async function* never(): AsyncGenerator<never> {
  await new Promise(() => undefined);
}

/** The launcher command that signs the agents' Claude Code in, as the person reading the banner would type it. */
const LOGIN_COMMAND = process.platform === 'win32' ? 'tools\\launch.ps1 -Login' : 'node tools/launch-unix.mjs login';

export class ClaudeEngine implements Engine {
  readonly id = 'claude' as const;
  readonly label = 'Claude';
  /** the SDK has no usage method (learned on a poll): later polls answer that without spawning */
  private usageMissing = false;
  /** subagent definitions from claude.subagents.agents */
  private subagentDefs: Record<string, AgentDefinition> = {};
  /** the Foreman's skills plugin (built from claude.context.skills when the engine is created) */
  private skillsPlugin: { path: string; ids: string[] } | undefined;
  /** context warnings already logged (each once per run, not once per turn) */
  private warned = new Set<string>();

  constructor(
    private fm: Foreman,
    private cfg: ClaudeConfig,
    private queryFn: typeof query = query,
  ) {
    const p = cfg.permissions;
    const s = cfg.subagents;
    if (s.enabled) {
      const { agents, problems } = loadSubagents(s.agents);
      for (const pr of problems) fm.log.warn(`claude.subagents: ${pr}`);
      this.subagentDefs = agents;
    }
    const rules = p.allow.length + p.deny.length + p.ask.length;
    fm.log.info(
      `permissions: ${p.mode === 'auto' ? `auto mode${p.protectCheckouts ? ' (checkouts protected)' : ''}` : 'AgentCraft policy'}` +
        `${rules ? `, ${rules} rule(s)` : ''}${p.webTools ? ', web tools' : ''}` +
        `${s.enabled ? `, subagents${Object.keys(this.subagentDefs).length ? ` (${Object.keys(this.subagentDefs).join(', ')})` : ''}` : ''}`,
    );
    this.prepareContext();
  }

  /**
   * Permission mode, tools, guardrail hook and the user's rules for one turn (claude.permissions,
   * claude.subagents). Policy mode is the default behaviour: every call goes through canUseTool.
   */
  private permissionOptions(spec: TurnSpec, canUseTool: CanUseTool): Partial<Options> {
    const p = this.cfg.permissions;
    const sub = this.cfg.subagents.enabled;
    // the lead's Bash is read-only: the policy asks before anything that writes
    const tools = spec.role === 'lead' ? ['Read', 'Grep', 'Glob', 'Bash'] : ['Read', 'Grep', 'Glob', 'Edit', 'Write', 'Bash', 'TodoWrite'];
    if (p.webTools) tools.push('WebFetch', 'WebSearch');
    if (sub) tools.push('Agent', 'Task');
    // claude.context skills: only the Foreman's plugin, only the skills enabled there
    if (this.skillsPlugin) tools.push('Skill');
    // no allowedTools: every tool call (incl. our MCP tools) goes through canUseTool/policy
    const disallowed = ['Bash(git push:*)', ...(sub ? [] : ['Task', 'Agent']), ...(p.webTools ? [] : ['WebSearch', 'WebFetch'])];
    const rules = p.allow.length || p.deny.length || p.ask.length ? { permissions: { allow: p.allow, deny: p.deny, ask: p.ask } } : undefined;
    const classify = spec.classify;
    return {
      permissionMode: p.mode === 'auto' ? 'auto' : 'default',
      canUseTool,
      tools,
      disallowedTools: disallowed,
      ...(rules ? { settings: rules } : {}),
      ...(sub && Object.keys(this.subagentDefs).length ? { agents: this.subagentDefs } : {}),
      ...(this.skillsPlugin ? { plugins: [{ type: 'local' as const, path: this.skillsPlugin.path, skipMcpDiscovery: true }], skills: this.skillsPlugin.ids } : {}),
      ...(p.mode === 'auto' && classify
        ? {
            hooks: {
              PreToolUse: [
                {
                  hooks: [
                    guardrailHook(
                      classify,
                      () => spec.protectedRoots?.() ?? [],
                      (tool, decision, reason, subagent) =>
                        this.fm.agentLog(spec.agentId, decision === 'deny' ? 'error' : 'tool', `guardrail ${decision === 'deny' ? 'blocked' : 'asks you'}${subagent ? ' (subagent)' : ''}: ${tool} (${truncate(reason, 160)})`),
                    ),
                  ],
                },
              ],
            },
          }
        : {}),
    };
  }

  /** Build the skills plugin and report what context the agents get (claude.context). */
  private prepareContext(): void {
    const c = this.cfg.context;
    const dir = path.join(this.fm.config.dataDir, 'agent-plugin');
    try {
      const { ids, problems } = buildSkillsPlugin(c.skills, dir);
      for (const p of problems) this.fm.log.warn(`claude.context: ${p}`);
      this.skillsPlugin = ids.length ? { path: dir, ids } : undefined;
    } catch (e) {
      this.fm.log.error(`claude.context: could not build the skills plugin: ${(e as Error).message}`);
      this.skillsPlugin = undefined;
    }
    // a listed file that is missing is skipped at every turn: say so once, here
    for (const f of c.files) {
      const file = path.resolve(expandHome(f));
      if (!fs.existsSync(file)) this.fm.log.warn(`claude.context: instruction file ${file} not found; agents start without it`);
    }
    const what = [
      c.repoInstructions ? 'repo CLAUDE.md/AGENTS.md/.claude/rules (base checkout)' : '',
      c.userInstructions ? '~/.claude/CLAUDE.md' : '',
      c.files.length ? `${c.files.length} instruction file(s)` : '',
      this.skillsPlugin ? `skills ${this.skillsPlugin.ids.map((i) => i.split(':')[1]).join(', ')}` : '',
      Object.keys(c.mcpServers).length ? `MCP ${Object.keys(c.mcpServers).join(', ')}` : '',
    ].filter(Boolean);
    this.fm.log.info(`agent context: ${what.join('; ') || 'none'}`);
  }

  private warnOnce(message: string): void {
    if (this.warned.has(message)) return;
    this.warned.add(message);
    this.fm.log.warn(`claude.context: ${message}`);
  }

  /** The enabled skills (and their copied files, readable) and the MCP tools allowed in config. */
  policyContext(): { readDirs?: string[]; skills?: string[]; mcpAllow: string[] } {
    return {
      ...(this.skillsPlugin ? { readDirs: [this.skillsPlugin.path], skills: this.skillsPlugin.ids } : {}),
      mcpAllow: this.cfg.context.mcpAllow,
    };
  }

  model(role: Role): string {
    return role === 'lead' ? this.cfg.leadModel : this.cfg.workerModel;
  }

  authFailedMessage(detail: string): string {
    return `Claude authentication failed (${detail}). Run \`${LOGIN_COMMAND}\` (or \`claude\` and /login), then relaunch.`;
  }

  /** "claude login" (opted in), else the API authentication the environment provides */
  authMode(): string {
    if (this.cfg.useClaudeLogin) return 'claude login';
    const api = detectApiAuth(process.env);
    return api.ok ? api.source : 'API key';
  }

  async checkAuth(): Promise<AuthCheck> {
    // API authentication by default; the claude.ai login only when explicitly opted into
    const api = detectApiAuth(process.env);
    const mode = this.authMode();
    if (!this.cfg.useClaudeLogin && !api.ok) return { ok: false, message: NO_API_AUTH_MESSAGE, mode };
    const q = this.queryFn({ prompt: never(), options: { settingSources: [], strictMcpConfig: true, persistSession: false, permissionMode: 'default', env: withAuthMode({ ...process.env }, this.cfg.useClaudeLogin) } });
    try {
      const info = await Promise.race([q.accountInfo(), new Promise<never>((_, r) => setTimeout(() => r(new Error('timed out after 45s')), 45_000))]);
      const ok = !!(info.email || info.organization || (info.apiKeySource && info.apiKeySource !== 'none') || (info.tokenSource && info.tokenSource !== 'none') || (info.apiProvider && info.apiProvider !== 'firstParty'));
      if (!ok) throw new Error('not logged in');
      const account = this.cfg.useClaudeLogin
        ? [info.organization, info.subscriptionType].filter(Boolean).join(' · ') || info.apiProvider || 'ok'
        : [api.ok ? api.source : 'API', info.organization].filter(Boolean).join(' · ');
      return { ok: true, account, mode };
    } catch (e) {
      return {
        ok: false,
        mode,
        message: this.cfg.useClaudeLogin
          ? `Claude login check failed: ${(e as Error).message}. Run \`${LOGIN_COMMAND}\` (or \`claude\` and /login), then relaunch. The sim backend still works.`
          : `Claude API check failed: ${(e as Error).message}. Check ANTHROPIC_API_KEY (or your cloud provider settings), then restart the Foreman. The sim backend still works.`,
      };
    } finally {
      try {
        q.close();
      } catch {
        /* ignore */
      }
    }
  }

  /**
   * The plan usage for the in-game display (docs/design/usage-display.md). API key / cloud
   * provider: plan limits do not apply, nothing is spawned. claude.ai login: a throwaway query like
   * checkAuth's asks the CLI for its /usage data through the SDK's experimental method, found by
   * name so a rename breaks neither the build nor the run (a missing method is remembered: the SDK
   * cannot change while the Foreman runs). Never throws; the answer and the env are never logged.
   */
  async usage(): Promise<Usage> {
    if (!this.cfg.useClaudeLogin) return { mode: 'api', windows: [], fetchedAt: Date.now() };
    const failed = (error: string): Usage => ({ mode: 'subscription', windows: [], fetchedAt: Date.now(), error });
    if (this.usageMissing) return failed(USAGE_ERRORS.missing);
    const t0 = Date.now();
    let q: Query | undefined;
    let timer: NodeJS.Timeout | undefined;
    let usage: Usage;
    try {
      q = this.queryFn({ prompt: never(), options: { settingSources: [], strictMcpConfig: true, persistSession: false, permissionMode: 'default', env: withAuthMode({ ...process.env }, this.cfg.useClaudeLogin) } });
      const target = q as unknown as Record<string, unknown>;
      const method = USAGE_METHODS.find((m) => typeof target[m] === 'function');
      if (!method) {
        this.usageMissing = true;
        usage = failed(USAGE_ERRORS.missing);
      } else {
        // skipBehaviors: no scan of seven days of local transcripts, only the plan limits
        const answer = (target[method] as (opts: { skipBehaviors: boolean }) => Promise<unknown>).call(q, { skipBehaviors: true });
        const timeout = new Promise<never>((_, reject) => {
          timer = setTimeout(() => reject(new Error(`timed out after ${USAGE_TIMEOUT_MS / 1000}s`)), USAGE_TIMEOUT_MS);
        });
        const raw = await Promise.race([answer, timeout]);
        usage = mapUsage(raw, Date.now());
        if (usage.error === USAGE_ERRORS.shape) this.fm.log.debug(`usage: unexpected answer, top-level keys: ${raw && typeof raw === 'object' ? Object.keys(raw).join(', ') : typeof raw}`);
      }
    } catch (e) {
      usage = failed(`usage request failed: ${truncate((e as Error)?.message ?? String(e), 160)}`);
    } finally {
      if (timer) clearTimeout(timer);
      try {
        q?.close();
      } catch {
        /* ignore */
      }
    }
    this.fm.log.debug(`usage: ${usageSummary(usage)} (${Date.now() - t0} ms)`);
    return usage;
  }

  async runTurn(spec: TurnSpec): Promise<TurnStats> {
    const { agentId, role, cwd, abort } = spec;
    const canUseTool: CanUseTool = async (toolName, input, opts) => {
      const r = await spec.permission(toolName, input, opts.signal, opts.title);
      return r.allow ? { behavior: 'allow', updatedInput: input } : { behavior: 'deny', message: r.message, ...(r.interrupt ? { interrupt: true } : {}) };
    };
    // claude.context: the instruction files the user chose, read by the Foreman (never through
    // settingSources) from the repository's base checkout, never the agent's (writable) worktree;
    // one block after the role prompt, which takes precedence
    const extra = instructionsBlock(this.cfg.context, spec.repoPath, userName(), undefined, (m) => this.warnOnce(m));
    const options: Options = {
      cwd,
      // the lead's fallback (claude.leadFallback) while its own model's usage limit is in force
      model: spec.modelOverride?.model ?? this.model(role),
      effort: spec.modelOverride?.effort ?? (role === 'lead' ? this.cfg.leadEffort : this.cfg.effort),
      maxTurns: role === 'lead' ? this.cfg.maxTurnsLead : this.cfg.maxTurnsWorker,
      settingSources: [],
      // only the servers passed here: no .mcp.json, no plugin servers, no claude.ai connectors
      strictMcpConfig: true,
      // permission mode, tools (incl. Skill), rules, subagents, skills plugin, guardrail hook
      ...this.permissionOptions(spec, canUseTool),
      // the user's extra servers first, so the team tools server can never be replaced
      mcpServers: { ...mcpServersFor(this.cfg.context, spec.repoPath), [MCP_SERVER]: mcpServer(spec.tools) },
      systemPrompt: { type: 'preset', preset: 'claude_code', append: extra ? `${spec.instructions}\n\n${extra}` : spec.instructions },
      abortController: abort,
      env: withAuthMode(spec.env, this.cfg.useClaudeLogin),
      spawnClaudeCodeProcess: (o) => {
        const child = spawn(o.command, o.args, { cwd: o.cwd, env: o.env as NodeJS.ProcessEnv, stdio: ['pipe', 'pipe', 'pipe'], signal: o.signal, windowsHide: true });
        child.stderr?.setEncoding('utf8');
        child.stderr?.on('data', (s: string) => this.fm.log.debug(`[${agentId} stderr] ${s.trim().slice(0, 300)}`));
        child.on('error', (e) => this.fm.log.debug(`[${agentId}] CLI process error: ${e.message}`));
        spec.onProcess(child);
        return child;
      },
      ...(spec.resume ? { resume: spec.resume } : {}),
      ...(this.cfg.maxBudgetUsdPerTurn ? { maxBudgetUsd: this.cfg.maxBudgetUsdPerTurn } : {}),
    };
    const mapper = new StreamMapper(this.fm, agentId, cwd, role, spec.onRateLimit);
    const q = this.queryFn({ prompt: spec.prompt, options });
    // the abort signal alone lets a CLI finish what it is doing (seen in a real run: ~6 s of
    // further turns after /stop). close() force-ends the subprocess and its transports.
    const closeQuery = () => {
      try {
        q.close();
      } catch {
        /* already closed */
      }
    };
    if (abort.signal.aborted) closeQuery();
    else abort.signal.addEventListener('abort', closeQuery, { once: true });
    let session: string | undefined;
    let model: string | undefined;
    for await (const msg of q) {
      if (abort.signal.aborted) break; // nothing from an aborted turn reaches the world
      mapper.handle(msg);
      if (mapper.model && mapper.model !== model) {
        model = mapper.model;
        spec.onModel?.(model);
      }
      if (mapper.stats.sessionId && mapper.stats.sessionId !== session) {
        session = mapper.stats.sessionId;
        spec.onSession(session);
      }
    }
    return mapper.stats;
  }
}
