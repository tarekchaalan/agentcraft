#!/usr/bin/env node
// Scene screenshot runner: drives the DevBridge through a list of shots and writes PNGs to
// artifacts/shots/ (or AGENTCRAFT_SHOTS_DIR). The game must be running (tools\launch.ps1 or
// gradlew runClient). Scene format: docs/QA.md ("Scene format"); runner: tools/lib/scene.mjs.
//
//   node tools/capture-screenshots.mjs tools/scenes/qa.json [--only a,b] [--port N] [--manifest out.json]
//        [--prefix qa/run1/] [--foreman [PORT]] [--anchors anchors.json] [--release] [--strict] [--verbose]
//
//   --prefix P      shot names are prefixed (names may contain '/', e.g. qa/<runId>/)
//   --foreman [N]   connect to the Foreman (default AGENTCRAFT_PORT or 7878) for scenes that send
//                   Foreman messages or use {{merge.repoId}}-style templates; also implied when the
//                   scene has "foreman": {"connect": true}
//   --anchors F     JSON {name:{x,y,z,yaw,pitch}} that wins over the mod's dev.anchors
//   --release       dev.release at the end (FOV pin off, HUD on, creative) to hand the view back
//   --strict        skipped shots count as failures (exit 1)
//
// Prints a JSON summary; exit code 1 if a shot failed (or was skipped with --strict). Stops early
// (summary.hung) if the game hangs.

import fs from 'node:fs';
import { DevClient, DEFAULT_PORT } from './lib/devclient.mjs';
import { ForemanClient, DEFAULT_FOREMAN_PORT } from './lib/foremanclient.mjs';
import { loadScene, runScene } from './lib/scene.mjs';

const argv = process.argv.slice(2);
let sceneFile = null;
let only = null;
let port = DEFAULT_PORT;
let manifest = null;
let prefix = '';
let foremanPort = null;
let anchorsFile = null;
for (let i = 0; i < argv.length; i++) {
  const a = argv[i];
  if (a === '--only') only = new Set(argv[++i].split(',').map((s) => s.trim()).filter(Boolean));
  else if (a === '--port') port = Number(argv[++i]);
  else if (a === '--manifest') manifest = argv[++i];
  else if (a === '--prefix') prefix = argv[++i];
  else if (a === '--anchors') anchorsFile = argv[++i];
  else if (a === '--foreman') foremanPort = argv[i + 1] && /^\d+$/.test(argv[i + 1]) ? Number(argv[++i]) : DEFAULT_FOREMAN_PORT;
  else if (!a.startsWith('--') && !sceneFile) sceneFile = a;
}
const flag = (f) => argv.includes(f);
if (!sceneFile) {
  console.error('usage: node tools/capture-screenshots.mjs <scene.json> [--only a,b] [--port N] [--manifest out.json] [--prefix p/] [--foreman [N]] [--anchors f.json] [--release] [--strict]');
  process.exit(2);
}

const scene = loadScene(sceneFile);
const log = (m) => process.stderr.write(`[capture-screenshots] ${m}\n`);
if (foremanPort === null && scene.foreman?.connect) foremanPort = scene.foreman.port ?? DEFAULT_FOREMAN_PORT;

let dev;
try {
  dev = await DevClient.connect({ port, timeoutMs: 120_000, onWait: (ms) => log(`waiting for DevBridge (${Math.round(ms / 1000)}s)...`) });
  await dev.waitInWorld({ timeoutMs: 300_000, onWait: (ms) => log(`waiting for world (${Math.round(ms / 1000)}s)...`) });
} catch (e) {
  dev?.close();
  log(`cannot start: ${e.message}`);
  console.log(JSON.stringify({ ok: false, scene: scene.file, hung: e.hung ? e.message : null, error: e.message, shots: [] }, null, 2));
  process.exit(1);
}

let foreman = null;
if (foremanPort !== null) {
  try {
    foreman = await ForemanClient.connect({ port: foremanPort, timeoutMs: 20_000, client: 'shoot' });
    log(`Foreman: ${foreman.url} (${foreman.state.foreman?.backend ?? '?'}${foreman.state.foreman?.showcase ? ', showcase' : ''})`);
  } catch (e) {
    log(`Foreman not connected (${e.message}); shots that need it are skipped`);
  }
}

let summary;
try {
  const anchorsOverride = anchorsFile ? JSON.parse(fs.readFileSync(anchorsFile, 'utf8')) : undefined;
  summary = await runScene({ scene, dev, foreman, prefix, only, anchorsOverride, log, verbose: flag('--verbose') });
  if (flag('--release') && !summary.hung) await dev.request('dev.release', {}).catch(() => {});
} finally {
  dev.close();
  foreman?.close();
}
if (flag('--strict') && summary.counts.skipped) summary.ok = false;
if (manifest) fs.writeFileSync(manifest, JSON.stringify(summary, null, 2));
console.log(JSON.stringify(summary, null, 2));
process.exit(summary.ok ? 0 : 1);
