// Scene runner shared by capture-screenshots.mjs and run-screenshot-qa.mjs: drives the DevBridge (and optionally the Foreman)
// through a list of shots. Scene format: docs/QA.md ("Scene format").
//
//   import { loadScene, runScene } from './lib/scene.mjs';
//   const summary = await runScene({ scene: loadScene(file), dev, foreman, prefix: 'qa/run1/' });
//
// Per shot: commands -> dev requests -> Foreman messages -> time/weather -> camera (anchor, scene
// anchor, or raw camera) -> screen (+ type/keys) -> waits -> screenshot -> close the screen.
// A shot is `skipped` (with a reason) when something it needs is missing: an anchor nobody
// provides, a screen the mod does not register, a Foreman entity a template refers to.
// It `failed` when a request errors for any other reason.

import fs from 'node:fs';
import path from 'node:path';

export function loadScene(file) {
  const raw = JSON.parse(fs.readFileSync(file, 'utf8').replace(/^﻿/, ''));
  const scene = Array.isArray(raw) ? { shots: raw } : raw;
  scene.file = path.resolve(file);
  return scene;
}

class SkipShot extends Error {
  constructor(reason) {
    super(reason);
    this.skip = true;
  }
}

// --- anchors ----------------------------------------------------------------------------------

const isNum = (v) => typeof v === 'number' && Number.isFinite(v);
function validAnchor(a) {
  return a && typeof a === 'object' && isNum(a.x) && isNum(a.y) && isNum(a.z) &&
    ((isNum(a.yaw) && isNum(a.pitch)) || (a.lookAt && isNum(a.lookAt.x) && isNum(a.lookAt.y) && isNum(a.lookAt.z)));
}

/**
 * Ask the mod for its camera anchors (dev.anchors). Accepts {anchors:{name:{x,y,z,yaw,pitch}}}
 * or the names at the top level of the reply. Unknown command -> {available:false}.
 */
export async function fetchAnchors(dev) {
  let res;
  try {
    res = await dev.request('dev.anchors', {}, { timeoutMs: 15_000 });
  } catch (e) {
    return { available: false, anchors: {}, error: e.message };
  }
  if (!res.ok) {
    const unknown = /unknown type/i.test(res.error ?? '');
    return { available: false, anchors: {}, error: unknown ? 'dev.anchors is not implemented by this mod build' : res.error };
  }
  const src = res.anchors && typeof res.anchors === 'object' ? res.anchors : res;
  const anchors = {};
  const invalid = [];
  for (const [k, v] of Object.entries(src)) {
    if (['id', 'type', 'ok'].includes(k)) continue;
    if (validAnchor(v)) anchors[k] = v;
    else if (v && typeof v === 'object') invalid.push(k);
  }
  return { available: true, anchors, invalid };
}

function applyOffset(cam, off) {
  if (!off) return cam;
  const c = { ...cam };
  if (isNum(off.dx)) c.x += off.dx;
  if (isNum(off.dy)) c.y += off.dy;
  if (isNum(off.dz)) c.z += off.dz;
  if (c.lookAt) {
    c.lookAt = { ...c.lookAt };
    if (isNum(off.dx)) c.lookAt.x += off.dx;
    if (isNum(off.dy)) c.lookAt.y += off.dy;
    if (isNum(off.dz)) c.lookAt.z += off.dz;
  }
  if (isNum(off.dyaw) && isNum(c.yaw)) c.yaw += off.dyaw;
  if (isNum(off.dpitch) && isNum(c.pitch)) c.pitch = Math.max(-90, Math.min(90, c.pitch + off.dpitch));
  return c;
}

const CAMERA_KEYS = ['x', 'y', 'z', 'yaw', 'pitch', 'lookAt', 'fov', 'mode', 'feet', 'closePause'];
function pickCamera(obj) {
  const out = {};
  for (const k of CAMERA_KEYS) if (obj[k] !== undefined) out[k] = obj[k];
  return out;
}

/**
 * Resolve where the camera goes for a shot.
 * Order: each name in shot.anchor (mod's dev.anchors, then the scene's fallback table), then the
 * raw shot.camera. Returns {camera, source, anchor, note} or {camera:null, missing:[names]}.
 */
export function resolveCamera(s, modAnchors, sceneAnchors, overrideAnchors) {
  const names = s.anchor === undefined ? [] : Array.isArray(s.anchor) ? s.anchor : [s.anchor];
  const tried = [];
  for (const name of names) {
    if (overrideAnchors?.[name] && validAnchor(overrideAnchors[name])) return { camera: pickCamera(overrideAnchors[name]), source: 'override', anchor: name, tried };
    if (modAnchors?.[name]) return { camera: pickCamera(modAnchors[name]), source: 'mod', anchor: name, tried };
    if (sceneAnchors?.[name] && validAnchor(sceneAnchors[name])) return { camera: pickCamera(sceneAnchors[name]), source: 'scene-anchor', anchor: name, tried };
    tried.push(name);
  }
  if (s.camera && s.camera.x !== undefined) return { camera: pickCamera(s.camera), source: names.length ? 'fallback-camera' : 'camera', anchor: null, tried };
  return { camera: null, missing: tried };
}

// --- templates ----------------------------------------------------------------------------------

/** Values a scene can reference as "{{merge.repoId}}" etc. (null without a Foreman). */
export function templateContext(foreman, extra = {}) {
  if (!foreman) return { ...extra };
  const st = foreman.state;
  const open = foreman.openDecisions();
  const byId = (m) => Object.fromEntries([...m.entries()]);
  return {
    merge: open.find((d) => d.kind === 'merge') ?? null,
    question: open.find((d) => d.kind === 'question') ?? null,
    permission: open.find((d) => d.kind === 'permission') ?? null,
    decision: open[0] ?? null,
    repo: [...st.repos.values()][0] ?? null,
    goal: st.goal ?? null,
    foreman: st.foreman ?? null,
    agents: byId(st.agents),
    tasks: byId(st.tasks),
    decisions: byId(st.decisions),
    repos: byId(st.repos),
    ...extra,
  };
}

function lookup(ctx, expr) {
  let cur = ctx;
  for (const part of expr.split('.')) {
    if (cur == null || typeof cur !== 'object' || !(part in cur)) return undefined;
    cur = cur[part];
  }
  return cur;
}

/** Deep-replace "{{a.b}}" in strings. A whole-string template keeps the value's type. */
export function resolveTemplates(value, ctx, { foreman } = {}) {
  if (typeof value === 'string') {
    const whole = value.match(/^\{\{\s*([\w.-]+)\s*\}\}$/);
    if (whole) {
      const v = lookup(ctx, whole[1]);
      if (v === undefined || v === null) throw new SkipShot(templateMissing(whole[1], foreman));
      return v;
    }
    return value.replace(/\{\{\s*([\w.-]+)\s*\}\}/g, (_m, expr) => {
      const v = lookup(ctx, expr);
      if (v === undefined || v === null) throw new SkipShot(templateMissing(expr, foreman));
      return String(v);
    });
  }
  if (Array.isArray(value)) return value.map((v) => resolveTemplates(v, ctx, { foreman }));
  if (value && typeof value === 'object') {
    const out = {};
    for (const [k, v] of Object.entries(value)) out[k] = resolveTemplates(v, ctx, { foreman });
    return out;
  }
  return value;
}

function templateMissing(expr, foreman) {
  if (!foreman) return `needs {{${expr}}} but no Foreman is connected`;
  const head = expr.split('.')[0];
  if (['merge', 'question', 'permission', 'decision'].includes(head)) return `needs {{${expr}}} but the Foreman has no open ${head === 'decision' ? '' : head + ' '}decision`;
  return `needs {{${expr}}} but the Foreman state has no such value`;
}

// --- state conditions ------------------------------------------------------------------------------

function checkCond(actual, cond) {
  if ('equals' in cond) return JSON.stringify(actual) === JSON.stringify(cond.equals);
  if ('notEquals' in cond) return JSON.stringify(actual) !== JSON.stringify(cond.notEquals);
  if ('gte' in cond) return typeof actual === 'number' && actual >= cond.gte;
  if ('lte' in cond) return typeof actual === 'number' && actual <= cond.lte;
  if ('exists' in cond) return (actual !== undefined && actual !== null) === !!cond.exists;
  return !!actual;
}

// --- the runner ----------------------------------------------------------------------------------

/**
 * @param {object} o
 * @param {object} o.scene            loadScene() result
 * @param {import('./devclient.mjs').DevClient} o.dev
 * @param {import('./foremanclient.mjs').ForemanClient|null} [o.foreman]
 * @param {string} [o.prefix]          screenshot name prefix, e.g. "qa/<runId>/" (names may contain /)
 * @param {Set<string>|null} [o.only]
 * @param {object} [o.anchorsOverride] extra anchor table that wins over the mod's
 * @param {(m:string)=>void} [o.log]
 * @param {boolean} [o.verbose]
 */
export async function runScene(o) {
  const { scene, dev } = o;
  const foreman = o.foreman ?? null;
  const prefix = o.prefix ?? '';
  const log = o.log ?? ((m) => process.stderr.write(`[scene] ${m}\n`));
  const defaults = scene.defaults ?? {};
  const shots = (scene.shots ?? []).filter((s) => !o.only || o.only.has(s.name));
  const started = Date.now();

  const anchorInfo = await fetchAnchors(dev);
  const modAnchors = anchorInfo.anchors;
  const overrideAnchors = o.anchorsOverride ?? null;
  if (!anchorInfo.available) log(`anchors: ${anchorInfo.error}; using scene fallbacks where given`);
  else log(`anchors: ${Object.keys(anchorInfo.anchors).length} from the mod (${Object.keys(anchorInfo.anchors).sort().join(', ') || 'none'})`);
  const sceneAnchors = scene.anchors ?? {};

  async function run(type, payload, opts) {
    const res = await dev.request(type, payload, opts);
    if (!res.ok) {
      const err = new Error(`${type} ${JSON.stringify(payload)}: ${res.error}`);
      err.stalled = !!res.stalled;
      err.devError = res.error;
      throw err;
    }
    return res;
  }

  async function runCommands(cmds, label) {
    if (!cmds?.length) return 0;
    let failed = 0;
    for (const cmd of cmds) {
      const r = await run('dev.command', { cmd });
      if (r.success === false) {
        failed++;
        if (o.verbose) log(`  ${cmd} -> ${r.messages?.join(' | ')}`);
      }
    }
    if (failed) log(`${label}: ${failed}/${cmds.length} commands reported failure or no change${o.verbose ? '' : ' (--verbose for details)'}`);
    return failed;
  }

  async function runDev(reqs, ctx) {
    const out = [];
    for (const r of reqs ?? []) {
      const { type, expectOk = true, ...payload } = resolveTemplates(r, ctx, { foreman });
      const res = await dev.request(type, payload);
      if (!res.ok && expectOk) {
        const err = new Error(`${type}: ${res.error}`);
        err.stalled = !!res.stalled;
        err.devError = res.error;
        throw err;
      }
      out.push({ type, ok: res.ok, error: res.ok ? undefined : res.error });
    }
    return out;
  }

  // Foreman messages: [{type, ...payload, expect?: "ack"|"diff"|"none", minFiles?, timeoutMs?}]
  async function runForeman(msgs, ctx) {
    const out = [];
    if (!msgs?.length) return out;
    if (!foreman) throw new SkipShot('sends Foreman messages but no Foreman is connected');
    for (const m of msgs) {
      const { type, expect, minFiles, timeoutMs, optional, ...payload } = resolveTemplates(m, ctx, { foreman });
      try {
        if (type === 'diff.request' || expect === 'diff') {
          const d = await foreman.diff(payload.repoId, payload.worktree, { timeoutMs: timeoutMs ?? 30_000 });
          const entry = { type, repoId: d.repoId, worktree: d.worktree, branch: d.branch, stats: d.stats, files: d.files.map((f) => `${f.status} ${f.path} +${f.additions} -${f.deletions}`) };
          if (minFiles && d.files.length < minFiles) throw new Error(`diff ${d.repoId}/${d.worktree} has ${d.files.length} files (< ${minFiles})`);
          out.push(entry);
        } else {
          const ack = await foreman.send(type, payload, { ack: expect !== 'none', timeoutMs: timeoutMs ?? 15_000 });
          out.push({ type, ok: true, result: ack?.result });
        }
      } catch (e) {
        if (optional) out.push({ type, ok: false, error: e.message });
        else throw e;
      }
    }
    return out;
  }

  async function waitForState(cond, label) {
    if (!cond) return null;
    const conds = Array.isArray(cond) ? cond : [cond];
    const notes = [];
    for (const c of conds) {
      const timeoutMs = c.timeoutMs ?? 30_000;
      const t0 = Date.now();
      let actual;
      let seen = false;
      while (true) {
        if (c.foreman) {
          if (!foreman) { notes.push(`${c.foreman}: no Foreman connected`); break; }
          actual = lookup(templateContext(foreman), c.foreman);
        } else {
          const st = await run('dev.state', {});
          actual = lookup(st, c.path ?? c.state);
        }
        if (actual !== undefined) seen = true;
        if (checkCond(actual, c)) break;
        const p = c.foreman ?? c.path ?? c.state;
        // a dev.state path this mod build never reports (older build): don't wait the full timeout
        const unreported = !c.foreman && !seen && c.optional !== false;
        if (unreported && Date.now() - t0 >= (c.unreportedMs ?? 5_000)) {
          notes.push(`${p} not reported by this mod build (wait skipped)`);
          break;
        }
        if (Date.now() - t0 >= timeoutMs) {
          const msg = `${label}: ${p} is ${JSON.stringify(actual)} after ${timeoutMs} ms`;
          if (c.required) throw new Error(msg);
          notes.push(msg);
          break;
        }
        await new Promise((r) => setTimeout(r, c.pollMs ?? 500));
      }
    }
    return notes;
  }

  const results = [];
  let hung = null;
  let setupFailed = null;
  try {
    const sctx = templateContext(foreman);
    await runCommands(scene.setup, 'setup');
    await runDev(scene.setupDev, sctx);
    if (scene.setupForeman?.length) {
      if (foreman) await runForeman(scene.setupForeman, sctx);
      else log('setupForeman skipped: no Foreman connected');
    }
    const setupWait = await waitForState(scene.waitFor, 'setup');
    if (setupWait?.length) log(`setup wait: ${setupWait.join('; ')}`);
  } catch (e) {
    setupFailed = e.message;
    log(`setup FAILED: ${e.message}`);
  }

  let lastTime = null;
  let lastWeather = null;
  for (const shot of setupFailed ? [] : shots) {
    const t0 = Date.now();
    const s = { ...defaults, ...shot };
    if (shot.camera && defaults.camera) s.camera = { ...defaults.camera, ...shot.camera };
    else if (!shot.camera && shot.anchor !== undefined) delete s.camera;   // a default camera is not a fallback
    const entry = { name: s.name, title: s.title, status: 'failed', ok: false, warnings: [] };
    let screenOpened = false;
    try {
      if (s.skip) throw new SkipShot(typeof s.skip === 'string' ? s.skip : 'marked skip in the scene');
      const ctx = templateContext(foreman, { shot: { name: s.name } });
      if (s.requires?.foreman && !foreman) throw new SkipShot('needs a Foreman connection');

      // camera first (decides skip before anything changes the world)
      const cam = resolveCamera(s, modAnchors, sceneAnchors, overrideAnchors);
      if (!cam.camera) {
        const why = anchorInfo.available ? 'the mod has no such anchor' : anchorInfo.error;
        throw new SkipShot(`no camera: anchor ${cam.missing.map((n) => `'${n}'`).join(' / ')} not available (${why}) and the shot has no fallback camera`);
      }
      let camera = applyOffset(cam.camera, s.offset);
      if (s.fov !== undefined && camera.fov === undefined) camera.fov = s.fov;
      if (s.cameraFov !== undefined) camera.fov = s.cameraFov;
      if (s.mode !== undefined && camera.mode === undefined) camera.mode = s.mode;
      entry.camera = { source: cam.source, anchor: cam.anchor ?? undefined, ...camera };
      if (cam.tried?.length) entry.warnings.push(`anchor ${cam.tried.join(' / ')} not available; used ${cam.source === 'fallback-camera' ? 'the fallback camera' : cam.source}`);
      if (cam.source === 'scene-anchor') entry.warnings.push(`anchor '${cam.anchor}' came from the scene's fallback table, not the mod`);

      await runCommands(resolveTemplates(s.commands, ctx, { foreman }), s.name);
      entry.dev = await runDev(s.dev, ctx);
      entry.foreman = await runForeman(s.foreman, ctx);
      if (!entry.dev.length) delete entry.dev;
      if (!entry.foreman.length) delete entry.foreman;

      if (s.time !== undefined && s.time !== lastTime) {
        await run('dev.time', { ticks: s.time });
        lastTime = s.time;
      }
      if (s.weather !== undefined && s.weather !== lastWeather) {
        await run('dev.weather', { weather: s.weather });
        lastWeather = s.weather;
      }
      await run('dev.camera', camera);

      if (s.screen !== undefined && s.screen !== null) {
        const req = typeof s.screen === 'string' ? { open: s.screen } : resolveTemplates(s.screen, ctx, { foreman });
        const res = await dev.request('dev.screen', req);
        if (!res.ok) {
          if (/unknown screen/i.test(res.error ?? '') && (s.onMissingScreen ?? 'skip') === 'skip') {
            throw new SkipShot(`screen '${req.open}' is not registered by this mod build`);
          }
          throw Object.assign(new Error(`dev.screen ${JSON.stringify(req)}: ${res.error}`), { stalled: !!res.stalled });
        }
        screenOpened = true;
        entry.screen = res.screen ?? req.open;
        if (s.type) await run('dev.wait', { frames: 2 });
      }
      if (s.type) await run('dev.type', { text: resolveTemplates(s.type, ctx, { foreman }) });
      for (const k of s.keys ?? []) await run('dev.key', typeof k === 'string' ? (k.startsWith('key.') && !k.startsWith('key.keyboard.') && !k.startsWith('key.mouse.') ? { mapping: k } : { key: k }) : k);

      const waitNotes = await waitForState(s.waitFor, s.name);
      if (waitNotes?.length) entry.warnings.push(...waitNotes);
      const waitMs = s.waitMs ?? s.delayMs ?? 0;
      if (waitMs || s.waitFrames) await run('dev.wait', { ...(waitMs ? { ms: waitMs } : {}), ...(s.waitFrames ? { frames: s.waitFrames } : {}) });

      const shotRes = await run('dev.screenshot', {
        name: prefix + s.name,
        hideHud: s.hideHud ?? true,
        ...(s.frames !== undefined ? { frames: s.frames } : {}),
        ...(s.waitChunks !== undefined ? { waitChunks: s.waitChunks } : {}),
        ...(s.chunkTimeoutMs !== undefined ? { chunkTimeoutMs: s.chunkTimeoutMs } : {}),
      });
      Object.assign(entry, { status: 'ok', ok: true, path: shotRes.path, width: shotRes.width, height: shotRes.height, stats: shotRes.stats });
      if (shotRes.chunksTimedOut) entry.warnings.push('chunks did not finish building before the capture');
      if (shotRes.paused) entry.warnings.push('the game was paused during the capture');
      if (shotRes.stats && shotRes.stats.meanLuma < 6) entry.warnings.push('image is nearly black');
      if (shotRes.stats && shotRes.stats.stdLuma < 2) entry.warnings.push('image is nearly flat (one color)');
      log(`${s.name}: ${shotRes.path} (${cam.source}${cam.anchor ? ' ' + cam.anchor : ''}, luma ${shotRes.stats?.meanLuma})${entry.warnings.length ? ' ! ' + entry.warnings.join('; ') : ''}`);
    } catch (e) {
      if (e.skip) {
        entry.status = 'skipped';
        entry.reason = e.message;
        log(`${s.name}: SKIPPED ${e.message}`);
      } else {
        entry.status = 'failed';
        entry.error = e.message;
        log(`${s.name}: FAILED ${e.message}`);
        const h = await dev.health();
        if (e.stalled || (h.ok && h.stalled) || !h.ok) {
          hung = h.ok ? `render thread stuck for ${h.msSinceLastFrame} ms` : `bridge not answering (${h.error})`;
          log(`aborting: the game looks hung (${hung}); relaunch it (dev.quit force-exits a hung game)`);
        }
      }
    } finally {
      if (screenOpened && !s.keepScreen && !hung) {
        await dev.request('dev.screen', { open: null }).catch(() => {});
      }
    }
    entry.ms = Date.now() - t0;
    if (!entry.warnings.length) delete entry.warnings;
    results.push(entry);
    if (hung) break;
  }

  const counts = { ok: 0, skipped: 0, failed: 0 };
  for (const r of results) counts[r.status]++;
  return {
    ok: !hung && !setupFailed && counts.failed === 0 && results.length === shots.length,
    scene: scene.file,
    totalMs: Date.now() - started,
    hung,
    setupFailed,
    anchors: { available: anchorInfo.available, names: Object.keys(anchorInfo.anchors).sort(), error: anchorInfo.error, invalid: anchorInfo.invalid },
    counts,
    shots: results,
  };
}
