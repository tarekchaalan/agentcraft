#!/usr/bin/env node
// Shot player for screen recording (OBS): sets up a shot and plays it in real time through the
// mod's dev.play (camera path every frame + timed events). The game must be running
// (tools\launch.ps1 -Dev ...). Shot format and examples: tools/shots/README.md; internals:
// mod/DEV.md "Shot playback".
//
//   node tools/play-camera-shots.mjs tools/shots/hq_orbit.json [more.json ...] [--port N] [--hold MS]
//        [--stills t1,t2,...] [--window WxH] [--no-setup] [--verbose]
//
//   --hold MS       after the setup, keep the first frame of the shot on screen for MS ms before
//                   playing (start the OBS recording in that window); default 0
//   --stills T,...  also save dev.screenshot stills at these times (seconds) during playback, to
//                   artifacts/shots/play/<name>_t<T>.png (a still costs one slow frame; leave it off
//                   for the real take)
//   --window WxH    resize the game window first (e.g. 2560x1440 to fill the monitor for OBS)
//   --no-setup      skip time/weather/commands/camera placement (replay right after a previous take)
//
// Per shot: setup (Foreman checks, time, weather, commands, requests), camera onto the path's
// first pose (dev.camera, spectator, HUD hidden) + chunk wait + agents settled, optional hold,
// then dev.play. Prints a JSON summary (fps, frame times, camera-vs-path error, events).
// Exit code 1 if a shot failed.

import fs from 'node:fs';
import path from 'node:path';
import { DevClient, DEFAULT_PORT } from './lib/devclient.mjs';

const argv = process.argv.slice(2);
const files = [];
const opt = { port: DEFAULT_PORT, hold: 0, stills: [], window: null, setup: true, verbose: false };
for (let i = 0; i < argv.length; i++) {
  const a = argv[i];
  if (a === '--port') opt.port = Number(argv[++i]);
  else if (a === '--hold') opt.hold = Number(argv[++i]);
  else if (a === '--stills') opt.stills = argv[++i].split(',').map(Number).filter((n) => Number.isFinite(n));
  else if (a === '--window') {
    const m = /^(\d+)x(\d+)$/.exec(argv[++i] ?? '');
    if (!m) usage(2);
    opt.window = { width: Number(m[1]), height: Number(m[2]) };
  } else if (a === '--no-setup') opt.setup = false;
  else if (a === '--verbose') opt.verbose = true;
  else if (a === '-h' || a === '--help') usage(0);
  else if (a.startsWith('--')) { console.error(`unknown option ${a}`); usage(2); }
  else files.push(a);
}
if (!files.length) usage(2);

function usage(code) {
  console.error('usage: node tools/play-camera-shots.mjs <shot.json> [more.json ...] [--port N] [--hold MS] [--stills t1,t2] [--window WxH] [--no-setup] [--verbose]');
  process.exit(code);
}

const log = (m) => process.stderr.write(`[play-camera-shots] ${m}\n`);
const vlog = (m) => opt.verbose && log(m);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function call(dev, type, payload = {}, timeoutMs) {
  const res = await dev.request(type, payload, timeoutMs ? { timeoutMs } : {});
  vlog(`${type} -> ${res.ok ? 'ok' : res.error}`);
  if (!res.ok) throw new Error(`${type}: ${res.error}`);
  return res;
}

async function waitForeman(dev, f) {
  const deadline = Date.now() + (f.timeoutMs ?? 60_000);
  while (true) {
    const fm = await call(dev, 'dev.foreman'); // link + model summary at the top level of the reply
    const ok = fm.connected && fm.snapshots > 0 && !fm.held && (!f.minAgents || fm.counts?.agents >= f.minAgents)
      && (!f.requireShowcase || /showcase/i.test(fm.message ?? ''));
    if (ok) return fm;
    if (Date.now() > deadline) {
      throw new Error(`Foreman not ready: ${JSON.stringify({ connected: fm.connected, held: fm.held, agents: fm.counts?.agents, message: fm.message })}`);
    }
    await sleep(500);
  }
}

async function setupShot(dev, shot) {
  const setup = shot.setup ?? {};
  if (setup.foreman) {
    const fm = await waitForeman(dev, setup.foreman);
    vlog(`Foreman ok: ${fm.counts?.agents} agents, ${fm.counts?.tasks} tasks (${fm.message ?? ''})`);
  }
  if (setup.closeScreen !== false) await call(dev, 'dev.screen', { open: null });
  if (setup.time !== undefined) await call(dev, 'dev.time', { ticks: setup.time });
  if (setup.weather) await call(dev, 'dev.weather', { weather: setup.weather });
  for (const c of setup.commands ?? []) await call(dev, 'dev.command', { cmd: c });
  for (const r of setup.requests ?? []) {
    const { type, ...rest } = r;
    await call(dev, type, rest, 120_000);
  }
  // The camera onto the first pose for real (server-side teleport, spectator: no hand, no collisions), chunks loaded there.
  const pose = (await call(dev, 'dev.play.pose', { camera: shot.camera, t: 0, duration: shot.duration })).pose;
  await call(dev, 'dev.camera', { x: pose.x, y: pose.y, z: pose.z, yaw: pose.yaw, pitch: Math.max(-90, Math.min(90, pose.pitch)), fov: pose.fov, mode: 'spectator', hideHud: !shot.showHud });
  await call(dev, 'dev.waitChunks', { timeoutMs: 60_000 }, 70_000);
  if (setup.settleAgents !== false) await call(dev, 'dev.agents', { settle: true });
  if (setup.waitMs) await sleep(setup.waitMs);
}

const PLAY_FIELDS = ['name', 'duration', 'camera', 'timeline', 'showHud', 'holdEndMs', 'foreman', 'log'];

async function playShot(dev, file) {
  const shot = JSON.parse(fs.readFileSync(file, 'utf8'));
  shot.name ??= path.basename(file, '.json');
  if (!(shot.duration > 0)) throw new Error(`${file}: 'duration' (seconds) is required`);
  const t0 = Date.now();
  if (opt.setup) {
    log(`${shot.name}: setup`);
    await setupShot(dev, shot);
  }
  const req = {};
  for (const k of PLAY_FIELDS) if (shot[k] !== undefined) req[k] = shot[k];
  req.timeline = [...(shot.timeline ?? [])];
  // stills alone are no choreography: don't hold the Foreman for them
  if (!shot.timeline?.length && shot.foreman === undefined) req.foreman = { hold: false };
  for (const t of opt.stills) {
    if (t > shot.duration) continue;
    req.timeline.push({ t, cmd: 'dev.screenshot', name: `play/${shot.name}_t${t.toFixed(2)}`, waitChunks: false, frames: 1 });
  }
  if (opt.hold > 0) {
    log(`${shot.name}: holding the first frame for ${opt.hold} ms (start the recorder now)`);
    await sleep(opt.hold);
  }
  log(`${shot.name}: playing ${shot.duration} s`);
  const res = await dev.request('dev.play', req, { timeoutMs: shot.duration * 1000 + 120_000 });
  if (!res.ok) throw new Error(`dev.play: ${res.error}`);
  const { id, type, ok, ...summary } = res;
  const p = summary.perf;
  log(`${shot.name}: ${summary.frames} frames at ${summary.resolution}, ${p.fps} fps (median ${p.frameMsMedian} ms, p99 ${p.frameMsP99} ms, max ${p.frameMsMax} ms, ${p.framesOver20ms} frames > 20 ms)`);
  const failed = summary.events.filter((e) => e.ok === false);
  return {
    ok: failed.length === 0,
    shot: file,
    name: shot.name,
    seconds: Math.round((Date.now() - t0) / 100) / 10,
    stills: opt.stills.filter((t) => t <= shot.duration).map((t) => `artifacts/shots/play/${shot.name}_t${t.toFixed(2)}.png`),
    ...summary,
  };
}

let dev;
try {
  dev = await DevClient.connect({ port: opt.port, timeoutMs: 60_000, onWait: (ms) => log(`waiting for DevBridge on :${opt.port} (${Math.round(ms / 1000)}s)...`) });
  await dev.waitInWorld({ timeoutMs: 300_000, onWait: (ms) => log(`waiting for the world (${Math.round(ms / 1000)}s)...`) });
  if (opt.window) {
    const w = await call(dev, 'dev.window', opt.window);
    log(`window ${w.width}x${w.height}, framebuffer ${w.framebufferWidth}x${w.framebufferHeight}`);
  }
} catch (e) {
  dev?.close();
  console.log(JSON.stringify({ ok: false, error: e.message, shots: [] }, null, 2));
  process.exit(1);
}

const results = [];
for (const f of files) {
  try {
    results.push(await playShot(dev, f));
  } catch (e) {
    log(`${f}: FAILED ${e.message}`);
    results.push({ ok: false, shot: f, error: e.message });
    const h = await dev.health().catch(() => null);
    if (!h?.ok || h.stalled) break;
  }
}
dev.close();
const okAll = results.every((r) => r.ok);
console.log(JSON.stringify({ ok: okAll, shots: results }, null, 2));
process.exit(okAll ? 0 : 1);
