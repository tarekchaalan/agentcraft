// Plan usage windows (claude.ai logins): how much of the 5-hour / 7-day allowance is used.
//
// Two sources, merged into one snapshot for foreman.status.usage:
//   rate_limit_event   stable; every turn's stream reports the window it is closest to (utilization
//                      0-1, resetsAt). The main source.
//   the CLI's /usage   the SDK exposes it only through an explicitly unstable method; read
//                      best-effort, here only, so a rename breaks nothing but this refresh.
import type { PlanUsage, UsageWindow } from '../../protocol.js';

const LABELS: Record<string, string> = {
  five_hour: '5h',
  seven_day: '7d',
  seven_day_opus: '7d Opus',
  seven_day_sonnet: '7d Sonnet',
  seven_day_overage_included: '7d',
  overage: 'extra',
};
const ORDER = ['five_hour', 'seven_day', 'seven_day_opus', 'seven_day_sonnet', 'overage'];

/** The SDK's experimental /usage method (name may change; see the SDK's Query docs). */
const USAGE_METHOD = 'usage_EXPERIMENTAL_MAY_CHANGE_DO_NOT_RELY_ON_THIS_API_YET';

function windowId(type: string): string {
  return type === 'seven_day_overage_included' ? 'seven_day' : type;
}

/** Merge one window into a snapshot (newest report wins for its window). */
export function withWindow(prev: PlanUsage | undefined, type: string, pct: number, resetsAt: number | undefined, now = Date.now()): PlanUsage {
  const id = windowId(type);
  const w: UsageWindow = { id, label: LABELS[type] ?? type.replace(/_/g, ' '), pct: Math.max(0, Math.min(100, Math.round(pct))) };
  if (resetsAt) w.resetsAt = resetsAt;
  const windows = [...(prev?.windows ?? []).filter((x) => x.id !== id), w];
  windows.sort((a, b) => (ORDER.indexOf(a.id) + 1 || 99) - (ORDER.indexOf(b.id) + 1 || 99));
  return { windows, updatedAt: now };
}

/** Windows whose reset time has passed are stale: drop them. */
export function pruneUsage(u: PlanUsage | undefined, now = Date.now()): PlanUsage | undefined {
  if (!u) return u;
  const windows = u.windows.filter((w) => !w.resetsAt || w.resetsAt > now);
  return windows.length ? { ...u, windows } : undefined;
}

type RateLimits = Record<string, { utilization?: number | null; resets_at?: string | null } | null | undefined>;

/**
 * Read the plan windows from a live query's /usage data. undefined when the method is missing
 * (renamed or removed), slow, failing, or the login has no plan limits (API key, cloud provider).
 */
export async function readPlanUsage(q: object, prev: PlanUsage | undefined, timeoutMs = 10_000): Promise<PlanUsage | undefined> {
  const fn = (q as Record<string, unknown>)[USAGE_METHOD];
  if (typeof fn !== 'function') return undefined;
  try {
    const res = (await Promise.race([
      (fn as (o: { skipBehaviors: boolean }) => Promise<unknown>).call(q, { skipBehaviors: true }),
      new Promise((_, reject) => setTimeout(() => reject(new Error('timeout')), timeoutMs).unref?.()),
    ])) as { rate_limits_available?: boolean; rate_limits?: RateLimits | null } | undefined;
    if (!res?.rate_limits_available || !res.rate_limits) return undefined;
    let u = prev;
    for (const [type, w] of Object.entries(res.rate_limits)) {
      if (!w || typeof w.utilization !== 'number' || !(type in LABELS)) continue;
      const at = w.resets_at ? Date.parse(w.resets_at) : NaN;
      u = withWindow(u, type, w.utilization, Number.isFinite(at) ? at : undefined);
    }
    return u;
  } catch {
    return undefined;
  }
}

/** "5h 42% · 7d 18%" for logs and text UIs. */
export function usageLine(u: PlanUsage | undefined): string {
  return (u?.windows ?? []).map((w) => `${w.label} ${w.pct}%`).join(' · ');
}

/** Put a (new) snapshot into foreman.status.usage; a changed line is logged once. */
export function publishUsage(fm: { status: { usage?: PlanUsage }; setStatus(p: { usage: PlanUsage }): void; log: { info(s: string): void } }, u: PlanUsage | undefined): void {
  if (!u) return;
  const before = usageLine(fm.status.usage);
  fm.setStatus({ usage: u });
  const now = usageLine(u);
  if (now !== before) fm.log.info(`plan usage: ${now}`);
}
