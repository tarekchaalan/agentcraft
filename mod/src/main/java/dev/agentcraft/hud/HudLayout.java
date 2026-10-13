package dev.agentcraft.hud;

import dev.agentcraft.hud.HudSettings.Position;
import dev.agentcraft.hud.HudSettings.Size;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntUnaryOperator;

/**
 * Where the in-game overlay goes (pure): the overlay's rectangle for a position, kept clear of what vanilla (and the
 * connection pill) draws, the toast column next to it, the overlap check the DevBridge reports, and the size steps.
 * All in GUI px.
 *
 * <p>Vanilla geometry (Minecraft 26.3 {@code Hud}, {@code BossHealthOverlay}, {@code ChatComponent}):
 * <ul>
 *   <li>boss bars: 182 px wide at the top centre, the first bar at y 12 (its title at y 3), one every 19 px while the
 *   next would start above a third of the screen height; a title wider than the bar widens the column;</li>
 *   <li>effect icons: 24 px squares from the top right edge, 25 px apart, beneficial at y 1, harmful at y 27 (15 px
 *   lower in the demo);</li>
 *   <li>hotbar: 182 px at the bottom centre plus the 29 px offhand slot on either side; in survival the hearts,
 *   armour, hunger and air rows and the held item's name reach about 62 px up, in creative the item name about 48 px;</li>
 *   <li>chat: from the left edge, its bottom 40 px above the screen's bottom, as wide and tall as the chat options
 *   say (unfocused height);</li>
 *   <li>scoreboard sidebar ({@code Hud.displayScoreboardSidebar}): at the right edge, a 10 px title row over up to 15
 *   rows of 9 px, its bottom at half the height plus a third of the rows' height;</li>
 *   <li>subtitles ({@code SubtitleOverlay}): at the right edge, row 0 centred 35 px above the bottom, each next row
 *   10 px higher;</li>
 *   <li>toasts ({@code ToastManager}): at the right edge, 160 px wide by default, slot {@code n} at {@code n} times the
 *   toast's height.</li>
 * </ul>
 */
public final class HudLayout {
	/** From the screen edges. */
	public static final int MARGIN = 4;
	/** Between the overlay (or the toasts) and anything else. */
	public static final int GAP = 3;
	/** Half the boss bar column (182 px bar). */
	static final int BOSS_HALF = 91;
	/** Half the hotbar plus the offhand slot. */
	static final int HOTBAR_HALF = 91 + 29;

	private HudLayout() {
	}

	/** A rectangle; empty when its width or height is not positive. */
	public record Rect(int x, int y, int w, int h) {
		public static final Rect NONE = new Rect(0, 0, 0, 0);

		public boolean empty() {
			return w <= 0 || h <= 0;
		}

		public int right() {
			return x + w;
		}

		public int bottom() {
			return y + h;
		}

		public boolean intersects(Rect o) {
			return !empty() && !o.empty() && x < o.right() && o.x < right() && y < o.bottom() && o.y < bottom();
		}

		public Rect grow(int d) {
			return empty() ? this : new Rect(x - d, y - d, w + 2 * d, h + 2 * d);
		}

		public boolean within(int guiW, int guiH) {
			return x >= 0 && y >= 0 && right() <= guiW && bottom() <= guiH;
		}

		/** The smallest rectangle holding both (an empty one is ignored). */
		public Rect union(Rect o) {
			if (o.empty()) {
				return this;
			}
			if (empty()) {
				return o;
			}
			int x0 = Math.min(x, o.x);
			int y0 = Math.min(y, o.y);
			return new Rect(x0, y0, Math.max(right(), o.right()) - x0, Math.max(bottom(), o.bottom()) - y0);
		}
	}

	/**
	 * What else is on screen this frame: the GUI size and scale, the effect icons (beneficial / harmful counts with an
	 * icon), the boss bars (count and widest title), whether the survival status bars are drawn, the chat area (its
	 * width and the height of the lines showing in GUI px, 0 = nothing), the chat's whole unfocused height, the
	 * connection pill drawn this frame (top right), and the vanilla extras measured by the client: the scoreboard
	 * sidebar ({@link #sidebar(int, int, int, int)}), the subtitles showing ({@link #subtitleRows(int, int, int, int)})
	 * and whether subtitles are on at all (then a band above the bottom right is kept for them, {@link #subtitles(Env)}),
	 * the advancement / recipe / system toasts in the top right ({@link #vanillaToast}), and the auth banner drawn this
	 * frame ({@link #banner}).
	 */
	public record Env(int guiW, int guiH, int guiScale, int beneficial, int harmful, boolean demo, int bossBars, int bossTitleW, boolean statusBars,
		int chatW, int chatH, int chatArea, Rect pill, Rect sidebar, Rect subtitleRows, boolean subtitlesOn, Rect toasts, Rect banner) {

		/** No effects, no boss bars, creative, the default chat (320 x 90), no pill, no vanilla extras. */
		public static Env of(int guiW, int guiH, int guiScale) {
			return new Env(guiW, guiH, guiScale, 0, 0, false, 0, 0, false, 320, 90, 90, Rect.NONE, Rect.NONE, Rect.NONE, false, Rect.NONE, Rect.NONE);
		}

		public Env effects(int beneficialIcons, int harmfulIcons) {
			return new Env(guiW, guiH, guiScale, beneficialIcons, harmfulIcons, demo, bossBars, bossTitleW, statusBars, chatW, chatH, chatArea, pill,
				sidebar, subtitleRows, subtitlesOn, toasts, banner);
		}

		public Env boss(int bars, int widestTitle) {
			return new Env(guiW, guiH, guiScale, beneficial, harmful, demo, bars, widestTitle, statusBars, chatW, chatH, chatArea, pill, sidebar,
				subtitleRows, subtitlesOn, toasts, banner);
		}

		public Env survival(boolean bars) {
			return new Env(guiW, guiH, guiScale, beneficial, harmful, demo, bossBars, bossTitleW, bars, chatW, chatH, chatArea, pill, sidebar,
				subtitleRows, subtitlesOn, toasts, banner);
		}

		public Env chat(int w, int h) {
			return new Env(guiW, guiH, guiScale, beneficial, harmful, demo, bossBars, bossTitleW, statusBars, w, h, chatArea, pill, sidebar,
				subtitleRows, subtitlesOn, toasts, banner);
		}

		/** The chat's full (unfocused) height, lines or not: bottom left always sits above it. */
		public Env chatArea(int h) {
			return new Env(guiW, guiH, guiScale, beneficial, harmful, demo, bossBars, bossTitleW, statusBars, chatW, chatH, h, pill, sidebar,
				subtitleRows, subtitlesOn, toasts, banner);
		}

		public Env withPill(Rect r) {
			return new Env(guiW, guiH, guiScale, beneficial, harmful, demo, bossBars, bossTitleW, statusBars, chatW, chatH, chatArea, r, sidebar,
				subtitleRows, subtitlesOn, toasts, banner);
		}

		public Env withDemo(boolean on) {
			return new Env(guiW, guiH, guiScale, beneficial, harmful, on, bossBars, bossTitleW, statusBars, chatW, chatH, chatArea, pill, sidebar,
				subtitleRows, subtitlesOn, toasts, banner);
		}

		/** The scoreboard sidebar on screen ({@link HudLayout#sidebar(int, int, int, int)}), NONE = none. */
		public Env withSidebar(Rect r) {
			return new Env(guiW, guiH, guiScale, beneficial, harmful, demo, bossBars, bossTitleW, statusBars, chatW, chatH, chatArea, pill, r,
				subtitleRows, subtitlesOn, toasts, banner);
		}

		/**
		 * Subtitles: {@code on} = the option is on (a band of {@link HudLayout#SUBTITLE_RESERVE_ROWS} rows is kept free for
		 * them), {@code rows} = the ones showing this frame ({@link HudLayout#subtitleRows(int, int, int, int)}, NONE = none).
		 */
		public Env withSubtitles(boolean on, Rect rows) {
			return new Env(guiW, guiH, guiScale, beneficial, harmful, demo, bossBars, bossTitleW, statusBars, chatW, chatH, chatArea, pill, sidebar,
				rows, on, toasts, banner);
		}

		/** The vanilla toasts showing in the top right ({@link HudLayout#vanillaToast}, all of them in one rectangle), NONE = none. */
		public Env withToasts(Rect r) {
			return new Env(guiW, guiH, guiScale, beneficial, harmful, demo, bossBars, bossTitleW, statusBars, chatW, chatH, chatArea, pill, sidebar,
				subtitleRows, subtitlesOn, r, banner);
		}

		/** The auth banner drawn this frame ({@link HudLayout#banner}), NONE = none. */
		public Env withBanner(Rect r) {
			return new Env(guiW, guiH, guiScale, beneficial, harmful, demo, bossBars, bossTitleW, statusBars, chatW, chatH, chatArea, pill, sidebar,
				subtitleRows, subtitlesOn, toasts, r);
		}
	}

	// ------------------------------------------------------------------ what vanilla draws

	/** How many of {@code bars} boss bars vanilla draws on a screen {@code guiH} tall (it stops at a third). */
	public static int bossBarsDrawn(int bars, int guiH) {
		int drawn = 0;
		int y = 12;
		for (int i = 0; i < bars; i++) {
			drawn++;
			y += 19;
			if (y >= guiH / 3) {
				break;
			}
		}
		return drawn;
	}

	/** The boss bar column (titles included), NONE without boss bars. */
	public static Rect bossBars(Env e) {
		int n = bossBarsDrawn(e.bossBars(), e.guiH());
		if (n <= 0) {
			return Rect.NONE;
		}
		int half = Math.max(BOSS_HALF, (e.bossTitleW() + 1) / 2);
		int x = e.guiW() / 2 - half;
		return new Rect(x, 0, 2 * half, 12 + 19 * (n - 1) + 5);
	}

	/** The effect icons in the top right corner, NONE without any. */
	public static Rect effects(Env e) {
		int cols = Math.max(e.beneficial(), e.harmful());
		if (cols <= 0) {
			return Rect.NONE;
		}
		int top = 1 + (e.demo() ? 15 : 0);
		int h = e.harmful() > 0 ? 26 + 24 : 24;
		int x = e.guiW() - 25 * cols;
		return new Rect(x, top, e.guiW() - x, h);
	}

	/** The hotbar with the offhand slots and (survival) the status rows above it, and the held item's name. */
	public static Rect hotbar(Env e) {
		int top = e.guiH() - (e.statusBars() ? 62 : 48);
		return new Rect(e.guiW() / 2 - HOTBAR_HALF, top, 2 * HOTBAR_HALF, e.guiH() - top);
	}

	/** The chat area (the lines showing), NONE when nothing shows. */
	public static Rect chat(Env e) {
		if (e.chatW() <= 0 || e.chatH() <= 0) {
			return Rect.NONE;
		}
		int bottom = e.guiH() - 40;
		return new Rect(0, bottom - e.chatH(), e.chatW() + 8, e.chatH());
	}

	/**
	 * The scoreboard sidebar vanilla draws ({@code Hud.displayScoreboardSidebar}) for {@code rows} entries (at most 15)
	 * whose widest line (the title, or "name: score") is {@code textW}: right-aligned 3 px from the edge with 2 px of
	 * background either side, a 10 px title row, rows of 9 px; its bottom at half the height plus a third of the rows'
	 * height. With no entries the title row still shows. NONE when {@code textW} is negative (no sidebar objective).
	 */
	public static Rect sidebar(int guiW, int guiH, int textW, int rows) {
		if (textW < 0) {
			return Rect.NONE;
		}
		int n = Math.max(0, Math.min(15, rows));
		int bottom = guiH / 2 + n * 9 / 3;
		int top = bottom - n * 9 - 10;
		int left = guiW - textW - 5;
		return new Rect(left, top, guiW - 1 - left, bottom - top);
	}

	/** Bottom of the subtitle column: vanilla centres row 0 at 35 px above the bottom, rows 10 px apart, 9 px + 1 px background. */
	static final int SUBTITLE_BOTTOM = 30;
	/** Rows kept free above the bottom right while subtitles are on (footsteps alone show one or two). */
	public static final int SUBTITLE_RESERVE_ROWS = 3;
	/** The width kept for them (most subtitles with their arrows are narrower). */
	public static final int SUBTITLE_RESERVE_W = 120;

	/**
	 * The subtitles vanilla draws ({@code SubtitleOverlay}): {@code rows} lines, each {@code lineW} wide (the widest
	 * subtitle plus "&lt; " and " &gt;"), right-aligned 2 px from the edge, row 0 centred 35 px above the bottom and each
	 * next row 10 px higher, with 1 px of background around the 9 px line. NONE without rows.
	 */
	public static Rect subtitleRows(int guiW, int guiH, int lineW, int rows) {
		if (rows <= 0 || lineW <= 0) {
			return Rect.NONE;
		}
		int half = lineW / 2;
		int left = guiW - 2 * half - 3;
		return new Rect(left, guiH - SUBTITLE_BOTTOM - 10 * rows, guiW - 1 - left, 10 * rows);
	}

	/**
	 * What the subtitles take: with the option on, a band of {@link #SUBTITLE_RESERVE_ROWS} rows {@link #SUBTITLE_RESERVE_W}
	 * wide above the bottom right (so the overlay does not jump each time a sound plays), plus the rows showing when
	 * they reach further; NONE with subtitles off.
	 */
	public static Rect subtitles(Env e) {
		if (!e.subtitlesOn()) {
			return Rect.NONE;
		}
		Rect band = new Rect(e.guiW() - SUBTITLE_RESERVE_W, e.guiH() - SUBTITLE_BOTTOM - 10 * SUBTITLE_RESERVE_ROWS, SUBTITLE_RESERVE_W,
			10 * SUBTITLE_RESERVE_ROWS);
		return band.union(e.subtitleRows());
	}

	/**
	 * A vanilla toast ({@code ToastManager}, {@code Toast.xPos/yPos}): {@code w} x {@code h} at the right edge, {@code slot}
	 * times its own height down. Counted at its full width while it slides in or out, so what avoids it moves once.
	 */
	public static Rect vanillaToast(int guiW, int w, int h, int slot) {
		if (w <= 0 || h <= 0) {
			return Rect.NONE;
		}
		return new Rect(guiW - w, Math.max(0, slot) * h, w, h);
	}

	static List<Rect> obstacles(Env e) {
		List<Rect> out = new ArrayList<>();
		for (Rect r : new Rect[] {bossBars(e), effects(e), hotbar(e), chat(e), e.pill(), e.sidebar(), subtitles(e), e.toasts(), e.banner()}) {
			if (!r.empty()) {
				out.add(r);
			}
		}
		return out;
	}

	// ------------------------------------------------------------------ size

	/**
	 * Framebuffer pixels per overlay pixel for a size: M = the GUI scale, S one less, L one more, so the overlay's
	 * font stays on whole pixels (crisp). S never goes below 2 framebuffer px (unreadable), so at GUI scale 2 or less
	 * S is M.
	 */
	public static int effectivePx(Size s, int guiScale) {
		int g = Math.max(1, guiScale);
		int d = switch (s) {
			case S -> -1;
			case M -> 0;
			case L -> 1;
		};
		return Math.max(g + d, Math.min(g, 2));
	}

	/** The pose scale the overlay is drawn with ({@link #effectivePx} / GUI scale). */
	public static float scale(Size s, int guiScale) {
		return effectivePx(s, guiScale) / (float) Math.max(1, guiScale);
	}

	/** {@code px} overlay pixels at scale {@code k}, rounded out to whole GUI px (what the rectangle must cover). */
	public static int scaled(int px, float k) {
		return (int) Math.ceil(px * k - 1e-4);
	}

	/** The widest the overlay may be (GUI px). */
	public static int maxWidth(Env e) {
		return Math.max(0, e.guiW() - 2 * MARGIN);
	}

	// ------------------------------------------------------------------ placement

	/** Where the overlay went: {@code rect} (NONE = no room anywhere), the position used, and whether it fell back. */
	public record Placement(Rect rect, Position used, boolean fallback) {
		public boolean placed() {
			return !rect.empty();
		}
	}

	/**
	 * Places a {@code w} x {@code h} overlay at {@code pos}, clear of everything in {@link Env} by {@link #GAP}:
	 * top positions slide down past what they meet (effect icons, the connection pill, boss bars), bottom positions
	 * slide up (above the hotbar and its status rows, above the chat), the right-edge middle tries down then up;
	 * bottom left starts above the chat's whole area. When the position has no room (a tiny window, a tall chat) it
	 * falls back to top right; when that has none either the rectangle is NONE (hidden as "no_room").
	 */
	public static Placement place(Env e, Position pos, int w, int h) {
		if (w <= 0 || h <= 0) {
			return new Placement(Rect.NONE, pos, false);
		}
		Rect r = at(e, pos, w, h);
		if (r != null) {
			return new Placement(r, pos, false);
		}
		if (pos != Position.TOP_RIGHT) {
			r = at(e, Position.TOP_RIGHT, w, h);
			if (r != null) {
				return new Placement(r, Position.TOP_RIGHT, true);
			}
		}
		return new Placement(Rect.NONE, pos, true);
	}

	private static Rect at(Env e, Position pos, int w, int h) {
		if (w > maxWidth(e) || h > e.guiH() - 2 * MARGIN) {
			return null;
		}
		int x = pos.left() ? MARGIN : pos.center() ? (e.guiW() - w) / 2 : e.guiW() - MARGIN - w;
		List<Rect> obs = obstacles(e);
		return switch (pos) {
			case TOP_CENTER, TOP_RIGHT, TOP_LEFT -> slide(e, obs, new Rect(x, MARGIN, w, h), 1);
			// bottom left sits above the chat's whole area (not only the lines showing), so it does not jump when a message comes
			case BOTTOM_LEFT -> slide(e, obs, new Rect(x, Math.min(e.guiH() - MARGIN, e.guiH() - 40 - e.chatArea() - GAP) - h, w, h), -1);
			case BOTTOM_RIGHT -> slide(e, obs, new Rect(x, e.guiH() - MARGIN - h, w, h), -1);
			case RIGHT_MIDDLE -> {
				Rect start = new Rect(x, (e.guiH() - h) / 2, w, h);
				Rect down = slide(e, obs, start, 1);
				yield down != null ? down : slide(e, obs, start, -1);
			}
		};
	}

	/** Moves {@code r} down (dir 1) or up (-1) past whatever it meets until it is clear; null when it leaves the screen. */
	private static Rect slide(Env e, List<Rect> obs, Rect r, int dir) {
		for (int guard = 0; guard < 32; guard++) {
			if (r.y() < MARGIN && dir < 0 || r.bottom() > e.guiH() - MARGIN && dir > 0 || r.y() < 0 || r.bottom() > e.guiH()) {
				return null;
			}
			int ny = r.y();
			boolean hit = false;
			for (Rect o : obs) {
				if (r.intersects(o.grow(GAP))) {
					hit = true;
					ny = dir > 0 ? Math.max(ny, o.bottom() + GAP) : Math.min(ny, o.y() - GAP - r.h());
				}
			}
			if (!hit) {
				return r;
			}
			r = new Rect(r.x(), ny, r.w(), r.h());
		}
		return null;
	}

	// ------------------------------------------------------------------ overlaps

	/** What a rectangle overlaps (no gap: touching is fine). */
	public record Overlaps(boolean bossbar, boolean effects, boolean hotbar, boolean chat, boolean pill, boolean offscreen, boolean sidebar,
		boolean subtitles, boolean toasts, boolean banner) {
		public boolean any() {
			return bossbar || effects || hotbar || chat || pill || offscreen || sidebar || subtitles || toasts || banner;
		}
	}

	public static Overlaps overlaps(Env e, Rect r) {
		if (r.empty()) {
			return new Overlaps(false, false, false, false, false, false, false, false, false, false);
		}
		return new Overlaps(r.intersects(bossBars(e)), r.intersects(effects(e)), r.intersects(hotbar(e)), r.intersects(chat(e)), r.intersects(e.pill()),
			!r.within(e.guiW(), e.guiH()), r.intersects(e.sidebar()), r.intersects(subtitles(e)), r.intersects(e.toasts()), r.intersects(e.banner()));
	}

	// ------------------------------------------------------------------ the auth banner

	/** The auth banner's narrowest width (GUI px) before it gives up narrowing and slides down instead. */
	public static final int BANNER_MIN_W = 150;

	/**
	 * Where the auth banner goes: centred, as high as it can be while clear of everything in {@code e} by {@link #GAP}
	 * (boss bars, effect icons, the connection pill, the sidebar, vanilla toasts, ...). Its height depends on its width
	 * (the message wraps), so it tries widths from {@code wantW} down to {@code minW} (never below {@link #BANNER_MIN_W})
	 * in 10 px steps and keeps the one whose bottom is highest (the wider on a tie): it narrows to fit between the
	 * corners before it drops below them. When nothing fits it sits under the boss bars, {@code wantW} wide (clamped).
	 */
	public static Rect banner(Env e, int wantW, int minW, IntUnaryOperator heightForWidth) {
		int maxW = Math.min(wantW, maxWidth(e));
		if (maxW <= 0) {
			return Rect.NONE;
		}
		List<Rect> obs = obstacles(e);
		Rect best = null;
		minW = Math.min(Math.max(BANNER_MIN_W, minW), maxW);
		for (int w = maxW;; w = Math.max(minW, w - 10)) {
			int h = heightForWidth.applyAsInt(w);
			if (h > 0) {
				Rect r = slide(e, obs, new Rect((e.guiW() - w) / 2, MARGIN, w, h), 1);
				if (r != null && (best == null || r.bottom() < best.bottom())) {
					best = r;
				}
			}
			if (w == minW) {
				break;
			}
		}
		if (best != null) {
			return best;
		}
		int h = Math.max(1, heightForWidth.applyAsInt(maxW));
		Rect boss = bossBars(e);
		return new Rect((e.guiW() - maxW) / 2, boss.empty() ? MARGIN : boss.bottom() + GAP, maxW, h);
	}

	// ------------------------------------------------------------------ toasts

	/**
	 * The toast column: toasts {@code w} wide at {@code x}, within {@code top}..{@code bottom}; {@code up} = the stack
	 * hugs the bottom (the overlay is at the bottom, so the toasts sit just above it).
	 */
	public record Column(int x, int top, int bottom, boolean up) {
		public int height() {
			return Math.max(0, bottom - top);
		}
	}

	/**
	 * Where toasts {@code w} wide stack for an overlay at {@code pos} drawn at {@code overlay} (NONE when the overlay
	 * is hidden: the toasts take its place): on the overlay's side (the right for the top centre), under it (top
	 * positions, right middle) or above it (bottom positions), clear of everything in {@link Env} by {@link #GAP}.
	 */
	public static Column toasts(Env e, Position pos, Rect overlay, int w) {
		int x = pos.left() ? MARGIN : e.guiW() - MARGIN - w;
		List<Rect> obs = obstacles(e);
		if (pos.bottom()) {
			int bottom;
			if (overlay.empty()) {
				Placement p = place(e, pos, w, 1);
				bottom = p.placed() ? p.rect().bottom() : e.guiH() - MARGIN;
			} else {
				bottom = overlay.y() - GAP;
			}
			// above anything this column meets at its bottom (the auth banner is wider than a narrow overlay's column)
			for (int guard = 0; guard < 16; guard++) {
				Rect probe = new Rect(x, bottom - 1, w, 1);
				int ny = bottom;
				for (Rect o : obs) {
					if (probe.intersects(o.grow(GAP))) {
						ny = Math.min(ny, o.y() - GAP);
					}
				}
				if (ny == bottom) {
					break;
				}
				bottom = ny;
			}
			int top = MARGIN;
			for (Rect o : obs) {
				if (o.bottom() <= bottom && x < o.right() && o.x() < x + w) {
					top = Math.max(top, o.bottom() + GAP);
				}
			}
			return new Column(x, top, bottom, true);
		}
		int top;
		if (overlay.empty()) {
			Placement p = place(e, pos.center() ? Position.TOP_RIGHT : pos, w, 1);
			top = p.placed() ? p.rect().y() : MARGIN;
		} else if (pos.center() && overlay.right() < x - GAP) {
			// the overlay is in the middle and the column is clear of it: the toasts start at the top beside it
			top = MARGIN;
		} else {
			top = overlay.bottom() + GAP;
		}
		// past anything this column meets at its top (boss bars are narrower than a wide overlay's column)
		for (int guard = 0; guard < 16; guard++) {
			Rect probe = new Rect(x, top, w, 1);
			int ny = top;
			for (Rect o : obs) {
				if (probe.intersects(o.grow(GAP))) {
					ny = Math.max(ny, o.bottom() + GAP);
				}
			}
			if (!overlay.empty() && probe.intersects(overlay.grow(GAP))) {
				ny = Math.max(ny, overlay.bottom() + GAP);
			}
			if (ny == top) {
				break;
			}
			top = ny;
		}
		int bottom = e.guiH() - MARGIN;
		for (Rect o : obs) {
			if (o.y() >= top && x < o.right() && o.x() < x + w) {
				bottom = Math.min(bottom, o.y() - GAP);
			}
		}
		return new Column(x, top, bottom, false);
	}
}
