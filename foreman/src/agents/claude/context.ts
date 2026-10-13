// Claude Code context for agents: instruction files, skills and extra MCP servers, chosen in
// config.json `claude.context` (the only place they are configured).
//
// Agents run with `settingSources: []` and `strictMcpConfig: true`, so the CLI loads no CLAUDE.md,
// settings, hooks, skills or MCP config of its own. Turning those sources on would also load
// settings-file `permissions.allow` rules, repo hooks and a repo's `.mcp.json`, which run before (or
// outside) the permission policy in canUseTool. Instead the Foreman reads exactly what the user
// configured:
//
//   instructions  the repository's CLAUDE.md / .claude/CLAUDE.md / AGENTS.md / .claude/rules/*.md
//                 (default on), read from the BASE checkout (never a worker's worktree, which an
//                 agent can write to); the user's ~/.claude/CLAUDE.md (opt in); extra files by path.
//                 `@path` imports expanded like Claude Code does, each file once; one block appended
//                 to the role prompt, which stays in charge
//   skills        copied into a Foreman-owned plugin with `allowed-tools` stripped (that
//                 frontmatter pre-approves tools ahead of canUseTool), enabled one by one
//   mcpServers    stdio servers, validated when the config loads; their tools still ask unless
//                 `mcpAllow` names them (exactly, or `mcp__<server>__*`)
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import type { McpStdioServerConfig } from '@anthropic-ai/claude-agent-sdk';

/** A stdio MCP server the agents may use (the SDK's McpStdioServerConfig subset). */
export interface McpStdioConfig {
  command: string;
  args?: string[];
  env?: Record<string, string>;
}

export interface AgentContextConfig {
  /** the repository's CLAUDE.md, .claude/CLAUDE.md, AGENTS.md and .claude/rules/*.md (default true) */
  repoInstructions: boolean;
  /** the user's ~/.claude/CLAUDE.md (default false: it is written for interactive sessions) */
  userInstructions: boolean;
  /** more instruction files (absolute, `~/...`, or relative to the AgentCraft home, resolved at load) */
  files: string[];
  /** cap on the appended instructions (characters) */
  maxChars: number;
  /** skill directories or names under ~/.claude/skills */
  skills: string[];
  /** extra stdio MCP servers for every agent (never read from a repo's .mcp.json) */
  mcpServers: Record<string, McpStdioConfig>;
  /** MCP tools that run without asking: full names `mcp__<server>__<tool>`, or `mcp__<server>__*` */
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

// ---- config.json claude.context -----------------------------------------------------------------

/** Lowercase letters, digits and "-": no "_", so `mcp__<server>__<tool>` always splits the same way. */
const MCP_NAME = /^[a-z0-9-]+$/;
/** an mcpAllow entry: `mcp__<server>__<tool>`, or `mcp__<server>__*` for all of a server's tools */
const MCP_ALLOW = /^mcp__([a-z0-9-]+)__(\S+)$/;

function stringList(v: unknown): string[] {
  return Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string' && x.trim().length > 0) : [];
}

/**
 * claude.context.mcpServers: stdio servers only. A bad entry fails the config load rather than
 * silently giving the agents fewer (or other) tools than configured. `agentcraft*` is refused: the
 * policy approves every `mcp__agentcraft__*` tool by prefix.
 */
export function parseMcpServers(v: unknown): Record<string, McpStdioConfig> {
  if (v === undefined) return {};
  if (typeof v !== 'object' || v === null || Array.isArray(v)) throw new Error('claude.context.mcpServers must be an object of name -> {command, args?, env?}');
  const out: Record<string, McpStdioConfig> = {};
  for (const [name, raw] of Object.entries(v as Record<string, unknown>)) {
    if (!MCP_NAME.test(name) || name.startsWith('agentcraft')) throw new Error(`bad MCP server name "${name}" in claude.context.mcpServers (lowercase letters, digits and "-"; not "agentcraft*")`);
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) throw new Error(`MCP server "${name}" must be an object {command, args?, env?}`);
    const o = raw as Record<string, unknown>;
    if (o.type !== undefined && o.type !== 'stdio') throw new Error(`MCP server "${name}": only stdio servers ({command, args?, env?}) are supported, not "${String(o.type)}"`);
    if (typeof o.command !== 'string' || !o.command.trim()) throw new Error(`MCP server "${name}" needs a "command"`);
    if (o.args !== undefined && !(Array.isArray(o.args) && o.args.every((a) => typeof a === 'string'))) throw new Error(`MCP server "${name}": args must be a list of strings`);
    if (o.env !== undefined && !(o.env && typeof o.env === 'object' && !Array.isArray(o.env) && Object.values(o.env).every((x) => typeof x === 'string'))) {
      throw new Error(`MCP server "${name}": env must be an object of strings`);
    }
    out[name] = { command: o.command, ...(o.args ? { args: o.args as string[] } : {}), ...(o.env ? { env: o.env as Record<string, string> } : {}) };
  }
  return out;
}

/** claude.context.mcpAllow: full tool names or `mcp__<server>__*`, of a server in mcpServers. */
export function parseMcpAllow(v: unknown, servers: Record<string, McpStdioConfig>): string[] {
  if (v === undefined) return [];
  if (!Array.isArray(v) || !v.every((t) => typeof t === 'string')) throw new Error('claude.context.mcpAllow must be a list of tool names');
  for (const t of v as string[]) {
    const m = MCP_ALLOW.exec(t);
    if (!m || !(m[1]! in servers) || (m[2]!.includes('*') && m[2] !== '*')) {
      throw new Error(`bad mcpAllow entry "${t}" (use mcp__<server>__<tool>, or mcp__<server>__* for all its tools, of a server in claude.context.mcpServers)`);
    }
  }
  return v as string[];
}

/** True when `pattern` (an mcpAllow entry) allows `toolName`: the exact name, or `mcp__<server>__*`. */
export function mcpAllowMatches(pattern: string, toolName: string): boolean {
  if (pattern === toolName) return true;
  const m = MCP_ALLOW.exec(pattern);
  return !!m && m[2] === '*' && toolName.startsWith(pattern.slice(0, -1));
}

/**
 * config.json claude.context. `home` is the AgentCraft home: relative `files` resolve against it
 * (where config.json lives); `~/...` stays and is expanded when read. Unknown keys are ignored and
 * wrong types of the plain settings fall back to the defaults; MCP entries are validated.
 */
export function parseContextConfig(v: unknown, home: string): AgentContextConfig {
  if (v !== undefined && (v === null || typeof v !== 'object' || Array.isArray(v))) throw new Error('claude.context must be an object');
  const o = (v ?? {}) as Record<string, unknown>;
  const servers = parseMcpServers(o.mcpServers);
  return {
    repoInstructions: typeof o.repoInstructions === 'boolean' ? o.repoInstructions : DEFAULT_CONTEXT.repoInstructions,
    userInstructions: typeof o.userInstructions === 'boolean' ? o.userInstructions : DEFAULT_CONTEXT.userInstructions,
    files: stringList(o.files).map((f) => (/^~(?=$|[\\/])/.test(f) || path.isAbsolute(f) ? f : path.resolve(home, f))),
    maxChars: typeof o.maxChars === 'number' && o.maxChars > 0 ? o.maxChars : DEFAULT_CONTEXT.maxChars,
    skills: stringList(o.skills),
    mcpServers: servers,
    mcpAllow: parseMcpAllow(o.mcpAllow, servers),
  };
}

/**
 * The configured stdio servers for an agent working on the repository at `repoPath` (its base
 * checkout). engram resolves its project from the cwd, and a worktree without a remote would
 * resolve to its own directory name: pin it to the base repo's name so memories land in one project.
 */
export function mcpServersFor(cfg: AgentContextConfig, repoPath: string): Record<string, McpStdioServerConfig> {
  const out: Record<string, McpStdioServerConfig> = {};
  for (const [name, s] of Object.entries(cfg.mcpServers)) {
    const args = [...(s.args ?? [])];
    if (name === 'engram' && !args.includes('--project')) args.push('--project', path.basename(repoPath));
    out[name] = { type: 'stdio', command: s.command, args, ...(s.env ? { env: s.env } : {}) };
  }
  return out;
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

/** The repository's instruction files: CLAUDE.md, .claude/CLAUDE.md, AGENTS.md, then .claude/rules/*.md by name. */
export function repoInstructionFiles(repoPath: string): string[] {
  const files = ['CLAUDE.md', path.join('.claude', 'CLAUDE.md'), 'AGENTS.md'].map((f) => path.join(repoPath, f));
  try {
    const rules = path.join(repoPath, '.claude', 'rules');
    for (const f of fs.readdirSync(rules).sort()) if (f.endsWith('.md')) files.push(path.join(rules, f));
  } catch {
    /* no rules directory */
  }
  return files;
}

/**
 * The instruction files for an agent on the repository whose base checkout is `repoPath` (the lead's
 * cwd; a worker's worktree is made from it), as labelled sections.
 */
export function instructionSources(cfg: AgentContextConfig, repoPath: string, home = os.homedir()): Array<{ label: string; file: string }> {
  const out: Array<{ label: string; file: string }> = [];
  if (cfg.userInstructions) out.push({ label: 'Your user instructions', file: path.join(home, '.claude', 'CLAUDE.md') });
  for (const f of cfg.files) out.push({ label: 'Instructions', file: path.resolve(expandHome(f, home)) });
  if (cfg.repoInstructions) for (const file of repoInstructionFiles(repoPath)) out.push({ label: 'Repository instructions', file });
  return out;
}

/** A file's name in its heading: repo-relative when inside the repo, with "/" on every platform. */
function shownPath(file: string, repoPath: string): string {
  const rel = path.relative(repoPath, file);
  const inside = rel !== '' && !rel.startsWith('..') && !path.isAbsolute(rel);
  return (inside ? rel : file).split(path.sep).join('/');
}

/**
 * The one block appended to an agent's system prompt ('' when there is nothing to add): one header,
 * then each file once (a file another one imports, or listed twice, is not repeated). `warn` hears
 * about truncation.
 */
export function instructionsBlock(cfg: AgentContextConfig, repoPath: string, userName: string, home = os.homedir(), warn?: (msg: string) => void): string {
  const seen = new Set<string>();
  const parts: string[] = [];
  for (const s of instructionSources(cfg, repoPath, home)) {
    const body = readInstructions(s.file, { home, seen }).trim();
    if (body) parts.push(`## ${s.label} (${shownPath(s.file, repoPath)})\n\n${body}`);
  }
  if (!parts.length) return '';
  let text = parts.join('\n\n');
  if (text.length > cfg.maxChars) {
    warn?.(`instructions for ${repoPath} are ${text.length} characters, over claude.context.maxChars ${cfg.maxChars}: truncated`);
    text = `${text.slice(0, cfg.maxChars)}\n\n(instructions truncated at ${cfg.maxChars} characters)`;
  }
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
