#!/usr/bin/env node
// macOS and Linux launcher for the Foreman and the Fabric development client (Windows: tools/launch.ps1).
//
//   node tools/launch-unix.mjs launch|stop|login [options]      (--help lists them)
//
// Everything is checked before anything starts: the options, Node 22+, a Java 25 JDK (JAVA_HOME
// when it is 25+, else Homebrew, the system JVM folders, SDKMAN or ~/.jdks, newest first) and that
// the ports are free or held by this launcher's own Foreman / game. If a launch fails or is
// interrupted (Ctrl+C), what that launch started is stopped again; what it reused is left alone.
//
// Exit codes (the same as tools/launch.ps1): 0 ok; 1 a step failed (the log tail is printed);
// 2 bad arguments; 3 a prerequisite is missing; 4 a port or the game is taken by something this
// launcher did not start; 130 interrupted.
//
// Run files and logs: artifacts/run/unix-{foreman,game}-<profile>.json, artifacts/logs/unix-*.log.
// Their names predate the rename from tools/unix.mjs and are kept, so a Foreman or game started by
// the older script is still found, reused and stopped.
import { spawn, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { EXIT, LaunchError, findJdk, jdkCandidates, javaMajorFromText, logTail, nodeVersionOk, parseOptions } from './lib/launcher.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const tools = path.join(root, 'tools');
const runDir = path.join(root, 'artifacts', 'run');
const logDir = path.join(root, 'artifacts', 'logs');
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const readJson = (file) => { try { return JSON.parse(fs.readFileSync(file, 'utf8')); } catch { return null; } };
const saveJson = (file, value) => {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, JSON.stringify(value, null, 2) + '\n');
};
const runFile = (kind, profile) => path.join(runDir, `unix-${kind}-${profile}.json`);

function usage(code = 0) {
  console.log(`AgentCraft launcher (macOS, Linux)
  node tools/launch-unix.mjs launch [--backend sim|claude|codex] [--repo PATH] [--use-claude-login]
                            [--world studio|survival] [--seed SEED]
                            [--home PATH] [--profile NAME] [--port N] [--dev-port N]
                            [--dev] [--showcase busy|late] [--reset]
                            [--no-game] [--no-foreman] [--no-wait] [--timeout SECONDS]
                            [--summary-json PATH] [--keep-on-failure]
                            [--foreman-arg VALUE] (repeatable)
  node tools/launch-unix.mjs stop [--game] [--foreman] [--profile NAME] [--stop-daemon]
  node tools/launch-unix.mjs login    sign the agents' Claude Code in (needed once for --use-claude-login)

Default: Claude backend, ~/.agentcraft, ports 7878/7879, the studio world. --dev mutes
the game, keeps it from taking focus, and disables desktop notifications.
--world survival opens (or creates) the "AgentCraft Survival" world instead: a normal
world in survival mode with the HQ built into its terrain; --seed fixes its seed.
--timeout: seconds to wait for the world (600; 1800 on this checkout's first build).
If a launch fails or you press Ctrl+C, what it started is stopped again
(--keep-on-failure leaves it running).

Exit codes: 0 ok, 1 a step failed, 2 bad arguments, 3 a prerequisite is missing,
4 a port or the game is in use by something this launcher did not start, 130 interrupted.`);
  process.exit(code);
}

// --- interruption ----------------------------------------------------------------------------------

// The first Ctrl+C (or SIGTERM/SIGHUP) makes the launch stop what it started and exit 130; a second one exits at once.
const interrupt = { signal: null };
for (const signal of ['SIGINT', 'SIGTERM', 'SIGHUP']) {
  process.on(signal, () => {
    if (interrupt.signal) process.exit(EXIT.interrupted);
    interrupt.signal = signal;
    console.error(`\nAgentCraft: ${signal}; stopping what this launch started (again to quit at once)`);
  });
}
function checkInterrupted() {
  if (interrupt.signal) throw new LaunchError(`interrupted (${interrupt.signal})`, EXIT.interrupted);
}

// --- processes -------------------------------------------------------------------------------------

function processStamp(pid) {
  if (!Number.isInteger(pid) || pid < 1) return null;
  const result = spawnSync('ps', ['-p', String(pid), '-o', 'lstart='], { encoding: 'utf8' });
  return result.status === 0 ? result.stdout.trim() || null : null;
}

function owned(info) { return info?.pid && info?.stamp && processStamp(info.pid) === info.stamp; }
function portOpen(port) {
  return new Promise((resolve) => {
    const socket = net.connect({ host: '127.0.0.1', port });
    socket.setTimeout(500);
    socket.once('connect', () => { socket.destroy(); resolve(true); });
    socket.once('timeout', () => { socket.destroy(); resolve(false); });
    socket.once('error', () => resolve(false));
  });
}

async function waitPort(port, timeoutMs, info, name) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    checkInterrupted();
    if (await portOpen(port)) return;
    if (info && !owned(info)) throw new LaunchError(`${name} exited before it opened port ${port}; see ${info.log}`, EXIT.failed, logTail(info.log, 20));
    await sleep(500);
  }
  throw new LaunchError(`${name} did not open port ${port} within ${Math.round(timeoutMs / 1000)} s; see ${info?.log ?? 'its logs'}`, EXIT.failed, logTail(info?.log, 20));
}

// --- prerequisites ---------------------------------------------------------------------------------

const JDK_HINT = {
  darwin: 'brew install openjdk@25',
  linux: 'your distribution\'s package manager (Arch: pacman -S jdk25-openjdk), or set JAVA_HOME to a Java 25 JDK',
};

function javaMajor(home) {
  const java = path.join(home, 'bin', 'java');
  if (!fs.existsSync(java)) return 0;
  const result = spawnSync(java, ['-version'], { encoding: 'utf8', timeout: 20000 });
  return javaMajorFromText(`${result.stderr ?? ''}${result.stdout ?? ''}`);
}

/** The Java 25+ JDK the game runs on: { home, source, major }. JAVA_HOME itself is never changed. */
function javaHome() {
  const jdk = findJdk({ min: 25, candidates: jdkCandidates(), probe: javaMajor });
  if (jdk) return jdk;
  const have = process.env.JAVA_HOME ? ` (JAVA_HOME ${process.env.JAVA_HOME} is Java ${javaMajor(process.env.JAVA_HOME) || 'unknown'})` : '';
  throw new LaunchError(`Java 25 is required for Minecraft 26.3 and none was found${have}. Install it with ${JDK_HINT[process.platform] ?? 'a Java 25 JDK'}`, EXIT.missing);
}

function installDeps(dir) {
  const lock = path.join(dir, 'package-lock.json');
  const stamp = path.join(dir, 'node_modules', '.package-lock.json');
  if (fs.existsSync(stamp) && fs.existsSync(lock) && fs.statSync(stamp).mtimeMs >= fs.statSync(lock).mtimeMs - 2000) return;
  console.log(`Installing npm dependencies in ${path.relative(root, dir)}/ ...`);
  const result = spawnSync('npm', ['ci', '--no-audit', '--no-fund'], { cwd: dir, stdio: 'inherit', timeout: 15 * 60_000 });
  checkInterrupted();
  if (result.error?.code === 'ETIMEDOUT') throw new LaunchError(`npm ci did not finish in 15 minutes in ${dir}`, EXIT.missing);
  if (result.status !== 0) throw new LaunchError(`npm ci failed in ${dir} (${result.error?.message ?? `exit ${result.status}`})`, EXIT.missing);
}

function start(command, args, cwd, log, env = {}) {
  const output = fs.openSync(log, 'a');
  const child = spawn(command, args, {
    cwd, env: { ...process.env, ...env }, detached: true,
    stdio: ['ignore', output, output],
  });
  child.on('error', () => {});
  fs.closeSync(output);
  child.unref();
  if (!child.pid) throw new LaunchError(`could not start ${command}`, EXIT.failed, logTail(log, 20));
  return { pid: child.pid, stamp: processStamp(child.pid), log, startedAt: new Date().toISOString() };
}

// The Claude Code CLI the agent SDK runs. Its login is separate from the Claude desktop app's.
function claudeCli() {
  installDeps(path.join(root, 'foreman'));
  const bundled = path.join(root, 'foreman', 'node_modules', '@anthropic-ai', `claude-agent-sdk-${process.platform}-${process.arch}`, process.platform === 'win32' ? 'claude.exe' : 'claude');
  return fs.existsSync(bundled) ? bundled : 'claude';
}

function claudeLoggedIn() {
  const result = spawnSync(claudeCli(), ['auth', 'status'], { encoding: 'utf8', timeout: 30000 });
  try { return JSON.parse(result.stdout).loggedIn === true; } catch { return false; }
}

function login() {
  const result = spawnSync(claudeCli(), ['auth', 'login'], { stdio: 'inherit' });
  if (result.status !== 0 || !claudeLoggedIn()) throw new LaunchError('Claude login did not complete');
  console.log('Claude login ok. Now launch with --backend claude --use-claude-login.');
}

/** A tools/*.mjs CLI: its stdout, or an error with its output. Killed after timeoutMs, or when this launch is interrupted. */
function runCli(script, args, timeoutMs = 30000) {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [path.join(tools, script), ...args], { cwd: root, stdio: ['ignore', 'pipe', 'pipe'] });
    let out = '';
    let err = '';
    child.stdout.on('data', (d) => { out += d; });
    child.stderr.on('data', (d) => { err += d; });
    const timer = setTimeout(() => child.kill('SIGKILL'), timeoutMs);
    const poll = setInterval(() => { if (interrupt.signal) child.kill('SIGTERM'); }, 200);
    const done = () => { clearTimeout(timer); clearInterval(poll); };
    child.on('error', (e) => { done(); reject(e); });
    child.on('close', (code, signal) => {
      done();
      if (interrupt.signal) return reject(new LaunchError(`interrupted (${interrupt.signal})`, EXIT.interrupted));
      if (code !== 0) return reject(new Error(`${script}: ${(out || err).trim() || `exit ${code ?? signal}`}`));
      resolve(out.trim());
    });
  });
}

async function foremanAuthFailed(port) {
  try { return JSON.parse(await runCli('foreman-cli.mjs', ['status', '--port', String(port)], 60000)).foreman?.auth === 'failed'; }
  catch (e) { if (e instanceof LaunchError) throw e; return false; }
}

function prepareAudio(dev) {
  const optionsFile = path.join(root, 'mod', 'run', 'options.txt');
  const savedFile = path.join(runDir, 'unix-audio.json');
  const template = path.join(root, 'mod', 'run-template', 'options.txt');
  const level = (text, name) => new RegExp(`^soundCategory_${name}:([^\\n]+)$`, 'm').exec(text)?.[1];
  const replace = (text, name, value) => text.replace(new RegExp(`^soundCategory_${name}:[^\\n]+$`, 'm'), `soundCategory_${name}:${value}`);
  if (!fs.existsSync(optionsFile) && dev) {
    saveJson(savedFile, { master: '1.0', music: level(fs.readFileSync(template, 'utf8'), 'music') });
    return;
  }
  if (!fs.existsSync(optionsFile) && !dev) {
    fs.mkdirSync(path.dirname(optionsFile), { recursive: true });
    fs.writeFileSync(optionsFile, replace(fs.readFileSync(template, 'utf8'), 'master', '1.0'));
  }
  if (!fs.existsSync(optionsFile)) return;
  let text = fs.readFileSync(optionsFile, 'utf8');
  if (dev) {
    if (!fs.existsSync(savedFile)) saveJson(savedFile, { master: level(text, 'master'), music: level(text, 'music') });
  } else {
    const saved = readJson(savedFile);
    if (saved) {
      if (level(text, 'master') === '0.0' && saved.master) text = replace(text, 'master', saved.master);
      if (level(text, 'music') === '0.0' && saved.music) text = replace(text, 'music', saved.music);
      fs.writeFileSync(optionsFile, text);
      fs.rmSync(savedFile, { force: true });
    }
  }
}

// --- launch ----------------------------------------------------------------------------------------

async function launch(opt, summary, started) {
  if (process.platform === 'win32') throw new LaunchError('tools/launch-unix.mjs is for macOS and Linux; use tools\\launch.ps1 on Windows', EXIT.args);
  const nodeVersion = nodeVersionOk(process.versions.node, 22);
  if (!nodeVersion.ok) throw new LaunchError(`Node 22+ is required (this is ${process.versions.node})`, EXIT.missing);
  for (const rel of ['foreman/package.json', 'foreman/src/main.ts', 'tools/devbridge-cli.mjs', 'tools/foreman-cli.mjs', ...(opt['no-game'] ? [] : ['mod/gradlew'])]) {
    if (!fs.existsSync(path.join(root, rel))) throw new LaunchError(`this checkout is incomplete (missing ${rel}); clone it again`, EXIT.missing);
  }
  fs.mkdirSync(runDir, { recursive: true });
  fs.mkdirSync(logDir, { recursive: true });
  const jdk = opt['no-game'] ? null : javaHome();
  if (jdk) console.log(`Java ${jdk.major}: ${jdk.home} (${jdk.source})`);

  // what is running, and the ports: checked before anything starts
  const fmFile = runFile('foreman', opt.profile);
  let fm = readJson(fmFile);
  const fmLive = owned(fm) && await portOpen(fm.port);
  if (!opt['no-foreman'] && !fmLive && await portOpen(opt.port)) {
    throw new LaunchError(`port ${opt.port} is already in use by something this launcher did not start (another Foreman: other profile or home?). Use --port N.`, EXIT.busy);
  }
  const gameFile = runFile('game', opt.profile);
  let game = null;
  if (!opt['no-game']) {
    for (const name of fs.readdirSync(runDir).filter((n) => /^unix-game-[\w-]+\.json$/.test(n))) {
      const otherFile = path.join(runDir, name);
      if (otherFile !== gameFile && owned(readJson(otherFile))) {
        throw new LaunchError(`another Minecraft client from this checkout is running (${name}); stop it before switching profiles`, EXIT.busy);
      }
    }
    game = readJson(gameFile);
    if (!owned(game) && await portOpen(opt['dev-port'])) {
      throw new LaunchError(`DevBridge port ${opt['dev-port']} is already in use by something this launcher did not start (another game?). Use --dev-port N.`, EXIT.busy);
    }
  }
  checkInterrupted();
  installDeps(tools);
  if (opt.backend === 'claude' && opt['use-claude-login'] && !opt['no-foreman'] && !claudeLoggedIn()) {
    throw new LaunchError('the agents\' Claude Code is not signed in (the Claude desktop app login is separate). Run: node tools/launch-unix.mjs login', EXIT.missing);
  }

  let fmPort = opt.port;
  if (!opt['no-foreman']) {
    if (owned(fm) && await portOpen(fm.port) && await foremanAuthFailed(fm.port)) {
      // a Foreman that failed its Claude check keeps failing until restarted; reusing it would hide a fixed login
      console.log(`Foreman ${fm.pid} failed its Claude auth check; restarting it`);
      await stopOne('foreman', fm, fmFile);
      fm = null;
    }
    if (owned(fm) && await portOpen(fm.port)) {
      fmPort = fm.port;
      summary.foreman.port = fmPort;
      console.log(`Reusing Foreman ${fm.pid} on :${fmPort}`);
      if (fm.backend !== opt.backend) console.warn(`Foreman is already using backend ${fm.backend}`);
      for (const repo of opt.repo) {
        try { console.log(await runCli('foreman-cli.mjs', ['repo-add', repo, '--port', String(fmPort)], 60000)); }
        catch (e) { if (e instanceof LaunchError) throw e; console.warn(`repo.add ${repo} failed: ${e.message}`); }
      }
    } else {
      if (await portOpen(fmPort)) throw new LaunchError(`port ${fmPort} is already in use`, EXIT.busy);
      installDeps(path.join(root, 'foreman'));
      const args = ['--import', 'tsx', 'src/main.ts', '--backend', opt.backend,
        '--profile', opt.profile, '--home', opt.home, '--port', String(fmPort)];
      for (const repo of opt.repo) args.push('--repo', repo);
      if (opt['use-claude-login']) args.push('--use-claude-login');
      if (opt.dev) args.push('--no-notify');
      if (opt.showcase) args.push('--showcase', opt.showcase);
      if (opt.reset || opt.showcase) args.push('--reset');
      args.push(...opt.foremanArgs);
      fm = { ...start(process.execPath, args, path.join(root, 'foreman'), path.join(logDir, `unix-foreman-${opt.profile}.log`)), backend: opt.backend, port: fmPort, home: opt.home };
      saveJson(fmFile, fm);
      started.foreman = { info: fm, file: fmFile };
      summary.foreman.started = true;
      await waitPort(fmPort, 120000, fm, 'Foreman');
      console.log(`Foreman running on :${fmPort} (PID ${fm.pid})`);
    }
  } else if (!await portOpen(fmPort)) {
    console.warn(`No Foreman is listening on :${fmPort}; the game will retry connecting.`);
  }
  if (opt['no-game']) return;

  if (owned(game)) {
    if (!await portOpen(game.devPort)) await waitPort(game.devPort, opt.timeout * 1000, game, 'Minecraft');
    console.log(`Minecraft is already running (PID ${game.pid}, DevBridge :${game.devPort})`);
    summary.game.devPort = game.devPort;
    if (game.foremanPort !== fmPort) console.warn(`It was launched for Foreman :${game.foremanPort}; stop the game before switching ports.`);
    return;
  }
  if (await portOpen(opt['dev-port'])) throw new LaunchError(`DevBridge port ${opt['dev-port']} is already in use`, EXIT.busy);
  prepareAudio(opt.dev);
  const gradleHome = process.env.GRADLE_USER_HOME || path.join(root, '.gradle-home');
  const env = {
    JAVA_HOME: jdk.home, GRADLE_USER_HOME: gradleHome,
    AGENTCRAFT_PORT: String(fmPort), AGENTCRAFT_DEV_PORT: String(opt['dev-port']),
    AGENTCRAFT_HOME: opt.home, AGENTCRAFT_PROFILE: opt.profile, AGENTCRAFT_WORLD: opt.world,
    AGENTCRAFT_MUTE: opt.dev ? '1' : '0', AGENTCRAFT_FOCUS: opt.dev ? '0' : '1',
    // the mod's Foreman launcher finds the Foreman started (or reused) above and leaves it alone; --no-foreman means
    // "I start my own": the launcher stays off then, unless the caller set AGENTCRAFT_LAUNCHER (1 = let the game start it)
    ...(opt['no-foreman'] ? { AGENTCRAFT_LAUNCHER: process.env.AGENTCRAFT_LAUNCHER || '0' } : {}),
  };
  if (opt.seed) env.AGENTCRAFT_WORLD_SEED = opt.seed;
  // a checkout's first build downloads Minecraft and Fabric: give it longer unless --timeout says otherwise
  const firstBuild = !fs.existsSync(path.join(root, 'mod', '.gradle', 'loom-cache', 'launch.cfg'));
  const waitSec = !opt.timeoutGiven && firstBuild ? 1800 : opt.timeout;
  game = { ...start('/bin/sh', [path.join(root, 'mod', 'gradlew'), 'runClient', '--console=plain'], path.join(root, 'mod'), path.join(logDir, 'unix-game.log'), env), devPort: opt['dev-port'], foremanPort: fmPort };
  saveJson(gameFile, game);
  started.game = { info: game, file: gameFile };
  summary.game.started = true;
  console.log(`Starting Minecraft (Gradle PID ${game.pid}); log: ${game.log}`);
  if (opt['no-wait']) return;
  if (firstBuild && !opt.timeoutGiven) console.log('First build of this checkout: waiting up to 30 minutes for the world.');
  const deadline = Date.now() + waitSec * 1000;
  await waitPort(game.devPort, deadline - Date.now(), game, 'Minecraft');
  console.log('Waiting for the studio world...');
  const remaining = Math.max(10, Math.round((deadline - Date.now()) / 1000));
  let state;
  try { state = JSON.parse(await runCli('devbridge-cli.mjs', ['wait', '--port', String(game.devPort), '--timeout', String(remaining)], (remaining + 30) * 1000)); }
  catch (e) {
    if (e instanceof LaunchError) throw e;
    const hint = opt.timeoutGiven ? '' : ' A slow first build may need longer: --timeout 3600.';
    throw new LaunchError(`the world was not ready after ${waitSec} s: ${e.message}.${hint}`, EXIT.failed, logTail(game.log, 20));
  }
  console.log(`Studio ready: Minecraft ${state.minecraft}, world ${state.world?.name ?? 'unknown'}, Foreman ${state.foreman?.link ?? 'unknown'}`);
  console.log(`Ready. Stop with: node tools/launch-unix.mjs stop --profile ${opt.profile}`);
}

// --- stop ------------------------------------------------------------------------------------------

/** Stops one launcher-owned process (recorded pid + start stamp) and removes its run file. */
async function stopOne(kind, info, file) {
  if (!owned(info)) { console.log(`${kind}: no launcher-owned process running`); return; }
  if (kind === 'game' && await portOpen(info.devPort)) {
    try { console.log(await runCli('devbridge-cli.mjs', ['quit', '--port', String(info.devPort), '--timeout', '20'], 120000)); }
    catch (error) { console.warn(error.message); }
  } else if (kind === 'foreman') {
    try { process.kill(info.pid, 'SIGTERM'); } catch {}
  }
  for (let i = 0; i < 40 && owned(info); i++) await sleep(250);
  if (owned(info)) {
    // launch made this PID a separate process group; only touch the recorded group.
    try { process.kill(-info.pid, 'SIGTERM'); } catch {}
    await sleep(1000);
    if (owned(info)) try { process.kill(-info.pid, 'SIGKILL'); } catch {}
  }
  fs.rmSync(file, { force: true });
  console.log(`${kind}: stopped`);
}

async function stop(opt) {
  const kinds = opt.game ? ['game'] : opt.foreman ? ['foreman'] : ['game', 'foreman'];
  for (const kind of kinds) {
    const file = runFile(kind, opt.profile);
    await stopOne(kind, readJson(file), file);
  }
  if (opt['stop-daemon'] && kinds.includes('game')) {
    const localGradleHome = path.join(root, '.gradle-home');
    if (process.env.GRADLE_USER_HOME && path.resolve(process.env.GRADLE_USER_HOME) !== localGradleHome) {
      throw new LaunchError('--stop-daemon requires this checkout\'s .gradle-home to avoid stopping other projects', EXIT.args);
    }
    const env = { ...process.env, JAVA_HOME: javaHome().home, GRADLE_USER_HOME: localGradleHome };
    const result = spawnSync('/bin/sh', [path.join(root, 'mod', 'gradlew'), '--stop'], { cwd: path.join(root, 'mod'), env, stdio: 'inherit' });
    if (result.status !== 0) throw new LaunchError('could not stop the Gradle daemon');
  }
}

/** After a failed or interrupted launch: stop what it started (never what it reused). */
async function cleanup(started, why) {
  if (!started.game && !started.foreman) return false;
  console.error(`Stopping what this launch started (${why})`);
  if (started.game) await stopOne('game', started.game.info, started.game.file);
  if (started.foreman) await stopOne('foreman', started.foreman.info, started.foreman.file);
  return true;
}

// --- main ------------------------------------------------------------------------------------------

let opt;
try {
  opt = parseOptions(process.argv.slice(2));
} catch (error) {
  for (const line of error.message.split('\n')) console.error(`AgentCraft: ${line}`);
  console.error('Run node tools/launch-unix.mjs --help for the options.');
  process.exit(error instanceof LaunchError ? error.code : EXIT.args);
}
if (opt.help) usage(0);

const started = { foreman: null, game: null };
const summary = { ok: false, exitCode: null, error: null, foreman: { started: false, port: opt.port }, game: { started: false, devPort: opt['dev-port'] } };
try {
  if (opt.action === 'launch') await launch(opt, summary, started);
  else if (opt.action === 'login') login();
  else await stop(opt);
  summary.ok = true;
  summary.exitCode = EXIT.ok;
} catch (error) {
  const code = interrupt.signal ? EXIT.interrupted : error instanceof LaunchError ? error.code : EXIT.failed;
  console.error(`AgentCraft: ${error.message}`);
  if (error.tail?.length) console.error(['--- log tail ---', ...error.tail].map((l) => `  ${l}`).join('\n'));
  summary.error = error.message;
  summary.exitCode = code;
  if (opt.action === 'launch') {
    if (opt['keep-on-failure']) {
      if (started.foreman) console.error(`--keep-on-failure: the Foreman (PID ${started.foreman.info.pid}) keeps running; node tools/launch-unix.mjs stop --foreman --profile ${opt.profile}`);
      if (started.game) console.error(`--keep-on-failure: the game (Gradle PID ${started.game.info.pid}) keeps running; node tools/launch-unix.mjs stop --game --profile ${opt.profile}`);
    } else {
      try { summary.cleanedUp = await cleanup(started, interrupt.signal ? 'interrupted' : 'the launch failed'); }
      catch (e) { console.error(`AgentCraft: cleanup failed (${e.message}); check with node tools/launch-unix.mjs stop --profile ${opt.profile}`); }
    }
  }
  process.exitCode = code;
} finally {
  if (opt.action === 'launch' && opt['summary-json']) saveJson(opt['summary-json'], summary);
}
