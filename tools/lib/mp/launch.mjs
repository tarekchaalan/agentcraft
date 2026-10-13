import fs from 'node:fs';
import { spawnSync } from 'node:child_process';
import path from 'node:path';
import { createHash } from 'node:crypto';

export const psQuote = value => `'${String(value).replaceAll("'", "''")}'`;

// JAVA_HOME and GRADLE_USER_HOME remain inherited, including under swarm wrappers.
export const exportScript = root => path.join(root, 'tools', 'lib', 'mp', 'export-launch.gradle');
// The marker is an unused project property: it only puts the run's identity on the command line.
export const gradleArguments = (root, output, marker) => ['-I', exportScript(root), 'mpExportLaunch', `-PmpLaunchFile=${output}`,
  ...(marker ? [`-PmpRun=${marker}`] : []), '--no-configuration-cache', '--console=plain'];
export function gradleInvocation(root, output, command, platform = process.platform, marker) {
  const args = gradleArguments(root, output, marker);
  const cwd = path.join(root, 'mod');
  // Node refuses to spawn a .bat or .cmd without a shell (EINVAL), so on Windows a custom one
  // runs through PowerShell like the default wrapper. PowerShell needs .\ for a file in cwd.
  if (command && !(platform === 'win32' && /\.(bat|cmd)$/i.test(command))) return { command, args, cwd };
  if (platform !== 'win32') return { command: 'sh', args: ['./gradlew', ...args], cwd };
  const batch = !command ? '.\\gradlew.bat'
    : psQuote(!/[\\/]/.test(command) && fs.existsSync(path.join(cwd, command)) ? `.\\${command}` : command);
  return { command: 'powershell.exe', args: ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-Command',
    `& ${batch} ${args.map(psQuote).join(' ')}; exit $LASTEXITCODE`], cwd };
}

// Java argfiles avoid Windows command-length limits; always quote paths and literal backslashes.
export function javaArgfile(args) {
  return args.map(value => {
    if (/[\r\n\0]/.test(value)) throw new Error('newline/NUL in Java argument');
    return `"${value.replaceAll('\\', '\\\\').replaceAll('"', '\\"')}"`;
  }).join('\n') + '\n';
}

export function javaArguments(launch, heap, client, serverPort, platform = process.platform) {
  if (!launch?.main || !launch.classpath?.length || !Array.isArray(launch.jvmArgs) || !Array.isArray(launch.args)) {
    throw new Error('invalid Loom launch metadata; run the build/export step');
  }
  // An environment or Loom heap default must never override the explicit harness cap.
  const jvm = launch.jvmArgs.filter(arg => !/^-Xm[sx]|^-XX:(?:Initial|Max)HeapSize=/.test(arg));
  const args = [...launch.args];
  for (const key of ['--username', '--gameDir', '--quickPlayMultiplayer', '--width', '--height']) {
    for (let i = args.indexOf(key); i >= 0; i = args.indexOf(key)) args.splice(i, 2);
  }
  if (client) args.push('--username', client.username, '--gameDir', client.gameDir,
    '--quickPlayMultiplayer', `localhost:${serverPort}`, '--width', '960', '--height', '540');
  return [...jvm, '-Xms256M', `-Xmx${heap}`, '-cp', launch.classpath.join(platform === 'win32' ? ';' : ':'), launch.main, ...args];
}

export function javaEnvironment(env = process.env) {
  // These inject arbitrary JVM flags after our command-line cap. Reject rather than silently
  // changing the operator's environment. Gradle still receives the environment unchanged.
  for (const key of ['JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS']) {
    if (env[key]?.trim()) throw new Error(`${key} must be unset for capped harness JVMs`);
  }
  return { ...env };
}

// Only the game clients opt in to the DevBridge commands that act on a remote server, which
// is how the harness drives them: never the server or a Foreman.
export function gameSpec(plan, launch, java, env, client) {
  if (!client) return { command: java, args: javaArguments(launch.server, plan.server.heap), cwd: plan.server.gameDir, log: plan.server.log, env };
  return { command: java, args: javaArguments(launch.client, client.heap, client, plan.server.port), cwd: client.gameDir, log: client.log,
    env: { ...env, AGENTCRAFT_PORT: String(client.foremanPort), AGENTCRAFT_DEV_PORT: String(client.devPort),
      AGENTCRAFT_HOME: plan.home, AGENTCRAFT_PROFILE: client.profile, AGENTCRAFT_PLAYER: client.username,
      AGENTCRAFT_DEV: '1', AGENTCRAFT_DEV_REMOTE: '1', AGENTCRAFT_FOREMAN: '1', AGENTCRAFT_AUTOWORLD: '0',
      AGENTCRAFT_MUTE: '1', AGENTCRAFT_FOCUS: '0', AGENTCRAFT_NOTIFY: '0' } };
}

// rcon ({port, password}) opens the loopback RCON listener that a Windows `down` stops the server through.
export function seedGameDirs(plan, rcon) {
  const dir = plan.server.gameDir;
  fs.mkdirSync(path.join(dir, 'config'), { recursive: true });
  // A local, offline test server only; bind to loopback to keep it off the LAN.
  const generator = { biome: 'minecraft:plains', layers: [
    { block: 'minecraft:bedrock', height: 1 }, { block: 'minecraft:stone', height: 124 },
    { block: 'minecraft:dirt', height: 3 }, { block: 'minecraft:grass_block', height: 1 }], structure_overrides: [] };
  fs.writeFileSync(path.join(dir, 'eula.txt'), 'eula=true\n');
  fs.writeFileSync(path.join(dir, 'server.properties'), [
    'server-ip=127.0.0.1', `server-port=${plan.server.port}`, 'online-mode=false', 'enforce-secure-profile=false',
    'level-name=mp-world', 'level-type=minecraft:flat', `generator-settings=${JSON.stringify(generator)}`,
    'gamemode=creative', 'difficulty=peaceful', 'spawn-protection=0', 'view-distance=6',
    'simulation-distance=4', 'max-players=2', ...(rcon ? ['enable-rcon=true', `rcon.port=${rcon.port}`,
      `rcon.password=${rcon.password}`, 'broadcast-rcon-to-ops=false'] : ['enable-rcon=false']),
    'enable-query=false', 'sync-chunk-writes=true', ''
  ].join('\n'));
  fs.writeFileSync(path.join(dir, 'config', 'agentcraft-server.json'), JSON.stringify({ enabled: true,
    plotStride: 128, autoAllocate: true, autoBuild: true, forceCreative: true, worldRules: true,
    protectPlots: true, relayRadiusChunks: 12, publicStatePerSecond: 4, intentsPerSecond: 10 }, null, 2));
  fs.writeFileSync(path.join(dir, 'ops.json'), JSON.stringify(plan.clients.map(client => ({
    uuid: offlineUuid(client.username), name: client.username, level: 4, bypassesPlayerLimit: true })), null, 2));
  for (const client of plan.clients) {
    fs.mkdirSync(client.gameDir, { recursive: true });
    const options = path.join(client.gameDir, 'options.txt');
    if (!fs.existsSync(options)) {
      const template = fs.readFileSync(path.join(plan.root, 'mod', 'run-template', 'options.txt'), 'utf8');
      fs.writeFileSync(options, template.replace('maxFps:120', 'maxFps:30'));
    }
  }
}

export function offlineUuid(username) {
  const bytes = createHash('md5').update(`OfflinePlayer:${username}`, 'utf8').digest();
  bytes[6] = (bytes[6] & 0x0f) | 0x30;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const h = bytes.toString('hex');
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

export function gradleEnvironment(root, env = process.env) {
  return { ...env, GRADLE_USER_HOME: env.GRADLE_USER_HOME || path.join(root, '.gradle-home') };
}

export function requireJava25(env = process.env, platform = process.platform, run = spawnSync) {
  const java = env.JAVA_HOME ? path.join(env.JAVA_HOME, 'bin', platform === 'win32' ? 'java.exe' : 'java') : 'java';
  const result = run(java, ['-version'], { env, encoding: 'utf8', windowsHide: true });
  if (result.error || result.status !== 0 || !/version "25(?:[.\"+-])/.test(`${result.stderr}\n${result.stdout}`)) {
    throw new Error(`Java 25 is required; set JAVA_HOME to a JDK 25 installation (${result.error?.message ?? result.stderr?.trim() ?? 'java -version failed'})`);
  }
  return java;
}
