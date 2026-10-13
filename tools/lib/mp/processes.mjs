import { spawn, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import { gradleArguments, gradleInvocation, psQuote } from './launch.mjs';

export const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
export function readJson(file) {
  try { return JSON.parse(fs.readFileSync(file, 'utf8')); }
  catch (error) { if (error.code === 'ENOENT') return null; throw new Error(`cannot read ${file}: ${error.message}`); }
}
export function saveJson(file, value) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  const tmp = `${file}.${process.pid}.tmp`;
  fs.writeFileSync(tmp, JSON.stringify(value, null, 2) + '\n');
  fs.renameSync(tmp, file);
}

export function powershell(root, script, run = spawnSync, options = {}) {
  const result = run('powershell.exe', ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-Command',
    `. ${psQuote(path.join(root, 'tools', 'lib', 'procs.ps1'))}; ${script}`], { encoding: 'utf8', windowsHide: true, ...options });
  if (result.error || result.status !== 0) throw new Error(`process inspection failed: ${result.error?.message ?? result.stderr}`);
  return result.stdout.trim();
}

export function processStamp(pid, root, { platform = process.platform, run = spawnSync } = {}) {
  if (!Number.isInteger(pid) || pid < 1) return null;
  if (platform === 'win32') return powershell(root, `Get-ProcStart ${pid}`, run) || null;
  const result = run('ps', ['-p', String(pid), '-o', 'lstart=', '-o', 'stat='],
    { encoding: 'utf8', env: { ...process.env, LC_ALL: 'C', TZ: 'UTC' } });
  if (result.error) throw result.error;
  if (result.status === 1) return null;
  if (result.status !== 0) throw new Error(`ps failed: ${result.stderr}`);
  const match = /^(.*?)\s+(\S+)\s*$/.exec(result.stdout.trim());
  return match && !match[2].startsWith('Z') ? match[1].trim() : null;
}

export function sameProcess(record, root, stamp = processStamp) {
  return !!(record?.pid && record?.startTime && stamp(record.pid, root) === record.startTime);
}

// POSIX `ps` reports start times in whole seconds, so a PID reused within its predecessor's
// start second passes sameProcess. There a process is this run's only while its command line
// still carries the entry's launch marker; a recorded child carries none, and must still be
// in the process group of the leader it was recorded under. Windows start times have 100 ns.
export function carriesRun(record, marker, table, platform = process.platform) {
  if (platform === 'win32' || !marker) return true;
  const row = table.find(p => p.pid === record.pid);
  return !!row && (record.groupPid && record.groupPid !== record.pid ? row.groupPid === record.groupPid : row.command.includes(marker));
}

export function processInventory(root) {
  if (process.platform === 'win32') {
    const raw = powershell(root, 'ConvertTo-Json -Compress -InputObject @(Get-CimInstance Win32_Process | Select-Object ProcessId,CommandLine)');
    return JSON.parse(raw || '[]').map(p => ({ pid: p.ProcessId, command: p.CommandLine ?? '' }));
  }
  const result = spawnSync('ps', ['-axww', '-o', 'pid=', '-o', 'pgid=', '-o', 'command='], { encoding: 'utf8' });
  if (result.error || result.status !== 0) throw new Error(`ps failed: ${result.error?.message ?? result.stderr}`);
  return result.stdout.split('\n').flatMap(line => {
    const m = /^\s*(\d+)\s+(\d+)\s+(.*)$/.exec(line);
    return m ? [{ pid: Number(m[1]), groupPid: Number(m[2]), command: m[3] }] : [];
  });
}

// `ps` prints argv joined by spaces, so a command that only quotes a build's command line
// (grep -F '<invocation>', sh -c "ps ... | grep ...") carries its marker and init script too.
// A build is therefore recognised by its shape: the launcher exactly as prepare() spawns it by
// default, or Java started by a Gradle launcher and ending in the harness's own Gradle
// arguments, which is what `sh ./gradlew` becomes, also behind a custom Gradle command.
// Windows keeps the quoting that ps drops, so quotes count only around the program's path.
export function isBuildLaunch(command, root, marker, platform = process.platform) {
  const bare = text => text.replace(/["']/g, '').trim();
  const line = bare(command);
  const from = line.indexOf(' -PmpLaunchFile='), to = line.lastIndexOf(` -PmpRun=${marker} `);
  if (!marker || from < 0 || to < from) return false;
  const output = line.slice(from + ' -PmpLaunchFile='.length, to);
  const spawned = gradleInvocation(root, output, undefined, platform, marker);
  if (line === bare([spawned.command, ...spawned.args].join(' '))) return true;
  const tail = ` ${bare(gradleArguments(root, output, marker).join(' '))}`;
  const program = /^"([^"]+)"|^(\S+)/.exec(command.trim())?.slice(1).find(Boolean) ?? '';
  return line.endsWith(tail) && /(^|[\\/])java(\.exe)?$/i.test(program) &&
    /gradle-wrapper\.jar|org\.gradle\./.test(line.slice(0, -tail.length));
}

// Durable intent precedes spawn. A unique marker outside the Java argfile permits recovery
// if the launcher dies in the tiny gap between spawn and recording the PID/start time.
// The build's marker is a Gradle project property; the shared daemon never carries it.
export function recoverProcesses(entry, root, inventory = processInventory(root), stamp = processStamp, platform = process.platform) {
  const found = [];
  const pending = [entry.wrapper, entry];
  while (pending.length) {
    const record = pending.pop();
    if (!record) continue;
    pending.push(...(record.members ?? []).map(member => ({ groupPid: record.pid, ...member })), ...(record.recoveredProcesses ?? []));
    if (sameProcess(record, root, stamp) && carriesRun(record, entry.marker, inventory, platform) &&
      !found.some(p => p.pid === record.pid)) found.push(record);
  }
  if (!/^ac-mp-[0-9a-f-]{36}$/.test(entry.marker ?? '')) return found;
  for (const p of inventory) {
    if (!p.command.includes(entry.marker) || found.some(record => record.pid === p.pid)) continue;
    const startsWithExe = executable => executable && (p.command.startsWith(`${executable} `) || p.command.startsWith(`"${executable}" `));
    // gradlew execs Java, so a build has no stable executable prefix: it is recognised by shape.
    const direct = startsWithExe(entry.executable) || (entry.kind === 'foreman' && p.command.trim() === entry.marker) ||
      (entry.kind === 'build' && isBuildLaunch(p.command, root, entry.marker, platform));
    const wrapper = startsWithExe(process.execPath) && p.command.includes(path.join(root, 'tools', 'lib', 'bgrun.mjs'));
    // A coordinator's grep/search containing our marker is never a harness process.
    if (!direct && !wrapper) continue;
    const startTime = stamp(p.pid, root);
    if (startTime) found.push({ pid: p.pid, startTime, role: wrapper ? 'wrapper' : 'primary',
      ...(p.groupPid === p.pid ? { groupPid: p.pid } : {}) });
  }
  return found;
}

export async function startProcess(spec, entry, state, persist, { platform = process.platform, shell = powershell, stamp = processStamp, timeoutMs = 20_000 } = {}) {
  entry.executable = spec.command;
  persist();
  fs.mkdirSync(path.dirname(spec.log), { recursive: true });
  let child;
  let error;
  if (platform === 'win32') {
    // Start-Bg uses ShellExecute and gives bgrun a hidden console, unlike detached spawn.
    const specFile = path.join(state.dir, `${entry.marker}.json`);
    const env = Object.fromEntries(Object.entries(spec.env ?? {}).filter(([key, value]) => process.env[key] !== value));
    saveJson(specFile, { ...spec, env });
    const result = JSON.parse(shell(state.root, `$s = Read-JsonFile ${psQuote(specFile)}; ` +
      `$e = @{}; if ($s.env) { $s.env.PSObject.Properties | ForEach-Object { $e[$_.Name] = [string]$_.Value } }; ` +
      `$L = @{Run=${psQuote(state.dir)}; Tools=${psQuote(path.join(state.root, 'tools'))}}; ` +
      `Start-Bg -L $L -NodeExe ${psQuote(process.execPath)} -Name ${psQuote(entry.marker)} ` +
      `-Command $s.command -Arguments @($s.args) -Cwd $s.cwd -Log $s.log -ErrLog '' -Env $e | ConvertTo-Json -Compress`,
      undefined, { timeout: Math.max(1, Math.ceil(timeoutMs)) }));
    entry.wrapper = { pid: result.wrapperPid, startTime: result.wrapperStart };
    entry.pid = result.pid; entry.startTime = result.start;
    persist();
    if (!sameProcess(entry, state.root, stamp)) throw new Error(`${entry.kind} exited during launch; see ${spec.log}`);
    return entry;
  }
  const fd = fs.openSync(spec.log, 'a');
  try { child = spawn(spec.command, spec.args, { cwd: spec.cwd, env: spec.env ?? process.env,
    detached: true, stdio: ['ignore', fd, fd] }); }
  finally { fs.closeSync(fd); }

  child.once('error', e => { error = e; });
  entry.pid = child.pid; entry.groupPid = child.pid; entry.startTime = stamp(child.pid, state.root);
  persist();
  child.unref();
  await sleep(25); // let asynchronous spawn errors arrive

  persist();
  if (error) throw error;
  if (!sameProcess(entry, state.root, stamp)) throw new Error(`${entry.kind} exited during launch; see ${spec.log}`);
  return entry;
}

/** Resolves true when a process had to be force-killed after the wait. */
export async function stopProcess(record, root, { timeoutMs = 10_000, consoleRecord = record, persist = () => {}, kind, requested = false,
  platform = process.platform, shell = powershell, stamp = processStamp, now = Date.now, delay = sleep, marker,
  inventory = processInventory, kill = process.kill.bind(process), budgetMs = Infinity } = {}) {
  const deadline = now() + budgetMs;
  // Read immediately before each signal: the live identities that still carry this run (carriesRun).
  const owned = list => {
    const live = list.filter(member => sameProcess(member, root, stamp));
    const table = live.length && marker && platform !== 'win32' ? inventory(root) : [];
    return live.filter(member => carriesRun(member, marker, table, platform));
  };
  if (!owned([record]).length) return;
  const members = platform !== 'win32' && record.groupPid === record.pid
    ? groupSnapshot(record, inventory(root), root, stamp) : [record];
  record.members = members.filter(member => member.pid !== record.pid);
  persist(); // retain child identities even if the group leader exits or down crashes
  if (platform === 'win32') {
    // Java treats Ctrl+Break as a thread dump, not a graceful shutdown: a JVM is only worth
    // waiting for when its exit was requested another way (dev.quit, or the server's RCON stop).
    if (kind === 'foreman' && sameProcess(consoleRecord, root, stamp)) {
      if (shell(root, `Send-CtrlBreak ${consoleRecord.pid}`).toLowerCase() !== 'true') timeoutMs = 0;
    } else if (kind !== 'client' && !requested) timeoutMs = 0;
  } else {
    let leader = true;
    if (requested) {
      // dev.quit replies first and stops Minecraft 250 ms later, so a client that acknowledged it
      // gets a bounded wait to exit by itself before any signal. Its exit ends the wait.
      const grace = Math.min(deadline, now() + timeoutMs);
      while (now() < grace && sameProcess(record, root, stamp)) await delay(Math.min(100, Math.max(0, deadline - now())));
      leader = owned([record]).length > 0; // read again after the wait
    }
    // Direct POSIX launches own a detached group. Signal its tools too, while the leader's
    // PID/start identity still matches, then retain member identities across leader exit.
    try { if (leader) kill(record.groupPid === record.pid ? -record.pid : record.pid, 'SIGTERM'); }
    catch (error) { if (error.code !== 'ESRCH') throw error; }
  }
  const until = Math.min(deadline, now() + timeoutMs);
  while (now() < until && members.some(member => sameProcess(member, root, stamp))) await delay(Math.min(100, Math.max(0, deadline - now())));
  const forced = owned(members);
  for (const member of forced) {
    // A Gradle daemon the build started is its descendant here, and is shared with later builds.
    if (platform === 'win32') shell(root, `Stop-OwnTree ${member.pid} ${psQuote(member.startTime)}${kind === 'build' ? ' -KeepGradleDaemons' : ''} | Out-Null`);
    else {
      try { kill(member.pid, 'SIGKILL'); }
      catch (error) { if (error.code !== 'ESRCH') throw error; }
    }
  }
  for (let i = 0; i < 50 && now() < deadline && members.some(member => sameProcess(member, root, stamp)); i++) await delay(Math.min(100, Math.max(0, deadline - now())));
  const left = owned(members);
  if (left.length) throw new Error(`processes ${left.map(member => member.pid).join(', ')} are still running`);
  return forced.length > 0;
}

/** The dedicated server's console `stop` (save, then exit) over RCON; rejects unless it was sent. */
export function rconStop(port, password, { timeoutMs = 5000 } = {}) {
  const packet = (id, type, body) => {
    const data = Buffer.alloc(14 + Buffer.byteLength(body)); // length, id, type, body, two NULs
    [data.length - 4, id, type].forEach((value, i) => data.writeInt32LE(value, 4 * i));
    data.write(body, 12);
    return data;
  };
  return new Promise((resolve, reject) => {
    const socket = net.connect({ host: '127.0.0.1', port });
    let sent = false;
    // Once the stop is on its way, a reply, a timeout and a dropped connection all mean the same.
    const finish = error => { socket.destroy(); sent ? resolve() : reject(error); };
    socket.setTimeout(timeoutMs, () => finish(new Error('RCON timed out')));
    socket.once('connect', () => socket.write(packet(1, 3, password)));
    // The server reads one packet at a time and answers a login with its id, or -1 to refuse.
    socket.on('data', reply => {
      if (sent || reply.length < 12 || reply.readInt32LE(4) !== 1) return finish(new Error('RCON refused the login'));
      sent = true;
      socket.write(packet(2, 2, 'stop'));
    });
    socket.once('error', finish);
    socket.once('close', () => finish(new Error('RCON closed before the stop was sent')));
  });
}

export function groupSnapshot(record, inventory, root, stamp = processStamp) {
  if (!sameProcess(record, root, stamp) || record.groupPid !== record.pid) return [];
  return [{ pid: record.pid, startTime: record.startTime }, ...inventory.filter(p => p.groupPid === record.pid && p.pid !== record.pid).flatMap(p => {
    const startTime = stamp(p.pid, root);
    return startTime ? [{ pid: p.pid, startTime, groupPid: record.pid }] : [];
  })];
}

export function ownsPort(entry, port, root) {
  if (!sameProcess(entry, root)) return false;
  if (process.platform === 'win32') return Number(powershell(root, `Get-PortOwner ${Number(port)}`)) === entry.pid;
  const result = spawnSync('lsof', ['-nP', `-iTCP:${port}`, '-sTCP:LISTEN', '-t'], { encoding: 'utf8' });
  return result.status === 0 && result.stdout.trim().split(/\s+/).includes(String(entry.pid));
}
