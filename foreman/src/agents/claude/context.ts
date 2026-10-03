// Claude Code context for agents: instruction files, skills and extra MCP servers, chosen in
// config.json `claude.context`.
//
// Agents run with `settingSources: []`, so the CLI loads no CLAUDE.md, settings, hooks, skills or
// MCP config of its own. Turning those sources on would also load settings-file `permissions.allow`
// rules and repo hooks, which run before (or outside) the permission policy in canUseTool. Instead
// the Foreman reads exactly what the user configured:
//
//   instructions  repo CLAUDE.md / AGENTS.md (default on), the user's ~/.claude/CLAUDE.md (opt in),
//                 extra files by path; `@path` imports expanded like Claude Code does; appended to
//                 the role prompt, which stays in charge
//   skills        copied into a Foreman-owned plugin with `allowed-tools` stripped (that
//                 frontmatter pre-approves tools ahead of canUseTool), enabled one by one
//   mcpServers    passed to the SDK as given; their tools still ask unless they match `mcpAllow`
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import type { McpServerConfig } from '@anthropic-ai/claude-agent-sdk';

export interface AgentContextConfig {
  /** the repository's CLAUDE.md, .claude/CLAUDE.md and AGENTS.md (default true) */
  repoInstructions: boolean;
  /** the user's ~/.claude/CLAUDE.md (default false: it is written for interactive sessions) */
  userInstructions: boolean;
  /** more instruction files (absolute or ~ paths) */
  files: string[];
  /** cap on the appended instructions (characters) */
  maxChars: number;
  /** skill directories or names under ~/.claude/skills */
  skills: string[];
  /** extra MCP servers for every agent */
  mcpServers: Record<string, McpServerConfig>;
  /** MCP tool names (or `prefix*`) that run without asking, e.g. "mcp__xcode__*" */
  mcpAllow: string[];
}

export const DEFAULT_CONTEXT: AgentContextConfig = {
  repoInstructions: true,
  userInstructions: false,
  files: [],
  maxChars: 24_000,
  skills: [],
  mcpServers: {},
  mcpAllow: [],
};

export const SKILLS_PLUGIN = 'agentcraft-skills';
const MAX_IMPORT_DEPTH = 5;

export function expandHome(p: string, home = os.homedir()): string {
  return p.replace(/^~(?=$|[\\/])/, home);
}

function realOrSelf(p: string): string {
  try {
    return fs.realpathSync.native(p);
  } catch {
    return path.resolve(p);
  }
}

function isFile(p: string): boolean {
  try {
    return fs.statSync(p).isFile();
  } catch {
    return false;
  }
}

/**
 * A memory file with its `@path` imports expanded in place (Claude Code's syntax: `@` + a path, not
 * inside code spans or fenced blocks, relative to the importing file, up to 5 levels). An import
 * that is missing, a directory, or already included stays as written.
 */
export function readInstructions(file: string, opts: { home?: string; seen?: Set<string>; depth?: number } = {}): string {
  const seen = opts.seen ?? new Set<string>();
  const depth = opts.depth ?? 0;
  const real = realOrSelf(file);
  if (seen.has(real) || !isFile(file)) return '';
  seen.add(real);
  const text = fs.readFileSync(file, 'utf8').replace(/\r\n/g, '\n');
  if (depth >= MAX_IMPORT_DEPTH) return text;
  let fenced = false;
  return text
    .split('\n')
    .map((line) => {
      if (/^\s*(```|~~~)/.test(line)) {
        fenced = !fenced;
        return line;
      }
      if (fenced) return line;
      // split on inline code spans; only text outside them is scanned
      return line
        .split(/(`[^`]*`)/)
        .map((part) =>
          part.startsWith('`')
            ? part
            : part.replace(/(^|\s)@((?:~|\.{1,2})?\/?[\w.@~+-]+(?:\/[\w.@~+-]+)*)/g, (m, lead: string, ref: string) => {
                const target = path.resolve(path.dirname(file), expandHome(ref, opts.home));
                if (!isFile(target) || seen.has(realOrSelf(target))) return m;
                const body = readInstructions(target, { ...opts, seen, depth: depth + 1 }).trim();
                return body ? `${lead}${body}` : m;
              }),
        )
        .join('');
    })
    .join('\n');
}

/** The instruction files for an agent working in `cwd`, as labelled sections. */
export function instructionSources(cfg: AgentContextConfig, cwd: string, home = os.homedir()): Array<{ label: string; file: string }> {
  const out: Array<{ label: string; file: string }> = [];
  if (cfg.userInstructions) out.push({ label: 'Your user instructions', file: path.join(home, '.claude', 'CLAUDE.md') });
  for (const f of cfg.files) out.push({ label: 'Instructions', file: path.resolve(expandHome(f, home)) });
  if (cfg.repoInstructions) {
    for (const f of ['CLAUDE.md', path.join('.claude', 'CLAUDE.md'), 'AGENTS.md']) out.push({ label: 'Repository instructions', file: path.join(cwd, f) });
  }
  return out;
}

/** The block appended to an agent's system prompt ('' when there is nothing to add). */
export function instructionsBlock(cfg: AgentContextConfig, cwd: string, userName: string, home = os.homedir()): string {
  const seen = new Set<string>();
  const parts: string[] = [];
  for (const s of instructionSources(cfg, cwd, home)) {
    const body = readInstructions(s.file, { home, seen }).trim();
    if (body) parts.push(`## ${s.label} (${s.file.startsWith(cwd) ? path.relative(cwd, s.file) : s.file})\n\n${body}`);
  }
  if (!parts.length) return '';
  let text = parts.join('\n\n');
  if (text.length > cfg.maxChars) text = `${text.slice(0, cfg.maxChars)}\n\n(instructions truncated at ${cfg.maxChars} characters)`;
  return `# Instructions from ${userName}'s files
Follow these where they apply to your task. Your AgentCraft role and its rules above take precedence where they conflict (your worktree, no push, how you hand work back, asking ${userName} through ask_user).

${text}`;
}

function skillSource(spec: string, home: string): string {
  const p = expandHome(spec, home);
  return /[\\/]/.test(spec) ? path.resolve(p) : path.join(home, '.claude', 'skills', spec);
}

/** Remove `allowed-tools` (it pre-approves tools ahead of the permission policy) from SKILL.md front matter. */
export function stripAllowedTools(skillMd: string): string {
  const text = skillMd.replace(/\r\n/g, '\n');
  if (!text.startsWith('---\n')) return skillMd;
  const end = text.indexOf('\n---', 4);
  if (end < 0) return skillMd;
  const lines = text.slice(4, end).split('\n');
  const kept: string[] = [];
  for (let i = 0; i < lines.length; i++) {
    if (/^allowed[-_]tools\s*:/i.test(lines[i]!)) {
      // and its continuation lines (YAML list / block)
      while (i + 1 < lines.length && /^\s+\S|^\s*-\s/.test(lines[i + 1]!)) i++;
      continue;
    }
    kept.push(lines[i]!);
  }
  return `---\n${kept.join('\n')}${text.slice(end)}`;
}

/**
 * Build the Foreman's skills plugin under `dir` from the configured skills (replacing what was
 * there). Returns the enabled skill ids ("agentcraft-skills:<name>") and any problems.
 */
export function buildSkillsPlugin(skills: string[], dir: string, home = os.homedir()): { ids: string[]; problems: string[] } {
  fs.rmSync(dir, { recursive: true, force: true });
  const ids: string[] = [];
  const problems: string[] = [];
  if (!skills.length) return { ids, problems };
  fs.mkdirSync(path.join(dir, '.claude-plugin'), { recursive: true });
  fs.writeFileSync(path.join(dir, '.claude-plugin', 'plugin.json'), `${JSON.stringify({ name: SKILLS_PLUGIN, version: '1.0.0', description: 'Skills the AgentCraft user enabled for agents' }, null, 2)}\n`);
  for (const spec of skills) {
    const src = skillSource(spec, home);
    const name = path.basename(src);
    if (!/^[a-z0-9][a-z0-9_-]*$/i.test(name) || !isFile(path.join(src, 'SKILL.md'))) {
      problems.push(`skill ${spec}: no SKILL.md in ${src}`);
      continue;
    }
    const dest = path.join(dir, 'skills', name);
    if (fs.existsSync(dest)) {
      problems.push(`skill ${spec}: a skill named ${name} is already enabled`);
      continue;
    }
    // a copy, not a link: the agent sees exactly what was enabled, and edits to it don't reach the source
    fs.cpSync(src, dest, { recursive: true, dereference: true });
    const md = path.join(dest, 'SKILL.md');
    fs.writeFileSync(md, stripAllowedTools(fs.readFileSync(md, 'utf8')));
    ids.push(`${SKILLS_PLUGIN}:${name}`);
  }
  return { ids, problems };
}
