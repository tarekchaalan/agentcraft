// Helpers of tools/launch-unix.mjs (the macOS and Linux launcher) that do not start anything:
// option parsing and checking, Java discovery, version parsing, log tails and the exit codes.
// tools/test/launcher.test.mjs covers them; tools/launch.ps1 follows the same rules on Windows.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

/** Exit codes, the same as tools/launch.ps1's. */
export const EXIT = Object.freeze({ ok: 0, failed: 1, args: 2, missing: 3, busy: 4, interrupted: 130 });

/** Ports other dev servers on this machine use; the launchers never take them. */
export const RESERVED_PORTS = Object.freeze([3000, 5173, 8080]);

export const ACTIONS = Object.freeze(['launch', 'stop', 'login']);

/** A failure with the exit code it maps to and, when a log explains it, the log's last lines. */
export class LaunchError extends Error {
  constructor(message, code = EXIT.failed, tail = []) {
    super(message);
    this.code = code;
    this.tail = tail;
  }
}

const VALUES = new Set(['backend', 'repo', 'home', 'profile', 'port', 'dev-port', 'showcase', 'summary-json', 'foreman-arg', 'world', 'seed', 'timeout']);
const SWITCHES = new Set(['use-claude-login', 'dev', 'reset', 'no-game', 'no-foreman', 'no-wait', 'game', 'foreman', 'stop-daemon', 'keep-on-failure', 'help']);

/** A port from an option or an environment variable: a whole number 1..65535 that is not reserved. */
export function checkPort(raw, what) {
  const text = String(raw ?? '').trim();
  const port = /^\d{1,5}$/.test(text) ? Number(text) : NaN;
  if (!Number.isInteger(port) || port < 1 || port > 65535) return { error: `${what} must be a port number from 1 to 65535 (got '${raw}')` };
  if (RESERVED_PORTS.includes(port)) return { error: `${what} ${port} is reserved for other dev servers (${RESERVED_PORTS.join(', ')}); pick another` };
  return { port };
}

/** A folder that is used or created: not an existing file, and some parent of it exists as a folder. */
export function checkFolderTarget(dir, what, statOf = safeStat) {
  const st = statOf(dir);
  if (st && !st.isDirectory()) return `${what} ${dir} is a file, not a folder`;
  let p = dir;
  while (!statOf(p)) {
    const up = path.dirname(p);
    if (up === p) return `${what} ${dir} cannot be created`;
    p = up;
  }
  if (!statOf(p).isDirectory()) return `${what} ${dir}: ${p} is a file, not a folder`;
  return null;
}

function safeStat(p) {
  try { return fs.statSync(p); } catch { return null; }
}

/**
 * Parses and checks the command line of tools/launch-unix.mjs. Every problem is reported at once,
 * as one LaunchError with exit code 2. Returns the options with the defaults filled in; `help` is set
 * when there is no action or --help was given.
 */
export function parseOptions(argv, { env = process.env, cwd = process.cwd(), homedir = os.homedir(), statOf = safeStat } = {}) {
  const args = [...argv];
  const out = { action: args.shift(), repo: [], foremanArgs: [] };
  const bad = [];
  const given = new Set();
  for (let i = 0; i < args.length; i++) {
    const a = args[i];
    if (!a.startsWith('--')) { bad.push(`unexpected argument '${a}'`); continue; }
    const key = a.slice(2);
    if (VALUES.has(key)) {
      const value = args[i + 1];
      // a Foreman flag is the value of --foreman-arg (--foreman-arg --workers); anything else must not look like an option
      if (value === undefined || (key !== 'foreman-arg' && value.startsWith('--'))) { bad.push(`--${key} needs a value`); continue; }
      i++;
      if (!value.trim()) { bad.push(`--${key} is empty`); continue; }
      given.add(key);
      if (key === 'repo') out.repo.push(path.resolve(cwd, value));
      else if (key === 'foreman-arg') out.foremanArgs.push(value);
      else out[key] = value;
    } else if (SWITCHES.has(key)) out[key] = true;
    else bad.push(`unknown option --${key}`);
  }
  if (out.action === undefined || out.action === '--help' || out.action === '-h' || out.action === 'help') return { ...out, action: undefined, help: true };
  if (!ACTIONS.includes(out.action)) bad.unshift(`unknown action '${out.action}' (launch, stop or login)`);
  if (out.help) return out;

  if (out.showcase !== undefined) {
    if (!['busy', 'late'].includes(out.showcase)) bad.push(`--showcase must be busy or late (got '${out.showcase}')`);
    if (given.has('backend') && out.backend !== 'sim') bad.push('--showcase needs the sim backend (it holds a scripted state)');
    out.backend = 'sim';
    out.profile ??= out.showcase === 'late' ? 'showcase-late' : 'showcase';
  }
  let backendFrom = '--backend';
  if (out.backend === undefined) {
    if (env.AGENTCRAFT_BACKEND) { out.backend = env.AGENTCRAFT_BACKEND; backendFrom = 'AGENTCRAFT_BACKEND'; } else out.backend = 'claude';
  }
  if (!['sim', 'claude', 'codex'].includes(out.backend)) bad.push(`bad ${backendFrom} '${out.backend}' (sim, claude or codex)`);
  let worldFrom = '--world';
  if (out.world === undefined) {
    if (env.AGENTCRAFT_WORLD) { out.world = env.AGENTCRAFT_WORLD; worldFrom = 'AGENTCRAFT_WORLD'; } else out.world = 'studio';
  }
  if (!['studio', 'survival'].includes(out.world)) bad.push(`bad ${worldFrom} '${out.world}' (studio or survival)`);
  out.profile ??= out.backend;
  if (!/^[\w-]{1,64}$/.test(out.profile)) bad.push(`bad profile name '${out.profile}' (1 to 64 letters, digits, _ and -)`);

  for (const [key, envName, fallback] of [['port', 'AGENTCRAFT_PORT', '7878'], ['dev-port', 'AGENTCRAFT_DEV_PORT', '7879']]) {
    const fromEnv = !given.has(key) && env[envName];
    const r = checkPort(given.has(key) ? out[key] : fromEnv ? env[envName] : fallback, given.has(key) || !fromEnv ? `--${key}` : envName);
    if (r.error) bad.push(r.error);
    out[key] = r.port ?? 0;
  }
  if (out.port && out.port === out['dev-port']) bad.push(`--port and --dev-port must differ (both ${out.port})`);

  out.timeoutGiven = given.has('timeout');
  if (out.timeoutGiven) {
    const t = /^\d{1,5}$/.test(String(out.timeout)) ? Number(out.timeout) : NaN;
    if (!(t >= 10 && t <= 86400)) bad.push(`--timeout must be a whole number of seconds from 10 to 86400 (got '${out.timeout}')`);
    out.timeout = t;
  } else out.timeout = 600;
  // a seed is a number or any one-line text (hashed like the world-creation screen does)
  // eslint-disable-next-line no-control-regex
  if (given.has('seed') && /[\u0000-\u001f\u007f]/.test(out.seed)) bad.push('--seed must be one line of text (no control characters)');

  out.home = path.resolve(cwd, out.home ?? env.AGENTCRAFT_HOME ?? path.join(homedir, '.agentcraft'));
  const homeProblem = checkFolderTarget(out.home, '--home', statOf);
  if (homeProblem) bad.push(homeProblem);
  if (out['summary-json'] !== undefined) {
    out['summary-json'] = path.resolve(cwd, out['summary-json']);
    const st = statOf(out['summary-json']);
    if (st?.isDirectory()) bad.push(`--summary-json ${out['summary-json']} is a folder, not a file`);
    else {
      const p = checkFolderTarget(path.dirname(out['summary-json']), '--summary-json', statOf);
      if (p) bad.push(p);
    }
  }
  if (out.action === 'launch') {
    for (const repo of out.repo) {
      if (!statOf(repo)?.isDirectory()) bad.push(`--repo ${repo} is not an existing folder`);
    }
  }
  if (bad.length) throw new LaunchError(bad.join('\n'), EXIT.args);
  return out;
}

// --- versions --------------------------------------------------------------------------------------

/** Major version in `java -version` output: 'version "25.0.1"' -> 25, 'version "1.8.0_392"' -> 8; 0 when none. */
export function javaMajorFromText(text) {
  const m = /version "(\d+)(?:\.(\d+))?/.exec(String(text ?? ''));
  if (!m) return 0;
  const major = Number(m[1]);
  return major === 1 && m[2] ? Number(m[2]) : major;
}

/** Numbers in a JDK folder's name, to try the newest first: 'jdk-25.0.1+8' -> [25, 0, 1], 'java-25-openjdk-amd64' -> [25]. */
export function jdkDirVersion(name) {
  const m = /(\d+(?:\.\d+)*)/.exec(String(name));
  return m ? m[1].split('.').map(Number) : [];
}

function compareVersionsDesc(a, b) {
  for (let i = 0; i < Math.max(a.length, b.length); i++) {
    const d = (b[i] ?? -1) - (a[i] ?? -1);
    if (d) return d;
  }
  return 0;
}

/** `node --version` / process.versions.node text -> { ok, major, minor }. */
export function nodeVersionOk(version, min = 22) {
  const m = /^v?(\d+)\.(\d+)\.\d+/.exec(String(version ?? '').trim());
  if (!m) return { ok: false, major: 0, minor: 0 };
  const major = Number(m[1]);
  return { ok: major >= min, major, minor: Number(m[2]) };
}

/**
 * JDK homes worth trying on macOS or Linux, most likely first: JAVA_HOME, Homebrew's openjdk@25 and
 * openjdk, the system JVM folders, SDKMAN and ~/.jdks (newest folder name first), then the `java` on
 * PATH. Nothing is run here; findJdk checks each one.
 */
export function jdkCandidates({ platform = process.platform, env = process.env, homedir = os.homedir(), list = listDir, realpath = safeRealpath, isFile = isExecutableFile } = {}) {
  const out = [];
  const add = (home, source) => { if (home && !out.some((c) => c.home === home)) out.push({ home, source }); };
  add(env.JAVA_HOME, 'JAVA_HOME');
  const scan = (dir, suffix = '') => list(dir)
    .filter((name) => !name.startsWith('.'))
    .sort((a, b) => compareVersionsDesc(jdkDirVersion(a), jdkDirVersion(b)))
    .forEach((name) => add(path.join(dir, name, suffix), `found in ${dir}`));
  if (platform === 'darwin') {
    for (const prefix of ['/opt/homebrew', '/usr/local']) {
      add(`${prefix}/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home`, 'Homebrew openjdk@25');
      add(`${prefix}/opt/openjdk/libexec/openjdk.jdk/Contents/Home`, 'Homebrew openjdk');
    }
    scan('/Library/Java/JavaVirtualMachines', 'Contents/Home');
    scan(path.join(homedir, 'Library', 'Java', 'JavaVirtualMachines'), 'Contents/Home');
  } else {
    scan('/usr/lib/jvm');
    scan('/usr/lib64/jvm');
  }
  scan(path.join(homedir, '.sdkman', 'candidates', 'java'));
  scan(path.join(homedir, '.jdks'));
  for (const dir of String(env.PATH ?? '').split(path.delimiter)) {
    const java = dir && path.join(dir, 'java');
    if (java && isFile(java)) {
      const real = realpath(java);
      if (real) add(path.dirname(path.dirname(real)), 'PATH');
      break;
    }
  }
  return out;
}

/** The first candidate whose java runs as $min or newer: { home, source, major }, or null. `probe(home)` returns the major. */
export function findJdk({ min = 25, candidates, probe }) {
  for (const c of candidates) {
    const major = probe(c.home);
    if (major >= min) return { ...c, major };
  }
  return null;
}

function listDir(dir) {
  try { return fs.readdirSync(dir); } catch { return []; }
}

function safeRealpath(p) {
  try { return fs.realpathSync(p); } catch { return null; }
}

function isExecutableFile(p) {
  try { return fs.statSync(p).isFile(); } catch { return false; }
}

/** The last `lines` lines of a log file (empty when it cannot be read). */
export function logTail(file, lines = 20) {
  if (!file) return [];
  try {
    const text = fs.readFileSync(file, 'utf8');
    return text.split(/\r?\n/).filter((l, i, all) => l || i < all.length - 1).slice(-lines);
  } catch {
    return [];
  }
}
