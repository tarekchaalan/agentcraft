// Plan usage windows in foreman.status.usage: from rate_limit_event and the CLI's /usage data.
import path from 'node:path';
import type { Options, SDKMessage } from '@anthropic-ai/claude-agent-sdk';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { ClaudeBackend } from '../src/agents/claude/index.js';
import { pruneUsage, readPlanUsage, usageLine, withWindow } from '../src/agents/claude/usage.js';
import { ForemanStatus } from '../src/protocol.js';
import { demoRepo, makeForeman, rmrf, tempDir, until, type Harness } from './helpers.js';

const USAGE = 'usage_EXPERIMENTAL_MAY_CHANGE_DO_NOT_RELY_ON_THIS_API_YET';

describe('usage snapshot', () => {
  it('merges windows, orders them and drops stale ones', () => {
    let u = withWindow(undefined, 'seven_day', 18.4, 5_000, 1);
    u = withWindow(u, 'five_hour', 41.6, 2_000, 2);
    u = withWindow(u, 'seven_day_overage_included', 20, 5_000, 3); // same window as seven_day
    expect(usageLine(u)).toBe('5h 42% · 7d 20%');
    expect(u.updatedAt).toBe(3);
    expect(usageLine(pruneUsage(u, 3_000))).toBe('7d 20%');
    expect(pruneUsage(u, 9_000)).toBeUndefined();
    expect(ForemanStatus.safeParse({ version: '1', backend: 'claude', auth: 'ok', usage: u }).success).toBe(true);
  });

  it('reads the /usage windows when the SDK offers them, and nothing otherwise', async () => {
    const q = {
      [USAGE]: async () => ({
        rate_limits_available: true,
        rate_limits: { five_hour: { utilization: 12, resets_at: '2030-01-01T05:00:00Z' }, seven_day: { utilization: 61, resets_at: null }, extra_usage: { utilization: 3 } },
      }),
    };
    const u = await readPlanUsage(q, undefined);
    expect(usageLine(u)).toBe('5h 12% · 7d 61%');
    expect(u!.windows[0]!.resetsAt).toBe(Date.parse('2030-01-01T05:00:00Z'));
    expect(await readPlanUsage({}, undefined)).toBeUndefined(); // renamed or removed
    expect(await readPlanUsage({ [USAGE]: async () => ({ rate_limits_available: false, rate_limits: null }) }, undefined)).toBeUndefined(); // API key
    expect(await readPlanUsage({ [USAGE]: async () => { throw new Error('x'); } }, undefined)).toBeUndefined();
  });
});

describe('usage in the claude backend', () => {
  let h: Harness;
  let home: string;
  let repoPath: string;
  let n = 0;
  const sid = () => `00000000-0000-4000-8000-${String(++n).padStart(12, '0')}`;
  const msg = (o: Record<string, unknown>) => ({ parent_tool_use_id: null, uuid: sid(), ...o }) as unknown as SDKMessage;

  beforeAll(async () => {
    home = tempDir();
    repoPath = await demoRepo();
    h = makeForeman(home, ['--backend', 'claude', '--workers', 'kit', '--repo', repoPath, '--use-claude-login']);
    const queryFn = ({ options }: { prompt: string; options?: Options }) => {
      void options;
      async function* run(): AsyncGenerator<SDKMessage> {
        const s = sid();
        yield msg({ type: 'system', subtype: 'init', session_id: s, model: 'fake-model', cwd: '', tools: [] });
        yield msg({ type: 'rate_limit_event', session_id: s, rate_limit_info: { status: 'allowed', utilization: 0.3, rateLimitType: 'five_hour', resetsAt: Date.now() + 3_600_000 } });
        yield msg({ type: 'result', subtype: 'success', is_error: false, result: 'ok', num_turns: 1, total_cost_usd: 0, session_id: s, duration_ms: 1, duration_api_ms: 1, usage: {}, modelUsage: {}, permission_denials: [] });
      }
      return Object.assign(run(), {
        close() {},
        accountInfo: async () => ({ email: 'x' }),
        [USAGE]: async () => ({ rate_limits_available: true, rate_limits: { seven_day: { utilization: 55, resets_at: new Date(Date.now() + 86_400_000).toISOString() } } }),
      });
    };
    await h.fm.start(new ClaudeBackend(h.fm, h.cfg.claude, { queryFn: queryFn as never, skipAuthCheck: true }));
  });

  afterAll(async () => {
    await h.fm.close();
    rmrf(home);
    rmrf(path.dirname(repoPath));
  });

  it('puts both sources into foreman.status.usage', async () => {
    await h.fm.submitGoal('anything');
    await until(() => usageLine(h.fm.status.usage) === '5h 30% · 7d 55%');
    expect(h.events.some((e) => e.type === 'foreman.status' && e.status.usage !== undefined)).toBe(true);
  });
});
