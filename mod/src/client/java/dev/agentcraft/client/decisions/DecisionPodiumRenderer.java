package dev.agentcraft.client.decisions;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.agentcraft.AgentCraft;
import dev.agentcraft.Cast;
import dev.agentcraft.block.entity.DecisionPodiumBlockEntity;
import dev.agentcraft.client.agents.PlateLayout;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.Protocol.Decision;
import dev.agentcraft.client.hud.Keys;
import dev.agentcraft.client.hud.UiBits;
import dev.agentcraft.client.mp.StudioView;
import dev.agentcraft.client.mp.Studios;
import dev.agentcraft.client.ui.Kit;
import dev.agentcraft.client.ui.TextUtil;
import dev.agentcraft.client.ui.UiStyle;
import dev.agentcraft.client.ui.WorldUi;
import dev.agentcraft.client.world.StationRenderState;
import dev.agentcraft.client.world.StationRenderer;
import dev.agentcraft.mp.StudioId;
import dev.agentcraft.mp.state.PublicStudioState;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Decision Podium BER: while decisions wait, a speech bubble rises from the podium (billboard, kit
 * {@code bubble} drawn opaque): the waiting count with a pulsing clay dot and the key hint, the first
 * decision's agent (face + name) and its question, two lines at most. It also keeps the podium's
 * {@code open} block state in sync (lit paper, lens and bell).
 */
public class DecisionPodiumRenderer extends StationRenderer<DecisionPodiumBlockEntity, DecisionPodiumRenderer.State> {
	/** Bubble pixel scale relative to vanilla name tags (1/40 block per px): 1/72 block per px. */
	private static final float SCALE = 40f / 72f;
	private static final int W = 172;
	private static final float TAIL_TIP_Y = 1.32f;
	private static final float GROW_FROM = 6f;
	private static final float GROW_MAX = 2.2f;
	/** The bubble is drawn this much nearer to the camera than the podium (depth only; see submit). */
	private static final double NUDGE_BLOCKS = 2.0;

	public static class State extends StationRenderState {
		public int count;
		public @Nullable String agentId;
		public String header = "";
		public FormattedCharSequence nameSeq = FormattedCharSequence.EMPTY;
		public FormattedCharSequence kindSeq = FormattedCharSequence.EMPTY;
		public int nameColor;
		public int nameWidth;
		public List<FormattedCharSequence> lines = List.of();
		public int width = W;
		public boolean stale;
		/** Remote studios show a visitor card: count + waiting face, never the question or the key hint. */
		public boolean remote;
		/** Size factor for the camera distance (constant screen size past {@link #GROW_FROM} blocks). */
		public float grow = 1f;
	}

	/** Bubble height in its own px for {@code lines} question lines (without the tail). */
	private static int height(int lines) {
		return 6 + 10 + 3 + 10 + lines * 10 + 4;
	}

	/** {@code studio} is the remote studio id for a remote card, {@code null} for the own one. */
	private record Cache(@Nullable StudioId studio, long revision, String decisionId, int count, String header, FormattedCharSequence name,
		FormattedCharSequence kind, int nameWidth, List<FormattedCharSequence> lines, int width) {
	}

	private @Nullable Cache cache;

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public boolean shouldRenderOffScreen() {
		// the bubble extends well above the block
		return true;
	}

	@Override
	protected void extractStation(DecisionPodiumBlockEntity be, State s, float partialTicks) {
		Optional<StudioView> maybe = Studios.at(be.getBlockPos());
		if (maybe.isPresent() && !maybe.get().own()) {
			extractRemote(s, maybe.get());
			return;
		}
		// no allocation per frame: count the waiting ones and keep the first
		Decision d = null;
		int count = 0;
		for (Decision x : DecisionQueue.open()) {
			if (!DecisionsFeature.isAnswering(x.id())) {
				if (d == null) {
					d = x;
				}
				count++;
			}
		}
		s.count = count;
		s.remote = false;
		s.stale = Foreman.state() == null || Foreman.state().isStale();
		DecisionsFeature.syncPodium(be.getBlockPos(), be.getBlockState(), count > 0);
		if (d == null) {
			s.agentId = null;
			return;
		}
		s.agentId = d.agentId();
		s.nameColor = UiBits.nameOnLight(d.agentId());
		Cache c = cache;
		if (c == null || c.studio() != null || c.revision() != s.foremanRevision || !c.decisionId().equals(d.id()) || c.count() != s.count) {
			Font font = Minecraft.getInstance().font;
			String header = s.count == 1 ? "1 decision waiting" : s.count + " decisions waiting";
			String name = UiBits.agentName(d.agentId());
			String kind = " · " + DecisionQueue.kindLabel(d.kind());
			int inner = W - 16;
			List<FormattedCharSequence> wrapped = TextUtil.wrap(font, UiBits.oneLine(d.question()), inner);
			// the whole question, wrapped to 3 lines (only a longer one is cut, on its third line)
			List<FormattedCharSequence> lines = new ArrayList<>(wrapped.subList(0, Math.min(3, wrapped.size())));
			if (wrapped.size() > 3) {
				List<String> plain = TextUtil.wrapPlain(font, UiBits.oneLine(d.question()), inner);
				String third = String.join(" ", plain.subList(2, plain.size()));
				lines.set(2, Component.literal(TextUtil.ellipsize(font, third, inner)).getVisualOrderText());
			}
			c = new Cache(null, s.foremanRevision, d.id(), s.count, header, Component.literal(name).getVisualOrderText(), Component.literal(kind)
				.getVisualOrderText(), font.width(name), List.copyOf(lines), W);
			cache = c;
		}
		applyCache(s, c);
		layoutPlacement(s);
	}

	/**
	 * The visitor card: the public open-decision count and the first public agent that waits. It never
	 * reads {@link Foreman}, the decision question or the viewer's queue. Cached per remote studio and
	 * public-state revision, never by {@code foremanRevision}.
	 */
	private void extractRemote(State s, StudioView view) {
		PublicStudioState ps = view.publicState();
		DecisionQueue.RemotePodium m = DecisionQueue.remote(ps);
		s.remote = true;
		s.count = m.count();
		s.stale = !view.online() || ps == null;
		s.agentId = m.agentId();
		s.kindSeq = FormattedCharSequence.EMPTY;
		s.lines = List.of();
		s.width = W;
		if (s.count <= 0) {
			return;
		}
		long rev = ps == null ? -1 : ps.rev();
		Cache c = cache;
		if (c == null || !view.id().equals(c.studio()) || c.revision() != rev || c.count() != s.count) {
			Font font = Minecraft.getInstance().font;
			String name = m.agentName() == null ? "" : m.agentName();
			c = new Cache(view.id(), rev, "remote", s.count, m.header(), Component.literal(name).getVisualOrderText(), FormattedCharSequence.EMPTY,
				font.width(name), List.of(), W);
			cache = c;
		}
		applyCache(s, c);
		s.nameColor = UiBits.nameOnLight(s.agentId);
		layoutPlacement(s);
	}

	private static void applyCache(State s, Cache c) {
		s.header = c.header();
		s.nameSeq = c.name();
		s.kindSeq = c.kind();
		s.nameWidth = c.nameWidth();
		s.lines = c.lines();
		s.width = c.width();
	}

	/** Grow-from-distance and the nameplate reserve, shared by the own and the remote card. */
	private static void layoutPlacement(State s) {
		// world size up to GROW_FROM blocks away, then it grows with the distance (constant screen size,
		// at most GROW_MAX) so the waiting count still reads from across the room
		double bx = s.blockPos.getX() + 0.5;
		double by = s.blockPos.getY() + TAIL_TIP_Y;
		double bz = s.blockPos.getZ() + 0.5;
		Vec3 cam = Minecraft.getInstance().gameRenderer.mainCamera().position();
		float dist = (float) Math.sqrt(cam.distanceToSqr(bx, by, bz));
		s.grow = Math.max(1f, Math.min(GROW_MAX, dist / GROW_FROM));
		// nameplates (the agent waiting next to the podium) lift to clear the bubble instead of covering it
		PlateLayout.reserve(bx, by, bz, s.width + 4, height(s.lines.size()) + 4 + 3, WorldUi.PX * SCALE * s.grow);
	}

	@Override
	public void submit(State s, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		if (s.count <= 0) {
			return;
		}
		Font font = Minecraft.getInstance().font;
		int light = WorldUi.uiLight();
		int w = s.width;
		int h = height(s.lines.size());
		poseStack.pushPose();
		float grow = s.grow;
		// pulled up to NUDGE_BLOCKS towards the camera (same place and size on screen): the agent waiting
		// right next to the podium can't hide the bubble's lower lines with its head
		double ox = s.blockPos.getX() - camera.pos.x;
		double oy = s.blockPos.getY() - camera.pos.y;
		double oz = s.blockPos.getZ() - camera.pos.z;
		double dist = Math.sqrt((ox + 0.5) * (ox + 0.5) + (oy + TAIL_TIP_Y) * (oy + TAIL_TIP_Y) + (oz + 0.5) * (oz + 0.5));
		float nudge = (float) Math.min(0.5, NUDGE_BLOCKS / Math.max(0.1, dist));
		WorldUi.billboard(poseStack, camera, 0.5, TAIL_TIP_Y, 0.5, SCALE * grow, nudge, ox, oy, oz);
		float x0 = -w / 2f;
		float y0 = -h - 4;
		WorldUi.submitNineSlice(poseStack, collector, WorldUi.Layer.SOLID, Kit.BUBBLE, x0, y0, w, h, 0xFFFFFFFF, light);
		WorldUi.submitSprite(poseStack, collector, WorldUi.Layer.SOLID, Kit.BUBBLE_TAIL, -4.5f, -5f, 9, 5, 0xFFFFFFFF, light);

		float tx = x0 + 8;
		float ty = y0 + 6;
		// header: pulsing clay dot + count, key hint on the right
		float pulse = 0.5f - 0.5f * (float) Math.cos((s.timeSeconds % 1.2f) / 1.2f * Math.PI * 2);
		int haloA = (int) (40 + 110 * pulse);
		WorldUi.submitSprite(poseStack, collector, WorldUi.Layer.OVERLAY, Kit.dot("waiting", true), tx - 2, ty - 2, 11, 11, (haloA << 24) | 0xFFFFFF,
			light);
		WorldUi.submitSprite(poseStack, collector, WorldUi.Layer.OVERLAY, Kit.dot("waiting", false), tx, ty, 7, 7, 0.15f, 0xFFFFFFFF, light);
		WorldUi.submitText(poseStack, collector, s.header, tx + 11, ty, s.stale ? UiBits.muted() : UiStyle.CLAY_DARK, light);
		if (!s.remote) {
			String key = Keys.decisions == null ? "J" : Keys.label(Keys.decisions);
			int kw = font.width(key);
			float kx = x0 + w - 8 - kw - 8;
			WorldUi.submitNineSlice(poseStack, collector, WorldUi.Layer.SOLID, Kit.KEYCAP, kx, ty - 2, kw + 8, 12, 0xFFFFFFFF, light);
			WorldUi.submitText(poseStack, collector, key, kx + 4, ty, UiStyle.color("palette.ui.text", 0xFF34312E), light);
		}
		ty += 10 + 3;
		// divider
		WorldUi.submitFill(poseStack, collector, tx, ty - 2, x0 + w - 8, ty - 1, UiStyle.color("palette.ui.edge", 0xFFC9BBA3), light);
		// agent face + name + kind
		if (Cast.get(s.agentId) != null) {
			Identifier tex = AgentCraft.id("textures/gui/portrait/" + s.agentId + ".png");
			float fx = tx;
			float fy = ty + 1;
			collector.order(1).submitCustomGeometry(poseStack, RenderTypes.textPolygonOffset(tex), (pose, vc) -> WorldUi.quad(pose, vc, fx, fy, fx + 8,
				fy + 8, 0.2f, 0, 0, 1, 1, 0xFFFFFFFF, light));
		}
		float nx = tx + 11;
		WorldUi.submitText(poseStack, collector, s.nameSeq, nx, ty + 1, s.nameColor, light);
		WorldUi.submitText(poseStack, collector, s.kindSeq, nx + s.nameWidth, ty + 1, UiBits.muted(), light);
		ty += 11;
		for (FormattedCharSequence line : s.lines) {
			WorldUi.submitText(poseStack, collector, line, tx, ty, UiBits.ink(), light);
			ty += 10;
		}
		poseStack.popPose();
	}
}
