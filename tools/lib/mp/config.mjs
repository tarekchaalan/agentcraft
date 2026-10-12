import path from 'node:path';

export function portsForSlot(slot) {
  if (!Number.isInteger(slot) || slot < 0 || slot > 99) throw new Error('--slot must be an integer from 0 to 99');
  return { server: 25600 + slot, a: { foreman: 27800 + 2 * slot, dev: 7900 + 2 * slot },
    b: { foreman: 27801 + 2 * slot, dev: 7901 + 2 * slot } };
}

function heap(value) {
  const match = /^(\d+)([MG])$/i.exec(value);
  const mb = match && Number(match[1]) * (match[2].toUpperCase() === 'G' ? 1024 : 1);
  if (!mb || mb < 256 || mb > 8192) throw new Error('heap must be 256M..8G, with an M or G suffix');
  return value.toUpperCase();
}

export function parseOptions(argv, root, env = process.env) {
  const out = { action: argv[0], clients: 2, backend: 'sim', timeout: 600 };
  const values = new Set(['slot', 'clients', 'backend', 'home', 'profile', 'server-heap', 'client-heap', 'timeout', 'gradle-command']);
  for (let i = 1; i < argv.length; i++) {
    const key = argv[i].replace(/^--/, '');
    if (!argv[i].startsWith('--')) throw new Error(`unexpected argument: ${argv[i]}`);
    if (values.has(key)) {
      if (!argv[i + 1] || argv[i + 1].startsWith('--')) throw new Error(`--${key} needs a value`);
      out[key] = argv[++i];
    } else if (['json', 'no-build', 'help'].includes(key)) out[key] = true;
    else throw new Error(`unknown option: --${key}`);
  }
  if (out.action === '--help' || out.help) return { help: true };
  if (!['up', 'down', 'status'].includes(out.action)) throw new Error('expected up, down or status (see --help)');
  out.slot = Number(out.slot ?? NaN);
  portsForSlot(out.slot);
  out.clients = Number(out.clients);
  if (![1, 2].includes(out.clients)) throw new Error('--clients must be 1 or 2');
  if (!['sim', 'claude'].includes(out.backend)) throw new Error('--backend must be sim or claude');
  out.timeout = Number(out.timeout);
  if (!Number.isFinite(out.timeout) || out.timeout < 1 || out.timeout > 3600) throw new Error('--timeout must be 1..3600 seconds');
  out.profile ??= `mp-${out.slot}`;
  if (!/^[A-Za-z0-9_-]+$/.test(out.profile)) throw new Error('invalid --profile');
  out.home = path.resolve(out.home ?? path.join(root, '.agentcraft-home'));
  // Environment defaults configure a launch. down and status start no JVM, so a stale or
  // invalid value there must never block cleanup; explicit flags are validated for every action.
  const launchEnv = out.action === 'up' ? env : {};
  out['server-heap'] = heap(out['server-heap'] ?? launchEnv.AGENTCRAFT_MP_SERVER_HEAP ?? '2G');
  out['client-heap'] = heap(out['client-heap'] ?? launchEnv.AGENTCRAFT_MP_CLIENT_HEAP ?? '2G');
  out['gradle-command'] ??= launchEnv.AGENTCRAFT_MP_GRADLE;
  return out;
}

export function makePlan(root, opt) {
  const ports = portsForSlot(opt.slot);
  const dir = path.join(root, 'artifacts', 'run', `mp-${opt.slot}`);
  return { version: 1, slot: opt.slot, root, dir, file: path.join(dir, 'state.json'),
    home: opt.home, profile: opt.profile, backend: opt.backend,
    server: { port: ports.server, heap: opt['server-heap'], gameDir: path.join(dir, 'server'), log: path.join(dir, 'server.log'), debugLog: path.join(dir, 'server', 'logs', 'debug.log') },
    clients: ['a', 'b'].slice(0, opt.clients).map(id => ({ id, username: `MP${opt.slot}_${id.toUpperCase()}`,
      profile: `${opt.profile}-${id}`, foremanPort: ports[id].foreman, devPort: ports[id].dev,
      heap: opt['client-heap'], gameDir: path.join(dir, `client-${id}`),
      debugLog: path.join(dir, `client-${id}`, 'logs', 'debug.log'), log: path.join(dir, `client-${id}.log`), foremanLog: path.join(dir, `foreman-${id}.log`) })) };
}
