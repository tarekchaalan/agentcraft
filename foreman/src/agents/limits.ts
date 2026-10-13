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

// ---- which models a limit applies to (claude.ai plan limits, see claude.leadFallback) ---------

const FAMILIES = new Set(['opus', 'sonnet', 'haiku', 'fable']);

/**
 * The model family a plan limit is specific to, from the rate-limit type the Claude CLI reports
 * (SDKRateLimitInfo.rateLimitType): "seven_day_opus" -> "opus", "seven_day_sonnet" -> "sonnet",
 * "seven_day_overage_included" -> "fable" (the weekly window of the plan's overage-included models,
 * which the CLI calls the "Fable limit"). Undefined for a limit of the whole account (five_hour,
 * seven_day, overage) and for anything unknown: switching models would not get past those.
 */
export function limitModelFamily(type: string | undefined): string | undefined {
  if (type === 'seven_day_overage_included') return 'fable';
  const m = /^seven_day_([a-z]+)$/.exec(type ?? '');
  return m && FAMILIES.has(m[1]!) ? m[1] : undefined;
}

/**
 * The model family a limit message names, for a turn that ended without a typed report: "You've hit
 * your Fable limit · resets 3pm", "You've reached your Fable limit" -> "fable". The session, weekly
 * and credit limits name none.
 */
export function limitFamilyFromText(text: string): string | undefined {
  const name = /\b(?:hit|reached) your (\w+) limit\b/i.exec(text)?.[1]?.toLowerCase();
  return name && FAMILIES.has(name) ? name : undefined;
}

/**
 * The model family whose own weekly window a usage window is (claude/usage.ts ids): "weekly:fable" ->
 * "fable", "weekly:opus" -> "opus". Undefined for the account's windows ("session", "weekly"), a
 * surface's ("weekly:claude-code") and one model version's ("weekly:opus-4-5"): a full window of
 * those says nothing about which agents a model limit would hold.
 */
export function usageWindowFamily(id: string): string | undefined {
  const m = /^weekly:([a-z]+)$/.exec(id);
  return m && FAMILIES.has(m[1]!) ? m[1] : undefined;
}

/** "claude-fable-5-1", "fable", "opus[1m]" -> their family; undefined when none is named ("default", "opusplan"). */
export function modelFamily(model: string | undefined): string | undefined {
  return /(?:^|[^a-z])(opus|sonnet|haiku|fable)(?![a-z])/i.exec(model ?? '')?.[1]?.toLowerCase();
}
