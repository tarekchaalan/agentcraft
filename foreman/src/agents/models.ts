// Display names for models, shown on the agents' nameplates ("Opus 5.5", "GPT-6 Astra"), and the
// model names each engine accepts (the console's /model, config.json claude/codex.agentModels).

const cap = (w: string) => (w ? w[0]!.toUpperCase() + w.slice(1) : w);

/**
 * "claude-opus-5-5" -> "Opus 5.5", "claude-haiku-4-5-20251001" -> "Haiku 4.5", "opus" -> "Opus",
 * "gpt-6-astra" -> "GPT-6 Astra", "gpt-5.1-codex-mini" -> "GPT-5.1 Codex Mini", "o4-mini" -> "o4 Mini".
 * Anything unrecognised is shown as it is (trimmed).
 */
export function modelLabel(model: string | undefined): string | undefined {
  const m = model?.trim();
  if (!m || m === 'default') return undefined;
  const claude = /^(?:claude-)?(opus|sonnet|haiku|fable)(?:-(\d+)(?:-(\d{1,2}))?)?(?:-\d{8})?(?:\[.*\])?$/i.exec(m);
  if (claude) return [cap(claude[1]!.toLowerCase()), claude[2] ? `${claude[2]}${claude[3] ? `.${claude[3]}` : ''}` : ''].filter(Boolean).join(' ');
  const gpt = /^gpt-([\d.]+)(?:-(.+))?$/i.exec(m);
  if (gpt) return [`GPT-${gpt[1]}`, ...(gpt[2] ?? '').split('-').filter(Boolean).map(cap)].join(' ');
  const o = /^(o\d+)(?:-(.+))?$/i.exec(m);
  if (o) return [o[1]!.toLowerCase(), ...(o[2] ?? '').split('-').filter(Boolean).map(cap)].join(' ');
  return m.slice(0, 24);
}

// ---- which models an engine accepts ---------------------------------------------------------

/** The Claude model aliases the SDK resolves to the current model of that family ("sonnet" -> claude-sonnet-5). */
export const CLAUDE_ALIASES = ['opus', 'sonnet', 'haiku', 'fable'] as const;
/** Claude Code aliases whose model depends on the release or settings; accepted, never suggested. */
const CLAUDE_OTHER_ALIASES = ['opusplan', 'best'];
/** Claude effort levels (the SDK's EffortLevel). */
export const CLAUDE_EFFORTS = ['low', 'medium', 'high', 'xhigh', 'max'] as const;
/** Codex reasoning efforts (model_reasoning_effort). */
export const CODEX_EFFORTS = ['none', 'minimal', 'low', 'medium', 'high', 'xhigh'] as const;

/** A model name: an alias, a model id or a provider's id (Bedrock, Vertex), no spaces or shell characters. */
const MODEL_NAME = /^[A-Za-z0-9][A-Za-z0-9._:@/[\]-]{0,99}$/;

/** "Sonnet[1m]" -> "sonnet" when it is one of the Claude aliases (any case), else undefined. */
function claudeAlias(model: string): string | undefined {
  const base = model.trim().toLowerCase().replace(/\[[^\]]*\]$/, '');
  return (CLAUDE_ALIASES as readonly string[]).includes(base) || CLAUDE_OTHER_ALIASES.includes(base) ? base : undefined;
}

/** A Claude model: an alias, a Claude model id in any provider's spelling ("claude-sonnet-5", "us.anthropic.claude-..."), or a Bedrock ARN. */
export function isClaudeModel(model: string): boolean {
  const m = model.trim().toLowerCase();
  return !!claudeAlias(m) || m.includes('claude') || m.startsWith('arn:aws:bedrock:');
}

/** What an engine says about a model it cannot run: an engine runs only its own provider's models. */
export type ModelProblem = 'invalid' | 'not-claude' | 'claude';

/**
 * Why `model` cannot run on the engine (undefined: it can). Claude agents take an alias (opus, sonnet,
 * haiku, fable), a Claude model id or a Bedrock ARN, so a typo ("sonet") or a Codex model is caught
 * here, not by a failed turn; Codex agents take any model name that is not a Claude one.
 */
export function modelProblem(engine: 'claude' | 'codex', model: string): ModelProblem | undefined {
  const m = model.trim();
  if (!MODEL_NAME.test(m) || m.toLowerCase() === 'default') return 'invalid';
  if (engine === 'claude') return isClaudeModel(m) ? undefined : 'not-claude';
  return isClaudeModel(m) ? 'claude' : undefined;
}

/** The spelling a model is kept in: a Claude alias in lower case ("Sonnet" -> "sonnet"), anything else as typed. */
export function normalizeModel(engine: 'claude' | 'codex', model: string): string {
  const m = model.trim();
  if (engine !== 'claude') return m;
  const alias = claudeAlias(m);
  return alias ? m.toLowerCase() : m;
}

/** The efforts an engine accepts. */
export function effortsFor(engine: 'claude' | 'codex'): readonly string[] {
  return engine === 'claude' ? CLAUDE_EFFORTS : CODEX_EFFORTS;
}
