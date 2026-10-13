#!/usr/bin/env node
// Background runner used by tools/launch.ps1:  node tools/lib/bgrun.mjs <spec.json>
//
// spec: {command, args[], cwd, log, errLog?, env?, verbatim?, statusFile}
//
// Why it exists: PowerShell's Start-Process with -RedirectStandardOutput creates the child with
// handle inheritance on, so a long-running Foreman/game would also inherit launch.ps1's own
// stdout/stderr. When those are a pipe (run-screenshot-qa.mjs, an IDE terminal, `| Tee-Object`) the caller then
// hangs until the game exits. launch.ps1 instead starts this script with ShellExecute (no handle
// inheritance, own hidden console) and this script opens the log files and runs the real
// command in the same hidden console. stop.ps1 sends Ctrl+Break to that console: the child
// (the Foreman) shuts down cleanly, this wrapper ignores the signal and exits with the child.
// If the wrapper is killed, libuv's job object takes the direct child with it.

import { spawn } from 'node:child_process';
import fs from 'node:fs';

const specFile = process.argv[2];
const spec = JSON.parse(fs.readFileSync(specFile, 'utf8').replace(/^﻿/, ''));
const status = (o) => {
  try {
    const tmp = `${spec.statusFile}.tmp`;
    fs.writeFileSync(tmp, JSON.stringify({ wrapperPid: process.pid, ...o }, null, 2));
    fs.renameSync(tmp, spec.statusFile);
  } catch {}
};

for (const sig of ['SIGINT', 'SIGBREAK', 'SIGTERM', 'SIGHUP']) {
  try { process.on(sig, () => {}); } catch {}   // the child gets the same console event and exits by itself
}

const out = fs.openSync(spec.log, 'a');
const err = spec.errLog ? fs.openSync(spec.errLog, 'a') : out;
let child;
try {
  child = spawn(spec.command, spec.args ?? [], {
    cwd: spec.cwd,
    env: { ...process.env, ...(spec.env ?? {}) },
    stdio: ['ignore', out, err],
    windowsHide: false,          // share this (already hidden) console, so Ctrl+Break reaches it
    windowsVerbatimArguments: !!spec.verbatim,
  });
} catch (e) {
  status({ error: e.message, exitCode: 1 });
  process.exit(1);
}
child.on('error', (e) => {
  status({ childPid: child.pid ?? null, error: e.message, exitCode: 1 });
  process.exit(1);
});
if (child.pid) status({ childPid: child.pid, startedAt: new Date().toISOString() });
child.on('exit', (code, signal) => {
  status({ childPid: child.pid, exitCode: code, signal, exitedAt: new Date().toISOString() });
  process.exit(code ?? 1);
});
