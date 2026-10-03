// Folder mode: AgentCraft works in any existing folder, not only in a git repository with commits.
// Empty folder, folder with files, git repository without commits, folder inside another repository.
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { MERGE_OPTIONS } from '../src/protocol.js';
import { countFolderFiles, FOLDER_DEFAULT_EXCLUDES } from '../src/repos.js';
import { demoRepo, makeForeman, rmrf, tempDir, type Harness } from './helpers.js';

let h: Harness;
let home: string;
const cleanup: string[] = [];

beforeAll(() => {
  home = tempDir();
  h = makeForeman(home, ['--backend', 'sim']);
});
afterAll(async () => {
  await h.fm.close();
  rmrf(home);
  for (const d of cleanup) rmrf(d);
});

function folder(files: Record<string, string> = {}): string {
  const dir = tempDir('ac-folder-');
  cleanup.push(dir);
  for (const [name, text] of Object.entries(files)) {
    fs.mkdirSync(path.dirname(path.join(dir, name)), { recursive: true });
    fs.writeFileSync(path.join(dir, name), text);
  }
  return dir;
}

const sh = (cwd: string, ...args: string[]): string => {
  try {
    return execFileSync('git', args, { cwd, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
  } catch (e) {
    return `FAILED ${(e as { stderr?: string }).stderr ?? ''}`.trim();
  }
};

/** a worker starts a task on the repo and writes files into its worktree */
async function work(repoId: string, agent: string, title: string, files: Record<string, string>) {
  const t = h.fm.tasks.create({ title, createdBy: 'marlow', repoId, assignee: agent });
  const wt = await h.fm.repos.createWorktree(repoId, agent, t);
  for (const [name, text] of Object.entries(files)) {
    fs.mkdirSync(path.dirname(path.join(wt.path, name)), { recursive: true });
    fs.writeFileSync(path.join(wt.path, name), text);
  }
  return { task: t, wt };
}

function approve(repoId: string, worktree: string) {
  const d = h.fm.createDecision({ agentId: 'marlow', kind: 'merge', question: `Merge ${worktree}?`, options: [...MERGE_OPTIONS], repoId, worktree });
  h.fm.decisions.answer(d.id, 'Merge');
  return h.fm.repos.merge(h.fm.decisions.get(d.id)!);
}

describe('folder mode', () => {
  it('works in an empty folder that is not a git repository, and never creates a .git in it', async () => {
    const dir = folder();
    const r = await h.fm.repos.add(dir);
    expect(r.mode).toBe('folder');
    expect(r.branch).toBe('main');
    expect(r.dirty).toBe(false);
    expect(fs.readdirSync(dir)).toEqual([]);
    expect(fs.existsSync(path.join(h.fm.repos.shadowRoot, `${r.id}.git`, 'HEAD'))).toBe(true);
    expect(h.fm.repos.shadowRoot.startsWith(path.resolve(h.cfg.dataDir))).toBe(true);
    expect((await h.fm.repos.add(dir)).id).toBe(r.id); // idempotent

    const { wt } = await work(r.id, 'kit', 'Snake game', { 'snake.py': 'print("snake")\n', 'README.md': '# Snake\n' });
    const d = await h.fm.repos.diff(r.id, wt.id);
    expect(d.files.map((f) => f.path).sort()).toEqual(['README.md', 'snake.py']);
    expect(fs.readdirSync(dir)).toEqual([]); // nothing reaches the folder before the user approves

    const res = await approve(r.id, wt.id);
    expect(res.files).toBe(2);
    expect(fs.readFileSync(path.join(dir, 'snake.py'), 'utf8')).toBe('print("snake")\n');
    expect(fs.readdirSync(dir).sort()).toEqual(['README.md', 'snake.py']); // no .git, nothing else
    expect(h.fm.repos.get(r.id)!.worktrees[0]!.status).toBe('merged');

    // the next task starts from the merged folder
    const next = await work(r.id, 'juniper', 'Add scores', {});
    expect(fs.existsSync(path.join(next.wt.path, 'snake.py'))).toBe(true);
    // and the merged diff stays readable after the worktree directory is gone
    const merged = await h.fm.repos.diff(r.id, wt.id);
    expect(merged.files.map((f) => f.path).sort()).toEqual(['README.md', 'snake.py']);
  });

  it('starts agents from the existing files, keeps dependency folders and ignored files out, and keeps your edits made in the meantime', async () => {
    const dir = folder({
      'a.txt': 'one\ntwo\nthree\n',
      '.gitignore': 'secret.log\n',
      'secret.log': 'token\n',
      'node_modules/dep/index.js': 'module.exports = 1;\n',
    });
    const r = await h.fm.repos.add(dir);
    expect(r.mode).toBe('folder');
    const { wt } = await work(r.id, 'kit', 'Edit a', { 'a.txt': 'ONE\ntwo\nthree\n', 'b.txt': 'agent\n' });
    expect(fs.readFileSync(path.join(wt.path, 'a.txt'), 'utf8')).toBe('ONE\ntwo\nthree\n');
    expect(fs.existsSync(path.join(wt.path, 'node_modules'))).toBe(false);
    expect(fs.existsSync(path.join(wt.path, 'secret.log'))).toBe(false);

    // you keep working in the folder while the agent works
    fs.writeFileSync(path.join(dir, 'a.txt'), 'one\ntwo\nTHREE\n');
    fs.writeFileSync(path.join(dir, 'c.txt'), 'yours\n');
    expect(h.fm.repos.get(r.id)!.dirty).toBe(false);

    await approve(r.id, wt.id);
    expect(fs.readFileSync(path.join(dir, 'a.txt'), 'utf8')).toBe('ONE\ntwo\nTHREE\n'); // both edits
    expect(fs.readFileSync(path.join(dir, 'b.txt'), 'utf8')).toBe('agent\n');
    expect(fs.readFileSync(path.join(dir, 'c.txt'), 'utf8')).toBe('yours\n');
    expect(fs.readFileSync(path.join(dir, 'secret.log'), 'utf8')).toBe('token\n');
    expect(fs.readFileSync(path.join(dir, 'node_modules', 'dep', 'index.js'), 'utf8')).toBe('module.exports = 1;\n');
    expect(fs.existsSync(path.join(dir, '.git'))).toBe(false);
  });

  it('a file you added that the agent also added is a conflict, never an overwrite', async () => {
    const dir = folder({ 'a.txt': 'a\n' });
    const r = await h.fm.repos.add(dir);
    const { wt } = await work(r.id, 'kit', 'Add c', { 'c.txt': 'agent\n' });
    fs.writeFileSync(path.join(dir, 'c.txt'), 'yours\n');
    await expect(approve(r.id, wt.id)).rejects.toMatchObject({ code: 'conflict', files: ['c.txt'] });
    expect(fs.readFileSync(path.join(dir, 'c.txt'), 'utf8')).toBe('yours\n');
    expect(h.fm.repos.get(r.id)!.worktrees[0]!.status).toBe('active'); // goes back to the worker
  });

  it('works in a git repository that has no commits yet, and leaves that repository alone', async () => {
    const cases: Array<Record<string, string>> = [{}, { 'a.txt': 'hello\n' }];
    for (const files of cases) {
      const dir = folder(files);
      sh(dir, 'init', '-q');
      expect(sh(dir, 'rev-parse', '--verify', 'HEAD')).toMatch(/^FAILED/); // really no commits
      const before = sh(dir, 'count-objects', '-v');
      const r = await h.fm.repos.add(dir);
      expect(r.mode).toBe('folder');
      const { wt } = await work(r.id, 'kit', 'Add b', { 'b.txt': 'agent\n' });
      if ('a.txt' in files) expect(fs.readFileSync(path.join(wt.path, 'a.txt'), 'utf8')).toBe('hello\n');
      await approve(r.id, wt.id);
      expect(fs.readFileSync(path.join(dir, 'b.txt'), 'utf8')).toBe('agent\n');
      // no commit, branch, object or ref was made in your repository: the files are yours to commit
      expect(sh(dir, 'rev-parse', '--verify', 'HEAD')).toMatch(/^FAILED/);
      expect(sh(dir, 'for-each-ref')).toBe('');
      expect(sh(dir, 'count-objects', '-v')).toBe(before);
      expect(sh(dir, 'status', '--porcelain')).toContain('b.txt');
    }
  });

  it('treats a folder inside another repository as a folder and does not touch that repository', async () => {
    const repo = await demoRepo();
    cleanup.push(path.dirname(repo));
    const sub = path.join(repo, 'new-app');
    fs.mkdirSync(sub);
    const headBefore = sh(repo, 'rev-parse', 'HEAD');
    const refsBefore = sh(repo, 'for-each-ref');
    const r = await h.fm.repos.add(sub);
    expect(r.mode).toBe('folder');
    expect(r.path).toBe(path.resolve(sub));
    const { wt } = await work(r.id, 'kit', 'Hello', { 'hello.txt': 'hi\n' });
    await approve(r.id, wt.id);
    expect(fs.readFileSync(path.join(sub, 'hello.txt'), 'utf8')).toBe('hi\n');
    expect(sh(repo, 'rev-parse', 'HEAD')).toBe(headBefore);
    expect(sh(repo, 'for-each-ref')).toBe(refsBefore);
    expect(sh(repo, 'status', '--porcelain')).toContain('new-app/');
  });

  it('keeps a git repository with commits in git mode', async () => {
    const repo = await demoRepo();
    cleanup.push(path.dirname(repo));
    const r = await h.fm.repos.add(repo);
    expect(r.mode).toBe('git');
    const { wt } = await work(r.id, 'kit', 'Note', { 'NOTE.md': 'x\n' });
    const before = sh(repo, 'rev-list', '--count', 'HEAD');
    await approve(r.id, wt.id);
    expect(Number(sh(repo, 'rev-list', '--count', 'HEAD'))).toBeGreaterThan(Number(before)); // a real commit
  });

  it('refuses a home directory, a drive root and an oversized folder', async () => {
    await expect(h.fm.repos.add(os.homedir())).rejects.toThrow(/home directory/);
    await expect(h.fm.repos.add(path.parse(process.cwd()).root)).rejects.toThrow(/home directory or a drive root/);
    const dir = folder();
    for (let i = 0; i < 12; i++) fs.writeFileSync(path.join(dir, `f${i}.txt`), 'x');
    fs.mkdirSync(path.join(dir, 'node_modules'));
    for (let i = 0; i < 50; i++) fs.writeFileSync(path.join(dir, 'node_modules', `m${i}.js`), 'x');
    expect(countFolderFiles(dir, 100)).toBe(12); // dependency folders are not counted
    expect(countFolderFiles(dir, 5)).toBeGreaterThan(5); // stops early once over the cap
    expect(FOLDER_DEFAULT_EXCLUDES).toContain('node_modules/');
  });

  it('survives a restart: the mode is saved and the private repository is reused', async () => {
    const dir = folder({ 'a.txt': 'a\n' });
    const r = await h.fm.repos.add(dir);
    await h.fm.close();
    h = makeForeman(home, ['--backend', 'sim']);
    const again = h.fm.repos.get(r.id)!;
    expect(again.mode).toBe('folder');
    expect((await h.fm.repos.add(dir)).id).toBe(r.id);
    const { wt } = await work(r.id, 'wren', 'After restart', { 'z.txt': 'z\n' });
    await approve(r.id, wt.id);
    expect(fs.readFileSync(path.join(dir, 'z.txt'), 'utf8')).toBe('z\n');
  });
});
