#!/usr/bin/env node
// DevBridge CLI. Talks to the running game (mod DevBridge, ws://127.0.0.1:${AGENTCRAFT_DEV_PORT:-7879}).
//
//   node tools/devbridge-cli.mjs wait                      wait for bridge + world (default 300s)
//   node tools/devbridge-cli.mjs state
//   node tools/devbridge-cli.mjs camera x y z yaw pitch [fov] [--mode creative|spectator|keep] [--feet]
//   node tools/devbridge-cli.mjs camera x y z [fov] --look tx,ty,tz      aim the eye at a point
//   node tools/devbridge-cli.mjs shot name [--hud] [--frames n] [--no-wait-chunks]
//   node tools/devbridge-cli.mjs cmd "/time set 6000"
//   node tools/devbridge-cli.mjs time 11000 | weather clear|rain|thunder | hud on|off
//   node tools/devbridge-cli.mjs screen pause|chat|inventory|options|title|<registered>|null
//   node tools/devbridge-cli.mjs key escape | type "hello" | waitchunks [timeoutMs] | ping | help
//   node tools/devbridge-cli.mjs release                   hand the view back (FOV pin off, HUD on, creative)
//   node tools/devbridge-cli.mjs raw '{"type":"dev.state"}'
//   node tools/devbridge-cli.mjs quit                      save + quit; waits for the game to close the bridge
//
// Options: --port N, --timeout SECONDS (bridge connect timeout; default 30, `wait` default 300).
// Prints the JSON response; exit code 0 when ok, 1 on error.

import { DevClient, DEFAULT_PORT } from './lib/devclient.mjs';

function usage(code = 2) {
  console.error(`usage: node tools/devbridge-cli.mjs <command> [args]
  wait [--bridge-only]          wait for the bridge (and the world unless --bridge-only)
  state | ping | help          ping also reports stalled/msSinceLastFrame (works when the game is hung)
  camera x y z yaw pitch [fov] [--mode spectator|creative|keep] [--feet] [--show-hud]
  camera x y z [fov] --look tx,ty,tz   (eye looks at the target point)
  shot name [--hud] [--frames n] [--no-wait-chunks]
  cmd "<command>"               e.g. cmd "/fill 0 65 0 4 69 4 oak_planks"
  time <ticks> | weather clear|rain|thunder | hud on|off
  screen <name>|null | key <key> | type "<text>" | waitchunks [timeoutMs]
  release [--mode keep]         hand the view back: FOV pin off, HUD on, spectator -> creative
  raw '<json>'                  send an arbitrary request
  quit                          save and quit the game
options: --port N (default ${DEFAULT_PORT}), --timeout SECONDS (connect timeout)`);
  process.exit(code);
}

const argv = process.argv.slice(2);
const flags = {};
const pos = [];
for (let i = 0; i < argv.length; i++) {
  const a = argv[i];
  if (a.startsWith('--')) {
    const key = a.slice(2);
    const next = argv[i + 1];
    if (['port', 'timeout', 'frames', 'mode', 'look'].includes(key)) {
      flags[key] = next;
      i++;
    } else {
      flags[key] = true;
    }
  } else {
    pos.push(a);
  }
}
const cmd = pos.shift();
if (!cmd || cmd === '-h' || flags.help) usage(cmd ? 0 : 2);

const port = flags.port ? Number(flags.port) : DEFAULT_PORT;
const num = (v, name) => {
  const n = Number(v);
  if (v === undefined || Number.isNaN(n)) {
    console.error(`expected a number for ${name}, got ${v}`);
    process.exit(2);
  }
  return n;
};

const isWait = cmd === 'wait';
const connectTimeoutMs = (flags.timeout ? Number(flags.timeout) : isWait ? 300 : 30) * 1000;
const log = (m) => process.stderr.write(`[devbridge-cli] ${m}\n`);

let dev;
try {
  dev = await DevClient.connect({
    port,
    timeoutMs: connectTimeoutMs,
    onWait: (ms) => log(`waiting for DevBridge on :${port} (${Math.round(ms / 1000)}s)...`),
  });
} catch (e) {
  console.log(JSON.stringify({ ok: false, error: e.message }, null, 2));
  process.exit(1);
}

let res;
try {
  switch (cmd) {
    case 'wait': {
      if (flags['bridge-only']) {
        res = { ok: true, bridge: true, hello: dev.hello };
      } else {
        const st = await dev.waitInWorld({
          timeoutMs: connectTimeoutMs,
          onWait: (ms, s) => log(`waiting for world (${Math.round(ms / 1000)}s, screen=${s?.screen?.class ?? 'none'})...`),
        });
        res = st;
      }
      break;
    }
    case 'state': res = await dev.request('dev.state'); break;
    case 'ping': res = await dev.request('dev.ping'); break;
    case 'help': res = await dev.request('dev.help'); break;
    case 'camera': {
      const look = flags.look ? String(flags.look).split(',').map((v, i) => num(v, 'look[' + i + ']')) : null;
      if (pos.length < (look ? 3 : 5)) usage();
      const payload = { x: num(pos[0], 'x'), y: num(pos[1], 'y'), z: num(pos[2], 'z') };
      if (look) {
        payload.lookAt = { x: look[0], y: look[1], z: look[2] };
        if (pos[3] !== undefined) payload.fov = num(pos[3], 'fov');
      } else {
        payload.yaw = num(pos[3], 'yaw');
        payload.pitch = num(pos[4], 'pitch');
        if (pos[5] !== undefined) payload.fov = num(pos[5], 'fov');
      }
      if (flags.mode) payload.mode = flags.mode;
      if (flags.feet) payload.feet = true;
      if (flags['show-hud']) payload.hideHud = false;
      res = await dev.request('dev.camera', payload);
      break;
    }
    case 'shot':
    case 'screenshot': {
      if (!pos[0]) usage();
      const payload = { name: pos[0], hideHud: !flags.hud };
      if (flags.frames) payload.frames = num(flags.frames, 'frames');
      if (flags['no-wait-chunks']) payload.waitChunks = false;
      res = await dev.request('dev.screenshot', payload);
      break;
    }
    case 'cmd':
    case 'command': {
      if (!pos.length) usage();
      res = await dev.request('dev.command', { cmd: pos.join(' ') });
      break;
    }
    case 'time': res = await dev.request('dev.time', { ticks: num(pos[0], 'ticks') }); break;
    case 'weather': res = await dev.request('dev.weather', { weather: pos[0] ?? 'clear' }); break;
    case 'hud': res = await dev.request('dev.hud', { hidden: !['on', 'show', 'true', '1'].includes(String(pos[0] ?? 'off')) }); break;
    case 'screen': res = await dev.request('dev.screen', { open: !pos[0] || pos[0] === 'null' ? null : pos[0] }); break;
    case 'key': res = await dev.request('dev.key', pos[0]?.startsWith('key.') && !pos[0].startsWith('key.keyboard.') && !pos[0].startsWith('key.mouse.') ? { mapping: pos[0] } : { key: pos[0] }); break;
    case 'type': res = await dev.request('dev.type', { text: pos.join(' ') }); break;
    case 'release': res = await dev.request('dev.release', flags.mode ? { mode: flags.mode } : {}); break;
    case 'waitchunks': res = await dev.request('dev.waitChunks', pos[0] ? { timeoutMs: num(pos[0], 'timeoutMs') } : {}); break;
    case 'raw': {
      const obj = JSON.parse(pos.join(' '));
      const { type, ...rest } = obj;
      res = await dev.request(type, rest);
      break;
    }
    case 'quit': {
      res = await dev.request('dev.quit');
      if (res.ok) {
        const closed = await dev.waitClosed(90_000);
        res.bridgeClosed = closed;
      }
      break;
    }
    default:
      console.error(`unknown command: ${cmd}`);
      usage();
  }
} catch (e) {
  res = { ok: false, error: e.message };
}

console.log(JSON.stringify(res, null, 2));
dev.close();
process.exit(res && res.ok ? 0 : 1);
