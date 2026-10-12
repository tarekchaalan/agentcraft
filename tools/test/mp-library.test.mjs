import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { clientCommand, isRemoteReady, readLogTail, readServerEvents, waitForClients } from '../lib/mp/index.mjs';
import { makePlan, parseOptions } from '../lib/mp/config.mjs';
import { seedGameDirs, offlineUuid } from '../lib/mp/launch.mjs';

const plan = makePlan(path.resolve('fixture'), parseOptions(['up', '--slot', '92'], path.resolve('fixture'), {}));
const ready = client => ({ ok: true, ready: true, world: { name: null, dimension: 'minecraft:overworld' },
  player: { name: client.username }, foreman: { connected: true, url: `ws://127.0.0.1:${client.foremanPort}` } });

test('remote readiness excludes title/loading screens, singleplayer and the wrong Foreman/player', () => {
  const client = plan.clients[0];
  assert.equal(isRemoteReady(ready(client), client), true);
  for (const patch of [{ ready: false }, { world: null }, { world: { name: 'AgentCraft HQ', dimension: 'x' } },
    { player: { name: 'wrong' } }, { foreman: { connected: false } },
    { foreman: { connected: true, url: 'ws://127.0.0.1:7878' } }]) {
    assert.equal(isRemoteReady({ ...ready(client), ...patch }, client), false);
  }
});

test('DevBridge adapter selects A/B ports, returns command response and always closes', async () => {
  let closed = 0;
  const connect = async options => {
    assert.equal(options.port, 8085);
    return { call: async (type, payload) => ({ type, payload }), close: () => closed++ };
  };
  assert.deepEqual(await clientCommand(plan, 'B', 'dev.camera', { anchor: 'cam_room' }, { connect }),
    { type: 'dev.camera', payload: { anchor: 'cam_room' } });
  await assert.rejects(clientCommand(plan, 'a', 'dev.state', {}, { connect: async () => ({
    call: async () => { throw new Error('bridge failed'); }, close: () => closed++ }) }), /bridge failed/);
  assert.equal(closed, 2);
  await assert.rejects(clientCommand(plan, 'c', 'dev.state', {}, { connect }), /not in this harness/);
});

test('wait readiness requires every configured client and supports a one-client plan', async () => {
  const calls = [];
  const command = async (_plan, id, type) => { calls.push([id, type]); return ready(plan.clients.find(c => c.id === id)); };
  const states = await waitForClients(plan, { command, timeoutMs: 1000 });
  assert.deepEqual(Object.keys(states).sort(), ['a', 'b']);
  assert.deepEqual(calls.sort(), [['a', 'dev.state'], ['b', 'dev.state']]);
  assert.deepEqual(Object.keys(await waitForClients({ ...plan, clients: plan.clients.slice(0, 1) }, { command, timeoutMs: 1000 })), ['a']);
  await assert.rejects(waitForClients(plan, { command, check: () => { throw new Error('client died'); } }), /client died/);
  await assert.rejects(waitForClients(plan, { timeoutMs: 1, command: async () => ({ ready: false }) }), /did not become ready/);
});

test('server telemetry reads complete lines with offsets and filters event names', t => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'mp-log-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  const local = { server: { debugLog: path.join(dir, 'debug.log') } };
  assert.deepEqual(readServerEvents(local), { events: [], offset: 0 });
  fs.writeFileSync(local.server.debugLog, '[INFO] normal\n[INFO] event=plot_allocated player=uuid index=1\n[INFO] event=hello_sent');
  const first = readServerEvents(local);
  assert.equal(first.events.length, 1);
  assert.deepEqual(first.events[0].fields, { event: 'plot_allocated', player: 'uuid', index: '1' });
  fs.appendFileSync(local.server.debugLog, ' protocol=1\n');
  const next = readServerEvents(local, { offset: first.offset, event: 'hello_sent' });
  assert.equal(next.events.length, 1);
  assert.equal(next.events[0].fields.protocol, '1');
  assert.equal(readServerEvents(local, { offset: next.offset }).events.length, 0);
  fs.writeFileSync(local.server.debugLog, 'event=presence online=false\n');
  assert.equal(readServerEvents(local, { offset: next.offset }).events[0].event, 'presence');
});

test('seed isolated dirs with loopback server, y64 flat world, enabled mode and both ops', t => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'mp-seed-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  fs.mkdirSync(path.join(root, 'mod', 'run-template'), { recursive: true });
  fs.writeFileSync(path.join(root, 'mod', 'run-template', 'options.txt'), fs.readFileSync(new URL('../../mod/run-template/options.txt', import.meta.url), 'utf8'));
  const local = makePlan(root, parseOptions(['up', '--slot', '92'], root, {}));
  seedGameDirs(local);
  const properties = fs.readFileSync(path.join(local.server.gameDir, 'server.properties'), 'utf8');
  assert.match(properties, /server-ip=127\.0\.0\.1\nserver-port=25692/);
  assert.match(properties, /online-mode=false/);
  assert.match(properties, /level-type=minecraft:flat/);
  const generator = JSON.parse(properties.match(/generator-settings=(.*)/)[1]);
  assert.equal(-64 + generator.layers.reduce((sum, layer) => sum + layer.height, 0) - 1, 64);
  assert.equal(JSON.parse(fs.readFileSync(path.join(local.server.gameDir, 'config', 'agentcraft-server.json'))).enabled, true);
  const ops = JSON.parse(fs.readFileSync(path.join(local.server.gameDir, 'ops.json')));
  assert.deepEqual(ops.map(op => op.uuid), local.clients.map(client => offlineUuid(client.username)));
  assert.deepEqual(ops.map(op => op.level), [4, 4]);
  for (const client of local.clients) {
    const options = path.join(client.gameDir, 'options.txt');
    assert.equal(fs.readFileSync(options, 'utf8'), fs.readFileSync(new URL('../../mod/run-template/options.txt', import.meta.url), 'utf8').replace('maxFps:120', 'maxFps:30'));
    fs.writeFileSync(options, 'custom');
    seedGameDirs(local);
    assert.equal(fs.readFileSync(options, 'utf8'), 'custom');
  }
});

test('long-running server logs are read in bounded chunks, preserving the cursor', t => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'mp-bounded-log-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  const local = { server: { debugLog: path.join(dir, 'debug.log') } };
  fs.writeFileSync(local.server.debugLog, 'event=presence online=true\n'.repeat(500));
  let offset = 0;
  let count = 0;
  do {
    const chunk = readServerEvents(local, { offset, maxBytes: 1024 });
    assert.ok(chunk.offset - offset <= 1024);
    offset = chunk.offset;
    count += chunk.events.length;
  } while (offset < fs.statSync(local.server.debugLog).size);
  assert.equal(count, 500);
  assert.equal(Buffer.byteLength(readLogTail(local.server.debugLog, 64)), 64);
  fs.writeFileSync(local.server.debugLog, 'x'.repeat(2048));
  assert.throws(() => readServerEvents(local, { maxBytes: 1024 }), /exceeds/);
});
