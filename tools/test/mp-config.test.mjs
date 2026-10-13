import test from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { makePlan, parseOptions, portsForSlot } from '../lib/mp/config.mjs';
import { gradleInvocation, javaArguments, javaArgfile, javaEnvironment, offlineUuid } from '../lib/mp/launch.mjs';

const root = path.resolve('fixture');
test('packet 92 uses the frozen port block, defaults to two isolated clients', () => {
  assert.deepEqual(portsForSlot(92), { server: 25692, a: { foreman: 27984, dev: 8084 }, b: { foreman: 27985, dev: 8085 } });
  const plan = makePlan(root, parseOptions(['up', '--slot', '92'], root, {}));
  assert.equal(plan.clients.length, 2);
  assert.deepEqual(plan.clients.map(c => c.profile), ['mp-92-a', 'mp-92-b']);
  assert.notEqual(plan.clients[0].gameDir, plan.clients[1].gameDir);
  assert.notEqual(plan.clients[0].gameDir, plan.server.gameDir);
  assert.notEqual(plan.clients[0].username, plan.clients[1].username);
  assert.equal(plan.home, path.join(root, '.agentcraft-home'));
  assert.equal(plan.server.heap, '2G');
  assert.deepEqual(plan.clients.map(c => c.heap), ['2G', '2G']);
});

test('one client, configurable heaps, environment precedence and explicit home/profile', () => {
  const opt = parseOptions(['up', '--slot', '92', '--clients', '1', '--client-heap', '3G',
    '--home', './fixture/home', '--profile', 'mp-custom'], root,
  { AGENTCRAFT_MP_CLIENT_HEAP: '4G', AGENTCRAFT_MP_SERVER_HEAP: '1024M', AGENTCRAFT_MP_GRADLE: 'gw-raw' });
  assert.equal(opt['client-heap'], '3G');
  assert.equal(opt['server-heap'], '1024M');
  assert.equal(opt['gradle-command'], 'gw-raw');
  const plan = makePlan(root, opt);
  assert.equal(plan.clients.length, 1);
  assert.equal(plan.home, path.resolve('fixture/home'));
  assert.equal(plan.clients[0].profile, 'mp-custom-a');
});

test('strict CLI validation prevents accidental defaults and unbounded JVMs', () => {
  for (const args of [[], ['up'], ['up', '--slot', '100'], ['up', '--slot', '-1'], ['up', '--slot', '1.5'],
    ['up', '--slot', '92', '--clients', '3'], ['up', '--slot', '92', '--profile', '../x'],
    ['up', '--slot', '92', '--client-heap', '128G'], ['up', '--slot', '92', '--server-heap', '0M'],
    ['up', '--slot', '92', '--backend', 'wat'], ['up', '--slot', '92', '--timeout', 'NaN'],
    ['up', '--slot', '92', '--home'], ['up', '--slot', '92', '--wat']]) {
    assert.throws(() => parseOptions(args, root, {}), undefined, String(args));
  }
});

test('portable wrapper invocation and optional swarm wrapper; no environment rewriting', () => {
  const posix = gradleInvocation(root, '/tmp/launch.json', undefined, 'darwin');
  assert.equal(posix.command, 'sh');
  assert.equal(posix.args[0], './gradlew');
  assert.equal(posix.cwd, path.join(root, 'mod'));
  assert.equal('env' in posix, false);
  const win = gradleInvocation(root, 'C:\\with space\\launch.json', undefined, 'win32');
  assert.equal(win.command, 'powershell.exe');
  assert.match(win.args.at(-1), /gradlew\.bat/);
  assert.match(win.args.at(-1), /'[-]PmpLaunchFile=C:\\with space\\launch.json'/);
  assert.equal(gradleInvocation(root, 'out', 'gw-raw').command, 'gw-raw');
});

test('direct launch preserves Loom flags and replaces heap and singleton game arguments', () => {
  const launch = { main: 'DLI', classpath: ['/one', '/two'], jvmArgs: ['-Xmx4G', '-Xms2G', '-XX:MaxHeapSize=900000',
    '-XstartOnFirstThread', '-Dfabric.dli.env=client'], args: ['--username', 'old', '--gameDir', 'old', '--width', '1920', 'nogui'] };
  const client = { username: 'MP92_A', gameDir: '/new game' };
  const args = javaArguments(launch, '2G', client, 25692, 'darwin');
  assert.deepEqual(args.filter(a => a.startsWith('-Xm')), ['-Xms256M', '-Xmx2G']);
  assert.equal(args.some(arg => /^-XX:(Initial|Max)HeapSize=/.test(arg)), false);
  assert.ok(args.includes('-XstartOnFirstThread'));
  assert.equal(args[args.indexOf('-cp') + 1], '/one:/two');
  assert.equal(args[args.indexOf('--gameDir') + 1], client.gameDir);
  assert.equal(args[args.indexOf('--username') + 1], client.username);
  assert.equal(args[args.indexOf('--quickPlayMultiplayer') + 1], 'localhost:25692');
  assert.equal(javaArguments(launch, '2G', client, 25692, 'win32')[args.indexOf('-cp') + 1], '/one;/two');
  assert.throws(() => javaArguments({}, '2G'));
});

test('Java argfiles escape Windows paths, quotes, spaces and reject multiline injection', () => {
  assert.equal(javaArgfile(['C:\\a b\\file', 'a"b', '#literal']), '"C:\\\\a b\\\\file"\n"a\\"b"\n"#literal"\n');
  assert.throws(() => javaArgfile(['a\n-Xmx32G']));
  assert.throws(() => javaArgfile(['x\0y']));
});

test('injected JVM flags cannot bypass the cap; Java/Gradle homes are preserved', () => {
  const env = { JAVA_HOME: 'custom-java', GRADLE_USER_HOME: 'shared-cache' };
  assert.deepEqual(javaEnvironment(env), env);
  for (const key of ['JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS']) {
    assert.throws(() => javaEnvironment({ ...env, [key]: '-Xmx32G' }), new RegExp(key));
  }
});

test('offline UUID matches Java UUID.nameUUIDFromBytes and distinct player identities', () => {
  assert.equal(offlineUuid('Notch'), 'b50ad385-829d-3141-a216-7e7d7539ba7f');
  assert.notEqual(offlineUuid('MP92_A'), offlineUuid('MP92_B'));
});
