import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import * as library from '../lib/mp/index.mjs';
import { makePlan, parseOptions } from '../lib/mp/config.mjs';
import { seedGameDirs } from '../lib/mp/launch.mjs';

const root = path.resolve('fixture');
const plan = makePlan(root, parseOptions(['up', '--slot', '92'], root, {}));
const ready = client => ({ ok: true, ready: true, world: { name: null, dimension: 'minecraft:overworld' },
  player: { name: client.username }, foreman: { connected: true, url: `ws://127.0.0.1:${client.foremanPort}` } });
const bindLine = 'DevBridge server error (port 8084): java.net.BindException: Address already in use';
function temporary(t) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'mp-review-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  return dir;
}

test('F1: library fails immediately on terminal bridge log, before querying even a ready bridge', async () => {
  let calls = 0;
  await assert.rejects(library.waitForClients(plan, {
    read: file => ({ offset: 100, lines: file === plan.clients[0].log ? [bindLine] : [] }),
    command: async (_plan, id) => { calls++; return ready(plan.clients.find(c => c.id === id)); },
  }), error => /client A/.test(error.message) && /8084/.test(error.message) && /BindException/.test(error.message) && error.message.includes(plan.clients[0].log));
  assert.equal(calls, 0);
});

test('F2: telemetry reads DEBUG file instead of INFO-only stdout', t => {
  const dir = temporary(t);
  const local = makePlan(dir, parseOptions(['up', '--slot', '92'], dir, {}));
  fs.mkdirSync(path.join(local.server.gameDir, 'logs'), { recursive: true });
  fs.writeFileSync(local.server.log, '[INFO] Done (1s)!\n');
  fs.writeFileSync(path.join(local.server.gameDir, 'logs', 'debug.log'),
    '[19:24:17] [Server thread/DEBUG] (agentcraft.mp) event=relay_sent studio=u recipients=1 bytes=412\n');
  assert.equal(library.readServerEvents(local).events[0]?.event, 'relay_sent');
  assert.equal(local.server.debugLog, path.join(local.server.gameDir, 'logs', 'debug.log'));
});

test('F14/F17: seed uses the actual template and leaves valid client distances to the graphics preset', t => {
  const dir = temporary(t);
  fs.mkdirSync(path.join(dir, 'mod', 'run-template'), { recursive: true });
  const template = fs.readFileSync(new URL('../../mod/run-template/options.txt', import.meta.url), 'utf8');
  fs.writeFileSync(path.join(dir, 'mod', 'run-template', 'options.txt'), template);
  const local = makePlan(dir, parseOptions(['up', '--slot', '92'], dir, {}));
  seedGameDirs(local);
  const options = fs.readFileSync(path.join(local.clients[0].gameDir, 'options.txt'), 'utf8');
  assert.equal(options, template.replace('maxFps:120', 'maxFps:30'));
  assert.match(options, /simulationDistance:10/);
  const properties = fs.readFileSync(path.join(local.server.gameDir, 'server.properties'), 'utf8');
  assert.match(properties, /view-distance=6\nsimulation-distance=4/);
});

test('F1: classifier matches only this port and treats non-bind bridge errors as terminal', () => {
  assert.equal(library.classifyBridgeError(plan.clients[0], 'normal log'), null);
  assert.equal(library.classifyBridgeError(plan.clients[1], bindLine), null);
  assert.equal(library.classifyBridgeError(plan.clients[0], bindLine).bindFailure, true);
  assert.equal(library.classifyBridgeError(plan.clients[0], 'DevBridge server error (port 8084): other cause').bindFailure, false);
});

for (const [platform, cooldown] of [['darwin', 35000], ['linux', 65000], ['win32', 0]]) {
  test(`F1: ${platform} bind recovery stops/relaunches only the failed client once within the budget`, async () => {
    let time = 0, attempts = 0;
    const calls = [];
    const states = { a: ready(plan.clients[0]), b: ready(plan.clients[1]) };
    const result = await library.waitWithBindRecovery(plan, { platform, timeoutMs: 90000,
      now: () => time, delay: async ms => { time += ms; },
      wait: async (_plan, options) => {
        assert.equal(options.timeoutMs, 90000 - time);
        if (attempts++ === 0) throw library.classifyBridgeError(plan.clients[0], bindLine);
        return states;
      },
      stopClient: async (id, remaining) => { calls.push(['stop', id, remaining]); time += 1000; },
      relaunchClient: async (id, remaining) => { calls.push(['launch', id, remaining]); },
      progress: message => calls.push(['notice', message]),
    });
    assert.equal(result, states);
    assert.equal(time, cooldown + 1000);
    assert.deepEqual(calls.filter(c => c[0] !== 'notice'), [['stop', 'a', 90000], ['launch', 'a', 89000 - cooldown]]);
    assert.equal(calls.filter(c => c[0] === 'notice').length, 1);
  });
}

test('F1: second bind failure, unrelated errors and insufficient budget never loop', async () => {
  for (const [timeoutMs, bind, expectedStops] of [[90000, true, 1], [100, true, 0], [90000, false, 0]]) {
    let time = 0, stopped = 0, launched = 0;
    const error = bind ? library.classifyBridgeError(plan.clients[0], bindLine) : new Error('other failure');
    await assert.rejects(library.waitWithBindRecovery(plan, { timeoutMs, platform: 'darwin',
      now: () => time, delay: async ms => { time += ms; }, wait: async () => { throw error; },
      stopClient: async () => { stopped++; }, relaunchClient: async () => { launched++; },
    }), bind ? /client A.*8084/ : /other failure/);
    assert.equal(stopped, expectedStops);
    assert.equal(launched, expectedStops);
  }
});

test('F1: incremental scanner starts at byte zero and finds a failure beyond the first chunk', async () => {
  const offsets = [];
  await assert.rejects(library.waitForClients(plan, { read: (_file, { offset }) => {
    offsets.push(offset);
    return offset === 0 ? { offset: 1024, lines: ['normal'] } : { offset: 2048, lines: [bindLine] };
  }, command: async () => assert.fail('must fail before command') }), /BindException/);
  assert.deepEqual(offsets, [0, 1024]);
});

test('F2: client DEBUG events have independent cursors and old plans derive the debug path', t => {
  const dir = temporary(t);
  const local = makePlan(dir, parseOptions(['up', '--slot', '92'], dir, {}));
  for (const client of local.clients) {
    fs.mkdirSync(path.dirname(client.debugLog), { recursive: true });
    fs.writeFileSync(client.debugLog, `[Render thread/DEBUG] event=received client=${client.id}\n`);
  }
  const first = library.readClientEvents(local, 'A');
  assert.equal(first.events[0].fields.client, 'a');
  assert.equal(library.readClientEvents(local, 'a', { offset: first.offset }).events.length, 0);
  delete local.clients[1].debugLog;
  assert.equal(library.readClientEvents(local, 'b').events[0].fields.client, 'b');
  assert.throws(() => library.readClientEvents(local, 'c'), /not in this harness/);
});

import { EventEmitter } from 'node:events';
import { waitForBuild, summarize, loadState } from '../lib/mp/harness.mjs';
import { gradleEnvironment, requireJava25 } from '../lib/mp/launch.mjs';
import { powershell, processStamp, startProcess, stopProcess, saveJson } from '../lib/mp/processes.mjs';
import { portsForSlot } from '../lib/mp/config.mjs';

test('F3: hung Gradle preparation times out; cancellation rejects without signalling an unverified PID', async () => {
  for (const cancel of [false, true]) {
    const child = new EventEmitter();
    child.kill = () => assert.fail('rollback must use recorded identity');
    let tick, time = 0, cleared = 0;
    const completion = waitForBuild(child, { timeoutMs: 1000, log: 'build.log', now: () => time,
      interval: fn => { tick = fn; return 1; }, clear: id => { assert.equal(id, 1); cleared++; },
      check: () => { if (cancel) throw new Error('interrupted'); },
    });
    time = 1000;
    tick();
    await assert.rejects(completion, cancel ? /interrupted/ : /Gradle preparation timed out.*build.log/);
    assert.equal(cleared, 1);
    assert.equal(child.listenerCount('exit'), 0);
  }
});

test('F4: launch export depends on Loom client setup, which prepares assets and natives', () => {
  const source = fs.readFileSync(new URL('../lib/mp/export-launch.gradle', import.meta.url), 'utf8');
  assert.match(source, /dependsOn 'classes', 'clientClasses', 'configureClientLaunch'/);
});

test('F5: POSIX start time pins locale and time zone, and rejects zombies/missing PIDs', () => {
  for (const [status, stdout, expected] of [[0, 'Sun Oct  4 12:00:00 2026 S\n', 'Sun Oct  4 12:00:00 2026'],
    [0, 'Sun Oct  4 12:00:00 2026 Z\n', null], [1, '', null]]) {
    assert.equal(processStamp(42, root, { platform: 'darwin', run: (command, args, options) => {
      assert.equal(command, 'ps');
      assert.deepEqual(args, ['-p', '42', '-o', 'lstart=', '-o', 'stat=']);
      assert.equal(options.env.LC_ALL, 'C');
      assert.equal(options.env.TZ, 'UTC');
      return { status, stdout };
    } }), expected);
  }
});

test('F8/F15: Gradle defaults to checkout-local home and respects both explicit environment variables', () => {
  assert.deepEqual(gradleEnvironment(root, { JAVA_HOME: 'jdk' }), { JAVA_HOME: 'jdk', GRADLE_USER_HOME: path.join(root, '.gradle-home') });
  const env = { JAVA_HOME: 'jdk', GRADLE_USER_HOME: 'swarm-cache' };
  assert.deepEqual(gradleEnvironment(root, env), env);
});

test('F15: Java 25 preflight uses JAVA_HOME and rejects older, missing and failed Java', () => {
  const env = { JAVA_HOME: 'jdk' };
  assert.equal(requireJava25(env, 'win32', (command, args, options) => {
    assert.equal(command, path.join('jdk', 'bin', 'java.exe'));
    assert.deepEqual(args, ['-version']);
    assert.equal(options.env, env);
    return { status: 0, stderr: 'openjdk version "25.0.1"' };
  }), path.join('jdk', 'bin', 'java.exe'));
  for (const result of [{ status: 0, stderr: 'openjdk version "24.0.1"' }, { error: new Error('ENOENT') }, { status: 1 }]) {
    assert.throws(() => requireJava25({}, 'linux', () => result), /Java 25 is required/);
  }
});

test('F10/F11: Windows calls existing hidden-console Start-Bg through bypassed PowerShell', async t => {
  const dir = temporary(t);
  const entry = { kind: 'client', marker: 'ac-mp-test' };
  let persisted = 0;
  await startProcess({ command: 'java', args: ['arg with space', "quote'"], cwd: dir, log: path.join(dir, 'client.log'), env: {} },
    entry, { dir, root }, () => persisted++, { platform: 'win32', stamp: () => 'primary', shell: (_root, script) => {
      assert.match(script, /Start-Bg -L \$L/);
      assert.match(script, /-Arguments @\(\$s.args\)/);
      const spec = JSON.parse(fs.readFileSync(path.join(dir, 'ac-mp-test.json')));
      assert.deepEqual(spec.args, ['arg with space', "quote'"]);
      assert.deepEqual(spec.env, {});
      return JSON.stringify({ pid: 42, start: 'primary', wrapperPid: 41, wrapperStart: 'wrapper' });
    } });
  assert.equal(entry.pid, 42);
  assert.deepEqual(entry.wrapper, { pid: 41, startTime: 'wrapper' });
  assert.equal(persisted, 2);
  assert.equal(powershell(root, 'Get-ProcStart 42', (command, args) => {
    assert.equal(command, 'powershell.exe');
    assert.deepEqual(args.slice(0, 5), ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-Command']);
    assert.match(args[5], /procs.ps1/);
    return { status: 0, stdout: 'stamp\n' };
  }), 'stamp');
});

for (const kind of ['server', 'foreman', 'client']) {
  test(`F10: Windows ${kind} uses the appropriate bounded stop path`, async () => {
    let live = true, time = 0;
    const calls = [];
    await stopProcess({ pid: 42, startTime: 'stamp' }, root, { kind, platform: 'win32', timeoutMs: 300,
      now: () => time, delay: async ms => { time += ms; }, stamp: () => live ? 'stamp' : null,
      shell: (_root, script) => {
        calls.push(script);
        if (script.startsWith('Stop-OwnTree')) live = false;
        return 'False';
      },
    });
    assert.equal(calls.some(c => c.startsWith('Send-CtrlBreak')), kind === 'foreman');
    assert.equal(calls.filter(c => c.startsWith('Stop-OwnTree')).length, 1);
    assert.equal(time, kind === 'client' ? 300 : 0);
  });
}

test('F12: stop refuses to signal a reused PID in either platform branch', async () => {
  for (const platform of ['linux', 'win32']) {
    await stopProcess({ pid: 42, startTime: 'old' }, root, { platform, stamp: () => 'new',
      shell: () => assert.fail('must not signal'), kill: () => assert.fail('must not signal'),
      inventory: () => assert.fail('must not inspect unrelated group') });
  }
});

test('F12: loadState rejects mismatched identity/version and malformed process/client lists', t => {
  const dir = temporary(t);
  const local = makePlan(dir, parseOptions(['up', '--slot', '92'], dir, {}));
  assert.equal(loadState(local), null);
  for (const patch of [{ slot: 91 }, { root: 'different' }, { version: 2 }, { processes: {} }, { clients: {} }]) {
    saveJson(local.file, { ...local, processes: [], ...patch });
    assert.throws(() => loadState(local), /invalid harness record/);
  }
});

test('F13: slot ports match literal campaign allocations including boundaries', () => {
  for (const [slot, server, fmA, devA, fmB, devB] of [[0, 25600, 27800, 7900, 27801, 7901],
    [90, 25690, 27980, 8080, 27981, 8081], [91, 25691, 27982, 8082, 27983, 8083],
    [92, 25692, 27984, 8084, 27985, 8085], [98, 25698, 27996, 8096, 27997, 8097], [99, 25699, 27998, 8098, 27999, 8099]]) {
    assert.deepEqual(portsForSlot(slot), { server, a: { foreman: fmA, dev: devA }, b: { foreman: fmB, dev: devB } });
  }
});

test('F13: readiness waits for the slower client and times out if only one ever becomes ready', async () => {
  for (const never of [false, true]) {
    let time = 0;
    const waiting = library.waitForClients(plan, { timeoutMs: 1200, now: () => time, delay: async ms => { time += ms; },
      command: async (_plan, id) => {
        if (id === 'a' && (never || time === 0)) throw new Error('not connected');
        return ready(plan.clients.find(c => c.id === id));
      },
    });
    if (never) { await assert.rejects(waiting, /did not become ready/); assert.equal(time, 1200); }
    else { assert.deepEqual(Object.keys(await waiting).sort(), ['a', 'b']); assert.equal(time, 500); }
  }
});

test('F16: JSON summary pins public keys and normalizes primary/wrapper/member records', () => {
  const entry = { kind: 'client', client: 'a', pid: 42, wrapper: { pid: 41 }, log: 'client.log' };
  const state = { ...plan, phase: 'ready', processes: [entry] };
  const summary = summarize(plan, state, [], () => [
    { pid: 42, startTime: 'a', secret: 'must not leak', members: [] },
    { pid: 41, startTime: 'b' }, { pid: 43, startTime: 'c' },
  ]);
  assert.deepEqual(Object.keys(summary).sort(), ['clients', 'error', 'home', 'phase', 'processes', 'profile', 'server', 'slot', 'stateFile', 'statesCapturedAt', 'version']);
  assert.deepEqual(Object.keys(summary.processes[0]).sort(), ['client', 'kind', 'log', 'pid', 'primaryRunning', 'processes']);
  assert.deepEqual(summary.processes[0].processes, [
    { pid: 42, startTime: 'a', role: 'primary' }, { pid: 41, startTime: 'b', role: 'wrapper' }, { pid: 43, startTime: 'c', role: 'member' },
  ]);
  assert.deepEqual(Object.keys(summary.server).sort(), ['debugLog', 'gameDir', 'heap', 'log', 'port', 'running']);
  assert.deepEqual(Object.keys(summary.clients[0]).sort(), ['debugLog', 'devPort', 'foremanLog', 'foremanPort', 'foremanRunning', 'gameDir', 'heap', 'id', 'log', 'profile', 'running', 'state', 'username']);
});

test('F1: stop and relaunch consume the same recovery deadline', async () => {
  for (const slow of ['stop', 'launch']) {
    let time = 0, launches = 0;
    await assert.rejects(library.waitWithBindRecovery(plan, { timeoutMs: 40000, platform: 'darwin',
      now: () => time, delay: async ms => { time += ms; },
      wait: async () => { throw library.classifyBridgeError(plan.clients[0], bindLine); },
      stopClient: async () => { if (slow === 'stop') time += 6000; },
      relaunchClient: async () => { launches++; time += 6000; },
    }), /bind recovery timed out/);
    assert.equal(launches, slow === 'stop' ? 0 : 1);
    assert.ok(time <= 41000);
  }
});

import { prepare } from '../lib/mp/harness.mjs';
import { processInventory, sameProcess } from '../lib/mp/processes.mjs';
import { fileURLToPath } from 'node:url';

test('F3/F8: timed-out preparation retains a stamped build record and inherits isolated Gradle environment', async t => {
  const dir = temporary(t);
  const opt = parseOptions(['up', '--slot', '92', '--timeout', '1'], dir, {});
  const local = makePlan(dir, opt);
  const state = { ...local, processes: [] };
  fs.mkdirSync(local.dir, { recursive: true });
  await assert.rejects(prepare(local, state, opt, () => {}, () => {}, {
    spawnBuild: (command, args, options) => {
      assert.equal(options.env.JAVA_HOME, process.env.JAVA_HOME);
      assert.equal(options.env.GRADLE_USER_HOME, process.env.GRADLE_USER_HOME || path.join(dir, '.gradle-home'));
      assert.equal(options.detached, process.platform !== 'win32');
      return { pid: 42 };
    }, stamp: () => 'build-start', wait: async (_child, options) => {
      assert.equal(options.timeoutMs, 1000);
      throw new Error('Gradle preparation timed out');
    },
  }), /timed out/);
  const recorded = JSON.parse(fs.readFileSync(local.file)).processes[0];
  assert.equal(recorded.pid, 42);
  assert.equal(recorded.startTime, 'build-start');
  if (process.platform !== 'win32') assert.equal(recorded.groupPid, 42);
});

test('F12: real harmless child start/stamp/inventory/stop, when process inspection is available', async t => {
  const repo = fileURLToPath(new URL('../../', import.meta.url));
  try { processStamp(process.pid, repo); processInventory(repo); }
  catch (error) {
    if (/EPERM|Operation not permitted|operation not permitted/.test(error.message)) {
      t.skip(`process inspection denied: ${error.message}`);
      return;
    }
    throw error;
  }
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'mp-child-'));
  const entry = { kind: 'foreman', marker: 'ac-mp-12345678-1234-1234-1234-123456789abc' };
  t.after(async () => {
    await stopProcess(entry, repo, { kind: 'foreman', timeoutMs: 500, consoleRecord: entry.wrapper ?? entry });
    if (entry.wrapper) await stopProcess(entry.wrapper, repo, { timeoutMs: 0 });
    fs.rmSync(dir, { recursive: true, force: true });
  });
  await startProcess({ command: process.execPath, args: ['-e', 'setInterval(() => {}, 1000)'],
    cwd: dir, log: path.join(dir, 'child.log') }, entry, { root: repo, dir }, () => {});
  assert.equal(sameProcess(entry, repo), true);
  assert.ok(processInventory(repo).some(p => p.pid === entry.pid));
  await stopProcess({ ...entry, startTime: 'forged-start' }, repo, { timeoutMs: 0 });
  assert.equal(sameProcess(entry, repo), true);
  await stopProcess(entry, repo, { kind: 'foreman', timeoutMs: 500, consoleRecord: entry.wrapper ?? entry });
  assert.equal(sameProcess(entry, repo), false);
});

test('F10: successful Windows Foreman Ctrl+Break allows a graceful exit without force-stop', async () => {
  let live = true, time = 0;
  const calls = [];
  await stopProcess({ pid: 42, startTime: 'stamp' }, root, { kind: 'foreman', platform: 'win32', timeoutMs: 300,
    consoleRecord: { pid: 41, startTime: 'wrapper' }, now: () => time,
    stamp: pid => pid === 41 ? 'wrapper' : live ? 'stamp' : null,
    delay: async ms => { time += ms; live = false; },
    shell: (_root, script) => { calls.push(script); return 'True'; },
  });
  assert.deepEqual(calls, ['Send-CtrlBreak 41']);
  assert.equal(time, 100);
});
