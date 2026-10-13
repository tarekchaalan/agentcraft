// tools/lib/checks.ps1 and tools/launch.ps1's argument handling, run in every PowerShell on this
// machine (Windows PowerShell 5.1 and PowerShell 7 on Windows; pwsh elsewhere) through
// tools/test/launch-checks.ps1. Every launch.ps1 call there is a -DryRun that starts nothing.
import test from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const tools = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const driver = path.join(tools, 'test', 'launch-checks.ps1');

function findShells() {
  const candidates = process.platform === 'win32'
    ? [path.join(process.env.SystemRoot ?? 'C:\\Windows', 'System32', 'WindowsPowerShell', 'v1.0', 'powershell.exe'), 'pwsh.exe']
    : ['pwsh'];
  return candidates.filter((shell) => spawnSync(shell, ['-NoProfile', '-NonInteractive', '-Command', 'exit 0'], { windowsHide: true, timeout: 60_000 }).status === 0);
}
const shells = findShells();
// launch.ps1 is the Windows launcher: it needs Windows (cmd.exe, Get-NetTCPConnection, ...)
const windows = process.platform === 'win32';

for (const shell of shells.length ? shells : ['(none)']) {
  test(`PowerShell checks and launch.ps1 arguments (${path.basename(shell)})`, { skip: !windows ? 'tools/launch.ps1 is the Windows launcher' : !shells.length && 'no PowerShell on this machine' }, (t) => {
    const scratch = fs.mkdtempSync(path.join(os.tmpdir(), 'launch-ps-'));
    t.after(() => fs.rmSync(scratch, { recursive: true, force: true }));
    // the launcher's environment defaults must not leak in from the machine running the tests
    const childEnv = { ...process.env };
    for (const key of Object.keys(childEnv)) if (/^AGENTCRAFT_(BACKEND|WORLD|PORT|DEV_PORT|HOME)$/i.test(key)) delete childEnv[key];
    const res = spawnSync(shell, ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', driver, '-Tools', tools, '-Scratch', scratch],
      { encoding: 'utf8', windowsHide: true, timeout: 180_000, env: childEnv });
    assert.equal(res.status, 0, `driver failed:\n${res.stdout}\n${res.stderr}`);
    const r = JSON.parse(res.stdout.slice(res.stdout.indexOf('{')));

    assert.deepEqual(r.javaMajor, [25, 17, 8, 26, 0]);
    assert.deepEqual(r.jdkDirs, ['25.0.4.1', '25.0', '25.30.17', '1.8.0', '21.0.5.11', '0.0']);
    assert.deepEqual(r.node, ['True/22', 'True/24', 'False/20', 'True/22', 'False/0', 'False/0']);
    assert.deepEqual(r.ports.slice(0, 2), ['ok:7878', 'ok:27900']);
    assert.match(r.ports[2], /^err:-Port must be a port number from 1 to 65535 \(got '0'\)$/);
    assert.match(r.ports[3], /got '65536'/);
    assert.match(r.ports[4], /got 'abc'/);
    assert.match(r.ports[5], /^err:-Port 3000 is reserved for other dev servers/);
    assert.match(r.ports[6], /got '1\.5'/);
    assert.deepEqual(r.ints, ['ok:600', 'err', 'err', 'err']);
    assert.deepEqual(r.speeds, ['ok:0.5', 'ok:2', 'err', 'err', 'err', 'err', 'err']);
    assert.deepEqual(r.profiles, [false, false, false, true, true, true]);
    assert.deepEqual(r.seeds, [false, false, false, true, true]);
    assert.deepEqual(r.quoting, ['plain', '"with space"', '"C:\\My Code\\\\"', '"say \\"hi\\""', '""']);

    const [scratchOk, newOk, fileAsFolder, underFile, fileOk, folderAsFile] = r.folderTargets;
    assert.equal(scratchOk, false);
    assert.equal(newOk, false);
    assert.match(fileAsFolder, /a-file\.txt is a file, not a folder$/);
    assert.equal(underFile, true);
    assert.equal(fileOk, false);
    assert.match(folderAsFile, /is a folder, not a file$/);

    assert.deepEqual(r.captured, { code: 3, stdout: JSON.stringify(['a b', 'say "hi"', 'C:\\My Code\\']), stderr: 'to stderr', timedOut: false });
    assert.equal(r.timeout.timedOut, true);
    assert.equal(r.timeout.code, -1);
    assert.ok(r.timeout.seconds < 10, `the timed-out probe took ${r.timeout.seconds} s`);

    const { bad, badSummary, showcase, env, ok, okSummary, busy } = r.launch;
    assert.equal(bad.code, 2, bad.out);
    for (const m of ['unknown parameter -Bogus', "'survival'", "bad -World 'mars'", "-Port must be a port number from 1 to 65535 (got '70000')",
      "-TimeoutSec must be a whole number from 10 to 86400 (got 'abc')", "-Speed must be a number above 0", '-Seed is empty', 'no-such-folder is not an existing folder']) {
      assert.ok(bad.out.includes(m), `missing "${m}" in:\n${bad.out}`);
    }
    assert.equal(badSummary.exitCode, 2);
    assert.equal(badSummary.ok, false);
    assert.match(badSummary.error, /unknown parameter -Bogus/);

    assert.equal(showcase.code, 2, showcase.out);
    for (const m of ['-Showcase needs the sim backend', "bad profile name 'bad name'", '-Port and -DevPort must differ (both 27911)', '-Notify and -NoNotify contradict each other']) {
      assert.ok(showcase.out.includes(m), `missing "${m}" in:\n${showcase.out}`);
    }
    assert.equal(env.code, 2, env.out);
    assert.ok(env.out.includes("bad AGENTCRAFT_BACKEND 'gpt'"), env.out);
    assert.ok(env.out.includes("AGENTCRAFT_DEV_PORT must be a port number from 1 to 65535 (got 'abc')"), env.out);

    assert.equal(ok.code, 0, ok.out);
    assert.ok(ok.out.includes('nothing is started'), ok.out);
    assert.equal(okSummary.ok, true);
    assert.equal(okSummary.exitCode, 0);

    assert.equal(busy.code, 4, busy.out);
    assert.match(busy.out, /port \d+ is already in use by/);
  });
}
