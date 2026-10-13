// Context the Foreman hands to agents in place of settingSources: the user's own instructions file and
// each repo's CLAUDE.md + .claude/rules, read from the BASE checkout (never the agent's worktree, which
// an agent can write to), plus the MCP servers named in config.
import fs from 'node:fs';
import path from 'node:path';
import type { McpStdioServerConfig } from '@anthropic-ai/claude-agent-sdk';
import type { McpStdioConfig } from '../../config.js';

/** cap on injected repo context; the rest is dropped with a warning */
export const REPO_CONTEXT_MAX_BYTES = 40_000;

export function readInstructions(file: string | undefined): string {
  if (!file) return '';
  try {
    return fs.readFileSync(file, 'utf8').trim();
  } catch (e) {
    throw new Error(`cannot read claude.instructionsFile ${file}: ${(e as Error).message}`);
  }
}

/** CLAUDE.md and .claude/rules/*.md of the repo checkout; no imports, no nested files, no settings. */
export function readRepoContext(repoPath: string, warn: (msg: string) => void): string {
  const files = [path.join(repoPath, 'CLAUDE.md')];
  try {
    const dir = path.join(repoPath, '.claude', 'rules');
    for (const f of fs.readdirSync(dir).sort()) if (f.endsWith('.md')) files.push(path.join(dir, f));
  } catch {
    /* no rules dir */
  }
  const parts: string[] = [];
  let size = 0;
  for (const f of files) {
    let text: string;
    try {
      text = fs.readFileSync(f, 'utf8').trim();
    } catch {
      continue;
    }
    if (!text) continue;
    // repo-relative, with "/" on every platform (".claude/rules/a.md", as the repo's own docs write it)
    const rel = path.relative(repoPath, f).split(path.sep).join('/');
    const part = `## ${rel}\n${text}`;
    if (size + part.length > REPO_CONTEXT_MAX_BYTES) {
      warn(`repo context for ${repoPath} over ${REPO_CONTEXT_MAX_BYTES} bytes: skipped ${rel} and later files`);
      break;
    }
    size += part.length;
    parts.push(part);
  }
  return parts.join('\n\n');
}

/**
 * The configured stdio servers for an agent working on `repoPath`. engram resolves its project from
 * the cwd, and a worktree without a remote would resolve to its own directory name: pin it to the
 * base repo's name so memories land in one project.
 */
export function configuredMcpServers(servers: Record<string, McpStdioConfig>, repoPath: string): Record<string, McpStdioServerConfig> {
  const out: Record<string, McpStdioServerConfig> = {};
  for (const [name, s] of Object.entries(servers)) {
    const args = [...(s.args ?? [])];
    if (name === 'engram' && !args.includes('--project')) args.push('--project', path.basename(repoPath));
    out[name] = { type: 'stdio', command: s.command, args, ...(s.env ? { env: s.env } : {}) };
  }
  return out;
}
