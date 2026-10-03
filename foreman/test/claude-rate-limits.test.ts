// Usage limits (claude.ai login): a turn refused by the limit is held and resumed after the reset
// instead of blocking its task, nobody starts a turn meanwhile, and a usage warning throttles workers.
import path from 'node:path';
import type { Options, SDKMessage } from '@anthropic-ai/claude-agent-sdk';
import { afterEach, describe, expect, it } from 'vitest';
import { ClaudeBackend } from '../src/agents/claude/index.js';
import { limitFromText, resetsAtMs } from '../src/agents/claude/stream.js';
import { demoRepo, makeForeman, rmrf, tempDir, until, type Harness } from './helpers.js';

type ToolServer = { instance: { _registeredTools: Record<string, { handler: (a: unknown, e: unknown) => Promise<{ content: Array<{ text: string }> }> }> } };

async function callTool(options: Options, name: string, args: Record<string, unknown>): Promise<string> {
  const server = options.mcpServers!.agentcraft as unknown as ToolServer;
  const res = await server.instance._registeredTools[name]!.handler(args, {});
  return res.content.map((c) => c.text).join('\n');
}

let n = 0;
const sid = () => `00000000-0000-4000-8000-${String(++n).padStart(12, '0')}`;
const msg = (o: Record<string, unknown>) => ({ parent_tool_use_id: null, uuid: sid(), ...o }) as unknown as SDKMessage;
const init = (s: string) => msg({ type: 'system', subtype: 'init', session_id: s, model: 'fake-model', cwd: '', tools: [] });
const ok = (s: string) => msg({ type: 'result', subtype: 'success', is_error: false, result: 'ok', num_turns: 1, total_cost_usd: 0, session_id: s, duration_ms: 1, duration_api_ms: 1, usage: {}, modelUsage: {}, permission_denials: [] });
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

type Turn = (p: string, opts: Options) => AsyncGenerator<SDKMessage>;

function fakeQuery(turn: Turn) {
  return ({ prompt, options }: { prompt: string; options?: Options }) =>
    Object.assign(turn(String(prompt), options!), { close() {}, accountInfo: async () => ({ email: 'x' }) });
}

let h: Harness | undefined;
let home: string;
let repoPath: string;

async function start(turn: Turn, args: string[]): Promise<Harness> {
  home = tempDir();
  repoPath = await demoRepo();
  h = makeForeman(home, ['--backend', 'claude', '--repo', repoPath, '--no-lead-review', ...args]);
  await h.fm.start(new ClaudeBackend(h.fm, h.cfg.claude, { queryFn: fakeQuery(turn) as never, skipAuthCheck: true }));
  return h;
}

afterEach(async () => {
  if (!h) return;
  await h.fm.close();
  rmrf(home);
  rmrf(path.dirname(repoPath));
  h = undefined;
});

describe('limitFromText', () => {
  it('recognises usage-limit errors and their reset time', () => {
    expect(limitFromText('Claude AI usage limit reached|1759500000')).toEqual({ limited: true, resetsAt: 1759500000_000 });
    expect(limitFromText('API Error: 429 rate_limit_error')).toEqual({ limited: true });
    expect(limitFromText('Reached maximum number of turns (80): turn limit reached')).toEqual({ limited: false });
    expect(resetsAtMs(1759500000)).toBe(1759500000_000);
    expect(resetsAtMs(1759500000_000)).toBe(1759500000_000);
  });
});

describe('usage limits in the claude backend', () => {
  it('holds a turn refused by the limit and resumes it after the reset', async () => {
    const workCalls: Array<{ prompt: string; resume?: string; at: number }> = [];
    let resetAt = 0;
    const fm = (
      await start(async function* (p, opts) {
        const s = sid();
        yield init(s);
        if (p.startsWith('New goal')) {
          await callTool(opts, 'create_task', { title: 'Do it', assignee: 'kit' });
          yield ok(s);
          return;
        }
        workCalls.push({ prompt: p, ...(opts.resume ? { resume: opts.resume } : {}), at: Date.now() });
        if (workCalls.length === 1) {
          resetAt = Date.now() + 1500;
          yield msg({ type: 'rate_limit_event', session_id: s, rate_limit_info: { status: 'rejected', resetsAt: resetAt, rateLimitType: 'five_hour' } });
          yield msg({ type: 'result', subtype: 'success', is_error: true, result: 'Claude AI usage limit reached', num_turns: 1, total_cost_usd: 0, session_id: s, duration_ms: 1, duration_api_ms: 1, usage: {}, modelUsage: {}, permission_denials: [] });
          return;
        }
        const task = /t\d+/.exec(fm.tasks.list()[0]!.id)![0];
        await callTool(opts, 'update_task', { task_id: task, status: 'review', summary: 'done after the reset' });
        yield ok(s);
      }, ['--workers', 'kit'])
    ).fm;

    await fm.submitGoal('do it');
    await until(() => workCalls.length === 1 && fm.agent('kit')!.activity.startsWith('usage limit'));
    const t = fm.tasks.list()[0]!;
    expect(t.status).toBe('doing'); // not blocked
    expect(fm.status.message).toMatch(/Usage limit reached \(five hour\)/);
    expect(fm.store.data.feed.some((f) => /Usage limit reached/.test(f.text))).toBe(true);

    await until(() => workCalls.length === 2, 10_000);
    expect(workCalls[1]!.at).toBeGreaterThanOrEqual(resetAt);
    expect(workCalls[1]!.prompt).toMatch(/A usage limit stopped your last turn/);
    expect(workCalls[1]!.resume).toBeDefined();
    await until(() => fm.tasks.get(t.id)!.status !== 'doing');
    expect(fm.status.message).toMatch(/^Claude \(lead/);
  });

  it('runs fewer workers at once while the plan reports a usage warning', async () => {
    let running = 0;
    let peak = 0;
    let worked = 0;
    const fm = (
      await start(async function* (p, opts) {
        const s = sid();
        yield init(s);
        if (p.startsWith('New goal')) {
          yield msg({ type: 'rate_limit_event', session_id: s, rate_limit_info: { status: 'allowed_warning', utilization: 0.92, resetsAt: Date.now() + 60_000, rateLimitType: 'seven_day' } });
          await callTool(opts, 'create_task', { title: 'One', assignee: 'kit' });
          await callTool(opts, 'create_task', { title: 'Two', assignee: 'juniper' });
          yield ok(s);
          return;
        }
        const task = /Your task: (t\d+)/.exec(p)?.[1];
        running++;
        peak = Math.max(peak, running);
        await sleep(300);
        running--;
        if (task) await callTool(opts, 'update_task', { task_id: task, status: 'review', summary: 'ok' });
        worked++;
        yield ok(s);
      }, ['--workers', 'kit,juniper', '--max-concurrent', '2'])
    ).fm;

    await fm.submitGoal('two things');
    await until(() => worked >= 2, 10_000);
    expect(peak).toBe(1);
    expect(fm.store.data.feed.some((f) => /Usage warning \(seven day\) \(92%\): 1 worker/.test(f.text))).toBe(true);
  });
});
