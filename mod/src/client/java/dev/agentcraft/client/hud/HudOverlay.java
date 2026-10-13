package dev.agentcraft.client.hud;

import com.google.gson.JsonObject;
import dev.agentcraft.hud.HudLayout;
import dev.agentcraft.hud.HudLayout.Column;
import dev.agentcraft.hud.HudLayout.Env;
import dev.agentcraft.hud.HudLayout.Overlaps;
import dev.agentcraft.hud.HudLayout.Placement;
import dev.agentcraft.hud.HudLayout.Rect;
import dev.agentcraft.hud.HudSettings;
import dev.agentcraft.hud.HudSettings.Style;
import dev.agentcraft.hud.HudVisibility;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.jspecify.annotations.Nullable;

/**
 * The in-game overlay in the chosen style ({@link HudConfig}): Off, Pill, Pill+ or Panel (default)
 * ({@link PillStyle}, {@link GoalBar}), at its position and size, placed by the pure {@link HudLayout} clear of
 * boss bars, effect icons, the connection pill and auth banner, the hotbar with its status rows, the chat, the
 * scoreboard sidebar, the subtitles and vanilla toasts; hidden on F1, when idle (auto-hide), in combat (optional)
 * or when there is no room ({@link HudVisibility}). Also says where the toasts stack ({@link #toastColumn}).
 * Registered after the connection banner (reads this frame's pill) and before the toasts. Client thread.
 */
public final class HudOverlay implements HudElement {
	/** The Panel at the top centre narrows down to this (overlay px) to stay beside the connection pill. */
	private static final int PANEL_MIN_W = 220;
	private static Rect rect = Rect.NONE;
	private static @Nullable Placement placement;
	private static @Nullable String hidden = HudVisibility.NO_WORLD;
	private static Env env = Env.of(426, 240, 3);
	private static float scale = 1f;
	private static Column column = new Column(0, 40, 200, false);
	private static String text = "";
	private static int contentW;
	private static int contentH;

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
		Minecraft mc = Minecraft.getInstance();
		GoalBar.beginFrame();
		HudSettings s = HudConfig.get();
		long now = System.currentTimeMillis();
		Env e = HudEnv.current(mc, g.guiWidth(), g.guiHeight());
		env = e;
		HudModel m = mc.player == null ? new HudModel() : HudModel.live();
		boolean idle = HudVisibility.idle(m.activeGoal(), m.needsPlayer(), m.peek != null);
		String reason = HudVisibility.hiddenReason(mc.player != null, mc.gui.hud.isHidden(), s.style(), m.hasData, s.hideInCombat(),
			HudCombat.inCombat(now), s.autoHide(), idle);
		float k = HudLayout.scale(s.size(), e.guiScale());
		scale = k;
		Rect r = Rect.NONE;
		Placement p = null;
		text = "";
		contentW = 0;
		contentH = 0;
		if (reason == null) {
			Font font = mc.font;
			int maxW = (int) Math.floor(HudLayout.maxWidth(e) / k);
			int[] wh = measure(s.style(), m, font, maxW);
			if (wh[0] <= 0 || wh[1] <= 0) {
				reason = HudVisibility.IDLE;
			} else {
				contentW = wh[0];
				contentH = wh[1];
				p = HudLayout.place(e, s.position(), HudLayout.scaled(wh[0], k), HudLayout.scaled(wh[1], k));
				if (s.style() == Style.PANEL && p.placed() && !p.fallback() && p.used() == HudSettings.Position.TOP_CENTER
					&& p.rect().y() > HudLayout.MARGIN) {
					// as the goal bar always did: narrower beside the connection pill rather than below it
					for (int cw = wh[0] - 10; cw >= PANEL_MIN_W; cw -= 10) {
						int[] c = GoalBar.measure(m, font, cw);
						Placement q = HudLayout.place(e, HudSettings.Position.TOP_CENTER, HudLayout.scaled(c[0], k), HudLayout.scaled(c[1], k));
						if (q.placed() && !q.fallback() && q.rect().y() < p.rect().y()) {
							p = q;
							wh = c;
							if (q.rect().y() <= HudLayout.MARGIN) {
								break;
							}
						}
					}
					contentW = wh[0];
					contentH = wh[1];
				}
				if (!p.placed()) {
					reason = HudVisibility.NO_ROOM;
				} else {
					r = p.rect();
					GoalBar.Align align = p.used().left() ? GoalBar.Align.LEFT : p.used().center() ? GoalBar.Align.CENTER : GoalBar.Align.RIGHT;
					draw(g, font, s.style(), m, wh[0], wh[1], r.x(), r.y(), k, align);
					text = s.style() == Style.PILL ? PillStyle.pillText(m, font, wh[0]) : "";
					GoalBar.bottom = r.bottom();
					GoalBar.right = r.right();
					GoalBar.pillClash = r.intersects(e.pill());
				}
			}
		}
		hidden = reason;
		rect = r;
		placement = p;
		column = HudLayout.toasts(e, p != null && p.placed() ? p.used() : s.position(), r, Toasts.W);
	}

	/** Overlay px: the style's width and height for this model (0, 0 = nothing to draw). */
	static int[] measure(Style style, HudModel m, Font font, int maxW) {
		return switch (style) {
			case OFF -> new int[] {0, 0};
			case PILL -> PillStyle.measurePill(m, font, maxW);
			case PILL_PLUS -> PillStyle.measurePlus(m, font, maxW);
			case PANEL -> GoalBar.measure(m, font, maxW);
		};
	}

	/** Draws a style {@code w} x {@code h} (overlay px) with its top left at {@code x, y} (GUI px), scaled by {@code k}. */
	private static void draw(GuiGraphicsExtractor g, Font font, Style style, HudModel m, int w, int h, int x, int y, float k, GoalBar.Align align) {
		int alpha = m.stale ? 200 : 255;
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(k, k);
		try {
			switch (style) {
				case PILL -> PillStyle.drawPill(g, font, m, w, alpha);
				case PILL_PLUS -> PillStyle.drawPlus(g, font, m, w, h, alpha);
				case PANEL -> GoalBar.draw(g, font, m, w, align);
				case OFF -> {
				}
			}
		} finally {
			g.pose().popMatrix();
		}
	}

	/** Where the toasts stack this frame (beside / under / above the overlay). */
	public static Column toastColumn() {
		return column;
	}

	public static Rect rect() {
		return rect;
	}

	public static @Nullable String hidden() {
		return hidden;
	}

	// ------------------------------------------------------------------ QA

	static @Nullable JsonObject rectJson(Rect r) {
		if (r.empty()) {
			return null;
		}
		JsonObject o = new JsonObject();
		o.addProperty("x", r.x());
		o.addProperty("y", r.y());
		o.addProperty("w", r.w());
		o.addProperty("h", r.h());
		return o;
	}

	/** {@code dev.hud.state}: style, position, size, rect, peek, hidden reason, overlaps and what the layout saw. */
	static void state(JsonObject o) {
		HudSettings s = HudConfig.get();
		long now = System.currentTimeMillis();
		o.addProperty("style", s.style().wire());
		o.addProperty("position", s.position().wire());
		o.addProperty("size", s.size().wire());
		// F1: vanilla skips the HUD elements, so the last frame's values would be stale
		var mc = Minecraft.getInstance();
		boolean f1 = mc.player != null && mc.gui.hud.isHidden();
		o.add("rect", f1 ? null : rectJson(rect));
		o.addProperty("hidden", f1 ? HudVisibility.F1 : hidden);
		Overlaps ov = HudLayout.overlaps(env, f1 ? Rect.NONE : rect);
		JsonObject overlaps = new JsonObject();
		overlaps.addProperty("bossbar", ov.bossbar());
		overlaps.addProperty("effects", ov.effects());
		overlaps.addProperty("hotbar", ov.hotbar());
		overlaps.addProperty("chat", ov.chat());
		overlaps.addProperty("pill", ov.pill());
		overlaps.addProperty("offscreen", ov.offscreen());
		overlaps.addProperty("sidebar", ov.sidebar());
		overlaps.addProperty("subtitles", ov.subtitles());
		overlaps.addProperty("toasts", ov.toasts());
		overlaps.addProperty("banner", ov.banner());
		overlaps.addProperty("any", ov.any());
		o.add("overlaps", overlaps);
		JsonObject peek = new JsonObject();
		var cur = HudPeeks.current(now);
		peek.addProperty("enabled", s.peek());
		peek.addProperty("active", cur != null);
		peek.addProperty("kind", cur == null ? null : cur.kind());
		peek.addProperty("text", cur == null ? null : cur.text());
		peek.addProperty("remainingMs", HudPeeks.remaining(now));
		peek.addProperty("waiting", HudPeeks.waiting());
		o.add("peek", peek);

		JsonObject ovl = new JsonObject();
		ovl.add("settings", s.toJson());
		ovl.addProperty("scale", scale);
		ovl.addProperty("effectivePx", HudLayout.effectivePx(s.size(), env.guiScale()));
		ovl.addProperty("contentW", contentW);
		ovl.addProperty("contentH", contentH);
		ovl.addProperty("placedAt", placement == null ? null : placement.used().wire());
		ovl.addProperty("fallback", placement != null && placement.fallback());
		ovl.addProperty("text", text);
		ovl.addProperty("inCombat", HudCombat.inCombat(now));
		ovl.addProperty("hostileNear", HudCombat.hostileNear());
		ovl.addProperty("lastHurtAt", HudCombat.lastHurtAt());
		JsonObject en = new JsonObject();
		en.addProperty("guiWidth", env.guiW());
		en.addProperty("guiHeight", env.guiH());
		en.addProperty("guiScale", env.guiScale());
		en.addProperty("bossBars", env.bossBars());
		en.addProperty("bossBarsDrawn", HudLayout.bossBarsDrawn(env.bossBars(), env.guiH()));
		en.addProperty("beneficialEffects", env.beneficial());
		en.addProperty("harmfulEffects", env.harmful());
		en.addProperty("survivalBars", env.statusBars());
		en.add("bossRect", rectJson(HudLayout.bossBars(env)));
		en.add("effectsRect", rectJson(HudLayout.effects(env)));
		en.add("hotbarRect", rectJson(HudLayout.hotbar(env)));
		en.add("chatRect", rectJson(HudLayout.chat(env)));
		en.add("pillRect", rectJson(env.pill()));
		en.add("sidebarRect", rectJson(env.sidebar()));
		en.addProperty("subtitlesOn", env.subtitlesOn());
		en.add("subtitleRowsRect", rectJson(env.subtitleRows()));
		en.add("subtitlesRect", rectJson(HudLayout.subtitles(env)));
		en.add("vanillaToastsRect", rectJson(env.toasts()));
		en.add("bannerRect", rectJson(env.banner()));
		ovl.add("env", en);
		JsonObject col = new JsonObject();
		col.addProperty("x", column.x());
		col.addProperty("top", column.top());
		col.addProperty("bottom", column.bottom());
		col.addProperty("up", column.up());
		ovl.add("toastColumn", col);
		o.add("overlay", ovl);
	}
}
