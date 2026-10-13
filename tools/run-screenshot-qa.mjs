#!/usr/bin/env node
// QA runner: showcase Foreman + game (Windows launch.ps1, or launch-unix.mjs on macOS and Linux), the QA scene, a contact
// sheet and a manifest, then it stops exactly what it started. See docs/QA.md.
//
//   node tools/run-screenshot-qa.mjs [--showcase busy|late] [--scene tools/scenes/qa.json] [--only qa01_exterior_hero,...]
//        [--port 7878] [--dev-port 7879] [--home <dir>] [--profile <name>] [--run-id <id>]
//        [--keep] [--no-launch] [--strict] [--stop-daemon] [--no-restore] [--columns 3]
//
//   --keep          leave the Foreman and the game running afterwards (faster re-runs)
//   --no-launch     use the game/Foreman that are already running on the ports (never start/stop)
//   --strict        skipped shots fail the run (use once the HQ provides every anchor)
//   --stop-daemon   also stop this checkout's Gradle daemon if this run launched the game
//   --no-restore    leave the player/time where the last shot put them (default: restored)
//
// Output: artifacts/shots/qa/<runId>/<shot>.png, contact_sheet.png, manifest.json (and
// artifacts/shots/qa/latest.json pointing at the run). Exit 0 when no shot failed.

import { spawn, execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { DevClient } from './lib/devclient.mjs';
import { ForemanClient } from './lib/foremanclient.mjs';
import { loadScene, runScene } from './lib/scene.mjs';
import { contactSheet, COLORS } from './lib/contactsheet.mjs';

const TOOLS = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(TOOLS, '..');
const log = (m) => process.stderr.write(`[run-screenshot-qa] ${m}\n`);

// --- args ------------------------------------------------------------------------------------------
const argv = process.argv.slice(2);
const opt = {};
for (let i = 0; i < argv.length; i++) {
  const a = argv[i];
  if (!a.startsWith('--')) continue;
  const k = a.slice(2);
  if (['showcase', 'scene', 'only', 'port', 'dev-port', 'home', 'profile', 'run-id', 'columns', 'timeout'].includes(k)) opt[k] = argv[++i];
  else opt[k] = true;
}
if (opt.help) {
  const lines = fs.readFileSync(fileURLToPath(import.meta.url), 'utf8').split(/\r?\n/).slice(1);
  console.error(lines.slice(0, lines.findIndex((l) => !l.startsWith('//'))).map((l) => l.replace(/^\/\/ ?/, '')).join('\n'));
  process.exit(0);
}

function git(args, cwd = ROOT) {
  try { return execFileSync('git', args, { cwd, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim(); } catch { return null; }
}
// QA state never goes to ~/.agentcraft: default to the main checkout's .agentcraft-home
function mainCheckout() {
  const common = git(['rev-parse', '--path-format=absolute', '--git-common-dir']);
  if (common && path.basename(common) === '.git') return path.dirname(path.resolve(common));
  return ROOT;
}

const showcase = opt.showcase === 'late' ? 'late' : 'busy';
const scenePath = path.resolve(opt.scene ?? path.join(TOOLS, 'scenes', 'qa.json'));
const port = Number(opt.port ?? process.env.AGENTCRAFT_PORT ?? 7878);
const devPort = Number(opt['dev-port'] ?? process.env.AGENTCRAFT_DEV_PORT ?? 7879);
const home = path.resolve(opt.home ?? path.join(mainCheckout(), '.agentcraft-home'));
const profile = opt.profile ?? (showcase === 'late' ? 'showcase-late' : 'showcase');
const stamp = new Date().toISOString().replace(/[-:]/g, '').replace('T', '-').slice(0, 15);
const runId = opt['run-id'] ?? stamp;
if (!/^[A-Za-z0-9_.-]+$/.test(runId)) { log(`bad --run-id ${runId}`); process.exit(2); }
const only = opt.only ? new Set(String(opt.only).split(',').map((s) => s.trim()).filter(Boolean)) : null;
const outDir = path.join(ROOT, 'artifacts', 'shots', 'qa', runId);
fs.mkdirSync(outDir, { recursive: true });
const launchSummaryPath = path.join(outDir, 'launch.json');
const gitInfo = { sha: git(['rev-parse', '--short', 'HEAD']), branch: git(['rev-parse', '--abbrev-ref', 'HEAD']), dirty: !!git(['status', '--porcelain', '--', 'mod', 'tools', 'foreman', 'assets-src']) };
const scene = loadScene(scenePath);
let env = {};
let sheet = null;

function runLauncher(action, args, logFile) {
  return new Promise((resolve) => {
    const windows = process.platform === 'win32';
    const command = windows
      ? path.join(process.env.SystemRoot ?? 'C:\\Windows', 'System32', 'WindowsPowerShell', 'v1.0', 'powershell.exe')
      : process.execPath;
    const argv = windows
      ? ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', path.join(TOOLS, action === 'launch' ? 'launch.ps1' : 'stop.ps1'), ...args]
      : [path.join(TOOLS, 'launch-unix.mjs'), action, ...args];
    const child = spawn(command, argv, { cwd: ROOT, windowsHide: true });
    const out = fs.createWriteStream(logFile);
    const pipe = (d) => { out.write(d); process.stderr.write(String(d).replace(/^(?=.)/gm, '    ')); };
    child.stdout.on('data', pipe);
    child.stderr.on('data', pipe);
    let done = false;
    const finish = (code) => { if (done) return; done = true; out.end(); resolve(code ?? 1); };
    child.on('close', (code) => finish(code));
    // Launchers detach long-running processes; never hang on a pipe they may still hold.
    child.on('exit', (code) => setTimeout(() => finish(code), 3000));
    child.on('error', () => finish(1));
  });
}

async function probeDev() {
  try {
    const d = await DevClient.connect({ port: devPort, timeoutMs: 1500 });
    d.close();
    return true;
  } catch { return false; }
}
async function probeForeman() {
  try {
    const f = await ForemanClient.connect({ port, timeoutMs: 1500, client: 'qa-probe' });
    const st = f.state.foreman;
    f.close();
    return st ?? {};
  } catch { return null; }
}

// --- launch ------------------------------------------------------------------------------------------
const t0 = Date.now();
let launch = null;
let launchCode = null;
if (!opt['no-launch']) {
  const gameUp = await probeDev();
  const fmUp = await probeForeman();
  const windows = process.platform === 'win32';
  const args = windows
    ? ['-Dev', '-Showcase', showcase, '-Home', home, '-Port', String(port), '-DevPort', String(devPort), '-Profile', profile, '-SummaryJson', launchSummaryPath]
    : ['--dev', '--showcase', showcase, '--home', home, '--port', String(port), '--dev-port', String(devPort), '--profile', profile, '--summary-json', launchSummaryPath];
  if (fmUp && !fmUp.showcase) {
    log(`a Foreman on :${port} is running but is not holding a showcase (backend ${fmUp.backend}); stop it or pick another --port`);
    process.exit(1);
  }
  // something answering that launch.ps1 did not start (or another profile): use it as-is
  if (gameUp) log(`a game already answers on DevBridge :${devPort}; using it`);
  log(`launching: ${windows ? 'tools/launch.ps1' : 'tools/launch-unix.mjs launch'} ${args.join(' ')}${gameUp ? ' (game already running)' : ''}`);
  launchCode = await runLauncher('launch', gameUp ? [...args, windows ? '-NoGame' : '--no-game'] : args, path.join(outDir, 'launch.log'));
  try { launch = JSON.parse(fs.readFileSync(launchSummaryPath, 'utf8').replace(/^\uFEFF/, '')); } catch {}
  if (launchCode !== 0) {
    log(`launcher failed (exit ${launchCode}); see ${path.join(outDir, 'launch.log')}`);
    await shutdown();
    writeManifest({ ok: false, error: `launcher failed (exit ${launchCode})`, shots: [] });
    process.exit(1);
  }
}

async function shutdown() {
  if (opt.keep || opt['no-launch'] || !launch) return null;
  const startedGame = launch.game?.started;
  const startedFm = launch.foreman?.started;
  if (!startedGame && !startedFm) return null;
  log(`stopping what this run started (${[startedGame && 'game', startedFm && 'Foreman'].filter(Boolean).join(' + ')})`);
  const windows = process.platform === 'win32';
  const args = windows
    ? ['-FromSummary', launchSummaryPath]
    : ['--profile', profile, ...(!startedGame ? ['--foreman'] : !startedFm ? ['--game'] : [])];
  if (opt['stop-daemon'] && startedGame) args.push(windows ? '-StopDaemon' : '--stop-daemon');
  const code = await runLauncher('stop', args, path.join(outDir, 'stop.log'));
  return code;
}

// --- shots -------------------------------------------------------------------------------------------
const fmPort = launch?.foreman?.port ?? port;
let dev = null;
let foreman = null;
let summary = null;
let fatal = null;
try {
  dev = await DevClient.connect({ port: devPort, timeoutMs: 60_000, onWait: (ms) => log(`waiting for DevBridge (${Math.round(ms / 1000)}s)...`) });
  const st = await dev.waitInWorld({ timeoutMs: 300_000 });
  env.game = { devPort, minecraft: dev.hello?.minecraft ?? st.minecraft, fps: st.fps, window: st.window ? { width: st.window.renderWidth ?? st.window.width, height: st.window.renderHeight ?? st.window.height, guiScale: st.window.guiScale } : null, foremanLink: st.foreman ?? null };
  try {
    foreman = await ForemanClient.connect({ port: fmPort, timeoutMs: 30_000, client: 'qa' });
    await foreman.waitForState((s) => s.foreman?.showcase === true, { timeoutMs: 240_000, what: 'the showcase hold' });
    env.foreman = { port: fmPort, ...foreman.summary() };
  } catch (e) {
    log(`Foreman: ${e.message}; shots that need it are skipped`);
    env.foreman = { port: fmPort, error: e.message };
    foreman?.close();
    foreman = null;
  }
  summary = await runScene({ scene, dev, foreman, prefix: `qa/${runId}/`, only, log, verbose: !!opt.verbose });
  // The QA world is the checkout's real HQ world (mod/run): put the player and the clock back
  // where they were (shots move the player in spectator and end at night), then hand the view back.
  if (!opt['no-restore'] && !summary.hung && st.player) {
    const p = st.player;
    const r = [];
    if (st.world?.time !== undefined) r.push(await dev.request('dev.time', { ticks: st.world.time }));
    r.push(await dev.request('dev.camera', { x: p.x, y: p.y, z: p.z, yaw: p.yaw, pitch: p.pitch, feet: true, mode: 'creative' }));
    r.push(await dev.request('dev.release', {}));
    const bad = r.filter((x) => !x.ok);
    env.restored = bad.length ? `partly: ${bad.map((x) => x.error).join('; ')}` : `player at ${p.x.toFixed(1)} ${p.y.toFixed(1)} ${p.z.toFixed(1)}, time ${st.world?.time}`;
  }
} catch (e) {
  fatal = e.message;
  log(`FAILED: ${e.message}`);
} finally {
  dev?.close();
  foreman?.close();
}

// --- contact sheet + manifest ------------------------------------------------------------------------
function tileNote(r) {
  if (r.status === 'skipped') return r.reason;
  if (r.status === 'failed') return r.error;
  const bits = [];
  if (r.camera?.source === 'mod') bits.push(`anchor ${r.camera.anchor}`);
  else if (r.camera?.source === 'scene-anchor') bits.push(`scene anchor ${r.camera.anchor}`);
  else if (r.camera?.source === 'fallback-camera') bits.push('fallback camera (no anchor)');
  else bits.push('raw camera');
  if (r.warnings?.length) bits.push(...r.warnings.filter((w) => !/not available; used the fallback camera/.test(w)));
  return bits.join(' | ');
}

// Re-shooting a subset into an existing run (--run-id X --only a,b) keeps that run's other shots.
let previous = null;
if (summary && only) {
  try { previous = JSON.parse(fs.readFileSync(path.join(outDir, 'manifest.json'), 'utf8')); } catch {}
}
if (summary && previous?.shots?.length) {
  const fresh = new Map(summary.shots.map((r) => [r.name, r]));
  const old = new Map(previous.shots.map((r) => [r.name, { ...r, carriedOver: true }]));
  const names = [...new Set([...scene.shots.map((s) => s.name), ...old.keys(), ...fresh.keys()])];
  summary.shots = names.map((n) => fresh.get(n) ?? old.get(n)).filter(Boolean);
  summary.counts = { ok: 0, skipped: 0, failed: 0 };
  for (const r of summary.shots) summary.counts[r.status]++;
  summary.ok = !summary.hung && !summary.setupFailed && summary.counts.failed === 0;
  log(`merged with the earlier shots of run ${runId} (${old.size} before, ${fresh.size} re-shot)`);
}
if (summary) {
  const tiles = summary.shots.map((r) => {
    const t = { name: r.name, title: scene.shots.find((s) => s.name === r.name)?.title, path: r.path, status: r.status, note: tileNote(r) };
    if (r.status === 'ok' && r.camera?.source === 'mod' && !r.warnings?.length) t.noteColor = COLORS.dim;
    return t;
  });
  const c = summary.counts;
  sheet = contactSheet({
    out: path.join(outDir, 'contact_sheet.png'),
    title: `AgentCraft QA ${runId}`,
    subtitle: `${gitInfo.branch ?? '?'}@${gitInfo.sha ?? '?'}${gitInfo.dirty ? '+dirty' : ''}  showcase ${showcase}  ok ${c.ok}  skipped ${c.skipped}  failed ${c.failed}  anchors ${summary.anchors.available ? summary.anchors.names.length : 'n/a'}`,
    tiles,
    columns: Number(opt.columns ?? 3),
  });
}

function writeManifest(extra) {
  const m = {
    runId,
    createdAt: new Date().toISOString(),
    totalMs: Date.now() - t0,
    rubric: 'docs/visual-bar.md (Review rubric: score 1-10 per axis, ship only if all >= 8)',
    checkout: ROOT,
    git: gitInfo,
    scene: scenePath,
    showcase,
    profile,
    home,
    strict: !!opt.strict,
    ...env,
    launch,
    ...extra,
  };
  fs.writeFileSync(path.join(outDir, 'manifest.json'), JSON.stringify(m, null, 2));
  fs.writeFileSync(path.join(ROOT, 'artifacts', 'shots', 'qa', 'latest.json'), JSON.stringify({ runId, dir: outDir, manifest: path.join(outDir, 'manifest.json'), contactSheet: sheet?.path ?? null }, null, 2));
  return m;
}

const stopCode = await shutdown();
let ok = !fatal && !!summary && summary.ok;
if (opt.strict && summary?.counts.skipped) ok = false;
const manifest = writeManifest({
  ok,
  error: fatal ?? undefined,
  stopped: stopCode === null ? 'nothing (reused or --keep)' : stopCode === 0 ? 'ok' : `launcher stop exit ${stopCode}`,
  anchors: summary?.anchors,
  counts: summary?.counts,
  hung: summary?.hung ?? null,
  setupFailed: summary?.setupFailed ?? null,
  contactSheet: sheet?.path ?? null,
  shots: (summary?.shots ?? []).map((r) => {
    const title = scene.shots.find((s) => s.name === r.name)?.title;
    return { ...r, title, file: r.path ? path.relative(outDir, r.path) : r.file };
  }),
});
console.log(JSON.stringify({ ok, runId, dir: outDir, contactSheet: manifest.contactSheet, manifest: path.join(outDir, 'manifest.json'), counts: manifest.counts, stopped: manifest.stopped, error: manifest.error }, null, 2));
process.exit(ok ? 0 : 1);
