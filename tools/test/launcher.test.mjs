// tools/lib/launcher.mjs: the option checks, Java discovery and exit codes of tools/launch-unix.mjs.
import test from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { EXIT, LaunchError, checkFolderTarget, checkPort, findJdk, jdkCandidates, jdkDirVersion, javaMajorFromText, nodeVersionOk, parseOptions } from '../lib/launcher.mjs';

const cwd = path.resolve('fixture-cwd');
const homedir = path.resolve('fixture-home');
// a fake file system: these folders exist, everything else does not
const folders = new Set([cwd, homedir, path.join(homedir, '.agentcraft'), path.resolve('/'), path.resolve('/repos'), path.resolve('/repos/app')]);
const files = new Set([path.resolve('/repos/notes.txt')]);
const statOf = (p) => folders.has(p) ? { isDirectory: () => true } : files.has(p) ? { isDirectory: () => false } : null;
const parse = (argv, env = {}) => parseOptions(argv, { env, cwd, homedir, statOf });
const problems = (argv, env = {}) => {
  try { parse(argv, env); } catch (e) { assert.ok(e instanceof LaunchError); assert.equal(e.code, EXIT.args); return e.message.split('\n'); }
  assert.fail(`no error for ${argv.join(' ')}`);
};

test('defaults: claude backend, ~/.agentcraft, 7878/7879, studio, 600 s', () => {
  const o = parse(['launch']);
  assert.equal(o.action, 'launch');
  assert.equal(o.backend, 'claude');
  assert.equal(o.profile, 'claude');
  assert.equal(o.home, path.join(homedir, '.agentcraft'));
  assert.equal(o.port, 7878);
  assert.equal(o['dev-port'], 7879);
  assert.equal(o.world, 'studio');
  assert.equal(o.timeout, 600);
  assert.equal(o.timeoutGiven, false);
});

test('environment defaults, and the option wins over them', () => {
  const env = { AGENTCRAFT_BACKEND: 'sim', AGENTCRAFT_WORLD: 'survival', AGENTCRAFT_PORT: '27878', AGENTCRAFT_DEV_PORT: '27879', AGENTCRAFT_HOME: '/state' };
  const o = parse(['launch'], env);
  assert.deepEqual([o.backend, o.world, o.port, o['dev-port'], o.home], ['sim', 'survival', 27878, 27879, path.resolve(cwd, '/state')]);
  const o2 = parse(['launch', '--backend', 'codex', '--port', '27900'], env);
  assert.deepEqual([o2.backend, o2.port], ['codex', 27900]);
});

test('no action or --help asks for the usage; an unknown action is an argument error', () => {
  assert.equal(parse([]).help, true);
  assert.equal(parse(['--help']).help, true);
  assert.equal(parse(['launch', '--help']).help, true);
  assert.match(problems(['frobnicate'])[0], /unknown action 'frobnicate'/);
});

test('every bad argument is reported at once', () => {
  const p = problems(['launch', '--bogus', 'x', '--world', 'mars', '--port', '70000', '--dev-port', '8080', '--timeout', '5', '--profile', 'bad name']);
  assert.deepEqual(p, [
    'unknown option --bogus',
    "unexpected argument 'x'",
    "bad --world 'mars' (studio or survival)",
    "bad profile name 'bad name' (1 to 64 letters, digits, _ and -)",
    "--port must be a port number from 1 to 65535 (got '70000')",
    '--dev-port 8080 is reserved for other dev servers (3000, 5173, 8080); pick another',
    "--timeout must be a whole number of seconds from 10 to 86400 (got '5')",
  ]);
});

test('values: missing, empty, or another option in their place', () => {
  assert.deepEqual(problems(['launch', '--repo']), ['--repo needs a value']);
  assert.deepEqual(problems(['launch', '--seed', '']), ['--seed is empty']);
  assert.deepEqual(problems(['launch', '--home', '--dev']), ['--home needs a value']);
  // a Foreman flag is a value of --foreman-arg
  assert.deepEqual(parse(['launch', '--foreman-arg', '--workers', '--foreman-arg', 'kit,wren']).foremanArgs, ['--workers', 'kit,wren']);
  assert.match(problems(['launch', '--seed', 'a\nb'])[0], /one line/);
  assert.equal(parse(['launch', '--seed', '-1234']).seed, '-1234');
});

test('ports from the environment are checked and named as such', () => {
  assert.deepEqual(problems(['launch'], { AGENTCRAFT_PORT: 'abc' }), ["AGENTCRAFT_PORT must be a port number from 1 to 65535 (got 'abc')"]);
  assert.deepEqual(problems(['launch', '--port', '7879']), ['--port and --dev-port must differ (both 7879)']);
  assert.deepEqual(problems(['launch'], { AGENTCRAFT_BACKEND: 'gpt' }), ["bad AGENTCRAFT_BACKEND 'gpt' (sim, claude or codex)"]);
});

test('showcase: sim backend and its own profile; another backend is refused', () => {
  const o = parse(['launch', '--showcase', 'late']);
  assert.deepEqual([o.backend, o.profile], ['sim', 'showcase-late']);
  assert.equal(parse(['launch', '--showcase', 'busy', '--backend', 'sim']).profile, 'showcase');
  assert.deepEqual(problems(['launch', '--showcase', 'busy', '--backend', 'claude']), ['--showcase needs the sim backend (it holds a scripted state)']);
  assert.match(problems(['launch', '--showcase', 'soon'])[0], /--showcase must be busy or late/);
});

test('folders: --repo must exist for launch, --home must not be a file, --summary-json not a folder', () => {
  assert.deepEqual(parse(['launch', '--repo', '/repos/app']).repo, [path.resolve('/repos/app')]);
  assert.deepEqual(problems(['launch', '--repo', '/repos/missing']), [`--repo ${path.resolve('/repos/missing')} is not an existing folder`]);
  assert.equal(parse(['stop', '--repo', '/repos/missing']).action, 'stop'); // stop does not use --repo
  assert.deepEqual(problems(['launch', '--home', '/repos/notes.txt']), [`--home ${path.resolve('/repos/notes.txt')} is a file, not a folder`]);
  assert.equal(parse(['launch', '--home', '/repos/new/state']).home, path.resolve('/repos/new/state')); // created later
  assert.deepEqual(problems(['launch', '--summary-json', '/repos/app']), [`--summary-json ${path.resolve('/repos/app')} is a folder, not a file`]);
  assert.match(problems(['launch', '--summary-json', '/repos/notes.txt/out.json'])[0], /is a file, not a folder/);
});

test('checkPort and checkFolderTarget', () => {
  assert.deepEqual(checkPort('27900', '--port'), { port: 27900 });
  assert.deepEqual(checkPort(' 1 ', '--port'), { port: 1 });
  for (const bad of ['0', '65536', '-1', '1.5', '', 'x', undefined]) assert.ok(checkPort(bad, '--port').error, String(bad));
  assert.match(checkPort('3000', 'AGENTCRAFT_PORT').error, /^AGENTCRAFT_PORT 3000 is reserved/);
  assert.equal(checkFolderTarget(path.resolve('/repos/app'), 'x', statOf), null);
  assert.equal(checkFolderTarget(path.resolve('/repos/a/b/c'), 'x', statOf), null);
  assert.match(checkFolderTarget(path.resolve('/repos/notes.txt/sub'), 'x', statOf), /is a file, not a folder/);
});

test('java -version output and JDK folder names', () => {
  assert.equal(javaMajorFromText('openjdk version "25.0.1" 2025-10-21 LTS'), 25);
  assert.equal(javaMajorFromText('java version "17.0.10" 2024-01-16 LTS'), 17);
  assert.equal(javaMajorFromText('java version "1.8.0_392"'), 8);
  assert.equal(javaMajorFromText('openjdk version "26-ea" 2026-03-17'), 26);
  assert.equal(javaMajorFromText('command not found'), 0);
  assert.equal(javaMajorFromText(undefined), 0);
  assert.deepEqual(jdkDirVersion('jdk-25.0.1+8'), [25, 0, 1]);
  assert.deepEqual(jdkDirVersion('java-25-openjdk-amd64'), [25]);
  assert.deepEqual(jdkDirVersion('default-java'), []);
});

test('Node version check', () => {
  assert.deepEqual(nodeVersionOk('22.18.0'), { ok: true, major: 22, minor: 18 });
  assert.deepEqual(nodeVersionOk('v24.1.0'), { ok: true, major: 24, minor: 1 });
  assert.equal(nodeVersionOk('v20.11.1').ok, false);
  assert.equal(nodeVersionOk('garbage').ok, false);
});

test('JDK candidates: JAVA_HOME first, then the platform folders newest first, then PATH; findJdk takes the first 25+', () => {
  const listing = {
    '/usr/lib/jvm': ['java-17-openjdk', 'java-25-openjdk', 'default-java', 'java-21-openjdk', '.hidden'],
    [path.join('/u', '.jdks')]: ['temurin-26.0.1'],
  };
  const list = (dir) => listing[dir] ?? [];
  const linux = jdkCandidates({ platform: 'linux', env: { JAVA_HOME: '/opt/jdk17', PATH: '' }, homedir: '/u', list, isFile: () => false });
  assert.deepEqual(linux.map((c) => c.home), ['/opt/jdk17', ...['java-25-openjdk', 'java-21-openjdk', 'java-17-openjdk', 'default-java'].map((n) => path.join('/usr/lib/jvm', n)), path.join('/u', '.jdks', 'temurin-26.0.1')]);
  assert.equal(linux[0].source, 'JAVA_HOME');
  const majors = { '/opt/jdk17': 17, [path.join('/usr/lib/jvm', 'java-25-openjdk')]: 25 };
  const jdk = findJdk({ min: 25, candidates: linux, probe: (home) => majors[home] ?? 0 });
  assert.deepEqual(jdk, { home: path.join('/usr/lib/jvm', 'java-25-openjdk'), source: 'found in /usr/lib/jvm', major: 25 });
  assert.equal(findJdk({ min: 25, candidates: linux, probe: () => 21 }), null);

  const mac = jdkCandidates({ platform: 'darwin', env: { PATH: `/bin${path.delimiter}/usr/local/bin` }, homedir: '/u', list: () => [],
    isFile: (p) => p === path.join('/usr/local/bin', 'java'), realpath: () => '/usr/local/Cellar/openjdk/25/libexec/openjdk.jdk/Contents/Home/bin/java' });
  assert.equal(mac[0].home, '/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home');
  assert.deepEqual(mac.at(-1), { home: path.dirname(path.dirname('/usr/local/Cellar/openjdk/25/libexec/openjdk.jdk/Contents/Home/bin/java')), source: 'PATH' });
});
