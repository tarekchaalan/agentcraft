// Per-model usage limits and the lead's fallback model (claude.leadFallback). A model's own limit
// (the Fable weekly limit, the Opus or Sonnet one) holds only the agents on that model; an
// account-wide limit holds everyone. A lead whose own model is at its limit (learned from its own
// turn, a worker's, the usage poll or the state saved before a restart) continues on the fallback
// model and effort right away; it returns to its own model when the limit resets; the state survives
// a restart, and a Fable limit an older Foreman saved as a team-wide hold is migrated. Fake SDK
// `query()`, no API calls.
import fs from 'node:fs';
import path from 'node:path';
import type { Options, SDKMessage } from '@anthropic-ai/claude-agent-sdk';
import { afterEach, describe, expect, it } from 'vitest';
import { ClaudeBackend } from '../src/agents/claude/index.js';
import { limitFamilyFromText, limitModelFamily, modelFamily, usageWindowFamily } from '../src/agents/limits.js';
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

async function boot(turn: Turn, calls: Call[], claude: Record<string, unknown>, o: { args?: string[]; home?: string; usage?: () => unknown; workers?: string } = {}): Promise<Harness> {
  if (!repoPath) {
    repoPath = await demoRepo();
    cleanup.push(path.dirname(repoPath));
  }
  const home = o.home ?? tempDir();
  if (!cleanup.includes(home)) cleanup.push(home);
  fs.writeFileSync(path.join(home, 'config.json'), JSON.stringify({ claude }));
  h = makeForeman(home, ['--backend', 'claude', '--repo', repoPath, '--no-lead-review', '--workers', o.workers ?? 'kit', ...(o.args ?? [])]);
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
type St = {
  inflight: Record<string, unknown>;
  limit?: { until: number; type?: string };
  modelLimits?: Record<string, { until: number; type?: string; guessed?: boolean; fromUsage?: boolean }>;
  leadFallback?: { until: number; model: string; family: string; guessed?: boolean };
};
const state = (hh: Harness) => hh.fm.store.data.backend.claude as St;
const feed = (hh: Harness, re: RegExp) => hh.fm.store.data.feed.some((f) => re.test(f.text));
const say = (hh: Harness, text: string) => hh.fm.handle({ v: 1, type: 'user.message', to: 'marlow', text }, () => undefined);
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));
/** a Claude login's plan usage with the Fable weekly window at `percent` (active), resetting at `resetsAt` */
const fableUsage = (percent: number, resetsAt: string) => ({
  subscription_type: 'max',
  rate_limits: { limits: [{ kind: 'weekly_scoped', percent, resets_at: resetsAt, is_active: percent >= 100, scope: { model: { display_name: 'Fable' } } }] },
});

/** a worker that finishes its task (the one its prompt names, else, for a continuation, the one it is doing) */
async function* work(p: string, opts: Options, s: string): AsyncGenerator<SDKMessage> {
  const task = /Your task: (t\d+)/.exec(p)?.[1] ?? h?.fm.tasks.list().find((t) => t.status === 'doing')?.id;
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
    expect(limitFamilyFromText("You've reached your Fable limit. Switch to another model to keep going")).toBe('fable');
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
    // the usage poll's windows that are one model family's own weekly window
    expect(usageWindowFamily('weekly:fable')).toBe('fable');
    expect(usageWindowFamily('weekly:opus')).toBe('opus');
    for (const id of ['session', 'weekly', 'weekly:claude-code', 'weekly:opus-4-5', 'weekly:fable-x']) expect(usageWindowFamily(id)).toBeUndefined();
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
    expect(state(hh).modelLimits?.fable).toMatchObject({ until: Math.floor(resetAt / 1000) * 1000, type: 'seven_day_overage_included' });
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

  it('a limit on the fallback too holds the lead and that model\'s workers; after that reset the lead stays on the fallback while its own limit lasts', async () => {
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
    await until(() => leads(calls).length === 2 && hh.fm.agent('marlow')!.activity.startsWith('Opus limit - resumes'));
    expect(leads(calls)[1]).toMatchObject({ model: 'claude-opus-5-5', effort: 'xhigh' });
    // the Opus limit holds the agents on Opus: the lead on its fallback, and Kit (workers run Opus here)
    expect(hh.fm.status.message).toMatch(/^Opus limit reached \(seven day opus\): Marlow and Kit wait until .+, then resume$/);
    expect(state(hh).limit).toBeUndefined();
    expect(state(hh).modelLimits?.opus).toMatchObject({ until: Math.floor(opusReset / 1000) * 1000, type: 'seven_day_opus' });
    expect(state(hh).modelLimits?.fable).toBeDefined();
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

  it('without a fallback configured, the lead waits for its model\'s limit; the banner and feed name the model, not the team', async () => {
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
    await until(() => leads(calls).length === 1 && hh.fm.agent('marlow')!.activity.startsWith('Fable limit - resumes'));
    expect(hh.fm.status.message).toMatch(/^Fable limit reached \(seven day overage included\): Marlow waits until .+, then resumes; the others keep working$/);
    expect(feed(hh, /^Fable limit reached \(seven day overage included\)\. Marlow \(on Fable\) starts no new turn until .+; the others keep working\.$/)).toBe(true);
    expect(feed(hh, /Usage limit reached|Nobody starts/)).toBe(false);
    expect(hh.events.some((e) => e.type === 'notify' && /^Fable limit reached: Marlow waits until/.test(e.text))).toBe(true);
    expect(state(hh).limit).toBeUndefined();
    expect(state(hh).modelLimits?.fable).toBeDefined();
    await until(() => leads(calls).length === 2, 10_000);
    const second = leads(calls)[1]!;
    expect(second.at).toBeGreaterThanOrEqual(Math.floor(resetAt / 1000) * 1000);
    expect(second).toMatchObject({ model: 'claude-fable-5-1', effort: 'high' });
    expect(second.prompt).toMatch(/^A usage limit stopped your last turn; it has reset now/);
    expect(state(hh).leadFallback).toBeUndefined();
    expect(feed(hh, /switched to/)).toBe(false);
    expect(feed(hh, /^Fable limit reset: Marlow resumes$/)).toBe(true);
  });

  it('a worker at the lead model\'s own limit waits for it; the lead switches to its fallback', async () => {
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
    await until(() => workers(calls).length === 1 && hh.fm.agent('kit')!.activity.startsWith('Fable limit - resumes'));
    expect(hh.fm.tasks.list()[0]!.status).toBe('doing');
    // the limit is Fable's: Kit (on Fable) waits; the lead, on Fable too, switches instead of waiting
    expect(state(hh).limit).toBeUndefined();
    expect(state(hh).modelLimits?.fable).toMatchObject({ until: Math.floor(resetAt / 1000) * 1000 });
    expect(state(hh).leadFallback).toMatchObject({ family: 'fable', until: Math.floor(resetAt / 1000) * 1000 });
    expect(feed(hh, /^Marlow switched to Opus 5\.5 \(xhigh\): Fable limit reached, back at \S/)).toBe(true);
    expect(feed(hh, /^Fable limit reached \(seven day overage included\)\. Kit \(on Fable\) starts no new turn until/)).toBe(true);
    expect(hh.fm.status.message).toMatch(/^Fable limit reached \(seven day overage included\): Kit waits until .+, then resumes; the others keep working$/);
    expect(hh.fm.agent('marlow')!.model).toBe('Opus 5.5');
    await until(() => workers(calls).length === 2, 10_000);
    const second = workers(calls)[1]!;
    expect(second.at).toBeGreaterThanOrEqual(Math.floor(resetAt / 1000) * 1000);
    expect(second).toMatchObject({ model: 'claude-fable-5-1', effort: 'high' });
    expect(second.prompt).toMatch(/^A usage limit stopped your last turn/);
    expect(feed(hh, /^Fable limit reset: Kit resumes$/)).toBe(true);
    expect(feed(hh, /^Marlow is back on Fable 5\.1 \(high\): the Fable limit reset$/)).toBe(true);
    expect(hh.fm.agent('marlow')!.model).toBe('Fable 5.1');
  });

  it('survives a restart; a restart whose config no longer asks for it drops it (the lead then waits for its model\'s limit)', async () => {
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
    expect(state(hh).modelLimits?.fable).toMatchObject({ until: until0 });
    expect(hh.fm.agent('marlow')!.model).toBe('Opus 5.5');
    expect(hh.fm.status.message).toBe('Claude (lead Opus 5.5 while Fable is limited, workers Opus 5.5)');
    await say(hh, 'still there?');
    await until(() => leads(calls).length === 3);
    expect(leads(calls)[2]).toMatchObject({ model: 'claude-opus-5-5', effort: 'xhigh' });
    await until(() => !state(hh).inflight.marlow); // that turn is over (a turn cut by the close would resume)
    await hh.fm.close();

    // --no-lead-fallback-model: the persisted fallback no longer applies. The Fable limit still does:
    // the lead (on Fable again) waits for it instead of running into it; the worker on Opus does not
    hh = await boot(turn, calls, OWNER, { home, args: ['--no-lead-fallback-model'] });
    expect(hh.cfg.claude.leadFallback).toBeUndefined();
    expect(state(hh).leadFallback).toBeUndefined();
    expect(state(hh).modelLimits?.fable).toMatchObject({ until: until0 });
    expect(hh.fm.agent('marlow')!.model).toBe('Fable 5.1');
    expect(hh.fm.status.message).toMatch(/^Fable limit reached \(seven day overage included\): Marlow waits until .+, then resumes; the others keep working$/);
    await say(hh, 'and now?');
    await until(() => hh.fm.agent('marlow')!.activity.startsWith('Fable limit - resumes'));
    await sleep(300);
    expect(leads(calls)).toHaveLength(3);
  });

  it('reproduces the frozen team: a Fable limit an older Foreman saved as a team-wide hold becomes the Fable limit; the lead plans on its fallback and the Opus worker works', async () => {
    const home = tempDir();
    const calls: Call[] = [];
    // the older Foreman, without a fallback: Marlow's plan turn on Fable is refused by the Fable limit
    let hh = await boot(
      async function* (_p, opts, call) {
        const s = sid();
        yield init(s, opts.model);
        if (call.lead) {
          yield rejected(s, 'seven_day_overage_included', Date.now() + 11 * 3600_000);
          return yield* refused(s, FABLE_LIMIT);
        }
        yield ok(s);
      },
      calls,
      NO_FALLBACK,
      { home },
    );
    await hh.fm.submitGoal('do it');
    await until(() => leads(calls).length === 1 && hh.fm.agent('marlow')!.activity.startsWith('Fable limit'));
    const stateFile = path.join(hh.cfg.dataDir, 'state.json');
    await hh.fm.close();
    // what that Foreman saved: the Fable limit as the team-wide `limit` (backend.claude.limit), nothing per model
    const until0 = Date.now() + 11 * 3600_000;
    const saved = JSON.parse(fs.readFileSync(stateFile, 'utf8')) as { backend: { claude: St } };
    delete saved.backend.claude.modelLimits;
    delete saved.backend.claude.leadFallback;
    saved.backend.claude.limit = { until: until0, type: 'seven_day_overage_included' };
    fs.writeFileSync(stateFile, JSON.stringify(saved));
    expect(hh.fm.goals()[0]!.status).toBe('planning');

    // the new Foreman, with claude.leadFallback configured
    hh = await boot(
      async function* (p, opts, call) {
        const s = sid();
        call.session = s;
        yield init(s, opts.model);
        if (!call.lead) return yield* work(p, opts, s);
        await callTool(opts, 'create_task', { title: 'Do it', assignee: 'kit' });
        yield ok(s);
      },
      calls,
      OWNER,
      { home },
    );
    // migrated: no team-wide hold; the Fable limit holds Fable only, and the lead runs its fallback until then
    expect(state(hh).limit).toBeUndefined();
    expect(state(hh).modelLimits?.fable).toEqual({ until: until0, type: 'seven_day_overage_included' });
    expect(state(hh).leadFallback).toMatchObject({ model: 'claude-fable-5-1', family: 'fable', until: until0 });
    expect(feed(hh, /^Marlow switched to Opus 5\.5 \(xhigh\): Fable limit reached, back at \S/)).toBe(true);
    expect(hh.fm.agent('marlow')!.model).toBe('Opus 5.5');
    // the re-plan of the goal starts (on the fallback, its session resumed) and the Opus worker works
    await until(() => workers(calls).length === 1);
    const replan = leads(calls)[1]!;
    expect(replan).toMatchObject({ model: 'claude-opus-5-5', effort: 'xhigh', resume: expect.any(String) });
    expect(replan.at).toBeLessThan(until0);
    expect(workers(calls)[0]).toMatchObject({ model: 'claude-opus-5-5', effort: 'high' });
    await until(() => hh.fm.goals()[0]!.status === 'active');
    await until(() => hh.fm.tasks.list()[0]!.status === 'review');
    expect(hh.fm.status.message).toBe('Claude (lead Opus 5.5 while Fable is limited, workers Opus 5.5)');
    expect(feed(hh, /Usage limit reached|Nobody starts/)).toBe(false);
  });

  it('a migrated team-wide limit that is not a model\'s own still holds everyone; one already over is dropped', async () => {
    const home = tempDir();
    const calls: Call[] = [];
    const turn: Turn = async function* (_p, opts) {
      const s = sid();
      yield init(s, opts.model);
      yield ok(s);
    };
    let hh = await boot(turn, calls, OWNER, { home });
    const stateFile = path.join(hh.cfg.dataDir, 'state.json');
    await hh.fm.close();
    const write = (limit: St['limit']) => {
      const saved = JSON.parse(fs.readFileSync(stateFile, 'utf8')) as { backend: { claude?: St } };
      saved.backend.claude = { inflight: {}, ...saved.backend.claude, ...(limit ? { limit } : {}) } as St;
      fs.writeFileSync(stateFile, JSON.stringify(saved));
    };
    // the account's 5-hour limit: everyone waits, as before
    const until5h = Date.now() + 3 * 3600_000;
    write({ until: until5h, type: 'five_hour' });
    hh = await boot(turn, calls, OWNER, { home });
    expect(state(hh).limit).toEqual({ until: until5h, type: 'five_hour' });
    expect(state(hh).modelLimits).toBeUndefined();
    expect(state(hh).leadFallback).toBeUndefined();
    await hh.fm.close();
    // a Fable limit that ended while the Foreman was down: gone, nobody waits
    write({ until: Date.now() - 1000, type: 'seven_day_overage_included' });
    hh = await boot(turn, calls, OWNER, { home });
    expect(state(hh).limit).toBeUndefined();
    expect(state(hh).modelLimits).toBeUndefined();
    expect(state(hh).leadFallback).toBeUndefined();
    expect(hh.fm.agent('marlow')!.model).toBe('Fable 5.1');
  });
});

describe('per-model usage limits', () => {
  it('a worker on Fable at the Fable limit waits; the lead on Opus keeps working', async () => {
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
          resetAt = Date.now() + 3000;
          yield rejected(s, 'seven_day_overage_included', resetAt);
          return yield* refused(s, FABLE_LIMIT);
        }
        yield* work(p, opts, s);
      },
      calls,
      { leadModel: 'claude-opus-5-5', workerModel: 'claude-fable-5-1', effort: 'high', leadEffort: 'high' },
    );
    const { fm } = hh;
    await fm.submitGoal('do it');
    await until(() => workers(calls).length === 1 && fm.agent('kit')!.activity.startsWith('Fable limit - resumes'));
    expect(state(hh).limit).toBeUndefined();
    expect(state(hh).modelLimits?.fable).toMatchObject({ until: Math.floor(resetAt / 1000) * 1000, type: 'seven_day_overage_included' });
    expect(fm.status.message).toMatch(/^Fable limit reached \(seven day overage included\): Kit waits until .+, then resumes; the others keep working$/);
    expect(feed(hh, /^Fable limit reached \(seven day overage included\)\. Kit \(on Fable\) starts no new turn until .+; the others keep working\.$/)).toBe(true);
    // the lead is on Opus: a message to it is answered now, not after the reset
    await say(hh, 'how is it going?');
    await until(() => leads(calls).length === 2);
    expect(leads(calls)[1]!.at).toBeLessThan(Math.floor(resetAt / 1000) * 1000);
    expect(leads(calls)[1]).toMatchObject({ model: 'claude-opus-5-5', effort: 'high' });
    expect(workers(calls)).toHaveLength(1);
    // Kit resumes at the reset, on its own model
    await until(() => workers(calls).length === 2, 10_000);
    expect(workers(calls)[1]!.at).toBeGreaterThanOrEqual(Math.floor(resetAt / 1000) * 1000);
    expect(workers(calls)[1]).toMatchObject({ model: 'claude-fable-5-1', effort: 'high' });
    expect(feed(hh, /^Fable limit reset: Kit resumes$/)).toBe(true);
    await until(() => fm.tasks.list()[0]!.status === 'review');
    expect(state(hh).modelLimits?.fable).toBeUndefined();
    expect(fm.status.message).toBe('Claude (lead Opus 5.5, workers Fable 5.1)');
  });

  it('an account-wide limit still holds everyone, whichever model they run', async () => {
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
          resetAt = Date.now() + 2000;
          yield rejected(s, 'five_hour', resetAt);
          return yield* refused(s, "You've hit your session limit · resets 3pm");
        }
        yield* work(p, opts, s);
      },
      calls,
      { leadModel: 'claude-opus-5-5', workerModel: 'claude-fable-5-1', effort: 'high', leadEffort: 'high' },
    );
    const { fm } = hh;
    await fm.submitGoal('do it');
    await until(() => workers(calls).length === 1 && fm.agent('kit')!.activity.startsWith('usage limit - resumes'));
    expect(state(hh).limit).toMatchObject({ type: 'five_hour' });
    expect(state(hh).modelLimits).toBeUndefined();
    expect(fm.status.message).toMatch(/^Usage limit reached \(five hour\): agents wait until .+, then resume$/);
    expect(feed(hh, /^Usage limit reached \(five hour\)\. Nobody starts a new turn until/)).toBe(true);
    // the lead (on Opus, another model than Kit's) waits too, and says why
    await say(hh, 'how is it going?');
    await until(() => fm.agent('marlow')!.activity.startsWith('usage limit - resumes'));
    await until(() => leads(calls).length === 2, 10_000);
    expect(leads(calls)[1]!.at).toBeGreaterThanOrEqual(Math.floor(resetAt / 1000) * 1000);
    await until(() => workers(calls).length === 2, 10_000);
    expect(workers(calls)[1]!.at).toBeGreaterThanOrEqual(Math.floor(resetAt / 1000) * 1000);
  });

  it('without a fallback, a lead at its model\'s limit waits while the workers on Opus keep working', async () => {
    const calls: Call[] = [];
    let release!: () => void;
    const released = new Promise<void>((r) => (release = r));
    let limitAt = 0;
    const hh = await boot(
      async function* (p, opts, call) {
        const s = sid();
        yield init(s, opts.model);
        if (call.lead) {
          if (leads(calls).length === 1) {
            await callTool(opts, 'create_task', { title: 'One', assignee: 'kit' });
            await callTool(opts, 'create_task', { title: 'Two', assignee: 'juniper' });
            return yield ok(s);
          }
          limitAt = Date.now();
          yield rejected(s, 'seven_day_overage_included', Date.now() + 60 * 60_000);
          return yield* refused(s, FABLE_LIMIT);
        }
        if (workers(calls).length === 1) await released; // Kit works while the lead hits the limit
        yield* work(p, opts, s);
      },
      calls,
      NO_FALLBACK,
      { workers: 'kit,juniper', args: ['--max-concurrent', '1'] },
    );
    const { fm } = hh;
    await fm.submitGoal('two things');
    await until(() => workers(calls).length === 1);
    await say(hh, 'status?');
    await until(() => leads(calls).length === 2 && fm.agent('marlow')!.activity.startsWith('Fable limit - resumes'));
    expect(state(hh).limit).toBeUndefined();
    expect(state(hh).modelLimits?.fable).toBeDefined();
    expect(fm.status.message).toMatch(/^Fable limit reached \(seven day overage included\): Marlow waits until .+, then resumes; the others keep working$/);
    // Kit finishes, and Juniper (on Opus) starts the next task while the lead still waits
    release();
    await until(() => workers(calls).length === 2);
    const juniper = workers(calls)[1]!;
    expect(juniper.at).toBeGreaterThan(limitAt);
    expect(juniper).toMatchObject({ model: 'claude-opus-5-5', effort: 'high' });
    await until(() => fm.tasks.list().every((t) => t.status === 'review'));
    expect(leads(calls)).toHaveLength(2);
    expect(fm.agent('marlow')!.activity).toMatch(/^Fable limit - resumes/);
  });

  it('the usage poll shows the Fable window full: the lead switches before any turn is refused, and back when it has room again', async () => {
    const calls: Call[] = [];
    const fableResets = new Date(Date.now() + 3 * 24 * 3600_000).toISOString();
    let fablePercent = 100;
    const hh = await boot(
      async function* (p, opts, call) {
        const s = sid();
        yield init(s, opts.model);
        if (!call.lead) return yield* work(p, opts, s);
        if (leads(calls).length === 1) {
          await callTool(opts, 'create_task', { title: 'Do it', assignee: 'kit' });
          fablePercent = 40; // the next poll (after this turn) shows room in the Fable window again
        }
        yield ok(s);
      },
      calls,
      { ...OWNER, useClaudeLogin: true },
      { usage: () => fableUsage(fablePercent, fableResets) },
    );
    const { fm } = hh;
    // the first poll, right after the auth check
    await until(() => !!state(hh).leadFallback);
    expect(state(hh).modelLimits?.fable).toEqual({ until: Date.parse(fableResets), fromUsage: true });
    expect(state(hh).leadFallback).toMatchObject({ family: 'fable', until: Date.parse(fableResets) });
    expect(feed(hh, /^Marlow switched to Opus 5\.5 \(xhigh\): Fable limit reached, back at \S/)).toBe(true);
    expect(fm.agent('marlow')!.model).toBe('Opus 5.5');
    // the lead plans on its fallback: no turn of it ever runs into the Fable limit
    await fm.submitGoal('do it');
    await until(() => leads(calls).length === 1);
    expect(leads(calls)[0]).toMatchObject({ model: 'claude-opus-5-5', effort: 'xhigh' });
    // the poll after that turn shows room: the limit only the poll had seen lifts, the lead is back on Fable
    await until(() => feed(hh, /^Marlow is back on Fable 5\.1 \(high\): the Fable limit reset$/));
    expect(state(hh).modelLimits?.fable).toBeUndefined();
    expect(state(hh).leadFallback).toBeUndefined();
    await until(() => workers(calls).length === 1);
    await until(() => !state(hh).inflight.marlow);
    await say(hh, 'and now?');
    await until(() => leads(calls).length === 2);
    expect(leads(calls)[1]).toMatchObject({ model: 'claude-fable-5-1', effort: 'high' });
  });
});
