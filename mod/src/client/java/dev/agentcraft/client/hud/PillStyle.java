package dev.agentcraft.client.hud;

import dev.agentcraft.client.foreman.Protocol.GoalStatus;
import dev.agentcraft.client.ui.Kit;
import dev.agentcraft.client.ui.Panels;
import dev.agentcraft.client.ui.TextUtil;
import dev.agentcraft.client.ui.UiStyle;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * The Pill and Pill+ overlay styles, drawn in overlay px at the origin (the overlay scales and places them).
 * <ul>
 *   <li><b>Pill</b>: one ink line: a mini progress bar and "40%", then the open task counts that are not zero
 *   ("2 doing · 1 blocked"); when a decision waits, a clay stripe, the pulsing clay dot and "2 decisions" with the
 *   decisions keycap. A peek widens it with what changed for a few seconds. Counts fall back to their short and dot
 *   forms when the line would be too wide.</li>
 *   <li><b>Pill+</b>: the same ink, a bit taller: the goal title and %, a thin progress bar, who is working
 *   ("Kit · Juniper …"), the next decision's first words with the keycap, and the peek.</li>
 * </ul>
 */
final class PillStyle {
	static final int PILL_H = 17;
	/** Pill+ width (overlay px). */
	static final int PLUS_W = 188;
	private static final int TEXT_Y = 5;
	private static final int BAR_W = 26;
	private static final String SEP = " · ";

	private PillStyle() {
	}

	/** How much room a count takes: "1 blocked", "1 blk", or just the dot and the number. */
	private enum Level {
		FULL, SHORT, DOTS
	}

	/** One piece of the pill line. */
	private interface Seg {
		int w();

		void draw(GuiGraphicsExtractor g, int x, int alpha);
	}

	private record Bar(double progress, String fill) implements Seg {
		@Override
		public int w() {
			return BAR_W;
		}

		@Override
		public void draw(GuiGraphicsExtractor g, int x, int alpha) {
			int tint = (alpha << 24) | 0xFFFFFF;
			Panels.sprite(g, Kit.PROGRESS_TRACK, x, 7, BAR_W, 4, tint);
			int fw = (int) Math.round(Math.max(0, Math.min(1, progress)) * BAR_W);
			if (fw >= 4) {
				Panels.sprite(g, Kit.progressFill(fill), x, 7, fw, 4, tint);
			}
		}
	}

	private record Txt(Font font, String text, int color, String dot, boolean pulse) implements Seg {
		@Override
		public int w() {
			return (dot == null ? 0 : 10) + font.width(text);
		}

		@Override
		public void draw(GuiGraphicsExtractor g, int x, int alpha) {
			int cx = x;
			if (dot != null) {
				if (pulse) {
					UiBits.pulsingDot(g, dot, cx, TEXT_Y);
				} else {
					Panels.sprite(g, Kit.dot(dot, false), cx, TEXT_Y, 7, 7, (alpha << 24) | 0xFFFFFF);
				}
				cx += 10;
			}
			g.text(font, text, cx, TEXT_Y, UiStyle.withAlpha(color, alpha), false);
		}
	}

	private record Gap(int w) implements Seg {
		@Override
		public void draw(GuiGraphicsExtractor g, int x, int alpha) {
		}
	}

	private record Cap(Font font, String key) implements Seg {
		@Override
		public int w() {
			return UiBits.keycapWidth(font, key);
		}

		@Override
		public void draw(GuiGraphicsExtractor g, int x, int alpha) {
			UiBits.keycap(g, font, key, x, 3);
		}
	}

	/** Clay stripe and pulse: something needs the player. */
	static boolean accent(HudModel m) {
		return m.waiting > 0 && !m.stale;
	}

	private static String fill(HudModel m) {
		if (m.stale || m.goal == null) {
			return "brass";
		}
		return switch (m.goal.status()) {
			case PLANNING -> "brass";
			case DONE -> "sage";
			case FAILED -> "red";
			default -> "teal";
		};
	}

	private static String decisionsKey() {
		return Keys.decisions == null ? "J" : Keys.label(Keys.decisions);
	}

	// ------------------------------------------------------------------ Pill

	/** The pill's widest line without a peek (overlay px); with a peek it may grow by {@link #PEEK_W}. */
	static final int PILL_MAX = 205;
	/** The peek's text at most (overlay px). */
	static final int PEEK_W = 130;
	/** Decisions level, other counts level: tried in order until the line fits. */
	private static final Level[][] LEVELS = {{Level.FULL, Level.FULL}, {Level.FULL, Level.SHORT}, {Level.FULL, Level.DOTS}, {Level.SHORT, Level.DOTS},
		{Level.DOTS, Level.DOTS}};
	private static final String[] COUNT_LABELS = {"doing", "review", "todo", "blocked"};
	private static final String[] COUNT_SHORT = {"doing", "rev", "todo", "blk"};
	private static final String[] COUNT_FAMILIES = {"working", "thinking", "idle", "error"};

	private static List<Seg> pillSegs(HudModel m, Font font, Level dec, Level other, boolean peek) {
		List<Seg> out = new ArrayList<>();
		int cream = m.stale ? UiBits.activityOnInk() : UiBits.cream();
		int muted = UiBits.activityOnInk();
		if (m.activeGoal()) {
			out.add(new Bar(m.goal.progress(), fill(m)));
			out.add(new Gap(4));
			out.add(new Txt(font, m.goal.pct() + "%", m.stale ? muted : UiStyle.BRASS, null, false));
		} else if (!m.needsPlayer() && m.peek == null) {
			boolean done = m.goal != null && m.goal.status() == GoalStatus.DONE;
			out.add(new Txt(font, done ? "Goal done" : "No active goal", muted, done ? "done" : "idle", false));
		}
		if (m.waiting > 0) {
			sep(out, font, false);
			String t = dec == Level.FULL ? UiBits.plural(m.waiting, "decision", "decisions") : m.waiting + (dec == Level.SHORT ? " dec" : "");
			out.add(new Txt(font, t, m.stale ? muted : UiStyle.CLAY, m.stale ? "idle" : "waiting", !m.stale));
			out.add(new Gap(4));
			out.add(new Cap(font, decisionsKey()));
		}
		if (m.activeGoal()) {
			boolean dots = other == Level.DOTS;
			for (int i = 0; i < 4; i++) {
				if (m.counts[i] == 0 || (i == 0 && m.counts[i] == 0)) {
					continue;
				}
				if (i == 2 && other != Level.FULL) {
					continue; // todo is the least urgent count: the first to go
				}
				String t = switch (other) {
					case FULL -> m.counts[i] + " " + COUNT_LABELS[i];
					case SHORT -> m.counts[i] + " " + COUNT_SHORT[i];
					case DOTS -> Integer.toString(m.counts[i]);
				};
				sep(out, font, dots && !out.isEmpty() && out.get(out.size() - 1) instanceof Txt last && last.dot() != null && !last.pulse());
				out.add(new Txt(font, t, cream, m.stale ? "idle" : COUNT_FAMILIES[i], false));
			}
		}
		if (m.peek != null && peek) {
			sep(out, font, false);
			out.add(new Txt(font, TextUtil.ellipsize(font, m.peek.text(), PEEK_W), cream, HudPeeks.family(m.peek.kind()), false));
		}
		return out;
	}

	/** " · " between parts; a small gap between two dot counts ("●1 ●3"). */
	private static void sep(List<Seg> out, Font font, boolean tight) {
		if (!out.isEmpty()) {
			out.add(tight ? new Gap(5) : new Txt(font, SEP, UiBits.activityOnInk(), null, false));
		}
	}

	private static int frame(HudModel m) {
		return 5 + 5 + (accent(m) ? 3 : 0);
	}

	private static int width(List<Seg> segs) {
		int w = 0;
		for (Seg s : segs) {
			w += s.w();
		}
		return w;
	}

	/**
	 * The pill's segments that fit (at most {@link #PILL_MAX}, plus the peek while one shows): decisions and the other
	 * counts step down from full to short to dots; a peek that does not fit at any step is left out.
	 */
	private static List<Seg> fitPill(HudModel m, Font font, int maxW) {
		int cap = Math.min(maxW, PILL_MAX + (m.peek != null ? PEEK_W + 20 : 0));
		if (m.peek != null) {
			for (Level[] l : LEVELS) {
				List<Seg> segs = pillSegs(m, font, l[0], l[1], true);
				if (width(segs) + frame(m) <= cap) {
					return segs;
				}
			}
		}
		List<Seg> segs = null;
		for (Level[] l : LEVELS) {
			segs = pillSegs(m, font, l[0], l[1], false);
			if (width(segs) + frame(m) <= Math.min(cap, PILL_MAX)) {
				return segs;
			}
		}
		return segs;
	}

	static int[] measurePill(HudModel m, Font font, int maxW) {
		List<Seg> segs = fitPill(m, font, maxW);
		return new int[] {Math.min(maxW, width(segs) + frame(m)), PILL_H};
	}

	static void drawPill(GuiGraphicsExtractor g, Font font, HudModel m, int w, int alpha) {
		List<Seg> segs = fitPill(m, font, w);
		Panels.sprite(g, Kit.TOOLTIP, 0, 0, w, PILL_H);
		int x = 5;
		if (accent(m)) {
			g.fill(2, 3, 4, PILL_H - 3, UiStyle.withAlpha(UiStyle.CLAY, alpha));
			x += 3;
		}
		// no scissor: a segment that would run past the edge (only at the narrowest fallback) is left out instead
		for (Seg s : segs) {
			if (x + s.w() > w - 4) {
				break;
			}
			s.draw(g, x, alpha);
			x += s.w();
		}
	}

	// ------------------------------------------------------------------ Pill+

	private static final int ROW = 11;

	/** The rows Pill+ draws (title and bar always). */
	private record PlusRows(boolean agents, boolean ask, boolean peek) {
		int height() {
			int h = TEXT_Y + 9 + 3 + 4 + 3;
			if (agents) {
				h += ROW;
			}
			if (ask) {
				h += ROW;
			}
			if (peek) {
				h += ROW;
			}
			return h + 2;
		}
	}

	private static PlusRows plusRows(HudModel m) {
		return new PlusRows(!m.working.isEmpty() || m.activeGoal(), m.waiting > 0, m.peek != null);
	}

	static int[] measurePlus(HudModel m, Font font, int maxW) {
		return new int[] {Math.min(maxW, PLUS_W), plusRows(m).height()};
	}

	static void drawPlus(GuiGraphicsExtractor g, Font font, HudModel m, int w, int h, int alpha) {
		PlusRows rows = plusRows(m);
		int tint = (alpha << 24) | 0xFFFFFF;
		int cream = UiStyle.withAlpha(m.stale ? UiBits.activityOnInk() : UiBits.cream(), alpha);
		int muted = UiStyle.withAlpha(UiBits.activityOnInk(), alpha);
		Panels.sprite(g, Kit.TOOLTIP, 0, 0, w, h);
		int ix = 5;
		if (accent(m)) {
			g.fill(2, 3, 4, h - 3, UiStyle.withAlpha(UiStyle.CLAY, alpha));
			ix += 3;
		}
		int right = w - 5;
		int y = TEXT_Y;
		int cx = ix;
		if (m.goal != null && (m.activeGoal() || m.goal.status() == GoalStatus.DONE)) {
			String pct = m.goal.pct() + "%";
			int pw = font.width(pct);
			String prefix = switch (m.goal.status()) {
				case PLANNING -> "Planning · ";
				case DONE -> "Done " + UiBits.CHECK + " ";
				default -> "";
			};
			String family = m.stale ? "idle" : m.goal.status() == GoalStatus.PLANNING ? "thinking" : m.goal.status() == GoalStatus.DONE ? "done" : "working";
			Panels.sprite(g, Kit.dot(family, false), cx, y + 1, 7, 7, tint);
			cx += 10;
			g.text(font, TextUtil.ellipsize(font, prefix + m.goal.text(), right - cx - pw - 6), cx, y, cream, false);
			g.text(font, pct, right - pw, y, m.stale ? muted : UiStyle.withAlpha(UiStyle.BRASS, alpha), false);
		} else {
			Panels.sprite(g, Kit.dot("idle", false), cx, y + 1, 7, 7, tint);
			g.text(font, "No active goal", cx + 10, y, muted, false);
		}
		y += 9 + 3;
		int bw = right - ix;
		Panels.sprite(g, Kit.PROGRESS_TRACK, ix, y, bw, 4, tint);
		double prog = m.goal == null ? 0 : m.goal.progress();
		int fw = (int) Math.round(Math.max(0, Math.min(1, prog)) * bw);
		if (fw >= 4) {
			Panels.sprite(g, Kit.progressFill(fill(m)), ix, y, fw, 4, tint);
		}
		y += 4 + 3;
		if (rows.agents()) {
			// right: the open task count; left: who is working
			String r = m.totalTasks > 0 ? m.counts[4] + "/" + m.totalTasks + " done" : "";
			int rw = font.width(r);
			if (!r.isEmpty()) {
				g.text(font, r, right - rw, y, muted, false);
			}
			drawAgents(g, font, m, ix, y, right - rw - (r.isEmpty() ? 0 : 6) - ix, alpha, muted);
			y += ROW;
		}
		if (rows.ask()) {
			String key = decisionsKey();
			int kw = UiBits.keycapWidth(font, key);
			if (m.stale) {
				Panels.sprite(g, Kit.dot("idle", false), ix, y, 7, 7, tint);
			} else {
				UiBits.pulsingDot(g, "waiting", ix, y);
			}
			String lead = m.waiting > 1 ? m.waiting + " · " : "";
			String q = m.nextDecision == null || m.nextDecision.isBlank() ? UiBits.plural(m.waiting, "decision", "decisions") : m.nextDecision;
			int clay = UiStyle.withAlpha(m.stale ? UiBits.activityOnInk() : UiStyle.CLAY, alpha);
			g.text(font, TextUtil.ellipsize(font, lead + q, right - kw - 4 - ix - 10), ix + 10, y, clay, false);
			UiBits.keycap(g, font, key, right - kw, y - 2);
			y += ROW;
		}
		if (rows.peek() && m.peek != null) {
			Panels.sprite(g, Kit.dot(HudPeeks.family(m.peek.kind()), false), ix, y, 7, 7, tint);
			g.text(font, TextUtil.ellipsize(font, m.peek.text(), right - ix - 10), ix + 10, y, cream, false);
		}
	}

	/** "Kit · Juniper …" in their name colours (the dots cycle while they work); "+2" when not all fit. */
	private static void drawAgents(GuiGraphicsExtractor g, Font font, HudModel m, int x, int y, int maxW, int alpha, int muted) {
		if (m.working.isEmpty()) {
			g.text(font, TextUtil.ellipsize(font, m.goal != null && m.goal.status() == GoalStatus.PLANNING ? m.planningLine : "nobody at work", maxW), x, y,
				muted, false);
			return;
		}
		int sepW = font.width(SEP);
		int dotsW = font.width("…") + 2;
		int cx = x;
		for (int i = 0; i < m.working.size(); i++) {
			String id = m.working.get(i);
			String name = UiBits.agentName(id);
			int left = m.working.size() - i;
			String rest = "+" + left;
			int need = (i > 0 ? sepW : 0) + font.width(name) + dotsW;
			if (cx + need > x + maxW) {
				if (i > 0 && cx + font.width(" " + rest) <= x + maxW) {
					g.text(font, " " + rest, cx, y, muted, false);
				}
				return;
			}
			if (i > 0) {
				g.text(font, SEP, cx, y, muted, false);
				cx += sepW;
			}
			g.text(font, name, cx, y, UiStyle.withAlpha(UiBits.nameOnDark(id), alpha), false);
			cx += font.width(name);
		}
		// working: a soft cycling ellipsis
		long ph = (System.currentTimeMillis() / 400) % 3;
		String dots = ph == 0 ? "." : ph == 1 ? ".." : "…";
		g.text(font, " " + dots, cx, y, muted, false);
	}

	/** QA: the pill's text as drawn (segments joined), for {@code dev.hud.state overlay.text}. */
	static String pillText(HudModel m, Font font, int maxW) {
		StringBuilder b = new StringBuilder();
		for (Seg s : fitPill(m, font, maxW)) {
			switch (s) {
				case Txt t -> b.append(t.text());
				case Cap c -> b.append('[').append(c.key()).append(']');
				case Bar bar -> b.append("[bar]");
				default -> b.append(' ');
			}
		}
		return b.toString().replaceAll(" +", " ").strip();
	}
}
