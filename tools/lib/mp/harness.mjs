import { spawn } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import fs from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import { makePlan } from './config.mjs';
import { gameSpec, gradleInvocation, javaArgfile, javaEnvironment, seedGameDirs, gradleEnvironment, requireJava25 } from './launch.mjs';
import { clientCommand, readLogTail, waitWithBindRecovery } from './index.mjs';
import { ownsPort, processInventory, processStamp, rconStop, readJson, recoverProcesses, sameProcess, saveJson, sleep,
  startProcess, stopProcess } from './processes.mjs';

export function portOpen(port) {
  return new Promise(resolve => {
    const socket = net.connect({ host: '127.0.0.1', port });
    const finish = value => { socket.destroy(); resolve(value); };
    socket.setTimeout(500);
    socket.once('connect', () => finish(true));
    socket.once('timeout', () => finish(false));
    socket.once('error', () => finish(false));
  });
}

export async function requireFreePorts(plan, open = portOpen) {
  for (const port of [plan.server.port, ...plan.clients.flatMap(c => [c.devPort, c.foremanPort])]) {
    if (await open(port)) throw new Error(`port ${port} is already in use; left its owner alone`);
  }
}

const freePort = () => new Promise((resolve, reject) => {
  const probe = net.createServer().once('error', reject);
  probe.listen(0, '127.0.0.1', () => { const { port } = probe.address(); probe.close(() => resolve(port)); });
});

export function loadState(plan) {
  const state = readJson(plan.file);
  if (state && (state.version !== 1 || state.slot !== plan.slot || state.root !== plan.root ||
    !Array.isArray(state.processes) || !Array.isArray(state.clients))) throw new Error(`invalid harness record: ${plan.file}`);
  return state;
}

export function summarize(plan, state, inventory, recover = recoverProcesses) {
  const running = (state?.processes ?? []).map(entry => {
    const processes = recover(entry, plan.root, inventory).map(record => ({ pid: record.pid, startTime: record.startTime,
      role: record.role ?? (record.pid === entry.pid ? 'primary' : record.pid === entry.wrapper?.pid ? 'wrapper' : 'member') }));
    return { kind: entry.kind, client: entry.client ?? null, log: entry.log, processes, pid: entry.pid ?? null,
      primaryRunning: processes.some(p => p.pid === entry.pid || p.role === 'primary') };
  });
  const alive = (kind, id) => running.some(entry => entry.kind === kind && entry.client === (id ?? null) && entry.primaryRunning);
  const source = state ?? plan;
  const anyAlive = running.some(entry => entry.processes.length);
  const allAlive = alive('server') && source.clients.every(client => alive('client', client.id) && alive('foreman', client.id));
  return { version: 1, slot: plan.slot, phase: anyAlive ? (allAlive && state?.phase === 'ready' ? 'ready' : 'partial') : 'stopped',
    home: source.home, profile: source.profile, stateFile: plan.file, error: state?.error ?? null,
    server: { ...source.server, running: !!alive('server') },
    clients: source.clients.map(client => ({ ...client, running: !!alive('client', client.id),
      foremanRunning: !!alive('foreman', client.id), state: state?.states?.[client.id] ?? null })),
    statesCapturedAt: state?.statesCapturedAt ?? null, processes: running };
}

// control.lock/<n>/owner.json is generation n of the slot's lock. A stale generation is never
// deleted to make room: its successor n+1 is claimed by an exclusive mkdir, so two controllers
// recovering the same stale lock contend for one name and only one of them can create it. The
// winner then reads the lock again, and holds it only while its own owner record is intact
// and no other generation has a live owner.
export async function withLock(plan, fn, { stamp = processStamp } = {}) {
  const lock = path.join(plan.dir, 'control.lock');
  fs.mkdirSync(lock, { recursive: true });
  const busy = () => new Error('another up/down is active for this slot; wait or interrupt that launcher');
  const owner = n => path.join(lock, String(n), 'owner.json');
  const remove = n => fs.rmSync(path.dirname(owner(n)), { recursive: true, force: true, maxRetries: 3 });
  const generations = () => fs.readdirSync(lock).filter(name => /^\d+$/.test(name)).map(Number);
  const live = n => {
    const lease = readJson(owner(n));
    if (lease) return sameProcess(lease, plan.root, stamp);
    // No owner yet: the claimant is between its mkdir and its first write, or died there.
    return Date.now() - (fs.statSync(path.dirname(owner(n)), { throwIfNoEntry: false })?.mtimeMs ?? 0) < 2000;
  };
  const seen = generations();
  if (seen.some(live)) throw busy();
  const mine = Math.max(0, ...seen) + 1;
  const lease = { pid: process.pid, startTime: stamp(process.pid, plan.root), token: randomUUID() };
  const held = () => readJson(owner(mine))?.token === lease.token;
  try { fs.mkdirSync(path.dirname(owner(mine))); }
  catch (error) { throw error.code === 'EEXIST' ? busy() : error; }
  try {
    saveJson(owner(mine), lease);
    const others = generations().filter(n => n !== mine);
    if (!held() || others.some(live)) throw busy();
    others.forEach(remove);
    return await fn();
  }
  finally { if (held()) remove(mine); }
}

export async function shutdown(plan, state, progress = () => {}, {
  inventory = processInventory, recover = recoverProcesses, portOwned = ownsPort,
  command = clientCommand, stop = stopProcess, platform = process.platform, serverStop = rconStop,
} = {}) {
  if (!state) return;
  state.phase = 'stopping';
  saveJson(plan.file, state);
  const failures = [];
  const table = inventory(plan.root);
  // Clients, Foremen, dedicated server, then any unfinished build. No shared daemon killing.
  const entries = [...state.processes].sort((a, b) =>
    ['client', 'foreman', 'server', 'build'].indexOf(a.kind) - ['client', 'foreman', 'server', 'build'].indexOf(b.kind));
  for (const entry of entries) {
    const records = recover(entry, plan.root, table).map(({ pid, startTime, groupPid, members, role }) => ({ pid, startTime, groupPid, members, role }));
    if (!records.length) continue;
    entry.recoveredProcesses = records;
    saveJson(plan.file, state);
    progress(`Stopping ${entry.kind}${entry.client ? ` ${entry.client.toUpperCase()}` : ''}...`);
    let requested = false;
    if (entry.kind === 'client') {
      const client = state.clients.find(c => c.id === entry.client);
      if (records.some(record => portOwned(record, client.devPort, plan.root))) {
        try { await command(state, client.id, 'dev.quit', {}, { timeoutMs: 5000 }); requested = true; }
        catch { /* a stalled/disconnected bridge falls back to the recorded PID */ }
      }
    } else if (entry.kind === 'server' && platform === 'win32' && state.rcon &&
      records.some(record => portOwned(record, state.rcon.port, plan.root))) {
      // Start-Bg gives the server no stdin and no console event makes a JVM exit cleanly, so
      // its `stop` (save, then exit) goes over the RCON port this run seeded and still owns.
      try { await serverStop(state.rcon.port, state.rcon.password); requested = true; }
      catch { /* not listening yet, or refused: nothing to wait for */ }
    }
    for (const record of records) {
      try {
        const forced = await stop(record, plan.root, { timeoutMs: entry.kind === 'server' ? 30_000 : 5000, requested, marker: entry.marker,
          kind: entry.kind, consoleRecord: entry.wrapper ?? record, persist: () => saveJson(plan.file, state) });
        if (forced && entry.kind === 'server' && record.role !== 'wrapper' && record.pid !== entry.wrapper?.pid) {
          progress('The server did not exit by itself and was killed without a final save: changes since its last autosave are lost.');
        }
      }
      catch (error) { failures.push(error.message); }
    }
  }
  const left = summarize(plan, state, inventory(plan.root), recover).processes.flatMap(entry => entry.processes);
  state.phase = left.length ? 'partial' : 'stopped';
  saveJson(plan.file, state);
  if (left.length || failures.length) throw new Error(`cleanup incomplete: ${[...failures, ...left.map(p => `PID ${p.pid}`)].join('; ')}`);
}

export async function prepare(plan, state, opt, progress, check, { spawnBuild = spawn, stamp = processStamp, wait = waitForBuild,
  ports = requireFreePorts } = {}) {
  const output = path.join(plan.dir, 'launch.json');
  if (!opt['no-build']) {
    progress('Preparing Loom launch metadata (no game tasks)...');
    const entry = { kind: 'build', marker: `ac-mp-${randomUUID()}`, log: path.join(plan.dir, 'build.log') };
    // The marker rides on the Gradle command line, so down finds the build without a recorded PID.
    const spec = gradleInvocation(plan.root, output, opt['gradle-command'], process.platform, entry.marker);
    state.processes.push(entry);
    saveJson(plan.file, state);
    const fd = fs.openSync(entry.log, 'w');
    let child;
    try { child = spawnBuild(spec.command, spec.args, { cwd: spec.cwd, env: gradleEnvironment(plan.root),
      windowsHide: true, detached: process.platform !== 'win32', stdio: ['ignore', fd, fd] }); }
    finally { fs.closeSync(fd); }
    const completion = wait(child, { timeoutMs: opt.timeout * 1000, log: entry.log, check });
    entry.pid = child.pid;
    if (process.platform !== 'win32') entry.groupPid = child.pid;
    entry.startTime = stamp(child.pid, plan.root);
    saveJson(plan.file, state);
    await completion;

    check();
  }
  const launch = readJson(output);
  if (!launch?.client || !launch?.server) throw new Error(`missing launch metadata: ${output}; omit --no-build or export with gw`);
  // The Gradle step can take minutes: the ports checked before it must still be free now,
  // immediately before the caller starts the processes that bind them.
  await ports(plan);
  return launch;
}

export function waitForBuild(child, { timeoutMs, log, check = () => {}, now = Date.now,
  interval = setInterval, clear = clearInterval } = {}) {
  const deadline = now() + timeoutMs;
  return new Promise((resolve, reject) => {
    const finish = error => {
      clear(timer);
      child.removeListener('error', failed);
      child.removeListener('exit', exited);
      error ? reject(error) : resolve();
    };
    const failed = error => finish(error);
    const exited = code => finish(code === 0 ? null : new Error(`Gradle preparation exited ${code}; see ${log}`));
    const timer = interval(() => {
      try {
        check();
        if (now() >= deadline) throw new Error(`Gradle preparation timed out; see ${log}`);
      } catch (error) { finish(error); }
    }, Math.min(500, timeoutMs));
    child.once('error', failed);
    child.once('exit', exited);
  });
}

async function waitUntil(test, entry, plan, timeoutMs, check) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    check();
    if (!sameProcess(entry, plan.root)) throw new Error(`${entry.kind} exited; see ${entry.log}`);
    if (await test()) return;
    await sleep(500);
  }
  throw new Error(`${entry.kind} startup timed out; see ${entry.log}`);
}

export async function runHarness(root, opt, { progress = () => {}, check = () => {} } = {}) {
  const plan = makePlan(root, opt);
  if (opt.action === 'status') return summarize(plan, loadState(plan), processInventory(root));
  return withLock(plan, async () => {
    const previous = loadState(plan);
    if (opt.action === 'down') {
      await shutdown(plan, previous, progress);
      return summarize(plan, loadState(plan), processInventory(root));
    }
    if (summarize(plan, previous, processInventory(root)).processes.some(entry => entry.processes.length)) {
      throw new Error('this slot still has harness processes; run down before up');
    }
    const state = { ...plan, phase: 'starting', processes: [], states: {} };
    saveJson(plan.file, state);
    try {
      check();
      const env = javaEnvironment();
      const java = requireJava25(env);
      await requireFreePorts(plan); // fails fast; prepare checks again after the Gradle step
      for (const [dir, dependency] of [['foreman', 'tsx'], ['tools', 'ws']]) {
        if (!fs.existsSync(path.join(root, dir, 'node_modules', dependency))) throw new Error(`run npm ci --prefix ${dir} first`);
      }
      const launch = await prepare(plan, state, opt, progress, check);
      // Windows stops the server over RCON (see shutdown). The slot's block has no port for it,
      // so it listens on whichever loopback port is free; the first persist below records it.
      if (process.platform === 'win32') state.rcon = { port: await freePort(), password: randomUUID() };
      seedGameDirs(plan, state.rcon);
      const persist = () => saveJson(plan.file, state);
      const start = async (kind, client, spec, timeoutMs = opt.timeout * 1000) => {
        check();
        const entry = { kind, client: client?.id, marker: `ac-mp-${randomUUID()}`, log: spec.log };
        state.processes.push(entry);
        fs.writeFileSync(spec.log, '');
        if (kind === 'foreman') spec.args.unshift(`--title=${entry.marker}`);
        else {
          const argfile = path.join(plan.dir, `${kind}-${client?.id ?? 'server'}.args`);
          fs.writeFileSync(argfile, javaArgfile(spec.args));
          spec.args = [`-Dagentcraft.mp.run=${entry.marker}`, `@${argfile}`];
        }
        return startProcess(spec, entry, state, persist, { timeoutMs });
      };
      async function launchClient(client, timeoutMs) {
        progress(`Starting client ${client.id.toUpperCase()} (${client.heap} heap, ${client.gameDir})...`);
        await start('client', client, gameSpec(plan, launch, java, env, client), timeoutMs);
      }
      progress(`Starting server :${plan.server.port} (${plan.server.heap} heap)...`);
      const server = await start('server', null, gameSpec(plan, launch, java, env));
      await waitUntil(() => readLogTail(plan.server.log).includes('Done ('), server, plan, opt.timeout * 1000, check);
      for (const client of plan.clients) {
        progress(`Starting Foreman ${client.id.toUpperCase()} :${client.foremanPort}...`);
        const fm = await start('foreman', client, { command: process.execPath,
          args: ['--import', 'tsx', 'src/main.ts', '--backend', opt.backend, '--home', plan.home,
            '--profile', client.profile, '--port', String(client.foremanPort), '--no-notify'],
          cwd: path.join(root, 'foreman'), log: client.foremanLog, env });
        await waitUntil(() => portOpen(client.foremanPort), fm, plan, opt.timeout * 1000, check);
        await launchClient(client);
      }

      let notice = 0;
      state.states = await waitWithBindRecovery(plan, { timeoutMs: opt.timeout * 1000, progress,
        stopClient: async (id, remaining) => {
          const entry = state.processes.findLast(p => p.kind === 'client' && p.client === id && !p.retired);
          const deadline = Date.now() + remaining;
          entry.recoveredProcesses = recoverProcesses(entry, root).map(({ pid, startTime, groupPid, role }) => ({ pid, startTime, groupPid, role }));
          persist();
          for (const record of entry.recoveredProcesses) {
            await stopProcess(record, root, { kind: 'client', marker: entry.marker, timeoutMs: 5000, budgetMs: Math.max(0, deadline - Date.now()), persist });
          }
          entry.retired = true;
          persist();
        },
        relaunchClient: (id, remaining) => launchClient(plan.clients.find(c => c.id === id), remaining),
        check: () => {
          check();
          for (const entry of state.processes.filter(p => p.kind !== 'build' && !p.retired)) {
            if (!sameProcess(entry, root)) throw new Error(`${entry.kind} ${entry.client ?? ''} exited; see ${entry.log}`);
          }
        }, onWait: () => { if (Date.now() - notice > 10_000) { notice = Date.now(); progress('Waiting for remote worlds and Foreman links...'); } } });
      state.phase = 'ready';
      state.statesCapturedAt = new Date().toISOString();
      persist();
      return summarize(plan, state, processInventory(root));
    } catch (error) {
      state.error = error.message;
      saveJson(plan.file, state);
      try { await shutdown(plan, state, progress); }
      catch (cleanup) { throw new Error(`${error.message}; ${cleanup.message}`); }
      throw error;
    }
  });
}
