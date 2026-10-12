import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { sameProcess, recoverProcesses, groupSnapshot, readJson, saveJson } from '../lib/mp/processes.mjs';
import { makePlan, parseOptions } from '../lib/mp/config.mjs';
import { summarize } from '../lib/mp/harness.mjs';

test('ownership requires PID plus exact start time, and leaves reused/dead/missing records alone', () => {
  const stamp = pid => pid === 42 ? 'new-start' : null;
  assert.equal(sameProcess({ pid: 42, startTime: 'new-start' }, '', stamp), true);
  for (const record of [null, {}, { pid: 42 }, { startTime: 'new-start' },
    { pid: 42, startTime: 'old-start' }, { pid: 43, startTime: 'new-start' }]) {
    assert.equal(sameProcess(record, '', stamp), false);
  }
});

test('crash recovery finds only the durable unique marker and captures identity before stop', () => {
  const marker = 'ac-mp-12345678-1234-1234-1234-123456789abc';
  const entry = { marker, executable: 'java', kind: 'client', pid: 42, startTime: 'old-start', wrapper: { pid: 40, startTime: 'wrapper' } };
  const inventory = [{ pid: 40, command: `node bgrun.mjs ${marker}.json` },
    { pid: 42, command: 'unrelated Java' }, { pid: 44, command: `java -Dagentcraft.mp.run=${marker} @args` },
    { pid: 45, command: `grep -Dagentcraft.mp.run=${marker}` },
    { pid: 46, command: `${process.execPath} ${path.join('tools', 'lib', 'bgrun.mjs')} ${path.join('run', `${marker}.json`)}` }];
  const stamp = pid => ({ 40: 'wrapper', 42: 'new-start', 44: 'game-start', 46: 'wrapper-start' })[pid] ?? null;
  assert.deepEqual(recoverProcesses(entry, '', inventory, stamp), [entry.wrapper,
    { pid: 44, startTime: 'game-start', role: 'primary' }, { pid: 46, startTime: 'wrapper-start', role: 'wrapper' }]);
  assert.deepEqual(recoverProcesses({ marker: 'java' }, '', inventory, stamp), []);
});

test('atomic records round-trip; corrupt records fail closed instead of hiding owned processes', t => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'mp-record-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  const file = path.join(dir, 'run.json');
  assert.equal(readJson(file), null);
  saveJson(file, { pid: 42, startTime: 'stamp' });
  assert.deepEqual(readJson(file), { pid: 42, startTime: 'stamp' });
  assert.deepEqual(fs.readdirSync(dir), ['run.json']);
  fs.writeFileSync(file, 'bad json');
  assert.throws(() => readJson(file), /cannot read/);
});

test('a process-group snapshot retains child identities after leader exit and excludes other launchers', () => {
  const record = { pid: 42, groupPid: 42, startTime: 'leader' };
  const table = [{ pid: 42, groupPid: 42 }, { pid: 43, groupPid: 42 }, { pid: 44, groupPid: 44 }];
  const stamp = pid => ({ 42: 'leader', 43: 'tool', 44: 'other' })[pid];
  const snapshot = groupSnapshot(record, table, '', stamp);
  assert.deepEqual(snapshot, [{ pid: 42, startTime: 'leader' }, { pid: 43, startTime: 'tool', groupPid: 42 }]);
  assert.equal(sameProcess(snapshot[1], '', pid => pid === 43 ? 'tool' : null), true);
  assert.equal(sameProcess(snapshot[1], '', () => 'reused'), false);
  assert.deepEqual(groupSnapshot({ ...record, startTime: 'old leader' }, table, '', stamp), []);
  const crashedDown = { ...record, recoveredProcesses: [{ ...record, members: [{ pid: 43, startTime: 'tool' }] }] };
  assert.deepEqual(recoverProcesses(crashedDown, '', [], pid => pid === 43 ? 'tool' : null), [{ pid: 43, startTime: 'tool', groupPid: 42 }]);
});

test('JSON summary describes stopped, ready and partial runs using recorded identity', () => {
  const root = path.resolve('fixture');
  const plan = makePlan(root, parseOptions(['up', '--slot', '92'], root, {}));
  const recover = entry => entry.alive ? [{ pid: entry.pid, startTime: entry.startTime }] : [];
  const stopped = summarize(plan, null, [], recover);
  assert.equal(stopped.phase, 'stopped');
  assert.equal(stopped.clients.length, 2);
  assert.equal(stopped.server.running, false);
  const processes = [{ kind: 'server' }, ...['a', 'b'].flatMap(client => [{ kind: 'client', client }, { kind: 'foreman', client }])]
    .map((p, i) => ({ ...p, pid: i + 1, startTime: 'stamp', alive: true }));
  const state = { ...plan, phase: 'ready', processes, states: { a: { ready: true } }, statesCapturedAt: 'captured' };
  const ready = summarize(plan, state, [], recover);
  assert.equal(ready.phase, 'ready');
  assert.equal(ready.clients[0].state.ready, true);
  assert.equal(ready.statesCapturedAt, 'captured');
  assert.equal(JSON.parse(JSON.stringify(ready)).processes.length, 5);
  processes[1].alive = false;
  assert.equal(summarize(plan, state, [], recover).phase, 'partial');
  for (const entry of processes) entry.alive = false;
  assert.equal(summarize(plan, state, [], recover).phase, 'stopped');
});

test('a surviving wrapper or child tool after the game exits is partial, not a running game', () => {
  const root = path.resolve('fixture');
  const plan = makePlan(root, parseOptions(['up', '--slot', '92', '--clients', '1'], root, {}));
  const state = { ...plan, phase: 'ready', processes: [{ kind: 'client', client: 'a', pid: 42 }] };
  const summary = summarize(plan, state, [], () => [{ pid: 43, startTime: 'wrapper' }]);
  assert.equal(summary.phase, 'partial');
  assert.equal(summary.clients[0].running, false);
  assert.equal(summary.processes[0].primaryRunning, false);
});
