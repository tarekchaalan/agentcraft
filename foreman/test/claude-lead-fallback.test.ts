// The lead's fallback model (claude.leadFallback): a lead whose own model hits a usage limit of its
// own (the Fable weekly limit) continues on the fallback model and effort right away, instead of the
// whole team waiting for the reset; an account-wide limit, or a limit on the fallback too, holds as
// before; the lead returns to its own model when the limit resets; workers are not affected; the
// state survives a restart. Fake SDK `query()`, no API calls.
import fs from 'node:fs';
import path from 'node:path';
import type { Options, SDKMessage } from '@anthropic-ai/claude-agent-sdk';
import { afterEach, describe, expect, it } from 'vitest';
import { ClaudeBackend } from '../src/agents/claude/index.js';
import { limitFamilyFromText, limitModelFamily, modelFamily } from '../src/agents/limits.js';
import { demoRepo, makeForeman, rmrf, tempDir, until, type Harness } from './helpers.js';

type ToolServer = { instance: { _registeredTools: Record<string, { handler: (a: unknown, e: unknown) => Promise<{ content: Array<{ text: string }> }> }> } };
const tools = (o: Options) => (o.mcpServers!.agentcraft as unknown as ToolServer).instance._registeredTools;
const callTool = async (o: Options, name: string, args: Record<string, unknown>) => (await tools(o)[name]!.handler(args, {})).content.map((c) => c.text).join('\n');

let n = 0;
const sid = () => `00000000-0000-4000-8000-${String(++n).padStart(12, '0')}`;
const msg = (o: Record<string, unknown>) => ({ parent_tool_use_id: null, uuid: sid(), ...o }) as unknown as SDKMessage;
const init = (s: string, model?: string) => msg({ type: 'system', subtype: 'init', session_id: s, model: model ?? 'fake', cwd: '', tools: [] });
const ok = (s: string) => msg({ type: 'result', subtype: 'success', is_error: false, result: 'ok', num_turns: 1, total_cost_usd: 0, session_id: s, duration_ms: 1, duration_api_ms: 1, usage: {}, modelUsage: {}, permission_denials: [] });
const rejected = (s: string, rateLimitType: string, resetsAt?: number) =>
  msg({ type: 'rate_limit_event', session_id: s, rate_limit_info: { status: 'rejected', rateLimitType, ...(resetsAt ? { resetsAt: Math.floor(resetsAt / 1000) } : {}) } });
/** what the CLI sends when a limit refuses the request: an API error message, then an error result */
function* refused(s: string, text: string): Generator<SDKMessage> {
  yield msg({ type: 'assistant', error: 'rate_limit', session_id: s, message: { role: 'assistant', content: [{ type: 'text', text }] } });
  yield msg({ type: 'result', subtype: 'success', is_error: true, result: text, num_turns: 1, total_cost_usd: 0, session_id: s, duration_ms: 1, duration_api_ms: 1, usage: {}, modelUsage: {}, permission_denials: [] });
}

interface Call {
  lead: boolean;
  prompt: string;
  model?: string;
  effort?: string;
  resume?: string;
  /** the session this fake turn reported */
  session?: string;
  at: number;
}
type Turn = (p: string, opts: Options, call: Call) => AsyncGenerator<SDKMessage>;

const USAGE_METHOD = 'usage_EXPERIMENTAL_MAY_CHANGE_DO_NOT_RELY_ON_THIS_API_YET';

/** `usage`: the plan usage the engine's poll gets (claude.ai login) */
function fakeQuery(turn: Turn, calls: Call[], usage?: () => unknown) {
  return ({ prompt, options }: { prompt: string; options?: Options }) => {
    const opts = options!;
    const base = { close() {}, accountInfo: async () => ({ email: 'x' }) };
    // the usage poll: a throwaway query that never sends a turn
    if (!opts.mcpServers) return { ...base, ...(usage ? { [USAGE_METHOD]: async () => usage() } : {}) };
    const call: Call = { lead: 'create_task' in tools(opts), prompt: String(prompt), at: Date.now(), ...(opts.model ? { model: opts.model } : {}), ...(opts.effort ? { effort: opts.effort } : {}), ...(opts.resume ? { resume: opts.resume } : {}) };
    calls.push(call);
    return Object.assign(turn(String(prompt), opts, call), base);
  };
}

/** The owner's setup: a Fable lead, Opus workers, high effort, Opus xhigh as the lead's fallback. */
const OWNER = { leadModel: 'claude-fable-5-1', workerModel: 'claude-opus-5-5', effort: 'high', leadEffort: 'high', leadFallback: { model: 'claude-opus-5-5', effort: 'xhigh' } };
const { leadFallback: _unused, ...NO_FALLBACK } = OWNER;
const FABLE_LIMIT = "You've hit your Fable limit · resets 3pm";

let h: Harness | undefined;
const cleanup: string[] = [];
let repoPath = '';

async function boot(turn: Turn, calls: Call[], claude: Record<string, unknown>, o: { args?: string[]; home?: string; usage?: () => unknown } = {}): Promise<Harness> {
  if (!repoPath) {
    repoPath = await demoRepo();
    cleanup.push(path.dirname(repoPath));
  }
  const home = o.home ?? tempDir();
  if (!cleanup.includes(home)) cleanup.push(home);
  fs.writeFileSync(path.join(home, 'config.json'), JSON.stringify({ claude }));
  h = makeForeman(home, ['--backend', 'claude', '--repo', repoPath, '--no-lead-review', '--workers', 'kit', ...(o.args ?? [])]);
  await h.fm.start(new ClaudeBackend(h.fm, h.cfg.claude, { queryFn: fakeQuery(turn, calls, o.usage) as never, skipAuthCheck: true }));
  return h;
}

afterEach(async () => {
  await h?.fm.close();
  h = undefined;
  for (const d of cleanup.splice(0)) rmrf(d);
  repoPath = '';
});

const leads = (calls: Call[]) => calls.filter((c) => c.lead);
const workers = (calls: Call[]) => calls.filter((c) => !c.lead);
type St = { inflight: Record<string, unknown>; limit?: { until: number }; leadFallback?: { until: number; model: string; family: string; guessed?: boolean } };
const state = (hh: Harness) => hh.fm.store.data.backend.claude as St;
const feed = (hh: Harness, re: RegExp) => hh.fm.store.data.feed.some((f) => re.test(f.text));
const say = (hh: Harness, text: string) => hh.fm.handle({ v: 1, type: 'user.message', to: 'marlow', text }, () => undefined);

/** a worker that finishes its task */
async function* work(p: string, opts: Options, s: string): AsyncGenerator<SDKMessage> {
  const task = /Your task: (t\d+)/.exec(p)?.[1];
  if (task) await callTool(opts, 'update_task', { task_id: task, status: 'review', summary: 'ok' });
  yield ok(s);
}

describe('which limits are a model\'s own', () => {
  it('maps the CLI rate-limit types and messages to a model family; account-wide limits to none', () => {
    expect(limitModelFamily('seven_day_overage_included')).toBe('fable'); // the CLI's "Fable limit"
    expect(limitModelFamily('seven_day_opus')).toBe('opus');
    expect(limitModelFamily('seven_day_sonnet')).toBe('sonnet');
    for (const t of ['five_hour', 'seven_day', 'overage', 'seven_day_oauth_apps', undefined]) expect(limitModelFamily(t)).toBeUndefined();
    expect(limitFamilyFromText(FABLE_LIMIT)).toBe('fable');
    expect(limitFamilyFromText("You've hit your Opus limit · resets Tue 9am")).toBe('opus');
    for (const t of ["You've hit your session limit · resets 3pm", "You've hit your weekly limit", "You've hit your usage credit limit", 'Claude AI usage limit reached|1759500000', 'rate_limit']) {
      expect(limitFamilyFromText(t)).toBeUndefined();
    }
    expect(modelFamily('claude-fable-5-1')).toBe('fable');
    expect(modelFamily('claude-opus-5-5')).toBe('opus');
    expect(modelFamily('opus')).toBe('opus');
    expect(modelFamily('opus[1m]')).toBe('opus');
    expect(modelFamily('claude-haiku-4-5-20251001')).toBe('haiku');
    for (const m of ['default', 'opusplan', undefined, '']) expect(modelFamily(m)).toBeUndefined();
  });
});

describe('the lead\'s fallback model (claude.leadFallback)', () => {
  it('a lead at its own model\'s limit continues on the fallback with its effort right away; workers keep working', async () => {
    const calls: Call[] = [];
    let resetAt = 0;
    const hh = await boot(
      async function* (p, opts, call) {
        const s = sid();
        call.session = s;
        yield init(s, opts.model);
        if (!call.lead) return yield* work(p, opts, s);
        if (leads(calls).length === 1) {
          resetAt = Date.now() + 60 * 60_000;
          yield rejected(s, 'seven_day_overage_included', resetAt);
          yield* refused(s, FABLE_LIMIT);
          return;
        }
        await callTool(opts, 'create_task', { title: 'Do it', assignee: 'kit' });
        yield ok(s);
      },
      calls,
      OWNER,
    );
    const { fm } = hh;
    expect(fm.agent('marlow')!.model).toBe('Fable 5.1');
    await fm.submitGoal('do it');
    await until(() => workers(calls).length === 1);
    const [first, second] = leads(calls);
    expect(first).toMatchObject({ model: 'claude-fable-5-1', effort: 'high' });
    // the same job continues at once, its session resumed, on the fallback model and effort
    expect(second).toMatchObject({ model: 'claude-opus-5-5', effort: 'xhigh', resume: first!.session });
    expect(second!.prompt).toMatch(/^The Fable usage limit stopped your last turn; you continue on Opus 5\.5 until it resets/);
    expect(second!.at).toBeLessThan(resetAt);
    // nobody waits: no team-wide limit, the worker runs its own model and effort
    expect(state(hh).limit).toBeUndefined();
    expect(state(hh).leadFallback).toMatchObject({ model: 'claude-fable-5-1', family: 'fable', until: Math.floor(resetAt / 1000) * 1000 });
    expect(workers(calls)[0]).toMatchObject({ model: 'claude-opus-5-5', effort: 'high' });
    expect(feed(hh, /^Marlow switched to Opus 5\.5 \(xhigh\): Fable limit reached, back at \S/)).toBe(true);
    expect(feed(hh, /Usage limit reached/)).toBe(false);
    expect(hh.toasts.some((t) => /Usage limit/.test(t.body))).toBe(false);
    // the badge and the banner show the model the lead runs now
    expect(fm.agent('marlow')!.model).toBe('Opus 5.5');
    expect(fm.status.message).toBe('Claude (lead Opus 5.5 while Fable is limited, workers Opus 5.5)');
    await until(() => fm.goals()[0]!.status === 'active');
  });

  it('recognises the limit from the message alone (no typed report); without a reset time it tries its own model again after the shortest wait', async () => {
    const calls: Call[] = [];
    const hh = await boot(
      async function* (_p, opts, call) {
        const s = sid();
        yield init(s, opts.model);
        if (call.lead && leads(calls).length === 1) return yield* refused(s, FABLE_LIMIT);
        yield ok(s);
      },
      calls,
      OWNER,
    );
    const t0 = Date.now();
    await hh.fm.submitGoal('do it');
    await until(() => leads(calls).length === 2);
    expect(leads(calls)[1]).toMatchObject({ model: 'claude-opus-5-5', effort: 'xhigh' });
    const f = state(hh).leadFallback!;
    expect(f).toMatchObject({ family: 'fable', guessed: true });
    expect(f.until).toBeGreaterThanOrEqual(t0 + 5 * 60_000);
    expect(f.until).toBeLessThan(Date.now() + 5 * 60_000 + 1000);
    expect(state(hh).limit).toBeUndefined();
  });

  it('a limit without a reset time lasts until the usage poll says the model\'s weekly window resets', async () => {
    const calls: Call[] = [];
    const fableResets = new Date(Date.now() + 3 * 24 * 3600_000).toISOString();
    // the plan usage shows the Fable window once the lead is at its limit (`h`: the Foreman being started)
    const usage = () => ({
      subscription_type: 'max',
      rate_limits: { limits: h && state(h)?.leadFallback ? [{ kind: 'weekly_scoped', percent: 100, resets_at: fableResets, scope: { model: { display_name: 'Fable' } } }] : [] },
    });
    const hh = await boot(
      async function* (_p, opts, call) {
        const s = sid();
        yield init(s, opts.model);
        if (call.lead && leads(calls).length === 1) return yield* refused(s, FABLE_LIMIT);
        yield ok(s);
      },
      calls,
      { ...OWNER, useClaudeLogin: true },
      { usage },
    );
    await hh.fm.submitGoal('do it');
    await until(() => leads(calls).length === 2);
    expect(leads(calls)[1]).toMatchObject({ model: 'claude-opus-5-5', effort: 'xhigh' });
    await until(() => state(hh).leadFallback?.guessed === undefined);
    expect(state(hh).leadFallback!.until).toBe(Date.parse(fableResets));
    expect(hh.fm.status.usage?.windows.map((w) => w.id)).toEqual(['weekly:fable']);
  });

  it('an account-wide limit holds the lead (and everyone) as before, on its own model', async () => {
    const calls: Call[] = [];
    let resetAt = 0;
    const hh = await boot(
      async function* (_p, opts, call) {
        const s = sid();
        yield init(s, opts.model);
        if (call.lead && leads(calls).length === 1) {
          resetAt = Date.now() + 1500;
          yield rejected(s, 'five_hour', resetAt);
          return yield* refused(s, "You've hit your session limit · resets 3pm");
        }
        yield ok(s);
      },
      calls,
      OWNER,
    );
    await hh.fm.submitGoal('do it');
    await until(() => leads(calls).length === 1 && hh.fm.agent('marlow')!.activity.startsWith('usage limit'));
    expect(hh.fm.status.message).toMatch(/Usage limit reached \(five hour\)/);
    await until(() => leads(calls).length === 2, 10_000);
    const second = leads(calls)[1]!;
    expect(second.at).toBeGreaterThanOrEqual(Math.floor(resetAt / 1000) * 1000);
    expect(second).toMatchObject({ model: 'claude-fable-5-1', effort: 'high' });
    expect(second.prompt).toMatch(/^A usage limit stopped your last turn; it has reset now/);
    expect(state(hh).leadFallback).toBeUndefined();
    expect(feed(hh, /switched to/)).toBe(false);
  });

  it('a limit on the fallback too holds as before; after that reset the lead stays on the fallback while its own limit lasts', async () => {
    const calls: Call[] = [];
    let opusReset = 0;
    const hh = await boot(
      async function* (_p, opts, call) {
        const s = sid();
        yield init(s, opts.model);
        if (call.lead) {
          const k = leads(calls).length;
          if (k === 1) {
            yield rejected(s, 'seven_day_overage_included', Date.now() + 60 * 60_000);
            return yield* refused(s, FABLE_LIMIT);
          }
          if (k === 2) {
            opusReset = Date.now() + 1500;
            yield rejected(s, 'seven_day_opus', opusReset);
            return yield* refused(s, "You've hit your Opus limit · resets 3pm");
          }
        }
        yield ok(s);
      },
      calls,
      OWNER,
    );
    await hh.fm.submitGoal('do it');
    await until(() => leads(calls).length === 2 && hh.fm.agent('marlow')!.activity.startsWith('usage limit'));
    expect(leads(calls)[1]).toMatchObject({ model: 'claude-opus-5-5', effort: 'xhigh' });
    expect(hh.fm.status.message).toMatch(/Usage limit reached \(seven day opus\)/);
    expect(state(hh).limit).toBeDefined();
    await until(() => leads(calls).length === 3, 10_000);
    const third = leads(calls)[2]!;
    expect(third.at).toBeGreaterThanOrEqual(Math.floor(opusReset / 1000) * 1000);
    expect(third).toMatchObject({ model: 'claude-opus-5-5', effort: 'xhigh' });
    expect(third.prompt).toMatch(/^A usage limit stopped your last turn; it has reset now/);
  });

  it('when its own model\'s limit resets, the lead runs it again from its next turn', async () => {
    const calls: Call[] = [];
    let resetAt = 0;
    const hh = await boot(
      async function* (_p, opts, call) {
        const s = sid();
        yield init(s, opts.model);
        if (call.lead && leads(calls).length === 1) {
          resetAt = Date.now() + 1500;
          yield rejected(s, 'seven_day_overage_included', resetAt);
          return yield* refused(s, FABLE_LIMIT);
        }
        yield ok(s);
      },
      calls,
      OWNER,
    );
    const { fm } = hh;
    await fm.submitGoal('do it');
    await until(() => leads(calls).length === 2);
    expect(leads(calls)[1]).toMatchObject({ model: 'claude-opus-5-5', effort: 'xhigh' });
    await until(() => feed(hh, /^Marlow is back on Fable 5\.1 \(high\): the Fable limit reset$/), 10_000);
    expect(Date.now()).toBeGreaterThanOrEqual(resetAt);
    expect(state(hh).leadFallback).toBeUndefined();
    expect(fm.agent('marlow')!.model).toBe('Fable 5.1');
    expect(fm.status.message).toBe('Claude (lead Fable 5.1, workers Opus 5.5)');
    await say(hh, 'how is it going?');
    await until(() => leads(calls).length === 3);
    expect(leads(calls)[2]).toMatchObject({ model: 'claude-fable-5-1', effort: 'high' });
  });

  it('a reset during a fallback turn: that turn finishes on the fallback, its nameplate says so until it ends', async () => {
    const calls: Call[] = [];
    let release!: () => void;
    const released = new Promise<void>((r) => (release = r));
    const hh = await boot(
      async function* (_p, opts, call) {
        const s = sid();
        yield init(s, opts.model);
        if (call.lead && leads(calls).length === 1) {
          yield rejected(s, 'seven_day_overage_included', Date.now() + 1500);
          return yield* refused(s, FABLE_LIMIT);
        }
        if (call.lead && leads(calls).length === 2) await released;
        yield ok(s);
      },
      calls,
      OWNER,
    );
    const { fm } = hh;
    await fm.submitGoal('do it');
    await until(() => leads(calls).length === 2);
    await until(() => feed(hh, /^Marlow is back on Fable 5\.1 \(high\)/), 10_000);
    expect(state(hh).inflight.marlow).toBeDefined();
    expect(fm.agent('marlow')!.model).toBe('Opus 5.5');
    release();
    await until(() => !state(hh).inflight.marlow);
    expect(fm.agent('marlow')!.model).toBe('Fable 5.1');
  });

  it('without a fallback configured, the same limit holds the lead and the team as before', async () => {
    const calls: Call[] = [];
    let resetAt = 0;
    const hh = await boot(
      async function* (_p, opts, call) {
        const s = sid();
        yield init(s, opts.model);
        if (call.lead && leads(calls).length === 1) {
          resetAt = Date.now() + 1500;
          yield rejected(s, 'seven_day_overage_included', resetAt);
          return yield* refused(s, FABLE_LIMIT);
        }
        yield ok(s);
      },
      calls,
      NO_FALLBACK,
    );
    expect(hh.cfg.claude.leadFallback).toBeUndefined();
    await hh.fm.submitGoal('do it');
    await until(() => leads(calls).length === 1 && hh.fm.agent('marlow')!.activity.startsWith('usage limit'));
    expect(hh.fm.status.message).toMatch(/Usage limit reached \(seven day overage included\)/);
    expect(feed(hh, /Usage limit reached/)).toBe(true);
    await until(() => leads(calls).length === 2, 10_000);
    const second = leads(calls)[1]!;
    expect(second.at).toBeGreaterThanOrEqual(Math.floor(resetAt / 1000) * 1000);
    expect(second).toMatchObject({ model: 'claude-fable-5-1', effort: 'high' });
    expect(second.prompt).toMatch(/^A usage limit stopped your last turn; it has reset now/);
    expect(state(hh).leadFallback).toBeUndefined();
    expect(feed(hh, /switched to/)).toBe(false);
  });

  it('a worker at a limit (even the lead model\'s own) is held as before; the lead does not switch', async () => {
    const calls: Call[] = [];
    let resetAt = 0;
    const hh = await boot(
      async function* (p, opts, call) {
        const s = sid();
        yield init(s, opts.model);
        if (call.lead) {
          if (leads(calls).length === 1) await callTool(opts, 'create_task', { title: 'Do it', assignee: 'kit' });
          return yield ok(s);
        }
        if (workers(calls).length === 1) {
          resetAt = Date.now() + 1500;
          yield rejected(s, 'seven_day_overage_included', resetAt);
          return yield* refused(s, FABLE_LIMIT);
        }
        yield* work(p, opts, s);
      },
      calls,
      { ...OWNER, workerModel: 'claude-fable-5-1' },
    );
    await hh.fm.submitGoal('do it');
    await until(() => workers(calls).length === 1 && hh.fm.agent('kit')!.activity.startsWith('usage limit'));
    expect(hh.fm.tasks.list()[0]!.status).toBe('doing');
    expect(hh.fm.status.message).toMatch(/Usage limit reached \(seven day overage included\)/);
    expect(state(hh).limit).toBeDefined();
    expect(state(hh).leadFallback).toBeUndefined();
    await until(() => workers(calls).length === 2, 10_000);
    const second = workers(calls)[1]!;
    expect(second.at).toBeGreaterThanOrEqual(Math.floor(resetAt / 1000) * 1000);
    expect(second).toMatchObject({ model: 'claude-fable-5-1', effort: 'high' });
    expect(second.prompt).toMatch(/^A usage limit stopped your last turn/);
    expect(hh.fm.agent('marlow')!.model).toBe('Fable 5.1');
    expect(feed(hh, /switched to/)).toBe(false);
  });

  it('survives a restart; a restart whose config no longer asks for it drops it', async () => {
    const home = tempDir();
    const calls: Call[] = [];
    const turn: Turn = async function* (_p, opts, call) {
      const s = sid();
      yield init(s, opts.model);
      if (call.lead && leads(calls).length === 1) {
        yield rejected(s, 'seven_day_overage_included', Date.now() + 60 * 60_000);
        return yield* refused(s, FABLE_LIMIT);
      }
      yield ok(s);
    };
    let hh = await boot(turn, calls, OWNER, { home });
    await hh.fm.submitGoal('do it');
    await until(() => leads(calls).length === 2 && hh.fm.goals()[0]!.status !== 'planning');
    const until0 = state(hh).leadFallback!.until;
    await hh.fm.close();

    // the same config: still on the fallback until the same time
    hh = await boot(turn, calls, OWNER, { home });
    expect(state(hh).leadFallback).toMatchObject({ model: 'claude-fable-5-1', until: until0 });
    expect(hh.fm.agent('marlow')!.model).toBe('Opus 5.5');
    expect(hh.fm.status.message).toBe('Claude (lead Opus 5.5 while Fable is limited, workers Opus 5.5)');
    await say(hh, 'still there?');
    await until(() => leads(calls).length === 3);
    expect(leads(calls)[2]).toMatchObject({ model: 'claude-opus-5-5', effort: 'xhigh' });
    await until(() => !state(hh).inflight.marlow); // that turn is over (a turn cut by the close would resume)
    await hh.fm.close();

    // --no-lead-fallback-model: the persisted fallback no longer applies
    hh = await boot(turn, calls, OWNER, { home, args: ['--no-lead-fallback-model'] });
    expect(hh.cfg.claude.leadFallback).toBeUndefined();
    expect(state(hh).leadFallback).toBeUndefined();
    expect(hh.fm.agent('marlow')!.model).toBe('Fable 5.1');
    await say(hh, 'and now?');
    await until(() => leads(calls).length === 4);
    expect(leads(calls)[3]).toMatchObject({ model: 'claude-fable-5-1', effort: 'high' });
  });
});
