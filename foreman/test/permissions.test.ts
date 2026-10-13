// claude.permissions (policy | auto + guardrails + rules + web tools) and claude.subagents.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import type { HookInput, Options, SDKMessage } from '@anthropic-ai/claude-agent-sdk';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { ClaudeBackend } from '../src/agents/claude/index.js';
import { guardrail, guardrailHook, writeTargets } from '../src/agents/claude/permissions.js';
import { loadSubagents } from '../src/agents/claude/subagents.js';
import { loadConfig } from '../src/config.js';
import { classifyToolUse, type PolicyContext } from '../src/policy.js';
import { demoRepo, makeForeman, rmrf, tempDir, until, type Harness } from './helpers.js';

const write = (p: string, s: string) => {
  fs.mkdirSync(path.dirname(p), { recursive: true });
  fs.writeFileSync(p, s);
};

describe('guardrails (auto mode)', () => {
  let root: string;
  let wt: string;
  let checkout: string;
  let ctx: PolicyContext;
  beforeAll(() => {
    root = fs.realpathSync(tempDir());
    wt = path.join(root, 'home', 'worktrees', 'app', 'kit-t1');
    checkout = path.join(root, 'code', 'app');
    fs.mkdirSync(wt, { recursive: true });
    fs.mkdirSync(checkout, { recursive: true });
    fs.writeFileSync(path.join(wt, '.git'), 'gitdir: x');
    // the test root lives under the OS temp dir, which the policy treats as scratch space: point the
    // scratch dir elsewhere so writes outside the worktree are judged like anywhere else
    ctx = { role: 'worker', cwd: wt, mcpServer: 'agentcraft', home: os.homedir(), tempDirs: [path.join(root, 'scratch')] };
  });
  afterAll(() => rmrf(root));
  const roots = () => [checkout, path.join(root, 'home')];
  const decide = (tool: string, input: Record<string, unknown>, c: PolicyContext = ctx) => guardrail(classifyToolUse(tool, input, c), roots())?.decision;

  it('keeps what the policy allows or denies', () => {
    expect(decide('Edit', { file_path: path.join(wt, 'src', 'a.ts') })).toBe('allow');
    expect(decide('Bash', { command: 'git push origin main' })).toBe('deny');
    expect(decide('Edit', { file_path: path.join(wt, 'a.ts') }, { ...ctx, role: 'lead' })).toBe('deny');
  });

  it('still asks for git internals and writes into protected checkouts', () => {
    expect(decide('Edit', { file_path: path.join(wt, '.git') })).toBe('ask');
    expect(decide('Bash', { command: 'GIT_DIR=/tmp/x git status' })).toBe('ask');
    expect(decide('Write', { file_path: path.join(checkout, 'src', 'x.ts') })).toBe('ask');
    expect(decide('Bash', { command: `cp README.md ${path.join(checkout, 'README.md')}` })).toBe('ask');
    expect(decide('Write', { file_path: path.join(root, 'home', 'worktrees', 'app', 'wren-t2', 'x.ts') })).toBe('ask'); // another agent's worktree
  });

  it('leaves everything else to the classifier', () => {
    expect(decide('Bash', { command: 'curl https://example.com' })).toBeUndefined();
    expect(decide('Bash', { command: 'codex exec -s workspace-write "review"' })).toBeUndefined();
    expect(decide('Bash', { command: 'npm install left-pad' })).toBeUndefined();
    expect(decide('Write', { file_path: path.join(root, 'elsewhere', 'notes.md') })).toBeUndefined();
    expect(decide('WebFetch', { url: 'https://docs.example.com' })).toBeUndefined();
    expect(guardrail(classifyToolUse('Write', { file_path: path.join(checkout, 'x') }, ctx), [])).toBeUndefined(); // protectCheckouts off
  });

  it('reads write targets from rule keys', () => {
    expect(writeTargets(['Bash:outside:cp:w:/a/b', 'Bash:outside:cat:r:/c', 'Write:/d', 'Edit:.git:/e', 'Edit:nopath', 'Bash:net:curl:x'])).toEqual(['/a/b', '/d']);
  });

  it('answers as a PreToolUse hook and reports what it stops', async () => {
    const seen: string[] = [];
    const hook = guardrailHook((t, i) => classifyToolUse(t, i, ctx), roots, (tool, decision, _r, sub) => seen.push(`${decision}:${tool}${sub ? `:${sub}` : ''}`));
    const run = (tool_name: string, tool_input: Record<string, unknown>, agent_id?: string) =>
      hook({ hook_event_name: 'PreToolUse', tool_name, tool_input, tool_use_id: 'x', session_id: 's', transcript_path: '', cwd: wt, ...(agent_id ? { agent_id } : {}) } as HookInput, 'x', { signal: new AbortController().signal });
    expect(await run('Bash', { command: 'curl https://example.com' })).toEqual({});
    expect(await run('Bash', { command: 'git push' }, 'sub1')).toMatchObject({ hookSpecificOutput: { permissionDecision: 'deny' } });
    expect(await run('Read', { file_path: path.join(wt, 'a') })).toMatchObject({ hookSpecificOutput: { permissionDecision: 'allow' } });
    expect(seen).toEqual(['deny:Bash:sub1']);
  });
});

describe('subagents in the policy', () => {
  const ctx: PolicyContext = { role: 'worker', cwd: '/tmp/wt', mcpServer: 'agentcraft' };
  it('refuses subagents unless enabled, and never with their own worktree', () => {
    expect(classifyToolUse('Agent', { prompt: 'x' }, ctx).action).toBe('deny');
    expect(classifyToolUse('Agent', { prompt: 'x', subagent_type: 'Explore' }, { ...ctx, subagents: true }).action).toBe('allow');
    expect(classifyToolUse('Task', { prompt: 'x' }, { ...ctx, subagents: true }).action).toBe('allow');
    expect(classifyToolUse('Agent', { prompt: 'x', isolation: 'worktree' }, { ...ctx, subagents: true }).action).toBe('deny');
  });
});

describe('subagent definitions', () => {
  it("reads Claude Code agent files (folded descriptions, tools, model) and never a permission mode", () => {
    const home = tempDir();
    try {
      write(path.join(home, '.claude', 'agents', 'gate-verifier.md'), '---\nname: gate-verifier\ndescription: >-\n  Verifies a gate.\n  Strictly.\ntools: Bash, Read, Grep\nmodel: sonnet\npermissionMode: bypassPermissions\n---\nYou verify.\n');
      write(path.join(home, 'x', 'critic.md'), '---\ndescription: Critiques\n---\nYou critique.');
      write(path.join(home, 'x', 'broken.md'), '---\nname: broken\n---\n');
      const { agents, problems } = loadSubagents(['gate-verifier', '~/x/critic.md', '~/x/broken.md', 'missing'], home);
      expect(agents['gate-verifier']).toEqual({ description: 'Verifies a gate. Strictly.', prompt: 'You verify.', tools: ['Bash', 'Read', 'Grep'], model: 'sonnet' });
      expect(agents.critic).toEqual({ description: 'Critiques', prompt: 'You critique.' });
      expect(problems).toHaveLength(2);
    } finally {
      rmrf(home);
    }
  });
});

describe('config', () => {
  it('reads claude.permissions and claude.subagents', () => {
    const home = tempDir();
    try {
      write(path.join(home, 'config.json'), JSON.stringify({ claude: { permissions: { mode: 'auto', allow: ['Bash(codex exec:*)', 7], webTools: true }, subagents: { enabled: true, agents: ['gate-verifier'] } } }));
      const c = loadConfig(['--home', home], {}).claude;
      expect(c.permissions).toEqual({ mode: 'auto', allow: ['Bash(codex exec:*)'], deny: [], ask: [], webTools: true, protectCheckouts: true });
      expect(c.subagents).toEqual({ enabled: true, agents: ['gate-verifier'] });
      write(path.join(home, 'config.json'), JSON.stringify({ claude: { permissions: { mode: 'yolo' } } }));
      expect(() => loadConfig(['--home', home], {})).toThrow(/unknown permissions mode/);
      write(path.join(home, 'config.json'), '{}');
      expect(loadConfig(['--home', home], {}).claude.permissions.mode).toBe('policy');
    } finally {
      rmrf(home);
    }
  });
});

// ---- what a session gets ------------------------------------------------------------------------

let n = 0;
const sid = () => `00000000-0000-4000-8000-${String(++n).padStart(12, '0')}`;
const msg = (o: Record<string, unknown>) => ({ parent_tool_use_id: null, uuid: sid(), ...o }) as unknown as SDKMessage;
type ToolServer = { instance: { _registeredTools: Record<string, { handler: (a: unknown, e: unknown) => Promise<unknown> }> } };

async function session(config: object): Promise<{ h: Harness; options: Options[]; cleanup: () => Promise<void> }> {
  const home = tempDir();
  const repoPath = await demoRepo();
  write(path.join(home, 'config.json'), JSON.stringify(config));
  const h = makeForeman(home, ['--backend', 'claude', '--workers', 'kit', '--repo', repoPath, '--no-lead-review']);
  const options: Options[] = [];
  const queryFn = ({ prompt, options: o }: { prompt: string; options?: Options }) => {
    async function* run(): AsyncGenerator<SDKMessage> {
      const s = sid();
      options.push(o!);
      yield msg({ type: 'system', subtype: 'init', session_id: s, model: 'm', cwd: '', tools: [] });
      if (String(prompt).startsWith('New goal')) await (o!.mcpServers!.agentcraft as unknown as ToolServer).instance._registeredTools.create_task!.handler({ title: 'x', assignee: 'kit' }, {});
      else {
        yield msg({ type: 'assistant', session_id: s, parent_tool_use_id: 'tu1', message: { content: [{ type: 'tool_use', id: 'st1', name: 'Grep', input: { pattern: 'TODO' } }] } });
        yield msg({ type: 'system', subtype: 'permission_denied', tool_name: 'Bash', tool_use_id: 'x', decision_reason_type: 'classifier', decision_reason: 'deletes outside the project', message: 'denied', session_id: s });
      }
      yield msg({ type: 'result', subtype: 'success', is_error: false, result: 'ok', num_turns: 1, total_cost_usd: 0, session_id: s, duration_ms: 1, duration_api_ms: 1, usage: {}, modelUsage: {}, permission_denials: [] });
    }
    return Object.assign(run(), { close() {}, accountInfo: async () => ({ email: 'x' }) });
  };
  await h.fm.start(new ClaudeBackend(h.fm, h.cfg.claude, { queryFn: queryFn as never, skipAuthCheck: true }));
  await h.fm.submitGoal('x');
  await until(() => options.length >= 2);
  return {
    h,
    options,
    cleanup: async () => {
      await h.fm.close();
      rmrf(home);
      rmrf(path.dirname(repoPath));
    },
  };
}

describe('session options', () => {
  it('policy mode (default) keeps upstream behaviour', async () => {
    const { options, cleanup } = await session({});
    try {
      const w = options[1]!;
      expect(w.permissionMode).toBe('default');
      expect(w.hooks).toBeUndefined();
      expect(w.settings).toBeUndefined();
      expect(w.tools).toEqual(['Read', 'Grep', 'Glob', 'Edit', 'Write', 'Bash', 'TodoWrite']);
      expect(w.disallowedTools).toEqual(['Bash(git push:*)', 'Task', 'Agent', 'WebSearch', 'WebFetch']);
      expect(w.agents).toBeUndefined();
    } finally {
      await cleanup();
    }
  });

  it('auto mode: classifier, guardrail hook, rules, web tools, subagents', async () => {
    const agentsHome = tempDir();
    write(path.join(agentsHome, 'checker.md'), '---\nname: checker\ndescription: Checks things\ntools: Read, Bash\n---\nCheck.');
    const { h, options, cleanup } = await session({
      claude: {
        permissions: { mode: 'auto', allow: ['Bash(codex exec:*)'], webTools: true },
        subagents: { enabled: true, agents: [path.join(agentsHome, 'checker.md')] },
      },
    });
    try {
      for (const o of options) {
        expect(o.permissionMode).toBe('auto');
        expect(o.hooks?.PreToolUse?.[0]?.hooks).toHaveLength(1);
        expect(o.settings).toEqual({ permissions: { allow: ['Bash(codex exec:*)'], deny: [], ask: [] } });
        expect(o.tools).toEqual(expect.arrayContaining(['WebFetch', 'WebSearch', 'Agent', 'Task']));
        expect(o.disallowedTools).toEqual(['Bash(git push:*)']);
        expect(Object.keys(o.agents ?? {})).toEqual(['checker']);
      }
      expect(options[0]!.tools).not.toContain('Edit'); // the lead stays read-only (its Bash is policed as read-only)
      const logs = () => {
        h.fm.flushLogs();
        return JSON.stringify(h.events.filter((e) => e.type === 'agent.log'));
      };
      await until(() => logs().includes('denied: Bash'));
      expect(logs()).toContain('↳ subagent Grep TODO');
      expect(logs()).toContain('denied: Bash (classifier: deletes outside the project)');
    } finally {
      await cleanup();
      rmrf(agentsHome);
    }
  });
});
