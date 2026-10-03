// Subagents for AgentCraft agents (config.json claude.subagents). Off by default: each agent then
// works alone. When enabled, agents may start Claude Code subagents (built-in ones such as Explore,
// plus the definitions listed here). Subagent tool calls go through the same canUseTool policy and
// PreToolUse guardrails as the agent's own, with its tools and in its worktree; a subagent can
// never get a separate git worktree (`isolation: "worktree"` is refused by the policy).
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import type { AgentDefinition } from '@anthropic-ai/claude-agent-sdk';

export interface SubagentsConfig {
  enabled: boolean;
  /** agent definition files: names under ~/.claude/agents (".md" optional) or paths */
  agents: string[];
}

export const DEFAULT_SUBAGENTS: SubagentsConfig = { enabled: false, agents: [] };

function frontMatter(text: string): { fields: Record<string, string>; body: string } {
  const t = text.replace(/\r\n/g, '\n');
  if (!t.startsWith('---\n')) return { fields: {}, body: t };
  const end = t.indexOf('\n---', 4);
  if (end < 0) return { fields: {}, body: t };
  const fields: Record<string, string> = {};
  let key = '';
  for (const line of t.slice(4, end).split('\n')) {
    const m = /^([\w-]+):\s*(.*)$/.exec(line);
    if (m) {
      key = m[1]!;
      fields[key] = m[2]!.replace(/^>-?\s*$|^\|-?\s*$/, '').replace(/^["']|["']$/g, '');
    } else if (key && /^\s+\S/.test(line)) {
      // folded / literal block continuation
      fields[key] = `${fields[key] ? `${fields[key]} ` : ''}${line.trim()}`;
    }
  }
  return { fields, body: t.slice(end + 4).replace(/^\n/, '') };
}

/** Read Claude Code agent definition files into SDK AgentDefinitions (keyed by agent name). */
export function loadSubagents(specs: string[], home = os.homedir()): { agents: Record<string, AgentDefinition>; problems: string[] } {
  const agents: Record<string, AgentDefinition> = {};
  const problems: string[] = [];
  for (const spec of specs) {
    const p = /[\\/]/.test(spec) ? path.resolve(spec.replace(/^~(?=$|[\\/])/, home)) : path.join(home, '.claude', 'agents', spec.endsWith('.md') ? spec : `${spec}.md`);
    let text: string;
    try {
      text = fs.readFileSync(p, 'utf8');
    } catch {
      problems.push(`subagent ${spec}: cannot read ${p}`);
      continue;
    }
    const { fields, body } = frontMatter(text);
    const name = fields.name || path.basename(p, '.md');
    if (!fields.description || !body.trim()) {
      problems.push(`subagent ${spec}: needs a description and a prompt`);
      continue;
    }
    const def: AgentDefinition = { description: fields.description, prompt: body.trim() };
    const tools = fields.tools?.split(',').map((s) => s.trim()).filter(Boolean);
    if (tools?.length) def.tools = tools;
    if (fields.model && fields.model !== 'inherit') def.model = fields.model;
    // never a permissionMode from the file: subagents run under the agent's mode and guardrails
    agents[name] = def;
  }
  return { agents, problems };
}
