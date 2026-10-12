package dev.agentcraft.client.agents;

import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.jspecify.annotations.Nullable;

/**
 * Render state of an agent: the vanilla avatar state (model, skin, walk cycle) plus AgentCraft's
 * extras. {@link #renderer} routes the submit back to {@link AgentRenderer} (vanilla would send any
 * AvatarRenderState to the player renderer; see EntityRenderDispatcherMixin). Phase 3 hooks may add
 * fields here.
 */
public class AgentRenderState extends AvatarRenderState {
	public @Nullable AgentRenderer renderer;
	/** Studio-qualified key for declutter tracks; public cast id remains on the entity's AgentView. */
	public String agentId = "";
	public AgentPose pose = AgentPose.STAND;
	/** The nameplate to draw this frame (full or compact, chosen by {@link PlateLayout}), or null (too far). */
	public Nameplate.@Nullable Data plate;
	/** Both variants (set in extract; {@link PlateLayout} picks {@link #plate} from them). */
	public Nameplate.@Nullable Data plateFull;
	public Nameplate.@Nullable Data plateCompact;
	/** Declutter lift above the natural spot, plate px (smoothed). */
	public float plateLift;
	/** World size factor of the plate ({@link Nameplate#distanceScale}). */
	public float plateScale = 1f;
	/** Depth nudge (fraction of the camera distance) that orders overlapping plates. */
	public float plateNudge;
	/** {@link AgentView#plateWeight()} at extract time. */
	public int plateWeight;
	/** The crosshair is on this agent (its plate is shown in full). */
	public boolean plateCrosshair;
	/** Seconds since the client started (for pulses), partial-tick accurate. */
	public float timeSeconds;

	// ---------------------------------------------------------------- agent life (Phase 3)

	/** The agent's life state (poses, particles, speech); the model and the extras read it. */
	public @Nullable AgentLife life;
	/** Pose channels interpolated for this frame ({@link AgentLife#frame}, see {@link AgentLife} P_* indices). */
	public float @Nullable [] posePose;
	/** Seated amount 0..1 (sit-down/stand-up blend). */
	public float sit;
	/** Vertical model offset (blocks) applied in setupRotations: the seat drop times {@link #sit}. */
	public float sitDrop;
	/** Open book in the hands (0 = none, 1 = open). */
	public float book;
	/** Page flip progress 0..1 of the held book. */
	public float pageFlip;
	/** Height of the plate's natural bottom above the feet (blocks): {@link Nameplate#HEIGHT} + seat drop. */
	public float plateBase = (float) Nameplate.HEIGHT;
	/** Seated at a desk/terminal with its own monitor (the plate gives way to the monitor up close). */
	public boolean atMonitor;
	/**
	 * Extra plate-space pixels stacked above the plate this frame (speech bubble, "!" marker) and
	 * their width: {@link PlateLayout} reserves them so other plates are lifted clear of them.
	 */
	public float stackHeight;
	public float stackWidth;
	/** "Needs you" marker above the plate: 0 = hidden, 1 = fully shown. */
	public float exclaim;
	/** Speech bubble visibility 0..1 (pop in/out). */
	public float bubble;
	/**
	 * Parts of the leader line to leave out because another plate or bubble is in the way (pairs of
	 * plate-space y, top then bottom, sorted top to bottom; {@link #leaderGapCount} pairs), written by
	 * {@link PlateLayout} (the array belongs to the layout and is reused).
	 */
	public float @Nullable [] leaderGaps;
	public int leaderGapCount;
	/** Partial tick this state was extracted with. */
	public float partialTick;
	/** Light at the agent (for lit extras like the book). */
	public int agentLight;
}
