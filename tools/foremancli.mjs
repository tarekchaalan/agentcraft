#!/usr/bin/env node
// Foreman CLI for scripts and QA: connects like the mod does (hello -> snapshot), prints JSON.
//
//   node tools/foremancli.mjs status [--wait-showcase SECONDS]   backend/auth + agents/tasks/decisions summary
//   node tools/foremancli.mjs wait [--timeout SECONDS]           wait until the Foreman answers
//   node tools/foremancli.mjs diff <repoId> <worktree>           structured diff stats (diff.request)
//   node tools/foremancli.mjs diff --decision d3                 the diff of a merge decision
//   node tools/foremancli.mjs repo-add <path>                    register a project folder (repo.add)
//   node tools/foremancli.mjs send <type> '<json payload>'       any client message; prints the ack
//   node tools/foremancli.mjs send user.message to=kit "text=hello there"   (same, key=value form)
//
// Options: --port N (default AGENTCRAFT_PORT or 7878), --timeout SECONDS (connect, default 15),
// --full (diff: print every file/hunk instead of a summary). Exit 0 when ok, 1 on error.

import { ForemanClient, DEFAULT_FOREMAN_PORT } from './lib/foremanclient.mjs';

const argv = process.argv.slice(2);
const flags = {};
const pos = [];
for (let i = 0; i < argv.length; i++) {
  const a = argv[i];
  if (a.startsWith('--')) {
    const key = a.slice(2);
    if (['port', 'timeout', 'wait-showcase', 'decision'].includes(key)) flags[key] = argv[++i];
    else flags[key] = true;
  } else pos.push(a);
}
const cmd = pos.shift();
if (!cmd || flags.help) {
  console.error('usage: node tools/foremancli.mjs status|wait|diff|send [args] [--port N] [--timeout S] [--wait-showcase S] [--json]');
  process.exit(cmd ? 0 : 2);
}
const port = flags.port ? Number(flags.port) : DEFAULT_FOREMAN_PORT;
const connectMs = (flags.timeout ? Number(flags.timeout) : cmd === 'wait' ? 120 : 15) * 1000;
const out = (o) => console.log(JSON.stringify(o, null, 2));

let fm;
try {
  fm = await ForemanClient.connect({
    port,
    timeoutMs: connectMs,
    client: 'cli',
    onWait: (ms) => process.stderr.write(`[foremancli] waiting for the Foreman on :${port} (${Math.round(ms / 1000)}s)...\n`),
  });
} catch (e) {
  out({ ok: false, error: e.message });
  process.exit(1);
}

let res;
try {
  switch (cmd) {
    case 'wait':
    case 'status': {
      if (flags['wait-showcase']) {
        await fm.waitForState((s) => s.foreman?.showcase === true, {
          timeoutMs: Number(flags['wait-showcase']) * 1000,
          what: 'the showcase hold (foreman.status.showcase)',
        });
      }
      if (cmd === 'status') {
        // a Foreman that just started is still checking Claude access ("auth unknown/checking"):
        // give that a few seconds so launch.ps1's banner shows the real result
        await fm
          .waitForState((s) => !['unknown', 'checking'].includes(s.foreman?.auth), { timeoutMs: 15_000, what: 'the auth check' })
          .catch(() => undefined);
      }
      res = { ok: true, ...fm.summary() };
      break;
    }
    case 'diff': {
      let repoId = pos[0];
      let worktree = pos[1];
      if (flags.decision) {
        const d = fm.state.decisions.get(flags.decision);
        if (!d) throw new Error(`no decision ${flags.decision}`);
        if (!d.repoId || !d.worktree) throw new Error(`decision ${d.id} (${d.kind}) has no repoId/worktree`);
        repoId = d.repoId;
        worktree = d.worktree;
      }
      if (!repoId || !worktree) throw new Error('usage: diff <repoId> <worktree> | diff --decision <id>');
      const d = await fm.diff(repoId, worktree);
      res = flags.full
        ? { ok: true, ...d }
        : {
            ok: true,
            repoId: d.repoId,
            worktree: d.worktree,
            base: d.base,
            branch: d.branch,
            stats: d.stats,
            truncated: d.truncated,
            files: d.files.map((f) => ({ path: f.path, status: f.status, additions: f.additions, deletions: f.deletions, hunks: f.hunks.length })),
          };
      break;
    }
    case 'repo-add': {
      if (!pos[0]) throw new Error('usage: repo-add <path>');
      const ack = await fm.send('repo.add', { path: pos.join(' ') });
      res = { ok: true, ack };
      break;
    }
    case 'send': {
      const type = pos[0];
      if (!type) throw new Error("usage: send <type> '<json payload>'");
      // payload: one JSON object, or key=value pairs (values parsed as JSON when they can be:
      // numbers, true/false; otherwise strings). key=value survives PowerShell 5.1's argument
      // quoting, which strips the double quotes inside a JSON argument.
      let payload = {};
      const rest = pos.slice(1);
      if (rest.length && rest[0].trim().startsWith('{')) payload = JSON.parse(rest.join(' '));
      else {
        for (const kv of rest) {
          const i = kv.indexOf('=');
          if (i <= 0) throw new Error(`expected key=value, got "${kv}"`);
          const k = kv.slice(0, i);
          const v = kv.slice(i + 1);
          try { payload[k] = /^(-?\d+(\.\d+)?|true|false|null)$/.test(v) ? JSON.parse(v) : v; } catch { payload[k] = v; }
        }
      }
      const ack = await fm.send(type, payload);
      res = { ok: true, ack };
      break;
    }
    default:
      throw new Error(`unknown command ${cmd}`);
  }
} catch (e) {
  res = { ok: false, error: e.message };
}
fm.close();
out(res);
process.exit(res.ok ? 0 : 1);
