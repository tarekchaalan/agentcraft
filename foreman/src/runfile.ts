// Run files: how launch scripts (and a second Foreman) find a running Foreman.
//
//   <home>/<profile>/foreman.json   the Foreman that owns this profile (authoritative)
//   <home>/foreman.json             the "primary" Foreman: the first live one; when it exits,
//                                   another live profile takes its place
//
// Two Foremen on one profile would both write state.json, so a second one is refused while the
// first is alive (pid alive AND its port answers - a reused pid alone does not count).
import fs from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import { readJson, writeJsonAtomic } from './util/fsx.js';

export interface RunInfo {
  pid: number;
  port: number;
  host: string;
  backend: string;
  profile: string;
  version: string;
  startedAt: string;
  /** the checkout this Foreman runs from, and its commit at start (the mod's launcher compares them to tell a stale Foreman) */
  root?: string;
  commit?: string;
}

export function profileRunFile(dataDir: string): string {
  return path.join(dataDir, 'foreman.json');
}

export function homeRunFile(home: string): string {
  return path.join(home, 'foreman.json');
}

function pidAlive(pid: number): boolean {
  if (!Number.isInteger(pid) || pid <= 0) return false;
  try {
    process.kill(pid, 0);
    return true;
  } catch (e) {
    return (e as NodeJS.ErrnoException).code === 'EPERM';
  }
}

function portAnswers(host: string, port: number, timeoutMs = 800): Promise<boolean> {
  return new Promise((resolve) => {
    const s = net.connect({ host, port });
    const done = (v: boolean) => {
      s.destroy();
      resolve(v);
    };
    s.setTimeout(timeoutMs, () => done(false));
    s.once('connect', () => done(true));
    s.once('error', () => done(false));
  });
}

function read(file: string): RunInfo | undefined {
  try {
    const r = readJson<RunInfo>(file);
    return r && typeof r.pid === 'number' ? r : undefined;
  } catch {
    return undefined;
  }
}

/** The run info in `file` if that Foreman is really running (and is not us). */
export async function liveOwner(file: string): Promise<RunInfo | undefined> {
  const r = read(file);
  if (!r || r.pid === process.pid || !pidAlive(r.pid)) return undefined;
  return (await portAnswers(r.host || '127.0.0.1', r.port)) ? r : undefined;
}

/** Record this Foreman: always in its profile; in <home> unless another live Foreman holds it. */
export async function claimRunFiles(home: string, dataDir: string, info: RunInfo): Promise<void> {
  writeJsonAtomic(profileRunFile(dataDir), info);
  const other = await liveOwner(homeRunFile(home));
  if (!other) writeJsonAtomic(homeRunFile(home), info);
}

/** Remove our run files; hand <home>/foreman.json to another live profile if there is one. */
export async function releaseRunFiles(home: string, dataDir: string): Promise<void> {
  const mine = (f: string) => read(f)?.pid === process.pid;
  if (mine(profileRunFile(dataDir))) fs.rmSync(profileRunFile(dataDir), { force: true });
  if (!mine(homeRunFile(home))) return;
  fs.rmSync(homeRunFile(home), { force: true });
  let next: RunInfo | undefined;
  try {
    for (const d of fs.readdirSync(home, { withFileTypes: true })) {
      if (!d.isDirectory()) continue;
      const r = await liveOwner(profileRunFile(path.join(home, d.name)));
      if (r && (!next || r.startedAt < next.startedAt)) next = r;
    }
  } catch {
    /* home unreadable: nothing to hand over */
  }
  if (next) writeJsonAtomic(homeRunFile(home), next);
}
