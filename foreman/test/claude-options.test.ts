// What the claude backend hands the SDK per turn: isolation defaults, and the opt-in vault config
// (repo context from the base checkout, instructions file, MCP servers). Fake `query()`, no API calls.
import fs from 'node:fs';
import path from 'node:path';
import type { Options, SDKMessage } from '@anthropic-ai/claude-agent-sdk';
import { afterEach, describe, expect, it } from 'vitest';
import { ClaudeBackend } from '../src/agents/claude/index.js';
import { injectedContext } from '../src/agents/prompts.js';
import { demoRepo, makeForeman, rmrf, tempDir, until } from './helpers.js';

type ToolServer = { instance: { _registeredTools: Record<string, { handler: (a: unknown, e: unknown) => Promise<unknown> }> } };

const sid = (k: string) => `00000000-0000-4000-8000-${k.padStart(12, '0')}`;
const msg = (o: Record<string, unknown>) => ({ parent_tool_use_id: null, uuid: sid(String(Math.random()).slice(2, 12)), ...o }) as unknown as SDKMessage;

/** Plays a plan that creates one task for kit, then lets kit's turn end; records every turn's options. */
function recorder(seen: Array<{ role: 'lead' | 'worker'; options: Options; prompt: string }>) {
  return ({ prompt, options }: { prompt: string | AsyncIterable<unknown>; options?: Options }) => {
    const opts = options!;
    const p = String(prompt);
    const isPlan = p.startsWith('New goal');
    seen.push({ role: isPlan ? 'lead' : 'worker', options: opts, prompt: p });
    async function* run(): AsyncGenerator<SDKMessage> {
      const s = sid(isPlan ? '1' : '2');
      yield msg({ type: 'system', subtype: 'init', session_id: s, model: 'fake', cwd: '', tools: [] });
      if (isPlan) {
        const server = opts.mcpServers!.agentcraft as unknown as ToolServer;
        await server.instance._registeredTools.create_task!.handler({ title: 'Do it', description: 'x', assignee: 'kit' }, {});
      }
      yield msg({ type: 'result', subtype: 'success', is_error: false, result: 'ok', num_turns: 1, total_cost_usd: 0, session_id: s, duration_ms: 1, duration_api_ms: 1, usage: {}, modelUsage: {}, permission_denials: [] });
    }
    return Object.assign(run(), { close() {}, accountInfo: async () => ({ email: 'x' }) });
  };
}

const cleanup: string[] = [];
afterEach(() => {
  for (const d of cleanup.splice(0)) rmrf(d);
});

async function runGoal(claudeConfig?: (repoPath: string, home: string) => Record<string, unknown>) {
  const home = tempDir();
  const repoPath = await demoRepo();
  cleanup.push(home, path.dirname(repoPath));
  const extra = claudeConfig?.(repoPath, home);
  if (extra) fs.writeFileSync(path.join(home, 'config.json'), JSON.stringify({ claude: extra }));
  const h = makeForeman(home, ['--backend', 'claude', '--workers', 'kit', '--repo', repoPath]);
  const seen: Array<{ role: 'lead' | 'worker'; options: Options; prompt: string }> = [];
  await h.fm.start(new ClaudeBackend(h.fm, h.cfg.claude, { queryFn: recorder(seen) as never, skipAuthCheck: true }));
  await h.fm.submitGoal('anything');
  await until(() => seen.some((x) => x.role === 'worker'));
  await h.fm.close();
  return { repoPath, lead: seen.find((x) => x.role === 'lead')!.options, worker: seen.find((x) => x.role === 'worker')!.options };
}

const append = (o: Options) => (o.systemPrompt as { append: string }).append;

describe('claude backend options', () => {
  it('without config: isolated, read-only lead, no push/subagents, only the agentcraft MCP server', async () => {
    const { lead, worker } = await runGoal();
    for (const o of [lead, worker]) {
      expect(o.settingSources).toEqual([]);
      expect(o.strictMcpConfig).toBe(true);
      expect(o.permissionMode).toBe('default');
      expect(o.canUseTool).toBeTypeOf('function');
      expect(o.disallowedTools).toEqual(expect.arrayContaining(['Bash(git push:*)', 'Task', 'Agent']));
      expect(Object.keys(o.mcpServers!)).toEqual(['agentcraft']);
      expect(append(o)).not.toContain('Instructions from');
    }
    expect(lead.tools).toEqual(['Read', 'Grep', 'Glob', 'Bash']); // no Edit/Write; the lead's Bash is policed as read-only
  });

  it('with vault config: context from the base checkout, instructions file, engram pinned to the base repo', async () => {
    const { repoPath, lead, worker } = await runGoal((repo, home) => {
      // uncommitted: present in the base checkout, absent from every worktree
      fs.writeFileSync(path.join(repo, 'CLAUDE.md'), 'Commits in Spanish.');
      fs.mkdirSync(path.join(repo, '.claude', 'rules'), { recursive: true });
      fs.writeFileSync(path.join(repo, '.claude', 'rules', 'a.md'), 'Rule A.');
      fs.writeFileSync(path.join(home, 'mine.md'), 'My global rules.');
      return { repoContext: true, instructionsFile: 'mine.md', mcpServers: { engram: { command: 'engram', args: ['mcp', '--tools=agent'] } } };
    });
    for (const o of [lead, worker]) {
      const a = append(o);
      expect(a).toContain('My global rules.');
      expect(a).toContain('## CLAUDE.md\nCommits in Spanish.');
      expect(a).toContain('## .claude/rules/a.md\nRule A.');
      expect(a.indexOf('AgentCraft rules win')).toBeLessThan(a.indexOf('My global rules.'));
      expect(o.settingSources).toEqual([]);
      expect(o.strictMcpConfig).toBe(true);
      expect(Object.keys(o.mcpServers!)).toEqual(['engram', 'agentcraft']);
      expect(o.mcpServers!.engram).toMatchObject({ command: 'engram', args: ['mcp', '--tools=agent', '--project', path.basename(repoPath)] });
    }
  });

  it('injectedContext adds the precedence line only when there is something to inject', () => {
    expect(injectedContext('', '')).toBe('');
    expect(injectedContext('mine', '')).toContain('AgentCraft rules win');
    expect(injectedContext('', 'repo')).toContain('Repo context');
  });
});
