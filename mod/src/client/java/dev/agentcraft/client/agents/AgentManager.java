package dev.agentcraft.client.agents;

import dev.agentcraft.AgentCraft;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.Protocol;
import dev.agentcraft.client.mp.MpMode;
import dev.agentcraft.client.mp.StudioView;
import dev.agentcraft.client.mp.Studios;
import dev.agentcraft.layout.Anchors;
import dev.agentcraft.mp.StudioId;
import dev.agentcraft.mp.state.PublicEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

/** Reconciles client-side agent managers with the studios currently visible to this client. */
public final class AgentManager {
	private static final AgentManager INSTANCE = new AgentManager();

	private @Nullable StudioAgents own;
	private final Map<StudioId, StudioAgents> remote = new LinkedHashMap<>();
	private volatile boolean studiosChanged = true;
	private int pathFailures;
	/** Client ticks seen; the agents' shared clock ({@link #clock}). */
	private long ticks;

	private AgentManager() {
	}

	public static AgentManager get() {
		return INSTANCE;
	}

	/** Own-studio agents only; agent cards and existing dev commands retain their local semantics. */
	public Map<String, ClientAgentEntity> entities() {
		return own == null ? Map.of() : own.entities();
	}

	public @Nullable ClientAgentEntity entity(String agentId) {
		return own == null ? null : own.entity(agentId);
	}

	public @Nullable ClientAgentEntity entity(StudioId studio, String agentId) {
		if (own != null && own.id().equals(studio)) {
			return own.entity(agentId);
		}
		StudioAgents agents = remote.get(studio);
		return agents == null ? null : agents.entity(agentId);
	}

	public @Nullable ClientAgentEntity byEntityId(int id) {
		if (own != null) {
			ClientAgentEntity entity = own.byEntityId(id);
			if (entity != null) {
				return entity;
			}
		}
		for (StudioAgents agents : remote.values()) {
			ClientAgentEntity entity = agents.byEntityId(id);
			if (entity != null) {
				return entity;
			}
		}
		return null;
	}

	public List<ClientAgentEntity> allEntities() {
		List<ClientAgentEntity> result = new ArrayList<>();
		if (own != null) {
			result.addAll(own.entities().values());
		}
		for (StudioAgents agents : remote.values()) {
			result.addAll(agents.entities().values());
		}
		return List.copyOf(result);
	}

	void onSnapshot() {
		if (own != null) {
			own.onSnapshot();
		}
	}

	/** Registry listeners wake reconciliation; mutation stays client-tick-owned. */
	void studioChanged(StudioId id) {
		studiosChanged = true;
	}

	void studioEvent(StudioId id, PublicEvent event, Minecraft mc) {
		if (mc.isSameThread()) {
			applyEvent(id, event);
		} else {
			mc.execute(() -> applyEvent(id, event));
		}
	}

	private void applyEvent(StudioId id, PublicEvent event) {
		StudioAgents agents = remote.get(id);
		if (agents != null) {
			agents.onEvent(event);
		}
	}

	/**
	 * The tick number agents stamp their advance with ({@link TickGate}). The entity ticks of a client tick
	 * run before {@link #tick} (END_CLIENT_TICK), which bumps it after {@link #catchUpEntityTicks}, so both
	 * read the same value within one client tick. One clock for every studio's agents.
	 */
	long clock() {
		return ticks;
	}

	/**
	 * Advance every agent whose entity tick did not run this client tick: Entity Culling (tickCulling, on by
	 * default) and similar mods skip ticking entities out of view, which froze client-only agents mid-walk.
	 * Same filters as {@code ClientLevel.tickEntities}: not while paused, removed, a passenger or tick-frozen.
	 */
	private void catchUpEntityTicks(Minecraft mc, ClientLevel lvl) {
		if (mc.isPaused()) {
			return;
		}
		for (ClientAgentEntity e : allEntities()) { // a copy: a ticker may add or remove agents
			if (e.isRemoved() || e.isPassenger() || e.level() != lvl || lvl.tickRateManager().isEntityFrozen(e)) {
				continue;
			}
			try {
				e.catchUp(ticks);
			} catch (Throwable ex) {
				AgentCraft.LOGGER.warn("agent catch-up tick failed", ex);
			}
		}
	}

	void tick(Minecraft mc) {
		ClientLevel lvl = mc.level;
		if (lvl != null) {
			catchUpEntityTicks(mc, lvl);
		}
		ticks++;
		syncStudios(mc);
		MpMode mode = MpMode.current();
		if (own != null) {
			if (visible(own)) {
				own.tick(mc, mode);
			} else {
				own.removeAll();
			}
		}
		for (StudioAgents agents : remote.values()) {
			if (visible(agents)) {
				agents.tick(mc, mode);
			} else {
				agents.removeAll();
			}
		}
		pathFailures = own == null ? 0 : own.pathFailures();
		for (StudioAgents agents : remote.values()) {
			pathFailures += agents.pathFailures();
		}
	}

	private void syncStudios(Minecraft mc) {
		StudioView ownView = Studios.own();
		if (own == null || !own.id().equals(ownView.id())) {
			if (own != null) {
				own.detach();
			}
			own = new StudioAgents(ownView, Studios.entityIdBase(ownView.id()));
		} else {
			own.updateView(ownView);
		}
		if (!studiosChanged) {
			return;
		}
		studiosChanged = false;
		Map<StudioId, StudioView> present = new HashMap<>();
		for (StudioView view : Studios.all()) {
			if (view.own() || view.id().equals(ownView.id())) {
				continue;
			}
			present.put(view.id(), view);
			StudioAgents agents = remote.get(view.id());
			if (agents == null) {
				agents = new StudioAgents(view, Studios.entityIdBase(view.id()));
				remote.put(view.id(), agents);
				if (MpMode.current() == MpMode.MULTIPLAYER) {
					logAttached(mc, view);
				}
			} else {
				agents.updateView(view);
			}
		}
		for (var iterator = remote.entrySet().iterator(); iterator.hasNext();) {
			var entry = iterator.next();
			if (!present.containsKey(entry.getKey())) {
				StudioAgents agents = entry.getValue();
				if (MpMode.current() == MpMode.MULTIPLAYER) {
					logDetached(mc, agents.view(), agents.entities().size());
				}
				agents.detach();
				iterator.remove();
			}
		}
	}

	private static void logAttached(Minecraft mc, StudioView view) {
		int count = view.publicState() == null ? 0 : view.publicState().agents().size();
		long revision = view.publicState() == null ? 0 : view.publicState().rev();
		StudioAgents.logAttached(view, count, StudioAgents.plotIndex(view), revision, mc.player == null ? null : mc.player.getUUID());
	}

	private static void logDetached(Minecraft mc, StudioView view, int count) {
		long revision = view.publicState() == null ? 0 : view.publicState().rev();
		StudioAgents.logDetached(view, count, StudioAgents.plotIndex(view), revision, mc.player == null ? null : mc.player.getUUID());
	}

	private static boolean visible(StudioAgents agents) {
		BlockPos sample = samplePoint(agents.view().layout());
		var at = Studios.at(sample);
		if (agents.view().own()) {
			return at.isEmpty() || at.get().id().equals(agents.id());
		}
		if (at.isPresent() && at.get().id().equals(agents.id())) {
			return true;
		}
		// Non-overlay fake mode intentionally renders both studios at the own layout.
		Anchors.Bounds ownBounds = Studios.own().layout().bounds();
		Anchors.Bounds remoteBounds = agents.view().layout().bounds();
		return Studios.plot(agents.id()).isEmpty() && ownBounds != null && remoteBounds != null
			&& ownBounds.contains(sample.getX(), sample.getY(), sample.getZ());
	}

	private static BlockPos samplePoint(Anchors.Layout layout) {
		var spawn = layout.get("spawn");
		if (spawn != null) {
			return BlockPos.containing(spawn.x(), spawn.y(), spawn.z());
		}
		Anchors.Bounds bounds = layout.bounds();
		if (bounds == null) {
			return BlockPos.ZERO;
		}
		return new BlockPos((bounds.minX() + bounds.maxX()) / 2, (bounds.minY() + bounds.maxY()) / 2,
			(bounds.minZ() + bounds.maxZ()) / 2);
	}

	public int movingCount() {
		int count = own == null ? 0 : own.movingCount();
		for (StudioAgents agents : remote.values()) {
			count += agents.movingCount();
		}
		return count;
	}

	public int pathFailures() {
		return pathFailures;
	}

	/** Snap every live studio for QA. */
	public int settle() {
		int count = own == null ? 0 : own.settle();
		for (StudioAgents agents : remote.values()) {
			count += agents.settle();
		}
		return count;
	}

	/**
	 * agentId -> the first open decision that agent <b>owns</b>. Every open decision has exactly one
	 * owner, so the HQ shows one "!" per decision waiting on the user:
	 * <ul>
	 *   <li>a merge belongs to the worker whose task it merges (the lead files it, but it is the
	 *       worker's finished work that waits; "t4 awaiting your merge"), or to the agent that filed
	 *       it when the task has no known assignee;</li>
	 *   <li>a question or permission prompt belongs to the agent that asked, whatever task it is
	 *       about (a question about Juniper's task is Marlow's question, not Juniper's).</li>
	 * </ul>
	 * An agent's own questions/permissions come before the merges it owns.
	 */
	public static String owner(ForemanState st, Protocol.Decision d) {
		if (d.kind() == Protocol.DecisionKind.MERGE && d.taskId() != null) {
			Protocol.Task t = st.task(d.taskId());
			if (t != null && t.assignee() != null && st.agent(t.assignee()) != null) {
				return t.assignee();
			}
		}
		return d.agentId();
	}
}
