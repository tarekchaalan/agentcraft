// Per-agent models: the console's /model (`agent.model`). config.json claude.agentModels /
// codex.agentModels (parsing, validation, precedence over leadModel / workerModel / effort), saving
// them (atomic, every other key kept), the message (targets, aliases, default, refusals, Codex vs
// Claude), the effect on the next turn (never the running one), and how a change meets a model's own
// usage limit and the lead's fallback. Fake SDK `query()` and a fake `codex app-server`, no API calls.
import fs from 'node:fs';
import path from 'node:path';
import type { Options, SDKMessage } from '@anthropic-ai/claude-agent-sdk';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ClaudeBackend } from '../src/agents/claude/index.js';
import { ClaudeEngine } from '../src/agents/claude/engine.js';
import { CodexEngine } from '../src/agents/codex/engine.js';
import { SimBackend } from '../src/agents/sim/index.js';
import { TeamBackend } from '../src/agents/team.js';
import { claudeModelFor, loadConfig, saveAgentModels } from '../src/config.js';
import { AgentModelInfo, type ClientMessage, type Outbound } from '../src/protocol.js';
import { demoRepo, makeForeman, rmrf, tempDir, until, type Harness } from './helpers.js';

type ToolServer = { instance: { _registeredTools: Record<string, { handler: (a: unknown, e: unknown) => Promise<{ content: Array<{ text: string }> }> }> } };
const tools = (o: Options) => (o.mcpServers!.agentcraft as unknown as ToolServer).instance._registeredTools;
const callTool = async (o: Options, name: string, args: Record<string, unknown>) => (await tools(o)[name]!.handler(args, {})).content.map((c) => c.text).join('\n');

let n = 0;
const sid = () => `00000000-0000-4000-8000-${String(++n).padStart(12, '0')}`;
const msg = (o: Record<string, unknown>) => ({ parent_tool_use_id: null, uuid: sid(), ...o }) as unknown as SDKMessage;
const init = (s: string, model?: string) => msg({ type: 'system', subtype: 'init', session_id: s, model: model ?? 'fake', cwd: '', tools: [] });
const ok = (s: string) => msg({ type: 'result', subtype: 'success', is_error: false, result: 'ok', num_turns: 1, total_cost_usd: 0, session_id: s, duration_ms: 1, duration_api_ms: 1, usage: {}, modelUsage: {}, permission_denials: [] });
const rejected = (s: string, rateLimitType: string, resetsAt: number) =>
  msg({ type: 'rate_limit_event', session_id: s, rate_limit_info: { status: 'rejected', rateLimitType, resetsAt: Math.floor(resetsAt / 1000) } });
function* refused(s: string, text: string): Generator<SDKMessage> {
  yield msg({ type: 'assistant', error: 'rate_limit', session_id: s, message: { role: 'assistant', content: [{ type: 'text', text }] } });
  yield msg({ type: 'result', subtype: 'success', is_error: true, result: text, num_turns: 1, total_cost_usd: 0, session_id: s, duration_ms: 1, duration_api_ms: 1, usage: {}, modelUsage: {}, permission_denials: [] });
}
const FABLE_LIMIT = "You've hit your Fable limit · resets 3pm";

interface Call {
  /** whose turn: from the agent's git identity in the turn's environment */
  agent: string;
  prompt: string;
  model?: string;
  effort?: string;
  at: number;
  /** the turn was stopped (it never should be by /model) */
  aborted?: boolean;
}
type Turn = (p: string, opts: Options, call: Call) => AsyncGenerator<SDKMessage>;

function fakeQuery(turn: Turn, calls: Call[]) {
  return ({ prompt, options }: { prompt: string; options?: Options }) => {
    const opts = options!;
    const base = { close() {}, accountInfo: async () => ({ email: 'x' }) };
    if (!opts.mcpServers) return base;
    const agent = /^(\w+)@agentcraft\.local$/.exec(String(opts.env?.GIT_AUTHOR_EMAIL))?.[1] ?? '?';
    const call: Call = { agent, prompt: String(prompt), at: Date.now(), ...(opts.model ? { model: opts.model } : {}), ...(opts.effort ? { effort: opts.effort } : {}) };
    calls.push(call);
    return Object.assign(turn(String(prompt), opts, call), base);
  };
}

let h: Harness | undefined;
const cleanup: string[] = [];
let repoPath = '';

/** A Claude team on the fake SDK; `config` is written as config.json (undefined: keep the file there). */
async function boot(turn: Turn, calls: Call[], config: Record<string, unknown> | undefined, o: { args?: string[]; home?: string; workers?: string } = {}): Promise<Harness> {
  if (!repoPath) {
    repoPath = await demoRepo();
    cleanup.push(path.dirname(repoPath));
  }
  const home = o.home ?? tempDir();
  if (!cleanup.includes(home)) cleanup.push(home);
  if (config) fs.writeFileSync(path.join(home, 'config.json'), JSON.stringify(config));
  h = makeForeman(home, ['--backend', 'claude', '--repo', repoPath, '--no-lead-review', '--workers', o.workers ?? 'kit', ...(o.args ?? [])]);
  await h.fm.start(new ClaudeBackend(h.fm, h.cfg.claude, { queryFn: fakeQuery(turn, calls) as never, skipAuthCheck: true }));
  return h;
}

afterEach(async () => {
  await h?.fm.close();
  h = undefined;
  for (const d of cleanup.splice(0)) rmrf(d);
  repoPath = '';
});

type Ack = Extract<Outbound, { type: 'ack' }> & { result?: { agents: AgentModelInfo[]; text: string; changed?: string[]; skipped?: Array<{ agentId: string; reason: string }>; saved?: boolean; saveError?: string } };

/** Send `agent.model` the way the console does; the ack. */
async function model(hh: Harness, agentId?: string, m?: string, effort?: string): Promise<Ack> {
  const replies: Outbound[] = [];
  const message = { v: 1, id: 'm1', type: 'agent.model', ...(agentId ? { agentId } : {}), ...(m ? { model: m } : {}), ...(effort ? { effort } : {}) } as ClientMessage;
  await hh.fm.handle(message, (r) => replies.push(r));
  return replies.find((r) => r.type === 'ack') as Ack;
}

const configFile = (hh: Harness) => path.join(hh.cfg.home, 'config.json');
const readConfig = (hh: Harness) => JSON.parse(fs.readFileSync(configFile(hh), 'utf8')) as Record<string, Record<string, unknown>>;
const feed = (hh: Harness, re: RegExp) => hh.fm.store.data.feed.some((f) => re.test(f.text));
const say = (hh: Harness, text: string) => hh.fm.handle({ v: 1, type: 'user.message', to: 'marlow', text }, () => undefined);
const of = (calls: Call[], agent: string) => calls.filter((c) => c.agent === agent);
type St = { inflight: Record<string, unknown>; limit?: unknown; modelLimits?: Record<string, { until: number }>; leadFallback?: { model: string; family: string; until: number } };
const state = (hh: Harness) => hh.fm.store.data.backend.claude as St;
const WORKERS = ['juniper', 'kit', 'wren', 'rowan', 'tove'];

/** a worker that finishes the task it is doing */
async function* work(opts: Options, s: string, agent: string): AsyncGenerator<SDKMessage> {
  const task = h?.fm.tasks.list().find((t) => t.assignee === agent && t.status === 'doing')?.id;
  if (task) await callTool(opts, 'update_task', { task_id: task, status: 'review', summary: 'ok' });
  yield ok(s);
}

// ---------------------------------------------------------------------------------------------

describe('config.json claude.agentModels / codex.agentModels', () => {
  let home: string | undefined;
  afterEach(() => {
    if (home) rmrf(home);
    home = undefined;
  });
  function load(file: Record<string, unknown> | undefined, args: string[] = [], env: NodeJS.ProcessEnv = {}) {
    home ??= tempDir();
    if (file) fs.writeFileSync(path.join(home, 'config.json'), JSON.stringify(file));
    else fs.rmSync(path.join(home, 'config.json'), { force: true });
    return loadConfig(['--home', home, ...args], env);
  }

  it('none by default; the lead\'s and workers\' models say whether they are the built-in default or configured', () => {
    const c = load(undefined);
    expect(c.claude.agentModels).toEqual({});
    expect(c.codex.agentModels).toEqual({});
    expect(c.claude.modelOrigin).toEqual({ lead: 'default', worker: 'default' });
    expect(load(undefined, [], { AGENTCRAFT_LEAD_MODEL: 'fable' }).claude.modelOrigin).toEqual({ lead: 'config', worker: 'default' });
    expect(load({ claude: { workerModel: 'haiku' } }).claude.modelOrigin).toEqual({ lead: 'default', worker: 'config' });
    expect(load(undefined, ['--model', 'sonnet']).claude.modelOrigin).toEqual({ lead: 'config', worker: 'config' });
  });

  it('reads both sections; an agent\'s own model and effort beat leadModel / workerModel / effort, from config.json or flags', () => {
    const c = load(
      {
        claude: { leadModel: 'opus', workerModel: 'sonnet', effort: 'low', agentModels: { marlow: { model: 'fable' }, juniper: { model: 'haiku', effort: 'max' } } },
        codex: { agentModels: { kit: { model: 'gpt-5.1-codex', effort: 'minimal' } } },
      },
      ['--worker-model', 'claude-opus-5-5', '--lead-model', 'claude-sonnet-5', '--effort', 'high'],
    );
    expect(c.claude.agentModels).toEqual({ marlow: { model: 'fable' }, juniper: { model: 'haiku', effort: 'max' } });
    expect(c.codex.agentModels).toEqual({ kit: { model: 'gpt-5.1-codex', effort: 'minimal' } });
    // its own model, and its role's effort when it has none of its own
    expect(claudeModelFor(c.claude, 'marlow', 'lead')).toEqual({ model: 'fable', effort: 'high' });
    expect(claudeModelFor(c.claude, 'juniper', 'worker')).toEqual({ model: 'haiku', effort: 'max' });
    // the others: the flags
    expect(claudeModelFor(c.claude, 'kit', 'worker')).toEqual({ model: 'claude-opus-5-5', effort: 'high' });
    expect(claudeModelFor(c.claude, undefined, 'lead')).toEqual({ model: 'claude-sonnet-5', effort: 'high' });
    // a hand-written alias runs in the CLI's spelling
    expect(load({ claude: { agentModels: { wren: { model: ' Sonnet ' } } } }).claude.agentModels).toEqual({ wren: { model: 'sonnet' } });
  });

  it('refuses a mistake at the start instead of failing an agent\'s turn', () => {
    const bad = (file: Record<string, unknown>, re: RegExp) => expect(() => load(file)).toThrow(re);
    bad({ claude: { agentModels: ['juniper'] } }, /config\.json claude\.agentModels must be an object/);
    bad({ claude: { agentModels: { bob: { model: 'sonnet' } } } }, /claude\.agentModels: no agent "bob" \(use marlow, juniper, kit, wren, rowan, tove\)/);
    bad({ claude: { agentModels: { Juniper: { model: 'sonnet' } } } }, /no agent "Juniper"/);
    bad({ claude: { agentModels: { juniper: 'sonnet' } } }, /claude\.agentModels\.juniper must be like \{"model"/);
    bad({ claude: { agentModels: { juniper: { effort: 'high' } } } }, /claude\.agentModels\.juniper must be like/);
    bad({ claude: { agentModels: { juniper: { model: 'sonet' } } } }, /juniper\.model "sonet" is not a Claude model/);
    bad({ claude: { agentModels: { juniper: { model: 'gpt-5' } } } }, /"gpt-5" is not a Claude model/);
    bad({ claude: { agentModels: { juniper: { model: 'son net' } } } }, /"son net" is not a model name/);
    bad({ claude: { agentModels: { juniper: { model: 'sonnet', effort: 'extreme' } } } }, /juniper\.effort "extreme" is not an effort \(use low, medium, high, xhigh, max\)/);
    bad({ codex: { agentModels: { kit: { model: 'sonnet' } } } }, /codex\.agentModels\.kit\.model "sonnet" is a Claude model/);
    bad({ codex: { agentModels: { kit: { model: 'gpt-5', effort: 'max' } } } }, /kit\.effort "max" is not an effort \(use none, minimal, low, medium, high, xhigh\)/);
  });
});

describe('saving per-agent models in config.json', () => {
  let home: string | undefined;
  afterEach(() => {
    vi.restoreAllMocks();
    if (home) rmrf(home);
    home = undefined;
  });

  it('changes only those entries: every other key and its order stay, two-space JSON, through a temp file and a rename', () => {
    home = tempDir();
    const file = path.join(home, 'config.json');
    const original = {
      userName: 'Alex',
      claude: { leadModel: 'opus', useClaudeLogin: true, agentModels: { kit: { model: 'haiku' } }, permissions: { mode: 'auto', allow: ['Bash(bd show:*)'] } },
      launcher: { enabled: true, stopOnExit: false },
      repos: ['C:/work/app'],
    };
    fs.writeFileSync(file, JSON.stringify(original));
    const rename = vi.spyOn(fs, 'renameSync');
    expect(saveAgentModels(home, [{ section: 'claude', agentId: 'juniper', value: { model: 'sonnet', effort: 'high' } }])).toBe(fs.realpathSync(file));
    expect(rename).toHaveBeenCalledWith(expect.stringMatching(/config\.json\.\d+\.\d+\.tmp$/), fs.realpathSync(file));
    const expected = { ...original, claude: { ...original.claude, agentModels: { kit: { model: 'haiku' }, juniper: { model: 'sonnet', effort: 'high' } } } };
    expect(fs.readFileSync(file, 'utf8')).toBe(`${JSON.stringify(expected, null, 2)}\n`);
    // removing the last ones takes agentModels away again; nothing else moves
    saveAgentModels(home, [
      { section: 'claude', agentId: 'juniper', value: undefined },
      { section: 'claude', agentId: 'kit', value: undefined },
    ]);
    const after = JSON.parse(fs.readFileSync(file, 'utf8')) as typeof original;
    expect(after).toEqual({ ...original, claude: { leadModel: 'opus', useClaudeLogin: true, permissions: original.claude.permissions } });
    expect(Object.keys(after)).toEqual(['userName', 'claude', 'launcher', 'repos']);
    expect(Object.keys(after.claude)).toEqual(['leadModel', 'useClaudeLogin', 'permissions']);
    expect(fs.readdirSync(home)).toEqual(['config.json']); // no temp file left
  });

  it('creates config.json and its section when there is none; Codex agents go under codex', () => {
    home = tempDir();
    saveAgentModels(home, [{ section: 'claude', agentId: 'tove', value: undefined }]); // nothing to remove: nothing written
    expect(fs.existsSync(path.join(home, 'config.json'))).toBe(false);
    saveAgentModels(home, [{ section: 'codex', agentId: 'kit', value: { model: 'gpt-5.1-codex' } }]);
    expect(JSON.parse(fs.readFileSync(path.join(home, 'config.json'), 'utf8'))).toEqual({ codex: { agentModels: { kit: { model: 'gpt-5.1-codex' } } } });
    // what it saved loads back
    expect(loadConfig(['--home', home], {}).codex.agentModels).toEqual({ kit: { model: 'gpt-5.1-codex' } });
  });

  it('leaves a config.json it cannot read as it is, and a failed write changes nothing', () => {
    home = tempDir();
    const file = path.join(home, 'config.json');
    const change = [{ section: 'claude' as const, agentId: 'kit', value: { model: 'haiku' } }];
    for (const [text, re] of [
      ['{ "claude": ', /config\.json is not valid JSON/],
      ['[1, 2]', /config\.json is not a JSON object/],
      ['{"claude": "opus"}', /config\.json "claude" is not an object/],
      ['{"claude": {"agentModels": []}}', /claude\.agentModels is not an object/],
    ] as const) {
      fs.writeFileSync(file, text);
      expect(() => saveAgentModels(home!, change)).toThrow(re);
      expect(fs.readFileSync(file, 'utf8')).toBe(text);
    }
    // the rename fails (disk error): the old file stays whole, the temp file goes
    fs.writeFileSync(file, '{"userName": "Alex"}');
    vi.spyOn(fs, 'renameSync').mockImplementationOnce(() => {
      throw Object.assign(new Error('disk error'), { code: 'EIO' });
    });
    expect(() => saveAgentModels(home!, change)).toThrow(/disk error/);
    expect(fs.readFileSync(file, 'utf8')).toBe('{"userName": "Alex"}');
    expect(fs.readdirSync(home)).toEqual(['config.json']);
  });
});

describe('agent.model on a Claude team', () => {
  const idle: Turn = async function* (_p, opts) {
    const s = sid();
    yield init(s, opts.model);
    yield ok(s);
  };

  it('lists every agent\'s model, effort and where it comes from; one agent by name, the lead, the workers or all', async () => {
    const hh = await boot(idle, [], { claude: { leadModel: 'claude-opus-5-5', effort: 'high', agentModels: { kit: { model: 'haiku', effort: 'low' } } } });
    const a = await model(hh);
    expect(a.ok).toBe(true);
    const agents = a.result!.agents;
    for (const i of agents) expect(AgentModelInfo.parse(i)).toEqual(i);
    expect(agents.map((i) => i.agentId)).toEqual(['marlow', ...WORKERS]);
    expect(agents[0]).toEqual({ agentId: 'marlow', name: 'Marlow', engine: 'claude', model: 'claude-opus-5-5', label: 'Opus 5.5', effort: 'medium', source: 'config' });
    expect(agents[1]).toEqual({ agentId: 'juniper', name: 'Juniper', engine: 'claude', model: 'sonnet', label: 'Sonnet', effort: 'high', source: 'default' });
    expect(agents[2]).toEqual({ agentId: 'kit', name: 'Kit', engine: 'claude', model: 'haiku', label: 'Haiku', effort: 'low', source: 'override' });
    expect(a.result!.text).toMatch(/^Marlow Opus 5\.5 \(medium\) \(config\), Juniper Sonnet \(high\) \(default\), Kit Haiku \(low\) \(override\), /);
    const ids = async (target: string) => (await model(hh, target)).result!.agents.map((i) => i.agentId);
    expect(await ids('Kit')).toEqual(['kit']);
    expect(await ids('@juniper')).toEqual(['juniper']);
    expect(await ids('lead')).toEqual(['marlow']);
    expect(await ids('foreman')).toEqual(['marlow']);
    expect(await ids('workers')).toEqual(WORKERS);
    expect(await ids('all')).toEqual(['marlow', ...WORKERS]);
    const bad = await model(hh, 'bob');
    expect(bad).toMatchObject({ ok: false, error: 'no agent named "bob" (an agent\'s name, lead, workers or all)' });
    // the nameplate shows the agent's own model too, off shift or not
    expect(hh.fm.agent('kit')!.model).toBe('Haiku');
    expect(hh.fm.agent('marlow')!.model).toBe('Opus 5.5');
  });

  it('/model juniper sonnet high: saved, on the nameplate and in the feed at once, and her next turn runs it', async () => {
    const calls: Call[] = [];
    const hh = await boot(
      async function* (p, opts, call) {
        const s = sid();
        yield init(s, opts.model);
        if (call.agent === 'marlow') {
          if (p.startsWith('New goal')) {
            await callTool(opts, 'create_task', { title: 'One', assignee: 'juniper' });
            await callTool(opts, 'create_task', { title: 'Two', assignee: 'kit' });
          }
          return yield ok(s);
        }
        yield* work(opts, s, call.agent);
      },
      calls,
      { userName: 'Sam', claude: { workerModel: 'claude-opus-5-5', leadReview: false }, launcher: { enabled: false, stopOnExit: true } },
      { workers: 'juniper,kit' },
    );
    const { fm } = hh;
    const before = hh.events.length;
    const a = await model(hh, 'Juniper', 'Sonnet', 'HIGH');
    expect(a.ok).toBe(true);
    expect(a.result!.text).toBe('Juniper now runs Sonnet (high) from the next turn; saved');
    expect(a.result).toMatchObject({ changed: ['juniper'], saved: true, agents: [{ agentId: 'juniper', model: 'sonnet', label: 'Sonnet', effort: 'high', source: 'override' }] });
    // the alias in lower case, the effort with it; every other key of config.json kept
    expect(hh.cfg.claude.agentModels).toEqual({ juniper: { model: 'sonnet', effort: 'high' } });
    expect(readConfig(hh)).toEqual({ userName: 'Sam', claude: { workerModel: 'claude-opus-5-5', leadReview: false, agentModels: { juniper: { model: 'sonnet', effort: 'high' } } }, launcher: { enabled: false, stopOnExit: true } });
    // at once: the nameplate (agent.upsert), the feed, the banner
    expect(fm.agent('juniper')!.model).toBe('Sonnet');
    expect(hh.events.slice(before).some((e) => e.type === 'agent.upsert' && e.agent.id === 'juniper' && e.agent.model === 'Sonnet')).toBe(true);
    expect(fm.store.data.feed.at(-1)).toMatchObject({ kind: 'system', text: 'Juniper switched to Sonnet (high, set by Alex)', agentId: 'juniper' });
    expect(fm.status.message).toBe('Claude (lead opus, workers Juniper sonnet, Kit Opus 5.5)');
    // the next turns: Juniper on hers, Kit on the workers' model and effort
    await fm.submitGoal('two things');
    await until(() => of(calls, 'juniper').length === 1 && of(calls, 'kit').length === 1);
    expect(of(calls, 'juniper')[0]).toMatchObject({ model: 'sonnet', effort: 'high' });
    expect(of(calls, 'kit')[0]).toMatchObject({ model: 'claude-opus-5-5', effort: 'medium' });
    expect(of(calls, 'marlow')[0]).toMatchObject({ model: 'opus', effort: 'medium' });
    expect(fm.store.logTail('juniper').some((e) => e.text.startsWith('Starting work t1 (sonnet)'))).toBe(true);
  });

  it('a change mid-turn never stops the turn: it finishes on its model, the next turn runs the new one', async () => {
    const calls: Call[] = [];
    let release!: () => void;
    const released = new Promise<void>((r) => (release = r));
    const hh = await boot(
      async function* (p, opts, call) {
        const s = sid();
        yield init(s, opts.model);
        if (call.agent === 'marlow') {
          if (p.startsWith('New goal')) await callTool(opts, 'create_task', { title: 'One', assignee: 'juniper' });
          return yield ok(s);
        }
        if (of(calls, 'juniper').length === 1) {
          await released; // ends without update_task: a follow-up turn nudges her
          call.aborted = opts.abortController!.signal.aborted;
          return yield ok(s);
        }
        yield* work(opts, s, call.agent);
      },
      calls,
      { claude: {} },
      { workers: 'juniper' },
    );
    const { fm } = hh;
    await fm.submitGoal('one thing');
    await until(() => of(calls, 'juniper').length === 1 && fm.agent('juniper')!.model === 'Sonnet');
    const a = await model(hh, 'juniper', 'haiku');
    expect(a.result!.text).toBe('Juniper now runs Haiku (medium) from the next turn; saved');
    expect(fm.agent('juniper')!.model).toBe('Haiku'); // what her next turn runs
    expect(state(hh).inflight.juniper).toBeDefined(); // the turn goes on
    release();
    await until(() => of(calls, 'juniper').length === 2);
    expect(of(calls, 'juniper')[0]).toMatchObject({ model: 'sonnet', aborted: false });
    expect(of(calls, 'juniper')[1]).toMatchObject({ model: 'haiku', effort: 'medium' });
    await until(() => fm.tasks.list()[0]!.status === 'review' && !state(hh).inflight.juniper);
    expect(fm.agent('juniper')!.model).toBe('Haiku');
  });

  it('"default" takes the agent\'s own model away again; groups; refusals change nothing', async () => {
    const hh = await boot(idle, [], { claude: { workerModel: 'claude-opus-5-5' } });
    const { fm } = hh;
    // the workers, then everyone: one feed line per new model
    const w = await model(hh, 'workers', 'haiku');
    expect(w.result!.text).toBe('Juniper, Kit, Wren, Rowan and Tove now run Haiku (medium) from the next turn; saved');
    expect(feed(hh, /^Juniper, Kit, Wren, Rowan and Tove switched to Haiku \(medium, set by Alex\)$/)).toBe(true);
    expect(Object.keys(hh.cfg.claude.agentModels)).toEqual(WORKERS);
    const all = await model(hh, 'all', 'fable', 'max');
    expect(all.result!.text).toBe('Marlow, Juniper, Kit, Wren, Rowan and Tove now run Fable (max) from the next turn; saved');
    expect((readConfig(hh).claude!.agentModels as Record<string, unknown>).marlow).toEqual({ model: 'fable', effort: 'max' });
    expect(fm.status.message).toBe('Claude (lead fable, workers fable)');
    // back to the configured model
    for (const id of ['marlow', ...WORKERS.filter((x) => x !== 'juniper')]) await model(hh, id, 'default');
    const r = await model(hh, 'juniper', 'DEFAULT');
    expect(r.result!.text).toBe('Juniper is back on the configured model, Opus 5.5 (medium), from the next turn; saved');
    expect(feed(hh, /^Juniper switched back to the configured model, Opus 5\.5 \(medium, set by Alex\)$/)).toBe(true);
    expect(r.result!.agents[0]).toMatchObject({ model: 'claude-opus-5-5', source: 'config' });
    expect(hh.cfg.claude.agentModels).toEqual({});
    expect(readConfig(hh)).toEqual({ claude: { workerModel: 'claude-opus-5-5' } }); // agentModels removed again
    expect(fm.agent('juniper')!.model).toBe('Opus 5.5');
    // nothing to take away: nothing written, nothing in the feed
    const lines = fm.store.data.feed.length;
    expect((await model(hh, 'juniper', 'default')).result).toMatchObject({ text: 'Juniper already runs Opus 5.5 (medium)', changed: [], saved: true });
    expect(fm.store.data.feed.length).toBe(lines);

    // refusals: an error, nothing changed, nothing written
    const file = fs.readFileSync(configFile(hh), 'utf8');
    for (const [target, m, effort, error] of [
      ['juniper', 'sonet', undefined, 'Juniper runs on Claude: sonet is not a Claude model (use opus, sonnet, haiku, fable or a claude-... model id)'],
      ['juniper', 'gpt-5', undefined, 'Juniper runs on Claude: gpt-5 is not a Claude model (use opus, sonnet, haiku, fable or a claude-... model id)'],
      ['juniper', 'sonnet', 'extreme', 'Juniper runs on Claude: there is no "extreme" effort (use low, medium, high, xhigh, max)'],
      ['juniper', 'son;net', undefined, 'Juniper runs on Claude: "son;net" is not a model name'],
      ['juniper', 'default', 'high', '"default" goes back to the configured model and effort: give no effort with it'],
      ['juniper', undefined, 'high', 'an effort goes with a model: /model juniper <model> high'],
      ['workers', 'gpt-5', undefined, 'Juniper, Kit, Wren, Rowan and Tove run on Claude: gpt-5 is not a Claude model (use opus, sonnet, haiku, fable or a claude-... model id)'],
    ] as const) {
      const bad = await model(hh, target, m, effort);
      expect(bad, `${target} ${m} ${effort}`).toMatchObject({ ok: false, error });
    }
    expect(hh.cfg.claude.agentModels).toEqual({});
    expect(fs.readFileSync(configFile(hh), 'utf8')).toBe(file);
    expect(fm.store.data.feed.length).toBe(lines);
    // a full model id and a provider's spelling are Claude models too
    expect((await model(hh, 'kit', 'claude-sonnet-5')).result!.text).toBe('Kit now runs Sonnet 5 (medium) from the next turn; saved');
    expect((await model(hh, 'kit', 'us.anthropic.claude-haiku-4-5-20251001-v1:0')).ok).toBe(true);
  });

  it('survives a restart, and beats the workers\' model given as a flag', async () => {
    const calls: Call[] = [];
    const home = tempDir();
    const turn: Turn = async function* (p, opts, call) {
      const s = sid();
      yield init(s, opts.model);
      if (call.agent === 'marlow') {
        if (p.startsWith('New goal')) await callTool(opts, 'create_task', { title: 'One', assignee: 'kit' });
        return yield ok(s);
      }
      yield* work(opts, s, call.agent);
    };
    let hh = await boot(turn, calls, { claude: {} }, { home });
    expect((await model(hh, 'kit', 'haiku', 'low')).result!.saved).toBe(true);
    await hh.fm.close();
    hh = await boot(turn, calls, undefined, { home, args: ['--worker-model', 'claude-opus-5-5'] });
    expect(hh.cfg.claude.agentModels).toEqual({ kit: { model: 'haiku', effort: 'low' } });
    expect(hh.fm.agent('kit')!.model).toBe('Haiku');
    expect(hh.fm.agent('juniper')!.model).toBe('Opus 5.5');
    await hh.fm.submitGoal('one thing');
    await until(() => of(calls, 'kit').length === 1);
    expect(of(calls, 'kit')[0]).toMatchObject({ model: 'haiku', effort: 'low' });
  });

  it('a config.json it cannot write: the change applies until the Foreman restarts, and says it was not saved', async () => {
    const hh = await boot(idle, [], { claude: {} });
    fs.writeFileSync(configFile(hh), '{ "claude": { broken');
    const a = await model(hh, 'kit', 'haiku');
    expect(a.ok).toBe(true);
    expect(a.result).toMatchObject({ saved: false, saveError: expect.stringMatching(/^config\.json is not valid JSON/) });
    expect(a.result!.text).toMatch(/^Kit now runs Haiku \(medium\) from the next turn; NOT saved: config\.json is not valid JSON/);
    expect(fs.readFileSync(configFile(hh), 'utf8')).toBe('{ "claude": { broken');
    expect(hh.cfg.claude.agentModels.kit).toEqual({ model: 'haiku' });
    expect(hh.fm.agent('kit')!.model).toBe('Haiku');
  });

  it('the sim team has no models to choose', async () => {
    const home = tempDir();
    cleanup.push(home);
    h = makeForeman(home, ['--backend', 'sim']);
    h.fm.backend = new SimBackend(h.fm, h.cfg.sim);
    expect(await model(h, 'kit', 'sonnet')).toMatchObject({ ok: false, error: 'the simulated team has no models to choose: /model needs the claude or codex backend' });
    expect(await model(h)).toMatchObject({ ok: false });
  });
});

describe('agent.model with a model\'s own usage limit and the lead\'s fallback', () => {
  const FALLBACK = { leadModel: 'claude-fable-5-1', workerModel: 'claude-opus-5-5', effort: 'high', leadEffort: 'high', leadFallback: { model: 'claude-opus-5-5', effort: 'xhigh' } };

  it('a lead on its fallback switched to another model: the fallback is over and its next turn runs the new one; switched back onto the limited model, the fallback applies again', async () => {
    const calls: Call[] = [];
    let resetAt = 0;
    const hh = await boot(
      async function* (_p, opts, call) {
        const s = sid();
        yield init(s, opts.model);
        if (call.agent === 'marlow' && of(calls, 'marlow').length === 1) {
          resetAt = Date.now() + 60 * 60_000;
          yield rejected(s, 'seven_day_overage_included', resetAt);
          return yield* refused(s, FABLE_LIMIT);
        }
        yield ok(s);
      },
      calls,
      { claude: FALLBACK },
    );
    const { fm } = hh;
    await fm.submitGoal('do it');
    await until(() => of(calls, 'marlow').length === 2 && !state(hh).inflight.marlow);
    expect(of(calls, 'marlow')[1]).toMatchObject({ model: 'claude-opus-5-5', effort: 'xhigh' });
    expect(state(hh).leadFallback).toMatchObject({ model: 'claude-fable-5-1', family: 'fable' });
    expect((await model(hh, 'lead')).result!.agents[0]).toMatchObject({ model: 'claude-fable-5-1', fallback: 'Opus 5.5 (xhigh)' });

    // onto Sonnet: no limit holds it, the fallback no longer applies
    const a = await model(hh, 'foreman', 'sonnet');
    expect(a.result!.text).toBe('Marlow now runs Sonnet (high) from the next turn; saved');
    expect(state(hh).leadFallback).toBeUndefined();
    expect(state(hh).modelLimits?.fable).toBeDefined(); // the Fable limit itself still holds Fable agents
    expect(fm.agent('marlow')!.model).toBe('Sonnet');
    expect(fm.status.message).toBe('Claude (lead sonnet, workers Opus 5.5)');
    expect(feed(hh, /^Marlow switched to Sonnet \(high, set by Alex\)$/)).toBe(true);
    await say(hh, 'still there?');
    await until(() => of(calls, 'marlow').length === 3);
    expect(of(calls, 'marlow')[2]).toMatchObject({ model: 'sonnet', effort: 'high' });
    expect(of(calls, 'marlow')[2]!.at).toBeLessThan(resetAt);
    await until(() => !state(hh).inflight.marlow);

    // back onto Fable while its limit lasts: the lead runs its fallback again, not into the limit
    const switches = () => fm.store.data.feed.filter((f) => /^Marlow switched to Opus 5\.5 \(xhigh\): Fable limit reached, back at \S/.test(f.text)).length;
    expect(switches()).toBe(1);
    const b = await model(hh, 'lead', 'fable');
    expect(b.result!.text).toMatch(/^Marlow now runs Fable \(high\) from the next turn; Marlow runs Opus 5\.5 \(xhigh\) meanwhile, while the Fable limit lasts \(until .+\); saved$/);
    expect(state(hh).leadFallback).toMatchObject({ model: 'fable', family: 'fable', until: Math.floor(resetAt / 1000) * 1000 });
    expect(feed(hh, /^Marlow switched to Fable \(high, set by Alex\)$/)).toBe(true);
    expect(switches()).toBe(2);
    expect(fm.agent('marlow')!.model).toBe('Opus 5.5');
    await say(hh, 'and now?');
    await until(() => of(calls, 'marlow').length === 4);
    expect(of(calls, 'marlow')[3]).toMatchObject({ model: 'claude-opus-5-5', effort: 'xhigh' });
    expect(state(hh).limit).toBeUndefined();
  });

  it('without a fallback: switched onto a limited model an agent waits for that limit; switched away it runs at once', async () => {
    const calls: Call[] = [];
    let resetAt = 0;
    const hh = await boot(
      async function* (p, opts, call) {
        const s = sid();
        yield init(s, opts.model);
        if (call.agent === 'marlow') {
          if (p.startsWith('New goal')) await callTool(opts, 'create_task', { title: 'One', assignee: 'kit' });
          return yield ok(s);
        }
        if (of(calls, 'kit').length === 1) {
          resetAt = Date.now() + 60 * 60_000;
          yield rejected(s, 'seven_day_overage_included', resetAt);
          return yield* refused(s, FABLE_LIMIT);
        }
        yield* work(opts, s, call.agent);
      },
      calls,
      { claude: { leadModel: 'claude-opus-5-5', workerModel: 'claude-fable-5-1', effort: 'high', leadEffort: 'high' } },
    );
    const { fm } = hh;
    await fm.submitGoal('do it');
    await until(() => of(calls, 'kit').length === 1 && fm.agent('kit')!.activity.startsWith('Fable limit - resumes'));
    await until(() => !state(hh).inflight.marlow);
    // the lead onto Fable: it waits for the Fable limit like Kit
    const a = await model(hh, 'lead', 'claude-fable-5-1');
    expect(a.result!.text).toMatch(/^Marlow now runs Fable 5\.1 \(high\) from the next turn; Marlow waits for the Fable limit \(until .+\); saved$/);
    expect(fm.status.message).toMatch(/^Fable limit reached \(seven day overage included\): Marlow and Kit wait until .+, then resume$/);
    await say(hh, 'status?');
    await until(() => fm.agent('marlow')!.activity.startsWith('Fable limit - resumes'));
    expect(of(calls, 'marlow')).toHaveLength(1);
    // Kit away from Fable: its held job runs now, long before the reset
    const b = await model(hh, 'kit', 'opus');
    expect(b.result!.text).toBe('Kit now runs Opus (high) from the next turn; saved');
    await until(() => of(calls, 'kit').length === 2);
    expect(of(calls, 'kit')[1]).toMatchObject({ model: 'opus', effort: 'high' });
    expect(of(calls, 'kit')[1]!.at).toBeLessThan(resetAt);
    expect(of(calls, 'kit')[1]!.prompt).toMatch(/^A usage limit stopped your last turn/);
    // the lead back to its configured Opus: its held follow-up runs now too
    const c = await model(hh, 'lead', 'default');
    expect(c.result!.text).toBe('Marlow is back on the configured model, Opus 5.5 (high), from the next turn; saved');
    await until(() => of(calls, 'marlow').length === 2);
    expect(of(calls, 'marlow')[1]).toMatchObject({ model: 'claude-opus-5-5', effort: 'high' });
    expect(state(hh).limit).toBeUndefined();
    expect(state(hh).modelLimits?.fable).toBeDefined();
    await until(() => fm.tasks.list()[0]!.status === 'review');
    expect(fm.status.message).toBe('Claude (lead Opus 5.5, workers opus)');
  });

  it('a turn on the old model refused by its limit after the change continues at once on the new model (nobody else waits)', async () => {
    const calls: Call[] = [];
    let release!: () => void;
    const released = new Promise<void>((r) => (release = r));
    const hh = await boot(
      async function* (p, opts, call) {
        const s = sid();
        yield init(s, opts.model);
        if (call.agent === 'marlow') {
          if (p.startsWith('New goal')) await callTool(opts, 'create_task', { title: 'One', assignee: 'kit' });
          return yield ok(s);
        }
        if (of(calls, 'kit').length === 1) {
          await released;
          yield rejected(s, 'seven_day_overage_included', Date.now() + 60 * 60_000);
          return yield* refused(s, FABLE_LIMIT);
        }
        yield* work(opts, s, call.agent);
      },
      calls,
      { claude: { leadModel: 'claude-opus-5-5', workerModel: 'claude-fable-5-1', effort: 'high', leadEffort: 'high' } },
    );
    const { fm } = hh;
    await fm.submitGoal('do it');
    await until(() => of(calls, 'kit').length === 1);
    await model(hh, 'kit', 'sonnet');
    release();
    await until(() => of(calls, 'kit').length === 2);
    expect(of(calls, 'kit')[1]).toMatchObject({ model: 'sonnet', effort: 'high' });
    expect(of(calls, 'kit')[1]!.prompt).toMatch(/^The Fable usage limit stopped your last turn; Alex has switched you to Sonnet, so you continue now\./);
    // the Fable limit holds Fable agents only, never everyone
    expect(state(hh).limit).toBeUndefined();
    expect(state(hh).modelLimits?.fable).toBeDefined();
    await until(() => fm.tasks.list()[0]!.status === 'review');
  });
});

describe('agent.model on a team with Codex agents', () => {
  const FAKE = path.join(path.dirname(new URL(import.meta.url).pathname.replace(/^\/(\w:)/, '$1')), 'fixtures', 'fake-codex.mjs');
  const S = '00000000-0000-4000-8000-000000000077';
  const m = (o: Record<string, unknown>) => ({ parent_tool_use_id: null, uuid: S, ...o }) as unknown as SDKMessage;

  afterEach(() => {
    delete process.env.FAKE_CODEX_SCENARIO;
    delete process.env.FAKE_CODEX_LOG;
  });

  it('a Codex agent takes Codex models only (saved under codex.agentModels, its next thread runs it); a group skips the agents on the other engine', async () => {
    const home = tempDir();
    cleanup.push(home);
    repoPath = await demoRepo();
    cleanup.push(path.dirname(repoPath));
    const scenario = path.join(home, 'scenario.json');
    fs.writeFileSync(scenario, JSON.stringify({ turns: [{ role: 'worker', match: 'Your task: t1', steps: [{ write: { file: 'src/cli.ts', content: '\n// codex\n' } }, { tool: 'update_task', args: { task_id: 't1', status: 'review', summary: 'Done.' } }] }] }));
    const log = path.join(home, 'codex.log');
    process.env.FAKE_CODEX_SCENARIO = scenario;
    process.env.FAKE_CODEX_LOG = log;
    fs.writeFileSync(path.join(home, 'config.json'), JSON.stringify({ codex: { effort: 'low' } }));
    // the Claude auth probe is the fake's accountInfo (claude login: no API key needed), Codex's the fake app-server's
    h = makeForeman(home, ['--backend', 'claude', '--use-claude-login', '--worker-engine', 'codex', '--workers', 'kit', '--repo', repoPath, '--no-lead-review']);
    const lead = ({ prompt, options }: { prompt: unknown; options?: Options }) => {
      async function* run(): AsyncGenerator<SDKMessage> {
        yield m({ type: 'system', subtype: 'init', session_id: S, model: options?.model ?? 'fake', cwd: '', tools: [] });
        if (String(prompt).startsWith('New goal')) await callTool(options!, 'create_task', { title: 'Codex work', assignee: 'kit' });
        yield m({ type: 'result', subtype: 'success', is_error: false, result: 'ok', num_turns: 1, total_cost_usd: 0, session_id: S, duration_ms: 1, duration_api_ms: 1, usage: {}, modelUsage: {}, permission_denials: [] });
      }
      return Object.assign(run(), { close() {}, accountInfo: async () => ({ email: 'x' }) });
    };
    const claude = new ClaudeEngine(h.fm, h.cfg.claude, lead as never);
    const codex = new CodexEngine(h.fm, h.cfg.codex, { bin: process.execPath, args: [FAKE] });
    await h.fm.start(new TeamBackend(h.fm, h.cfg.claude, { name: 'claude', engines: { lead: claude, worker: codex } }));
    const hh = h;
    const { fm } = hh;
    expect((await model(hh, 'kit')).result!.agents[0]).toEqual({ agentId: 'kit', name: 'Kit', engine: 'codex', model: 'gpt-fake', label: 'gpt-fake', effort: 'low', source: 'default' });

    // the other engine's models, and efforts it does not know, are refused
    expect(await model(hh, 'kit', 'sonnet')).toMatchObject({ ok: false, error: 'Kit runs on Codex: sonnet is a Claude model (use a Codex model such as gpt-5.1-codex, or default)' });
    expect(await model(hh, 'kit', 'claude-opus-5-5')).toMatchObject({ ok: false, error: expect.stringMatching(/^Kit runs on Codex: claude-opus-5-5 is a Claude model/) });
    expect(await model(hh, 'kit', 'gpt-5.1-codex', 'max')).toMatchObject({ ok: false, error: 'Kit runs on Codex: there is no "max" effort (use none, minimal, low, medium, high, xhigh)' });
    expect(await model(hh, 'marlow', 'gpt-5.1-codex')).toMatchObject({ ok: false, error: expect.stringMatching(/^Marlow runs on Claude: gpt-5\.1-codex is not a Claude model/) });

    const a = await model(hh, 'kit', 'gpt-5.1-codex', 'high');
    expect(a.result!.text).toBe('Kit now runs GPT-5.1 Codex (high) from the next turn; saved');
    expect(readConfig(hh)).toEqual({ codex: { effort: 'low', agentModels: { kit: { model: 'gpt-5.1-codex', effort: 'high' } } } });
    expect(fm.agent('kit')).toMatchObject({ engine: 'codex', model: 'GPT-5.1 Codex' });
    expect(fm.status.message).toBe('Claude lead opus · Codex workers GPT-5.1 Codex');

    // everyone: the Claude lead changes, the Codex workers are skipped
    const all = await model(hh, 'all', 'sonnet');
    expect(all.ok).toBe(true);
    expect(all.result!.text).toBe('Marlow now runs Sonnet (medium) from the next turn; Juniper, Kit, Wren, Rowan and Tove unchanged (on Codex); saved');
    expect(all.result!.skipped!.map((s) => s.agentId)).toEqual(WORKERS);
    expect(all.result!.skipped![1]!.reason).toBe('Kit runs on Codex: sonnet is a Claude model (use a Codex model such as gpt-5.1-codex, or default)');
    expect(readConfig(hh).claude).toEqual({ agentModels: { marlow: { model: 'sonnet' } } });

    // Kit's next thread runs its own model and effort
    await fm.submitGoal('codex work');
    await until(() => fm.tasks.list()[0]?.status === 'review', 60_000);
    const starts = fs
      .readFileSync(log, 'utf8')
      .split('\n')
      .filter(Boolean)
      .map((l) => JSON.parse(l) as { method?: string; params?: { model?: string; config?: { model_reasoning_effort?: string } } })
      .filter((l) => l.method === 'thread/start');
    expect(starts[0]!.params).toMatchObject({ model: 'gpt-5.1-codex', config: { model_reasoning_effort: 'high' } });
    // back to the workers' model (the Codex config's) and effort
    expect((await model(hh, 'kit', 'default')).result!.text).toBe('Kit is back on the configured model, gpt-fake (low), from the next turn; saved');
    expect(readConfig(hh).codex).toEqual({ effort: 'low' });
  }, 120_000);
});
