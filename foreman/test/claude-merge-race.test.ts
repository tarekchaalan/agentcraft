// The lead's review turn asks for a merge, the user approves it at once, and the turn ends while
// the merge is still running (the task leaves "review" only when the merge is done). The Foreman's
// "lead gave no verdict" fallback must not open a second merge decision for the same task: that
// duplicate pointed at a branch that was already merged, and "Request changes" on it sent the
// worker back into its removed worktree.
import fs from 'node:fs';
import path from 'node:path';
import type { Options, SDKMessage } from '@anthropic-ai/claude-agent-sdk';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { ClaudeBackend } from '../src/agents/claude/index.js';
import { demoRepo, makeForeman, rmrf, tempDir, until, type Harness } from './helpers.js';

type ToolServer = { instance: { _registeredTools: Record<string, { handler: (a: unknown, e: unknown) => Promise<{ content: Array<{ text: string }> }> }> } };

async function callTool(options: Options, name: string, args: Record<string, unknown>): Promise<string> {
  const server = options.mcpServers!.agentcraft as unknown as ToolServer;
  const res = await server.instance._registeredTools[name]!.handler(args, {});
  return res.content.map((c) => c.text).join('\n');
}

const sid = (k: string) => `00000000-0000-4000-8000-${k.padStart(12, '0')}`;
let n = 0;
const msg = (o: Record<string, unknown>) => ({ parent_tool_use_id: null, uuid: sid(String(++n)), ...o }) as unknown as SDKMessage;
const init = (session: string) => msg({ type: 'system', subtype: 'init', session_id: session, model: 'fake-model', cwd: '', tools: [] });
const result = (session: string, text = 'done') => msg({ type: 'result', subtype: 'success', is_error: false, result: text, num_turns: 1, total_cost_usd: 0.01, session_id: session, duration_ms: 1, duration_api_ms: 1, usage: {}, modelUsage: {}, permission_denials: [] });

let h: Harness;
let home: string;
let repoPath: string;
let merging: Promise<unknown> | undefined;

function fakeQuery() {
  return ({ prompt, options }: { prompt: string | AsyncIterable<unknown>; options?: Options }) => {
    const opts = options!;
    const p = String(prompt);
    async function* run(): AsyncGenerator<SDKMessage> {
      if (p.startsWith('New goal')) {
        const s = sid('1001');
        yield init(s);
        await callTool(opts, 'create_task', { title: 'Add a --version flag', description: 'print the version', assignee: 'kit' });
        yield result(s, 'planned');
        return;
      }
      const task = /Your task: (t\d+)/.exec(p)?.[1];
      if (task) {
        const s = sid('2001');
        yield init(s);
        fs.appendFileSync(path.join(opts.cwd!, 'README.md'), '\n`notes --version` prints the version.\n');
        await callTool(opts, 'update_task', { task_id: task, status: 'review', summary: 'Documented --version.' });
        yield result(s, 'implemented');
        return;
      }
      const review = /Review request: (t\d+)/.exec(p)?.[1];
      if (review) {
        const s = sid('1001');
        yield init(s);
        await callTool(opts, 'request_merge', { task_id: review, summary: 'Looks good.' });
        // the user approves while the lead is still finishing its turn
        const d = h.fm.decisions.open().find((x) => x.kind === 'merge' && x.taskId === review)!;
        merging = h.fm.answerDecision(d.id, 'Merge');
        yield result(s, 'Sent it to you for merging.');
        return;
      }
      const s = sid('9999');
      yield init(s);
      yield result(s, 'ok');
    }
    return Object.assign(run(), { close() {}, accountInfo: async () => ({ email: 'x' }) });
  };
}

beforeAll(async () => {
  home = tempDir();
  repoPath = await demoRepo();
  h = makeForeman(home, ['--backend', 'claude', '--workers', 'kit', '--repo', repoPath]);
  await h.fm.start(new ClaudeBackend(h.fm, h.cfg.claude, { queryFn: fakeQuery() as never, skipAuthCheck: true }));
});

afterAll(async () => {
  await h.fm.close();
  rmrf(home);
  rmrf(path.dirname(repoPath));
});

describe('claude backend: approving a merge while the review turn ends', () => {
  it('opens exactly one merge decision', async () => {
    const goal = await h.fm.submitGoal('Add a --version flag');
    await until(() => merging !== undefined, 60_000);
    await merging;
    const t = h.fm.tasks.forGoal(goal.id)[0]!;
    await until(() => h.fm.tasks.get(t.id)!.status === 'done', 30_000);
    // give a late fallback (lead turn end) every chance to run
    await new Promise((r) => setTimeout(r, 500));
    expect(h.fm.decisions.list().filter((d) => d.kind === 'merge' && d.taskId === t.id)).toHaveLength(1);
    expect(h.fm.decisions.open()).toHaveLength(0);
  });
});
