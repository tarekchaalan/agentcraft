package dev.agentcraft.client.agents;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.agentcraft.client.ui.Kit;
import dev.agentcraft.client.ui.TextUtil;
import dev.agentcraft.client.ui.UiStyle;
import dev.agentcraft.client.ui.WorldUi;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * The agent nameplate: an opaque ink pill (kit {@code nameplate}, drawn on {@link WorldUi.Layer#SOLID})
 * above the head with a status dot + the name in the agent's colour, and the activity line under it
 * in warm grey. Billboarded, depth-tested, full-bright. Waiting-on-user agents get a pulsing clay
 * halo on the dot.
 *
 * <p>Two variants per agent, both cached on the {@link AgentView} and rebuilt only when their text
 * changes (drawing allocates nothing per frame): the full plate and a compact name-only pill.
 * {@link PlateLayout} picks one per frame, lifts plates that would overlap on screen, and gives each
 * a depth nudge so that, while two plates still overlap (mid-animation), one hides the other
 * completely. A lifted plate gets a thin leader line down to its agent's head.
 *
 * <p>Phase 3 geometry that must sit with the plate (speech bubbles) should draw in plate space:
 * {@code poseStack.pushPose(); Nameplate.plateSpace(state, poseStack, camera); ... y < Nameplate.top(state) ...; poseStack.popPose();}
 */
public final class Nameplate {
	/** Bottom of the plate above the feet of a standing agent, in blocks (before any declutter lift; seated agents: {@link AgentRenderState#plateBase}). */
	public static final double HEIGHT = 2.12;
	public static final int MAX_ACTIVITY_PX = 116;
	/** Plates keep their world size up to this distance, then grow with it (constant screen size) ... */
	public static final double SCALE_FROM = 12;
	/** ... up to this factor (so mid-distance plates stay legible, docs/visual-bar.md "three distances"). */
	public static final float SCALE_MAX = 1.6f;
	private static final int DOT = 7;
	private static final int GAP = 3;
	/** Leader lines are drawn when the plate is lifted more than this (plate px). */
	private static final float LEADER_MIN = 3f;

	/** Gap between the name and the model chip, and the chip's side padding (px). */
	private static final int CHIP_GAP = 4;
	private static final int CHIP_PAD = 3;

	/**
	 * A laid-out plate (pixel units). {@code compact}: name only. {@code chip}: the model the agent
	 * runs ("Opus 5.5", "GPT-6 Astra"), drawn after the name in its engine's colours; empty for the sim.
	 */
	public record Data(String name, int nameColor, String family, String activity, boolean stale, boolean paused, boolean compact,
		FormattedCharSequence nameSeq, FormattedCharSequence activitySeq, int width, int height, int innerWidth, int row1Width, int row2Width,
		FormattedCharSequence activitySeq2, int row3Width, String chip, FormattedCharSequence chipSeq, int chipWidth, int chipBg, int chipFg) {
		boolean sameText(String n, int c, String f, String a, boolean s, boolean p, String ch) {
			return name.equals(n) && nameColor == c && family.equals(f) && activity.equals(a) && stale == s && paused == p && chip.equals(ch);
		}
	}

	/** The chip text for a view: its model, or its engine when the model is unknown; "" for the sim. */
	static String chipOf(AgentView v) {
		// off-shift agents are not on the team: no engine to show
		if (v.engine == null || !v.active) {
			return "";
		}
		if (v.model != null && !v.model.isBlank()) {
			return v.model;
		}
		return "codex".equals(v.engine) ? "Codex" : "claude".equals(v.engine) ? "Claude" : v.engine;
	}

	/** Chip colours by engine: Claude in clay, Codex in OpenAI's black and white. */
	private static int chipBg(@org.jspecify.annotations.Nullable String engine) {
		return "codex".equals(engine) ? 0xFFEDEDED : 0xFFD97757;
	}

	private static int chipFg(@org.jspecify.annotations.Nullable String engine) {
		return "codex".equals(engine) ? 0xFF0D0D0D : 0xFF2A170E;
	}

	private Nameplate() {
	}

	/** The full plate for a view, cached until its name/colour/dot/activity/stale changes. Client thread. */
	public static Data of(AgentView v) {
		String act = v.activityLine();
		String fam = v.dotFamily();
		Data d = v.plateCache;
		if (d != null && d.sameText(v.name, v.nameColor, fam, act, v.stale, v.showsPaused(), chipOf(v))) {
			return d;
		}
		d = layout(v, fam, act, false);
		v.plateCache = d;
		return d;
	}

	/** The compact (name-only) plate; the full plate itself when there is no activity line. */
	public static Data compactOf(AgentView v) {
		Data full = of(v);
		if (full.row2Width() == 0) {
			return full;
		}
		Data d = v.compactCache;
		if (d != null && d.sameText(v.name, v.nameColor, full.family(), full.activity(), v.stale, full.paused(), full.chip())) {
			return d;
		}
		d = layout(v, full.family(), full.activity(), true);
		v.compactCache = d;
		return d;
	}

	private static Data layout(AgentView v, String fam, String act, boolean compact) {
		Font font = Minecraft.getInstance().font;
		Kit.Padding pad = Kit.padding("nameplate");
		// the activity wraps to a second line before it is ever cut ("outlining the Tags docs");
		// a question waiting for you is shown in full by the podium bubble, so the plate just says so
		String shown = act.startsWith("asking you") ? "question for you" : act.replace("`", "");
		String activity = "";
		String activity2 = "";
		if (!compact && !shown.isEmpty()) {
			List<String> wrapped = TextUtil.wrapPlain(font, shown, MAX_ACTIVITY_PX);
			activity = wrapped.get(0);
			if (wrapped.size() > 1) {
				activity2 = TextUtil.ellipsize(font, String.join(" ", wrapped.subList(1, wrapped.size())), MAX_ACTIVITY_PX);
			}
		}
		String chip = chipOf(v);
		int chipW = chip.isEmpty() ? 0 : font.width(chip) + 2 * CHIP_PAD;
		int row1 = DOT + GAP + font.width(v.name) + (chipW > 0 ? CHIP_GAP + chipW : 0);
		int row2 = activity.isEmpty() ? 0 : font.width(activity);
		int row3 = activity2.isEmpty() ? 0 : font.width(activity2);
		int innerW = Math.max(row1, Math.max(row2, row3));
		int w = innerW + pad.left() + pad.right() + 2;
		int h = pad.top() + 9 + (activity.isEmpty() ? 0 : 10) + (activity2.isEmpty() ? 0 : 10) + pad.bottom() + 1;
		return new Data(v.name, v.nameColor, fam, act, v.stale, v.showsPaused(), compact, Component.literal(v.name).getVisualOrderText(),
			Component.literal(activity).getVisualOrderText(), w, h, innerW, row1, row2, Component.literal(activity2).getVisualOrderText(), row3,
			chip, Component.literal(chip).getVisualOrderText(), chipW, chipBg(v.engine), chipFg(v.engine));
	}

	/** World size factor for a plate at this camera distance (blocks). */
	public static float distanceScale(double distance) {
		return (float) Math.max(1.0, Math.min(SCALE_MAX, distance / SCALE_FROM));
	}

	/**
	 * Move the pose (at the entity origin) into the plate's pixel space: origin at the bottom centre
	 * of the plate as it would sit without declutter, x right, y down, 1 unit = 1 GUI px of plate.
	 * Includes the distance scale and the depth nudge, so anything drawn here lines up with the plate.
	 */
	public static void plateSpace(AgentRenderState s, PoseStack poseStack, CameraRenderState camera) {
		WorldUi.billboard(poseStack, camera, 0, s.plateBase, 0, s.plateScale, s.plateNudge, s.x - camera.pos.x, s.y - camera.pos.y, s.z - camera.pos.z);
	}

	/** Top edge of the drawn plate in plate space (negative = above the origin); 0 when there is no plate. */
	public static float top(AgentRenderState s) {
		return s.plate == null ? 0f : -s.plate.height() - s.plateLift;
	}

	public static void submit(AgentRenderState s, Data d, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		Kit.Padding pad = Kit.padding("nameplate");
		float lift = s.plateLift; // negative: pulled down to stay on screen (PlateLayout)
		float x0 = -d.width() / 2f;
		float y0 = -d.height() - lift;
		int light = WorldUi.uiLight();

		poseStack.pushPose();
		plateSpace(s, poseStack, camera);
		if (lift > LEADER_MIN) {
			// a hairline from the plate down to just above the head, in the agent's colour; it passes
			// behind other plates and bubbles on its way (PlateLayout cuts gaps where they are)
			int line = UiStyle.withAlpha(d.nameColor(), 0xFF);
			float top = -lift;
			float[] gaps = s.leaderGaps;
			for (int i = 0; gaps != null && i < s.leaderGapCount; i++) {
				float g0 = gaps[2 * i];
				float g1 = gaps[2 * i + 1];
				if (g0 > top) {
					WorldUi.submitFill(poseStack, collector, -0.5f, top, 0.5f, Math.min(g0, -1f), line, light);
				}
				top = Math.max(top, g1);
			}
			if (top < -1f) {
				WorldUi.submitFill(poseStack, collector, -0.5f, top, 0.5f, -1f, line, light);
			}
		}
		WorldUi.submitNineSlice(poseStack, collector, WorldUi.Layer.SOLID, Kit.NAMEPLATE, x0, y0, d.width(), d.height(), 0xFFFFFFFF, light);
		float cx = x0 + pad.left() + 1 + (d.innerWidth() - d.row1Width()) / 2f;
		float ty = y0 + pad.top() + 1;
		if (d.paused()) {
			// paused by you: a pause glyph instead of the dot (also on the compact pill)
			poseStack.pushPose();
			poseStack.translate(0f, 0f, 0.3f);
			int bar = UiStyle.base("ink_ui.activity", 0xFFC4BDB2);
			WorldUi.submitFill(poseStack, collector, cx + 0.5f, ty + 1, cx + 2.5f, ty + 1 + DOT, bar, light);
			WorldUi.submitFill(poseStack, collector, cx + 4.5f, ty + 1, cx + 6.5f, ty + 1 + DOT, bar, light);
			poseStack.popPose();
		} else {
			if ("waiting".equals(d.family())) {
				float pulse = 0.5f + 0.5f * (float) Math.sin(s.timeSeconds * Math.PI * 2 / (UiStyle.metric("metrics.pulse_ms", 1200) / 1000.0));
				int a = (int) (90 + 165 * pulse);
				WorldUi.submitSprite(poseStack, collector, Kit.dot("waiting", true), cx - 2, ty - 1, 11, 11, (a << 24) | 0xFFFFFF, light);
			}
			WorldUi.submitSprite(poseStack, collector, WorldUi.Layer.OVERLAY, Kit.dot(d.family(), false), cx, ty + 1, DOT, DOT, 0.15f, 0xFFFFFFFF, light);
		}
		WorldUi.submitText(poseStack, collector, d.nameSeq(), cx + DOT + GAP, ty, d.stale() ? UiStyle.withAlpha(d.nameColor(), 0xB0) : d.nameColor(),
			light);
		if (d.chipWidth() > 0) {
			// the model chip after the name; nudged towards the camera (fill, then its text) so it sits on the plate
			float chx = cx + d.row1Width() - d.chipWidth();
			int alpha = d.stale() ? 0xB0 : 0xFF;
			poseStack.pushPose();
			poseStack.translate(0f, 0f, 0.3f);
			WorldUi.submitFill(poseStack, collector, chx, ty - 1, chx + d.chipWidth(), ty + 9, UiStyle.withAlpha(d.chipBg(), alpha), light);
			poseStack.translate(0f, 0f, 0.3f);
			WorldUi.submitText(poseStack, collector, d.chipSeq(), chx + CHIP_PAD, ty, UiStyle.withAlpha(d.chipFg(), alpha), light);
			poseStack.popPose();
		}
		if (d.row2Width() > 0) {
			int actColor = d.stale() ? UiStyle.base("ink_ui.ghost", 0xFF857D71) : UiStyle.base("ink_ui.activity", 0xFFC4BDB2);
			WorldUi.submitText(poseStack, collector, d.activitySeq(), x0 + pad.left() + 1 + (d.innerWidth() - d.row2Width()) / 2f, ty + 10, actColor,
				light);
			if (d.row3Width() > 0) {
				WorldUi.submitText(poseStack, collector, d.activitySeq2(), x0 + pad.left() + 1 + (d.innerWidth() - d.row3Width()) / 2f, ty + 20,
					actColor, light);
			}
		}
		poseStack.popPose();
	}
}
