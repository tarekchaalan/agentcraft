// Claude Code context for agents (claude.context): instruction files, skills, extra MCP servers.
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import type { Options, SDKMessage } from '@anthropic-ai/claude-agent-sdk';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { ClaudeBackend } from '../src/agents/claude/index.js';
import { buildSkillsPlugin, DEFAULT_CONTEXT, instructionsBlock, readInstructions, stripAllowedTools } from '../src/agents/claude/context.js';
import { loadConfig } from '../src/config.js';
import { classifyToolUse } from '../src/policy.js';
import { demoRepo, makeForeman, rmrf, tempDir, until, type Harness } from './helpers.js';

const write = (p: string, s: string) => {
  fs.mkdirSync(path.dirname(p), { recursive: true });
  fs.writeFileSync(p, s);
};

describe('readInstructions', () => {
  it('expands @imports (relative, ~, nested) once, outside code', () => {
    const home = tempDir();
    const dir = tempDir();
    try {
      write(path.join(home, '.codex', 'AGENTS.md'), 'MACHINE FACTS\n@nested.md');
      write(path.join(home, '.codex', 'nested.md'), 'NESTED');
      write(path.join(dir, 'docs', 'style.md'), 'STYLE RULES');
      write(
        path.join(dir, 'CLAUDE.md'),
        ['@~/.codex/AGENTS.md', 'See @docs/style.md for style.', 'Again @docs/style.md', 'Not `@docs/style.md` in code', '```', '@docs/style.md', '```', 'mail me@example.com', '@missing.md'].join('\n'),
      );
      const out = readInstructions(path.join(dir, 'CLAUDE.md'), { home });
      expect(out).toContain('MACHINE FACTS\nNESTED');
      expect(out).toContain('See STYLE RULES for style.');
      expect(out).toContain('Again @docs/style.md'); // already included
      expect(out).toContain('Not `@docs/style.md` in code');
      expect(out).toContain('```\n@docs/style.md\n```');
      expect(out).toContain('mail me@example.com');
      expect(out).toContain('@missing.md');
    } finally {
      rmrf(home);
      rmrf(dir);
    }
  });

  it('survives import cycles', () => {
    const dir = tempDir();
    try {
      write(path.join(dir, 'a.md'), 'A @b.md');
      write(path.join(dir, 'b.md'), 'B @a.md');
      expect(readInstructions(path.join(dir, 'a.md'))).toBe('A B @a.md');
    } finally {
      rmrf(dir);
    }
  });
});

describe('instructionsBlock', () => {
  it('includes repo files by default and the user CLAUDE.md only when asked', () => {
    const home = tempDir();
    const repo = tempDir();
    try {
      write(path.join(home, '.claude', 'CLAUDE.md'), 'USER GLOBAL');
      write(path.join(repo, 'CLAUDE.md'), 'REPO CLAUDE @AGENTS.md');
      write(path.join(repo, 'AGENTS.md'), 'REPO AGENTS');
      const extra = path.join(home, 'notes.md');
      write(extra, 'EXTRA FILE');
      const def = instructionsBlock(DEFAULT_CONTEXT, repo, 'Alex', home);
      expect(def).toContain('REPO CLAUDE REPO AGENTS');
      expect(def.match(/REPO AGENTS/g)).toHaveLength(1); // imported, not repeated
      expect(def).not.toContain('USER GLOBAL');
      expect(def).toContain('take precedence');
      const all = instructionsBlock({ ...DEFAULT_CONTEXT, userInstructions: true, files: ['~/notes.md'] }, repo, 'Alex', home);
      expect(all.indexOf('USER GLOBAL')).toBeLessThan(all.indexOf('EXTRA FILE'));
      expect(all.indexOf('EXTRA FILE')).toBeLessThan(all.indexOf('REPO CLAUDE'));
      expect(instructionsBlock({ ...DEFAULT_CONTEXT, repoInstructions: false }, repo, 'Alex', home)).toBe('');
      expect(instructionsBlock({ ...DEFAULT_CONTEXT, maxChars: 10 }, repo, 'Alex', home)).toContain('truncated at 10 characters');
    } finally {
      rmrf(home);
      rmrf(repo);
    }
  });
});

describe('skills plugin', () => {
  it('strips allowed-tools from SKILL.md front matter', () => {
    expect(stripAllowedTools('---\nname: x\nallowed-tools: Bash(*), Read\ndescription: d\n---\nbody')).toBe('---\nname: x\ndescription: d\n---\nbody');
    expect(stripAllowedTools('---\nname: x\nallowed-tools:\n  - Bash\n  - Edit\ndescription: d\n---\nbody')).toBe('---\nname: x\ndescription: d\n---\nbody');
    expect(stripAllowedTools('no front matter')).toBe('no front matter');
  });

  it('copies the configured skills into a plugin', () => {
    const home = tempDir();
    const out = tempDir();
    try {
      write(path.join(home, '.claude', 'skills', 'roadmap', 'SKILL.md'), '---\nname: roadmap\ndescription: r\nallowed-tools: Bash(*)\n---\nRoadmap body');
      write(path.join(home, '.claude', 'skills', 'roadmap', 'scripts', 'x.sh'), 'echo hi');
      write(path.join(home, 'elsewhere', 'deploy', 'SKILL.md'), '---\nname: deploy\ndescription: d\n---\nDeploy');
      const dir = path.join(out, 'plugin');
      const res = buildSkillsPlugin(['roadmap', '~/elsewhere/deploy', 'nope'], dir, home);
      expect(res.ids).toEqual(['agentcraft-skills:roadmap', 'agentcraft-skills:deploy']);
      expect(res.problems).toEqual([expect.stringContaining('skill nope')]);
      expect(JSON.parse(fs.readFileSync(path.join(dir, '.claude-plugin', 'plugin.json'), 'utf8')).name).toBe('agentcraft-skills');
      expect(fs.readFileSync(path.join(dir, 'skills', 'roadmap', 'SKILL.md'), 'utf8')).not.toContain('allowed-tools');
      expect(fs.existsSync(path.join(dir, 'skills', 'roadmap', 'scripts', 'x.sh'))).toBe(true);
      // the source is untouched
      expect(fs.readFileSync(path.join(home, '.claude', 'skills', 'roadmap', 'SKILL.md'), 'utf8')).toContain('allowed-tools');
      expect(buildSkillsPlugin([], dir, home).ids).toEqual([]);
      expect(fs.existsSync(dir)).toBe(false);
    } finally {
      rmrf(home);
      rmrf(out);
    }
  });
});

describe('policy with context', () => {
  const ctx = { role: 'worker' as const, cwd: '/tmp/wt', mcpServer: 'agentcraft' };
  it('refuses the Skill tool unless the skill is enabled', () => {
    expect(classifyToolUse('Skill', { skill: 'roadmap' }, ctx).action).toBe('deny');
    const withSkills = { ...ctx, skills: ['agentcraft-skills:roadmap'] };
    expect(classifyToolUse('Skill', { skill: 'roadmap' }, withSkills).action).toBe('allow');
    expect(classifyToolUse('Skill', { skill: 'agentcraft-skills:roadmap' }, withSkills).action).toBe('allow');
    expect(classifyToolUse('Skill', { skill: 'cf-infra' }, withSkills).action).toBe('deny');
  });

  it('allows MCP tools matching mcpAllow and asks for the rest', () => {
    const c = { ...ctx, mcpAllow: ['mcp__xcode__*', 'mcp__docs__search'] };
    expect(classifyToolUse('mcp__xcode__build', {}, c).action).toBe('allow');
    expect(classifyToolUse('mcp__docs__search', {}, c).action).toBe('allow');
    expect(classifyToolUse('mcp__docs__write', {}, c).action).toBe('ask');
    expect(classifyToolUse('mcp__other__x', { }, { ...ctx, mcpAllow: ['mcp__*'] }).action).toBe('ask');
  });
});

describe('config', () => {
  it('reads claude.context and refuses to replace the agentcraft server', () => {
    const home = tempDir();
    try {
      write(
        path.join(home, 'config.json'),
        JSON.stringify({
          claude: {
            context: {
              userInstructions: true,
              files: ['~/.codex/AGENTS.md', 3],
              skills: ['roadmap-keeper'],
              mcpServers: { xcode: { command: 'xcodebuildmcp' }, agentcraft: { command: 'evil' } },
              mcpAllow: ['mcp__xcode__*', 'Bash', 'mcp__agentcraft__x'],
            },
          },
        }),
      );
      const c = loadConfig(['--home', home], {}).claude.context;
      expect(c.userInstructions).toBe(true);
      expect(c.repoInstructions).toBe(true);
      expect(c.files).toEqual(['~/.codex/AGENTS.md']);
      expect(Object.keys(c.mcpServers)).toEqual(['xcode']);
      expect(c.mcpAllow).toEqual(['mcp__xcode__*']);
      expect(loadConfig(['--home', tempDir()], {}).claude.context).toEqual(DEFAULT_CONTEXT);
    } finally {
      rmrf(home);
    }
  });
});

// ---- the options a real session gets -----------------------------------------------------------

type ToolServer = { instance: { _registeredTools: Record<string, { handler: (a: unknown, e: unknown) => Promise<{ content: Array<{ text: string }> }> }> } };
let n = 0;
const sid = () => `00000000-0000-4000-8000-${String(++n).padStart(12, '0')}`;
const msg = (o: Record<string, unknown>) => ({ parent_tool_use_id: null, uuid: sid(), ...o }) as unknown as SDKMessage;

describe('claude backend sessions', () => {
  let h: Harness;
  let home: string;
  let repoPath: string;
  let skillHome: string;
  const seen: Array<{ role: string; options: Options; mcpVerdict?: string }> = [];

  beforeAll(async () => {
    home = tempDir();
    skillHome = tempDir();
    repoPath = await demoRepo();
    write(path.join(repoPath, 'CLAUDE.md'), 'REPO RULE: use the demo style');
    execFileSync('git', ['-C', repoPath, 'add', 'CLAUDE.md']);
    execFileSync('git', ['-C', repoPath, '-c', 'user.name=t', '-c', 'user.email=t@t', 'commit', '-qm', 'claude.md']);
    write(path.join(skillHome, 'ship', 'SKILL.md'), '---\nname: ship\ndescription: s\nallowed-tools: Bash(*)\n---\nShip it');
    write(
      path.join(home, 'config.json'),
      JSON.stringify({ claude: { context: { skills: [path.join(skillHome, 'ship')], mcpServers: { docs: { type: 'http', url: 'http://127.0.0.1:1/mcp' } }, mcpAllow: ['mcp__docs__*'] } } }),
    );
    h = makeForeman(home, ['--backend', 'claude', '--workers', 'kit', '--repo', repoPath, '--no-lead-review']);
    const queryFn = ({ prompt, options }: { prompt: string; options?: Options }) => {
      const opts = options!;
      async function* run(): AsyncGenerator<SDKMessage> {
        const s = sid();
        yield msg({ type: 'system', subtype: 'init', session_id: s, model: 'fake-model', cwd: '', tools: [] });
        const server = opts.mcpServers!.agentcraft as unknown as ToolServer;
        if (String(prompt).startsWith('New goal')) {
          seen.push({ role: 'lead', options: opts });
          await server.instance._registeredTools.create_task!.handler({ title: 'Do it', assignee: 'kit' }, {});
        } else if (/Your task/.test(String(prompt))) {
          const v = await opts.canUseTool!('mcp__docs__lookup', {}, { signal: new AbortController().signal, toolUseID: 'x', requestId: 'r' } as never);
          seen.push({ role: 'worker', options: opts, mcpVerdict: v?.behavior ?? "none" });
        }
        yield msg({ type: 'result', subtype: 'success', is_error: false, result: 'ok', num_turns: 1, total_cost_usd: 0, session_id: s, duration_ms: 1, duration_api_ms: 1, usage: {}, modelUsage: {}, permission_denials: [] });
      }
      return Object.assign(run(), { close() {}, accountInfo: async () => ({ email: 'x' }) });
    };
    await h.fm.start(new ClaudeBackend(h.fm, h.cfg.claude, { queryFn: queryFn as never, skipAuthCheck: true }));
  });

  afterAll(async () => {
    await h.fm.close();
    rmrf(home);
    rmrf(skillHome);
    rmrf(path.dirname(repoPath));
  });

  it('gives lead and workers the instructions, skills and MCP servers, and keeps settingSources off', async () => {
    await h.fm.submitGoal('do it');
    await until(() => seen.some((s) => s.role === 'worker'));
    for (const s of seen) {
      const o = s.options;
      expect(o.settingSources).toEqual([]);
      expect((o.systemPrompt as { append: string }).append).toContain('REPO RULE: use the demo style');
      expect(o.tools).toContain('Skill');
      expect(o.skills).toEqual(['agentcraft-skills:ship']);
      expect(o.plugins?.[0]?.path).toBe(path.join(h.cfg.dataDir, 'agent-plugin'));
      expect(Object.keys(o.mcpServers!).sort()).toEqual(['agentcraft', 'docs']);
    }
    const worker = seen.find((s) => s.role === 'worker')!;
    expect(worker.options.tools).toContain('Bash');
    expect(worker.mcpVerdict).toBe('allow');
    expect(fs.readFileSync(path.join(h.cfg.dataDir, 'agent-plugin', 'skills', 'ship', 'SKILL.md'), 'utf8')).not.toContain('allowed-tools');
  });
});
