import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { shutdown, summarize } from '../lib/mp/harness.mjs';
import { makePlan, parseOptions } from '../lib/mp/config.mjs';
import { readJson } from '../lib/mp/processes.mjs';

function fixture(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'mp-down-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const plan = makePlan(root, parseOptions(['up', '--slot', '92', '--clients', '1'], root, {}));
  // A crash left a durable intent without a child PID; the recovery dependency finds it.
  const processes = [{ kind: 'server', alive: true, pid: 100, startTime: 'server' },
    { kind: 'foreman', client: 'a', alive: true, pid: 101, startTime: 'fm' },
    { kind: 'client', client: 'a', alive: true, recoveredPid: 102, startTime: 'game' }];
  const state = { ...plan, phase: 'starting', processes, states: {} };
  const recover = entry => entry.alive ? [{ pid: entry.pid ?? entry.recoveredPid, startTime: entry.startTime, role: 'primary' }] : [];
  return { plan, state, recover };
}

test('down after a startup crash quits owned clients, stops recovered PIDs and server, and is idempotent', async t => {
  const { plan, state, recover } = fixture(t);
  const calls = [];
  const deps = { inventory: () => [], recover, portOwned: record => record.pid === 102,
    command: async (_plan, id, type) => { calls.push(`${id}:${type}`); throw new Error('stalled'); },
    stop: async record => {
      calls.push(record.pid);
      state.processes.find(entry => (entry.pid ?? entry.recoveredPid) === record.pid).alive = false;
    } };
  await shutdown(plan, state, undefined, deps);
  assert.deepEqual(calls, ['a:dev.quit', 102, 101, 100]);
  assert.equal(readJson(plan.file).phase, 'stopped');
  await shutdown(plan, state, undefined, deps);
  assert.deepEqual(calls, ['a:dev.quit', 102, 101, 100]);
});

test('a foreign process on the DevBridge port is never sent dev.quit', async t => {
  const { plan, state, recover } = fixture(t);
  let quits = 0;
  await shutdown(plan, state, undefined, { inventory: () => [], recover, portOwned: () => false,
    command: async () => { quits++; }, stop: async record => {
      state.processes.find(entry => (entry.pid ?? entry.recoveredPid) === record.pid).alive = false;
    } });
  assert.equal(quits, 0);
  assert.equal(state.phase, 'stopped');
});

test('cleanup continues after a stop error, retains survivors and reports partial state', async t => {
  const { plan, state, recover } = fixture(t);
  const stopped = [];
  await assert.rejects(shutdown(plan, state, undefined, { inventory: () => [], recover, portOwned: () => false,
    stop: async record => {
      if (record.pid === 102) throw new Error('cannot stop game');
      stopped.push(record.pid);
      state.processes.find(entry => entry.pid === record.pid).alive = false;
    } }), /cleanup incomplete: cannot stop game; PID 102/);
  assert.deepEqual(stopped, [101, 100]);
  assert.equal(readJson(plan.file).phase, 'partial');
  const persisted = readJson(plan.file);
  const summary = summarize(plan, persisted, [], entry => (entry.recoveredProcesses ?? []).filter(record => record.pid === 102));
  assert.equal(summary.clients[0].running, true); // retain the crash-recovered primary identity for retry/status
});
