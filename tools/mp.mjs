#!/usr/bin/env node
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseOptions } from './lib/mp/config.mjs';
import { runHarness } from './lib/mp/harness.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
let interrupted = false;
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => { interrupted = true; });
try {
  const [major, minor] = process.versions.node.split('.').map(Number);
  if (major < 22 || (major === 22 && minor < 18)) throw new Error('Node 22.18+ is required');
  const opt = parseOptions(process.argv.slice(2), root);
  if (opt.help) {
    console.log(`AgentCraft multiplayer harness
  node tools/mp.mjs up --slot N [--clients 1|2] [--backend sim|claude]
      [--home PATH] [--profile NAME] [--server-heap 2G] [--client-heap 2G]
      [--timeout 600] [--gradle-command EXECUTABLE] [--no-build] [--json]
  node tools/mp.mjs down --slot N [--json]
  node tools/mp.mjs status --slot N [--json]
Slot: 0..99. Default: 2 clients, sim, project .agentcraft-home, profile mp-N.
Requires npm ci in tools/ and foreman/, Java 25. Logs/records: artifacts/run/mp-N/.
Heap env: AGENTCRAFT_MP_SERVER_HEAP / AGENTCRAFT_MP_CLIENT_HEAP.
Gradle env: JAVA_HOME / GRADLE_USER_HOME inherited; AGENTCRAFT_MP_GRADLE overrides wrapper.
up returns after every client joins remotely. Always down afterwards.`);
  } else {
    const summary = await runHarness(root, opt, { progress: message => console.error(message),
      check: () => { if (interrupted) throw new Error('launcher interrupted'); } });
    if (opt.json) console.log(JSON.stringify({ ok: true, ...summary }, null, 2));
    else {
      console.log(`MP slot ${summary.slot}: ${summary.phase}; server :${summary.server.port}`);
      for (const client of summary.clients) console.log(`  ${client.id.toUpperCase()}: game ${client.running ? 'running' : 'stopped'}, Foreman :${client.foremanPort}, DevBridge :${client.devPort}`);
      console.log(`Records/logs: ${path.dirname(summary.stateFile)}`);
    }
  }
} catch (error) {
  if (process.argv.includes('--json')) console.log(JSON.stringify({ ok: false, error: error.message }));
  else console.error(`AgentCraft MP: ${error.message}`);
  process.exitCode = 1;
}
