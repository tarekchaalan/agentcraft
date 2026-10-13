// Usage limits as the engines report them: a plan limit (claude.ai login) refusing a turn, or an
// API rate limit. Engine-neutral, so the team (team.ts) can hold and resume turns for any engine.

const LIMIT_RE = /usage limit|rate[ _-]?limit/i;

/**
 * A turn's error text that reports the plan's usage limit ("Claude AI usage limit reached|1759500000",
 * "rate_limit_error"): whether it is one, and when it resets if the text says.
 */
export function limitFromText(text: string): { limited: boolean; resetsAt?: number } {
  if (!LIMIT_RE.test(text)) return { limited: false };
  const at = resetsAtMs(Number(/\|(\d{9,13})\b/.exec(text)?.[1]));
  return at ? { limited: true, resetsAt: at } : { limited: true };
}

/** resetsAt arrives in epoch seconds; accept ms too. */
export function resetsAtMs(v: unknown): number | undefined {
  if (typeof v !== 'number' || !Number.isFinite(v) || v <= 0) return undefined;
  return v < 1e12 ? v * 1000 : v;
}
