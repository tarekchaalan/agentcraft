// Thin wrapper over the git CLI. Never uses a shell; never touches global config.
import os from 'node:os';
import path from 'node:path';
import { GIT_REDIRECT_VARS, PUSH_BLOCK_URL } from '../gitsafety.js';
import { run, type RunResult } from './proc.js';

export class GitError extends Error {
  constructor(
    message: string,
    readonly result: RunResult,
    readonly args: string[],
  ) {
    super(message);
    this.name = 'GitError';
  }
}

export interface GitOptions {
  env?: NodeJS.ProcessEnv;
  input?: string;
  /** Do not throw on non-zero exit. */
  allowFail?: boolean;
  timeoutMs?: number;
}

// No core.autocrlf override: in the user's own checkout the Foreman must see and write files exactly
// as the user's git does (with autocrlf=true, forcing false made CRLF files look modified, so merges
// were refused as "dirty", and an ff-only merge would have written LF files). Agent worktrees are
// checked out with LF explicitly (RepoManager.createWorktree); both configs read them as clean.
//
// The Foreman's own git calls (worktree add, commits, merges) never run repository hooks and never
// use a transport: a hook in the user's repo (post-commit, post-checkout, reference-transaction, husky)
// could otherwise push or run arbitrary code with the Foreman's environment. core.hooksPath points at
// a directory that never exists; protocol.allow=never + pushInsteadOf block every remote. The Foreman
// needs no network: everything it does is local.
const NO_HOOKS_DIR = path.join(os.tmpdir(), 'agentcraft-no-hooks-7f3e9c');
// The diff parser expects a/ b/ prefixes; the user's diff.mnemonicPrefix, diff.noprefix or
// diff.srcPrefix/dstPrefix would otherwise turn src/cli.ts into i/src/cli.ts or cli.ts.
const BASE_ARGS = [
  '-c', 'core.quotepath=false', '-c', 'color.ui=false', '-c', 'commit.gpgsign=false',
  '-c', 'diff.mnemonicPrefix=false', '-c', 'diff.noprefix=false', '-c', 'diff.srcPrefix=a/', '-c', 'diff.dstPrefix=b/',
  '-c', `core.hooksPath=${NO_HOOKS_DIR}`,
  '-c', 'protocol.allow=never',
  '-c', `url.${PUSH_BLOCK_URL}.pushInsteadOf=`,
];

/**
 * process.env without variables that would point git at another repository, work tree or index
 * (e.g. GIT_DIR inherited from a git hook that launched the Foreman): every git call names its
 * repository by cwd. Explicit `opts.env` entries (a temp GIT_INDEX_FILE) are added back on top.
 */
function baseEnv(): NodeJS.ProcessEnv {
  const env: NodeJS.ProcessEnv = { ...process.env };
  for (const k of Object.keys(env)) if (GIT_REDIRECT_VARS.includes(k.toUpperCase())) delete env[k];
  return env;
}

export async function git(cwd: string, args: string[], opts: GitOptions = {}): Promise<RunResult> {
  const env = { ...baseEnv(), GIT_TERMINAL_PROMPT: '0', GCM_INTERACTIVE: 'never', GIT_ALLOW_PROTOCOL: 'agentcraft-none', GIT_OPTIONAL_LOCKS: '0', LC_ALL: 'C', ...(opts.env ?? {}) };
  const res = await run('git', [...BASE_ARGS, ...args], { cwd, env, input: opts.input, timeoutMs: opts.timeoutMs ?? 120_000 });
  if (res.code !== 0 && !opts.allowFail) {
    const msg = (res.stderr || res.stdout).trim().split('\n').slice(-3).join(' | ');
    throw new GitError(`git ${args.slice(0, 3).join(' ')} failed: ${msg}`, res, args);
  }
  return res;
}

export async function gitOut(cwd: string, args: string[], opts: GitOptions = {}): Promise<string> {
  return (await git(cwd, args, opts)).stdout.trim();
}

/**
 * The user's own git config value (repo, global, system), read WITHOUT the Foreman's `-c`
 * overrides above (which would otherwise answer e.g. commit.gpgsign=false).
 */
export async function gitConfigGet(cwd: string, key: string, type?: 'bool'): Promise<string | undefined> {
  const env = { ...baseEnv(), GIT_TERMINAL_PROMPT: '0', LC_ALL: 'C' };
  const res = await run('git', ['config', ...(type ? [`--type=${type}`] : []), '--get', key], { cwd, env, timeoutMs: 15_000 });
  const v = res.stdout.trim();
  return res.code === 0 && v ? v : undefined;
}

/** The placeholder git identity of an agent ("AgentCraft Kit <kit@agentcraft.local>"); 'user' -> "AgentCraft". */
export function agentGitIdentity(agentId: string): NodeJS.ProcessEnv {
  const name = agentId === 'user' ? 'AgentCraft' : `AgentCraft ${agentId[0]!.toUpperCase()}${agentId.slice(1)}`;
  return identityEnv(name, `${agentId}@agentcraft.local`);
}

export function identityEnv(name: string, email: string): NodeJS.ProcessEnv {
  return {
    GIT_AUTHOR_NAME: name,
    GIT_AUTHOR_EMAIL: email,
    GIT_COMMITTER_NAME: name,
    GIT_COMMITTER_EMAIL: email,
  };
}

export interface WorktreeListEntry {
  path: string;
  head?: string;
  branch?: string; // short name, e.g. "main"
  detached: boolean;
  bare: boolean;
}

export async function listWorktrees(repoPath: string, opts: GitOptions = {}): Promise<WorktreeListEntry[]> {
  const out = (await git(repoPath, ['worktree', 'list', '--porcelain'], opts)).stdout;
  const entries: WorktreeListEntry[] = [];
  let cur: WorktreeListEntry | undefined;
  for (const line of out.split(/\r?\n/)) {
    if (line.startsWith('worktree ')) {
      cur = { path: line.slice(9), detached: false, bare: false };
      entries.push(cur);
    } else if (!cur) continue;
    else if (line.startsWith('HEAD ')) cur.head = line.slice(5);
    else if (line.startsWith('branch ')) cur.branch = line.slice(7).replace(/^refs\/heads\//, '');
    else if (line === 'detached') cur.detached = true;
    else if (line === 'bare') cur.bare = true;
  }
  return entries;
}
