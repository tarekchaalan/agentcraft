import { createHash } from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { DevClient } from '../devclient.mjs';
import { sleep } from './processes.mjs';
export { portsForSlot, makePlan, parseOptions } from './config.mjs';

export async function clientCommand(plan, id, type, payload = {}, { timeoutMs = 10_000, connect = DevClient.connect } = {}) {
  const client = plan.clients.find(c => c.id === id.toLowerCase());
  if (!client) throw new Error(`client ${id} is not in this harness`);
  const dev = await connect({ host: '127.0.0.1', port: client.devPort, timeoutMs });
  try { return await dev.call(type, payload, { timeoutMs }); }
  finally { dev.close(); }
}

export function isRemoteReady(state, client) {
  // Before MP-12 adds mode/server fields, dev.state uses null world.name for a remote
  // connection. Require a loaded dimension, expected player and own synced Foreman too.
  return state?.ok === true && state.ready === true && state.world?.name === null &&
    typeof state.world.dimension === 'string' && state.player?.name === client.username &&
    state.foreman?.connected === true && state.foreman.url === `ws://127.0.0.1:${client.foremanPort}`;
}

export async function waitForClients(plan, { timeoutMs = 600_000, command = clientCommand, check = () => {}, onWait = () => {},
  read = readLogLines, now = Date.now, delay = sleep } = {}) {
  const deadline = now() + timeoutMs;
  const scan = bridgeErrorScanner(plan, read);
  const states = {};
  while (now() < deadline) {
    scan();
    check();
    const replies = await Promise.allSettled(plan.clients.map(async client => {
      const state = await command(plan, client.id, 'dev.state', {}, { timeoutMs: Math.min(2000, Math.max(1, deadline - now())) });
      states[client.id] = state;
      return isRemoteReady(state, client);
    }));
    scan();
    check(); // an interruption or a process exit during the requests must not be reported as ready
    if (now() >= deadline) break;
    if (replies.every(reply => reply.status === 'fulfilled' && reply.value)) return states;
    onWait(states);
    await delay(Math.min(500, Math.max(0, deadline - now())));
  }
  throw new Error(`clients did not become ready on the remote server within ${timeoutMs}ms; see client logs`);
}

// A log's generation is a digest of its first complete lines (at most 4 KiB), which an
// append-only file never rewrites. Two logs with identical heads are indistinguishable.
function logGeneration(fd, length) {
  const head = Buffer.alloc(Math.min(length, 4096));
  return `${head.length}-${createHash('sha256').update(head.subarray(0, fs.readSync(fd, head, 0, head.length, 0))).digest('hex').slice(0, 16)}`;
}

/** Pass the returned offset and generation back together; an offset alone misses a regrown log. */
export function readLogLines(file, { offset = 0, generation, maxBytes = 1024 * 1024 } = {}) {
  if (!Number.isInteger(offset) || offset < 0) throw new Error('invalid log offset');
  if (generation !== undefined && !/^\d{1,4}-[0-9a-f]{16}$/.test(generation)) throw new Error('invalid log generation');
  if (!Number.isInteger(maxBytes) || maxBytes < 1024 || maxBytes > 8 * 1024 * 1024) throw new Error('maxBytes must be 1KB..8MB');
  let data;
  let fd;
  try {
    fd = fs.openSync(file, 'r');
    const size = fs.fstatSync(fd).size;
    // A new up truncates or replaces logs. A shorter file shows that by itself; one that has
    // regrown past the offset only by its generation.
    if (offset > size || (offset && generation && generation !== logGeneration(fd, Number.parseInt(generation, 10)))) offset = 0;
    data = Buffer.alloc(Math.min(maxBytes, size - offset));
    data = data.subarray(0, fs.readSync(fd, data, 0, data.length, offset));
    const next = offset + data.lastIndexOf(10) + 1;
    generation = next ? logGeneration(fd, next) : undefined;
  }
  catch (error) { if (error.code === 'ENOENT') return { offset: 0, lines: [] }; throw error; }
  finally { if (fd !== undefined) fs.closeSync(fd); }
  const cursor = generation ? { generation } : {};
  const last = data.lastIndexOf(10);
  if (last < 0) {
    if (data.length === maxBytes) throw new Error(`log line exceeds ${maxBytes} bytes`);
    return { offset, ...cursor, lines: [] };
  }
  return { offset: offset + last + 1, ...cursor, lines: data.subarray(0, last + 1).toString('utf8').split(/\r?\n/) };
}

function readEvents(source, options = {}) {
  const { lines, ...cursor } = readLogLines(source.debugLog ?? path.join(source.gameDir, 'logs', 'debug.log'), options);
  const events = lines.flatMap(line => {
    const name = /\bevent=([a-z_0-9]+)/.exec(line)?.[1];
    if (!name || (options.event && name !== options.event)) return [];
    return [{ event: name, fields: Object.fromEntries([...line.matchAll(/\b([A-Za-z_][A-Za-z_0-9]*)=([^\s]+)/g)]
      .map(match => [match[1], match[2]])), line }];
  });
  return { ...cursor, events };
}

/**
 * Read DEBUG telemetry, independently of the INFO-only stdout capture. To continue, pass back
 * both `offset` and `generation`: a log replaced by a later `up` is then read from its start.
 */
export const readServerEvents = (plan, options) => readEvents(plan.server, options);
export function readClientEvents(plan, id, options) {
  const client = plan.clients.find(c => c.id === id.toLowerCase());
  if (!client) throw new Error(`client ${id} is not in this harness`);
  return readEvents(client, options);
}

export function classifyBridgeError(client, line) {
  const match = /DevBridge server error \(port (\d+)\):\s*(.*)/.exec(line);
  if (!match || Number(match[1]) !== client.devPort) return null;
  const error = new Error(`client ${client.id.toUpperCase()} DevBridge port ${client.devPort}: ${match[2]}; see ${client.log}`);
  return Object.assign(error, { client: client.id, bindFailure: /BindException|Address already in use|EADDRINUSE/.test(match[2]) });
}

function bridgeErrorScanner(plan, read) {
  const offsets = new Map();
  return () => {
    for (const client of plan.clients) {
      let offset = offsets.get(client.id) ?? 0;
      for (;;) {
        const chunk = read(client.log, { offset });
        for (const line of chunk.lines) {
          const error = classifyBridgeError(client, line);
          if (error) throw error;
        }
        if (chunk.offset === offset) break;
        offset = chunk.offset;
      }
      offsets.set(client.id, offset);
    }
  };
}

/** One retry per client, sharing the readiness deadline with stop, cooldown and relaunch. */
export async function waitWithBindRecovery(plan, { timeoutMs = 600_000, wait = waitForClients,
  stopClient, relaunchClient, platform = process.platform, now = Date.now, delay = sleep,
  progress = () => {}, check = () => {}, ...options } = {}) {
  const deadline = now() + timeoutMs;
  const retried = new Set();
  for (;;) {
    try { return await wait(plan, { ...options, check, now, delay, timeoutMs: Math.max(0, deadline - now()) }); }
    catch (error) {
      if (!error.bindFailure || retried.has(error.client)) throw error;
      retried.add(error.client);
      const cooldown = platform === 'win32' ? 0 : platform === 'darwin' ? 35_000 : 65_000;
      if (deadline - now() <= cooldown) throw new Error(`${error.message}; insufficient --timeout remaining for bind recovery`);
      progress(`${error.message}; waiting ${cooldown / 1000}s for TIME_WAIT, then relaunching this client once.`);
      await stopClient(error.client, deadline - now());
      const until = now() + cooldown;
      while (now() < until && now() < deadline) {
        check();
        await delay(Math.min(500, until - now(), deadline - now()));
      }
      if (now() >= deadline) throw new Error(`${error.message}; bind recovery timed out`);
      await relaunchClient(error.client, deadline - now());
      if (now() >= deadline) throw new Error(`${error.message}; bind recovery timed out`);
    }
  }
}

export function readLogTail(file, maxBytes = 64 * 1024) {
  const fd = fs.openSync(file, 'r');
  try {
    const size = fs.fstatSync(fd).size;
    const data = Buffer.alloc(Math.min(maxBytes, size));
    return data.subarray(0, fs.readSync(fd, data, 0, data.length, Math.max(0, size - maxBytes))).toString('utf8');
  } finally { fs.closeSync(fd); }
}
