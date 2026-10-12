package dev.agentcraft.client.agents;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.agentcraft.AgentCraft;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.object.book.BookModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.EnchantTableRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.sprite.SpriteGetter;
import net.minecraft.world.entity.player.PlayerModelType;
import org.jspecify.annotations.Nullable;

/**
 * Draws an agent with the vanilla player model (both skin layers, slim or wide arms), plus the
 * AgentCraft nameplate and Phase 3 hook geometry. One instance per arm model; the dispatcher mixin
 * picks the right one per entity and routes submits back here.
 *
 * <p>Agent life (Phase 3): the model is an {@link AgentModel} (postures from {@link AgentLife}),
 * seated agents are lowered onto their seat in {@link #setupRotations}, a reading agent holds an
 * open vanilla book, and the plate stack (nameplate, speech bubble, "needs you" marker) plus the
 * state particles are drawn in {@link #submitNameDisplay}.
 */
public class AgentRenderer extends AvatarRenderer<ClientAgentEntity> {
	private static @Nullable AgentRenderer wide;
	private static @Nullable AgentRenderer slim;
	/** Nameplates are drawn within this distance (blocks). */
	public static final double PLATE_DISTANCE = 40;
	/** Particles are simulated always but drawn only within this distance (blocks). */
	public static final double PARTICLE_DISTANCE = 48;

	private final BookModel book;
	private final SpriteGetter sprites;

	public AgentRenderer(EntityRendererProvider.Context context, boolean slimArms) {
		super(context, slimArms);
		this.model = new AgentModel(context.bakeLayer(slimArms ? ModelLayers.PLAYER_SLIM : ModelLayers.PLAYER), slimArms);
		this.book = new BookModel(context.bakeLayer(ModelLayers.BOOK));
		this.sprites = context.getSprites();
	}

	/** Registered for the agent entity type: builds both variants on every resource reload. */
	public static AgentRenderer provide(EntityRendererProvider.Context context) {
		slim = new AgentRenderer(context, true);
		wide = new AgentRenderer(context, false);
		return wide;
	}

	public static @Nullable AgentRenderer forModel(PlayerModelType model) {
		return model == PlayerModelType.SLIM ? slim : wide;
	}

	@Override
	public AvatarRenderState createRenderState() {
		return new AgentRenderState();
	}

	@Override
	public void extractRenderState(ClientAgentEntity entity, AvatarRenderState state, float partialTicks) {
		super.extractRenderState(entity, state, partialTicks);
		if (!(state instanceof AgentRenderState s)) {
			return;
		}
		s.renderer = this;
		AgentView v = entity.view();
		s.agentId = v.plateKey;
		s.pose = v.pose;
		s.atMonitor = v.pose == AgentPose.SIT && ("desk".equals(v.station) || "terminal".equals(v.station));
		s.timeSeconds = (entity.tickCount + partialTicks) / 20f;
		s.agentLight = s.lightCoords;
		s.partialTick = partialTicks;
		AgentLife life = entity.life();
		life.extract(s, partialTicks);
		// Nameplates are world information (like the Task Wall), so they stay visible with the HUD hidden (F1, screenshots).
		// PlateLayout (end of level extraction) picks full/compact, lift and depth nudge for this frame.
		if (s.distanceToCameraSq < PLATE_DISTANCE * PLATE_DISTANCE) {
			s.plateFull = Nameplate.of(v);
			s.plateCompact = Nameplate.compactOf(v);
			s.plate = s.plateFull;
			s.plateScale = Nameplate.distanceScale(Math.sqrt(s.distanceToCameraSq));
			PlateStack.measure(s, life);
		} else {
			s.plate = null;
			s.plateFull = null;
			s.plateCompact = null;
			s.plateScale = 1f;
			s.stackHeight = 0;
			s.stackWidth = 0;
		}
		s.plateLift = 0f;
		s.plateNudge = 0f;
		s.plateWeight = v.plateWeight();
		if (s.bubble > 0f && s.plateWeight > 0) {
			// a speaking agent keeps its bubble next to it; quieter plates make room
			s.plateWeight = Math.max(s.plateWeight, 5);
		}
		s.plateCrosshair = entity == Minecraft.getInstance().crosshairPickEntity;
		for (AgentHooks.Extractor e : AgentHooks.EXTRACTORS) {
			try {
				e.extract(entity, s, partialTicks);
			} catch (Throwable t) {
				AgentCraft.LOGGER.warn("agent extractor failed", t);
			}
		}
	}

	@Override
	protected void setupRotations(AvatarRenderState state, PoseStack poseStack, float bodyRot, float entityScale) {
		if (state instanceof AgentRenderState s && s.sitDrop != 0f) {
			poseStack.translate(0f, s.sitDrop, 0f);
		}
		super.setupRotations(state, poseStack, bodyRot, entityScale);
	}

	@Override
	public void submit(AvatarRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		super.submit(state, poseStack, collector, camera);
		if (state instanceof AgentRenderState s && s.book > 0.02f && !s.isInvisible) {
			submitBook(s, poseStack, collector);
		}
	}

	/** An open vanilla book held in both hands in front of the chest, pages facing the reader's eyes. */
	private void submitBook(AgentRenderState s, PoseStack poseStack, SubmitNodeCollector collector) {
		poseStack.pushPose();
		poseStack.translate(0f, s.sitDrop, 0f);
		poseStack.rotateDegrees(Axis.YP, -s.bodyRot);
		// hands meet ~1.0 block above the feet, 0.42 in front (READ pose, model scale 0.9375)
		float open = Math.min(1f, s.book);
		poseStack.translate(0f, 1.04f, 0.5f);
		poseStack.rotateDegrees(Axis.XP, 48f);
		poseStack.rotateDegrees(Axis.YP, 90f);
		float sc = 0.85f * (0.6f + 0.4f * open);
		poseStack.scale(sc, sc, sc);
		float flip = s.pageFlip;
		AgentLife life = s.life;
		if (life == null) {
			poseStack.popPose();
			return;
		}
		BookModel.State st = life.bookState(1.3f * open, flip > 0 ? flip : 0.12f, flip > 0 ? Math.min(1f, flip * 1.3f) : 0.88f);
		collector.submitModel(book, st, poseStack, s.lightCoords, OverlayTexture.NO_OVERLAY, -1, EnchantTableRenderer.BOOK_TEXTURE, sprites, 0);
		poseStack.popPose();
	}

	@Override
	protected void submitNameDisplay(AvatarRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		if (!(state instanceof AgentRenderState s)) {
			return;
		}
		if (s.plate != null) {
			Nameplate.submit(s, s.plate, poseStack, collector, camera);
			PlateStack.submit(s, poseStack, collector, camera);
		}
		AgentLife life = s.life;
		if (life != null && s.distanceToCameraSq < PARTICLE_DISTANCE * PARTICLE_DISTANCE) {
			life.particles.submit(poseStack, collector, camera, s.x, s.y, s.z, s.partialTick, s.lightCoords);
		}
		for (AgentHooks.Submitter h : AgentHooks.SUBMITTERS) {
			try {
				h.submit(s, poseStack, collector, camera);
			} catch (Throwable t) {
				AgentCraft.LOGGER.warn("agent submitter failed", t);
			}
		}
	}
}
