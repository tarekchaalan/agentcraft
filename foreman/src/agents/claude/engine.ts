// The Claude engine: one Claude Agent SDK `query()` per turn. We spawn the CLI ourselves (same as
// the SDK's local spawn) so its pid is known and a stopped turn's whole process tree can be ended.
import { spawn } from 'node:child_process';
import path from 'node:path';
import { query, type CanUseTool, type Options } from '@anthropic-ai/claude-agent-sdk';
import type { ClaudeConfig } from '../../config.js';
import type { Foreman } from '../../foreman.js';
import { userName } from '../../user.js';
import type { AuthCheck, Engine, Role, TurnSpec, TurnStats } from '../engine.js';
import { detectApiAuth, NO_API_AUTH_MESSAGE, withAuthMode } from './auth.js';
import { buildSkillsPlugin, instructionsBlock } from './context.js';
import { StreamMapper } from './stream.js';
import { MCP_SERVER, mcpServer } from './tools.js';

export class ClaudeEngine implements Engine {
  readonly id = 'claude' as const;
  readonly label = 'Claude';
  /** the Foreman's skills plugin (built from claude.context.skills when the engine is created) */
  private skillsPlugin: { path: string; ids: string[] } | undefined;

  constructor(
    private fm: Foreman,
    private cfg: ClaudeConfig,
    private queryFn: typeof query = query,
  ) {
    this.prepareContext();
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
    const what = [
      c.repoInstructions ? 'repo CLAUDE.md/AGENTS.md' : '',
      c.userInstructions ? '~/.claude/CLAUDE.md' : '',
      c.files.length ? `${c.files.length} instruction file(s)` : '',
      this.skillsPlugin ? `skills ${this.skillsPlugin.ids.map((i) => i.split(':')[1]).join(', ')}` : '',
      Object.keys(c.mcpServers).length ? `MCP ${Object.keys(c.mcpServers).join(', ')}` : '',
    ].filter(Boolean);
    this.fm.log.info(`agent context: ${what.join('; ') || 'none'}`);
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
    return `Claude authentication failed (${detail}). Run \`claude\` and /login, then restart the Foreman.`;
  }

  async checkAuth(): Promise<AuthCheck> {
    // API authentication by default; the claude.ai login only when explicitly opted into
    const api = detectApiAuth(process.env);
    if (!this.cfg.useClaudeLogin && !api.ok) return { ok: false, message: NO_API_AUTH_MESSAGE };
    async function* never(): AsyncGenerator<never> {
      await new Promise(() => undefined);
    }
    const q = this.queryFn({ prompt: never(), options: { settingSources: [], persistSession: false, permissionMode: 'default', env: withAuthMode({ ...process.env }, this.cfg.useClaudeLogin) } });
    try {
      const info = await Promise.race([q.accountInfo(), new Promise<never>((_, r) => setTimeout(() => r(new Error('timed out after 45s')), 45_000))]);
      const ok = !!(info.email || info.organization || (info.apiKeySource && info.apiKeySource !== 'none') || (info.tokenSource && info.tokenSource !== 'none') || (info.apiProvider && info.apiProvider !== 'firstParty'));
      if (!ok) throw new Error('not logged in');
      const account = this.cfg.useClaudeLogin
        ? [info.organization, info.subscriptionType].filter(Boolean).join(' · ') || info.apiProvider || 'ok'
        : [api.ok ? api.source : 'API', info.organization].filter(Boolean).join(' · ');
      return { ok: true, account };
    } catch (e) {
      return {
        ok: false,
        message: this.cfg.useClaudeLogin
          ? `Claude login check failed: ${(e as Error).message}. Run \`claude\` and /login, then restart the Foreman. The sim backend still works.`
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

  async runTurn(spec: TurnSpec): Promise<TurnStats> {
    const { agentId, role, cwd, abort } = spec;
    const canUseTool: CanUseTool = async (toolName, input, opts) => {
      const r = await spec.permission(toolName, input, opts.signal, opts.title);
      return r.allow ? { behavior: 'allow', updatedInput: input } : { behavior: 'deny', message: r.message, ...(r.interrupt ? { interrupt: true } : {}) };
    };
    // claude.context: the instruction files the user chose, read by the Foreman (never through
    // settingSources), after the role prompt, which takes precedence
    const extra = instructionsBlock(this.cfg.context, cwd, userName());
    // the lead's Bash is read-only: the policy asks before anything that writes
    const baseTools = role === 'lead' ? ['Read', 'Grep', 'Glob', 'Bash'] : ['Read', 'Grep', 'Glob', 'Edit', 'Write', 'Bash', 'TodoWrite'];
    const options: Options = {
      cwd,
      model: this.model(role),
      effort: role === 'lead' ? this.cfg.leadEffort : this.cfg.effort,
      maxTurns: role === 'lead' ? this.cfg.maxTurnsLead : this.cfg.maxTurnsWorker,
      settingSources: [],
      permissionMode: 'default',
      canUseTool,
      tools: this.skillsPlugin ? [...baseTools, 'Skill'] : baseTools,
      // no allowedTools: every tool call (incl. our MCP tools) goes through canUseTool/policy
      disallowedTools: ['Bash(git push:*)', 'Task', 'Agent', 'WebSearch', 'WebFetch'],
      // the user's extra servers first, so the team tools server can never be replaced
      mcpServers: { ...this.cfg.context.mcpServers, [MCP_SERVER]: mcpServer(spec.tools) },
      ...(this.skillsPlugin ? { plugins: [{ type: 'local' as const, path: this.skillsPlugin.path, skipMcpDiscovery: true }], skills: this.skillsPlugin.ids } : {}),
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
    const mapper = new StreamMapper(this.fm, agentId, cwd, role);
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
