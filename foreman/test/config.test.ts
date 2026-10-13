import fs from 'node:fs';
import path from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { loadConfig } from '../src/config.js';
import { rmrf, tempDir } from './helpers.js';

let home: string | undefined;
afterEach(() => {
  if (home) rmrf(home);
  home = undefined;
});

function load(args: string[]) {
  home = tempDir();
  return loadConfig(['--home', home, ...args], {});
}

describe('loadConfig argument checking', () => {
  it('accepts the documented claude flags', () => {
    const cfg = load(['--backend', 'claude', '--workers', 'juniper,kit', '--model', 'sonnet', '--effort', 'low', '--no-notify', '--max-budget', '2']);
    expect(cfg.claude.workers).toEqual(['juniper', 'kit']);
    expect(cfg.claude.leadModel).toBe('sonnet');
    expect(cfg.claude.workerModel).toBe('sonnet');
    expect(cfg.claude.effort).toBe('low');
    expect(cfg.claude.leadEffort).toBe('low');
    expect(cfg.notify).toBe(false);
  });

  it('reads lead read commands as a comma list or a config.json array', () => {
    home = tempDir();
    const read = (args: string[], env = {}) => loadConfig(['--home', home!, ...args], env).claude.leadReadCommands;
    expect(read(['--lead-read-commands', 'bd show, bd list,'])).toEqual(['bd show', 'bd list']);
    expect(read([], { AGENTCRAFT_LEAD_READ_COMMANDS: 'bd show' })).toEqual(['bd show']);
    fs.writeFileSync(path.join(home, 'config.json'), JSON.stringify({ claude: { leadReadCommands: ['bd show'] } }));
    expect(read([])).toEqual(['bd show']);
  });

  it('rejects lead read commands that could write or run code', () => {
    home = tempDir();
    for (const bad of ['rm', 'git status', './bd show', 'bd show; rm x', 'bd show > f', 'bash', 'bd $(x)']) {
      expect(() => loadConfig(['--home', home!, '--lead-read-commands', bad], {})).toThrow(/lead read command/);
    }
  });

  it('accepts the sim flags launch.ps1 passes', () => {
    const cfg = load(['--backend', 'sim', '--profile', 'x', '--port', '41000', '--reset', '--showcase', 'late', '--speed', '2', '--autostart']);
    expect(cfg.sim.showcaseAt).toBe('showcase-late');
    expect(cfg.sim.speed).toBe(2);
  });

  it('preserves every repeated repo flag passed by the Unix launcher', () => {
    const cfg = load(['--backend', 'sim', '--repo', '/repos/first', '--repo', '/repos/second']);
    expect(cfg.repos).toEqual(['/repos/first', '/repos/second']);
  });

  it('accepts repeated equals-form repo flags and legacy comma-separated lists together', () => {
    const cfg = load(['--repo=/repos/first', '--repo', ' /repos/second, /repos/third, ', '--repo=/repos/fourth']);
    expect(cfg.repos).toEqual(['/repos/first', '/repos/second', '/repos/third', '/repos/fourth']);
  });

  it('continues to use the final value for non-repeatable flags', () => {
    const cfg = load(['--backend', 'claude', '--backend=sim', '--port', '41000', '--port=41001']);
    expect(cfg.backend).toBe('sim');
    expect(cfg.port).toBe(41001);
  });

  // regression: PowerShell `-File launch.ps1 -ForemanArgs '--workers,juniper,kit,--model,sonnet'` hands
  // the Foreman ONE argument; it used to be ignored silently and the team started on opus/medium/3 workers
  it('refuses an argument that PowerShell joined with commas', () => {
    expect(() => load(['--backend', 'claude', '--workers,juniper,kit,--model,sonnet,--effort,low'])).toThrow(/unknown option "--workers,juniper,kit,--model,sonnet,--effort,low"/);
  });

  it('refuses mistyped flags and stray positionals', () => {
    expect(() => load(['--wokers', 'kit'])).toThrow(/unknown option "--wokers"/);
    expect(() => load(['--backend', 'sim', 'oops', 'extra'])).toThrow(/unexpected argument "oops"/);
  });

  it('refuses an unknown effort instead of falling back to medium', () => {
    expect(() => load(['--effort', 'lo'])).toThrow(/unknown effort "lo"/);
  });
});

describe('claude.leadFallback', () => {
  const owner = { leadModel: 'claude-fable-5-1', workerModel: 'claude-opus-5-5', effort: 'high', leadEffort: 'high', leadFallback: { model: 'claude-opus-5-5', effort: 'xhigh' } };
  function withFile(claude: Record<string, unknown> | undefined, args: string[] = [], env: NodeJS.ProcessEnv = {}) {
    home ??= tempDir();
    if (claude) fs.writeFileSync(path.join(home, 'config.json'), JSON.stringify({ claude }));
    else fs.rmSync(path.join(home, 'config.json'), { force: true });
    return loadConfig(['--home', home, ...args], env).claude;
  }

  it('is off by default, and the shipped defaults stay opus / sonnet / medium', () => {
    const c = load([]).claude;
    expect(c.leadFallback).toBeUndefined();
    expect(c).toMatchObject({ leadModel: 'opus', workerModel: 'sonnet', effort: 'medium', leadEffort: 'medium' });
  });

  it('reads config.json claude.leadFallback {model, effort}; the effort is optional (the lead\'s own then)', () => {
    expect(withFile(owner)).toMatchObject({ leadModel: 'claude-fable-5-1', leadEffort: 'high', leadFallback: { model: 'claude-opus-5-5', effort: 'xhigh' } });
    expect(withFile({ leadFallback: { model: 'claude-opus-5-5' } }).leadFallback).toEqual({ model: 'claude-opus-5-5' });
  });

  it('config.json < env < flags, field by field', () => {
    const env = { AGENTCRAFT_LEAD_FALLBACK_MODEL: 'sonnet' };
    expect(withFile(owner, [], env).leadFallback).toEqual({ model: 'sonnet', effort: 'xhigh' });
    expect(withFile(owner, [], { ...env, AGENTCRAFT_LEAD_FALLBACK_EFFORT: 'max' }).leadFallback).toEqual({ model: 'sonnet', effort: 'max' });
    expect(withFile(owner, ['--lead-fallback-model', 'claude-haiku-4-5'], env).leadFallback).toEqual({ model: 'claude-haiku-4-5', effort: 'xhigh' });
    expect(withFile(owner, ['--lead-fallback-effort', 'low'], { AGENTCRAFT_LEAD_FALLBACK_EFFORT: 'max' }).leadFallback).toEqual({ model: 'claude-opus-5-5', effort: 'low' });
    // flags and env alone, without a config.json
    expect(withFile(undefined, ['--lead-fallback-model=opus', '--lead-fallback-effort=xhigh']).leadFallback).toEqual({ model: 'opus', effort: 'xhigh' });
    expect(withFile(undefined, [], { AGENTCRAFT_LEAD_FALLBACK_MODEL: 'opus', AGENTCRAFT_LEAD_FALLBACK_EFFORT: 'high' }).leadFallback).toEqual({ model: 'opus', effort: 'high' });
  });

  it('--no-lead-fallback-model turns off a fallback set in config.json or the environment', () => {
    expect(withFile(owner, ['--no-lead-fallback-model']).leadFallback).toBeUndefined();
    expect(withFile(undefined, ['--no-lead-fallback-model'], { AGENTCRAFT_LEAD_FALLBACK_MODEL: 'opus' }).leadFallback).toBeUndefined();
    // --no-lead-fallback-effort: the lead's own effort
    expect(withFile(owner, ['--no-lead-fallback-effort']).leadFallback).toEqual({ model: 'claude-opus-5-5' });
  });

  it('validates the effort like the other efforts, wherever it comes from, and refuses one without a model', () => {
    expect(() => withFile({ leadFallback: { model: 'opus', effort: 'extra' } })).toThrow(/unknown effort "extra" \(use low, medium, high, xhigh, max\)/);
    expect(() => withFile(undefined, ['--lead-fallback-model', 'opus'], { AGENTCRAFT_LEAD_FALLBACK_EFFORT: 'hi' })).toThrow(/unknown effort "hi"/);
    expect(() => withFile(undefined, ['--lead-fallback-model', 'opus', '--lead-fallback-effort', 'x-high'])).toThrow(/unknown effort "x-high"/);
    expect(() => withFile(undefined, ['--lead-fallback-model', 'opus', '--lead-fallback-effort'])).toThrow(/unknown effort "true"/);
    // a typo is caught even when the model comes from nowhere
    expect(() => withFile(undefined, ['--lead-fallback-effort', 'xhigh'])).toThrow(/a lead fallback effort needs a fallback model/);
    expect(() => withFile({ leadFallback: { effort: 'xhigh' } })).toThrow(/needs a fallback model/);
    for (const v of ['xhigh', 'max', 'low']) expect(withFile(undefined, ['--lead-fallback-model', 'opus', '--lead-fallback-effort', v]).leadFallback?.effort).toBe(v);
  });

  it('refuses a claude.leadFallback that is not {model, effort?}', () => {
    expect(() => withFile({ leadFallback: 'claude-opus-5-5' })).toThrow(/claude\.leadFallback must be an object/);
    expect(() => withFile({ leadFallback: ['claude-opus-5-5'] })).toThrow(/must be an object/);
    expect(() => withFile({ leadFallback: null })).toThrow(/must be an object/);
    expect(() => withFile({ leadFallback: { model: 5 } })).toThrow(/leadFallback\.model must be a model id/);
    expect(() => withFile({ leadFallback: { model: ' ' } })).toThrow(/leadFallback\.model must be a model id/);
  });
});

describe('use-claude-login', () => {
  it('comes from --use-claude-login, AGENTCRAFT_USE_CLAUDE_LOGIN or config.json claude.useClaudeLogin (not a top-level key)', () => {
    home = tempDir();
    const login = (args: string[] = [], env: NodeJS.ProcessEnv = {}) => loadConfig(['--home', home!, ...args], env).claude.useClaudeLogin;
    expect(login()).toBe(false);
    expect(login(['--use-claude-login'])).toBe(true);
    expect(login([], { AGENTCRAFT_USE_CLAUDE_LOGIN: '1' })).toBe(true);
    fs.writeFileSync(path.join(home, 'config.json'), JSON.stringify({ useClaudeLogin: true }));
    expect(login()).toBe(false); // the key path is claude.useClaudeLogin
    fs.writeFileSync(path.join(home, 'config.json'), JSON.stringify({ claude: { useClaudeLogin: true } }));
    expect(login()).toBe(true);
    // flag and env still decide over config.json
    expect(login([], { AGENTCRAFT_USE_CLAUDE_LOGIN: '0' })).toBe(false);
    expect(login(['--use-claude-login'], { AGENTCRAFT_USE_CLAUDE_LOGIN: '0' })).toBe(true);
  });
});

describe('user name', () => {
  it('comes from --user-name, then AGENTCRAFT_USER_NAME, then config.json, else the OS account', async () => {
    const fs = await import('node:fs');
    const path = await import('node:path');
    const { defaultUserName } = await import('../src/user.js');
    expect(load(['--user-name', 'Sam']).userName).toBe('Sam');
    home = tempDir();
    expect(loadConfig(['--home', home], { AGENTCRAFT_USER_NAME: 'Robin' }).userName).toBe('Robin');
    fs.writeFileSync(path.join(home, 'config.json'), JSON.stringify({ userName: 'Kai' }));
    expect(loadConfig(['--home', home], {}).userName).toBe('Kai');
    rmrf(home);
    const d = load([]).userName;
    expect(d).toBe(defaultUserName());
    expect(d.length).toBeGreaterThan(0);
  });

  it('is sent to the mod in foreman.status and used in prompts', async () => {
    const { makeForeman } = await import('./helpers.js');
    const { leadSystemPrompt } = await import('../src/agents/prompts.js');
    home = tempDir();
    const h = makeForeman(home, ['--user-name', 'Sam']);
    try {
      expect(h.fm.status.userName).toBe('Sam');
      expect(h.fm.nameOf('user')).toBe('Sam');
      expect(leadSystemPrompt(h.fm, ['kit'])).toContain('The user is Sam.');
    } finally {
      await h.fm.close();
    }
  });
});

describe('claude.context: instruction files, mcpServers, mcpAllow', () => {
  function loadWith(claude: Record<string, unknown>, args: string[] = []) {
    home = tempDir();
    fs.writeFileSync(path.join(home, 'config.json'), JSON.stringify({ claude }));
    return loadConfig(['--home', home, ...args], {});
  }
  const engram = { engram: { command: 'engram', args: ['mcp', '--tools=agent'] } };

  it('has repo instructions on and no files or MCP servers by default', () => {
    expect(load([]).claude.context).toMatchObject({ repoInstructions: true, files: [], mcpServers: {}, mcpAllow: [] });
  });

  it('reads stdio servers and mcpAllow (exact names or mcp__<server>__*); relative files resolve against the home', () => {
    const c = loadWith({ context: { files: ['mine.md', '~/x.md'], mcpServers: { ...engram, xcode: { type: 'stdio', command: 'xcodebuildmcp', env: { A: '1' } } }, mcpAllow: ['mcp__engram__mem_search', 'mcp__xcode__*'] } }).claude.context;
    expect(c.mcpServers).toEqual({ ...engram, xcode: { command: 'xcodebuildmcp', env: { A: '1' } } });
    expect(c.mcpAllow).toEqual(['mcp__engram__mem_search', 'mcp__xcode__*']);
    expect(c.files).toEqual([path.join(home!, 'mine.md'), '~/x.md']);
  });

  it('refuses server names that could inherit the agentcraft approval, non-stdio servers, and bad mcpAllow entries', () => {
    for (const name of ['agentcraft', 'agentcraft-x', 'agentcraft__x', 'a__b', 'Eng']) expect(() => loadWith({ context: { mcpServers: { [name]: { command: 'x' } } } })).toThrow(/bad MCP server name/);
    expect(() => loadWith({ context: { mcpServers: { docs: { type: 'http', url: 'http://127.0.0.1:1/mcp' } } } })).toThrow(/only stdio servers/);
    expect(() => loadWith({ context: { mcpServers: { docs: { args: ['x'] } } } })).toThrow(/needs a "command"/);
    expect(() => loadWith({ context: { mcpServers: { docs: { command: 'x', args: 'y' } } } })).toThrow(/args must be a list/);
    expect(() => loadWith({ context: { mcpServers: engram, mcpAllow: ['mem_search'] } })).toThrow(/bad mcpAllow/);
    expect(() => loadWith({ context: { mcpServers: engram, mcpAllow: ['mcp__github__create_issue'] } })).toThrow(/bad mcpAllow/);
    expect(() => loadWith({ context: { mcpServers: engram, mcpAllow: ['mcp__engram__mem_*'] } })).toThrow(/bad mcpAllow/);
    expect(() => loadWith({ context: { mcpServers: engram, mcpAllow: ['mcp__*'] } })).toThrow(/bad mcpAllow/);
    expect(() => loadWith({ context: { mcpServers: engram, mcpAllow: ['mcp__agentcraft__x'] } })).toThrow(/bad mcpAllow/);
  });

  it('refuses the old top-level keys and names their place under claude.context', () => {
    expect(() => loadWith({ repoContext: true })).toThrow(/claude\.repoContext .*claude\.context\.repoInstructions/);
    expect(() => loadWith({ instructionsFile: 'mine.md' })).toThrow(/claude\.context\.files/);
    expect(() => loadWith({ mcpServers: engram })).toThrow(/claude\.context\.mcpServers/);
    expect(() => loadWith({ mcpAllow: [] })).toThrow(/claude\.context\.mcpAllow/);
  });
});
