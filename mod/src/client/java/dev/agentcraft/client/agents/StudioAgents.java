package dev.agentcraft.client.agents;

import dev.agentcraft.AgentCraft;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.Protocol;
import dev.agentcraft.client.foreman.Protocol.Agent;
import dev.agentcraft.client.foreman.Protocol.AgentState;
import dev.agentcraft.client.mp.MpMode;
import dev.agentcraft.client.mp.StudioView;
import dev.agentcraft.client.mp.Studios;
import dev.agentcraft.layout.Anchor;
import dev.agentcraft.layout.AnchorNames;
import dev.agentcraft.layout.Anchors;
import dev.agentcraft.mp.MpEvents;
import dev.agentcraft.mp.MpLog;
import dev.agentcraft.mp.Plot;
import dev.agentcraft.mp.StudioId;
import dev.agentcraft.mp.state.AgentStateWire;
import dev.agentcraft.mp.state.PublicAgent;
import dev.agentcraft.mp.state.PublicEvent;
import dev.agentcraft.mp.state.PublicStudioState;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Keeps this studio's {@link ClientAgentEntity} instances in sync with its state and layout (client
 * thread, every client tick):
 * <ul>
 *   <li>new agent: spawned standing at its target anchor (no walk-in from nowhere);</li>
 *   <li>station/active change: walks there along a {@link GridPathfinder} route (teleports if
 *       there is no route, e.g. the HQ was rebuilt around it);</li>
 *   <li>agent gone after a snapshot: removed;</li>
 *   <li>Foreman link down: agents stay where they are with a dimmed "Foreman offline" plate;</li>
 *   <li>layout republished ({@code /agentcraft hq}): everyone is placed at their new anchors.</li>
 * </ul>
 * The own studio uses the local Foreman and, in singleplayer, keeps the fallback row near world
 * spawn until a layout is published. Remote studios use only their public state and events.
 *
 * <p>Phase 3: a station anchor with a seat block ({@link Seats}) is walked to via a free cell next
 * to the seat, then the agent steps in and sits; leaving a seat starts with standing up. An own
 * {@code waiting_user} agent walks to the user spot by the podium, or, when you are inside the HQ
 * (not spectating), to a free spot about three blocks from you. A remote waiter uses its owner's
 * spot when the owner is inside the plot, otherwise that studio's podium spot.
 */
final class StudioAgents {
	private static final int ID_WINDOW = 1000;
	/** Teleport instead of walking when the route is longer than this (blocks). */
	private static final double MAX_WALK = 96;
	/** Ticks it takes to get up from a seat before walking off. */
	private static final int STAND_UP_TICKS = 8;
	/** A waiting agent re-approaches you once you moved this far from where it chose its spot (blocks). */
	private static final double FOLLOW_SLACK = 2.6;
	private static final int UNKNOWN_PLOT = -1;
	/** How far from you a waiting agent stands (blocks). */
	static final double USER_DISTANCE = 3.2;

	private final StudioId id;
	private final int slot;
	private final IdPool ids;
	private final Map<String, ClientAgentEntity> entities = new LinkedHashMap<>();
	private final Map<String, ClientAgentEntity> entityView = Collections.unmodifiableMap(entities);
	private final Map<Integer, ClientAgentEntity> byEntityId = new HashMap<>();
	private final StationAssigner assigner = new StationAssigner();
	private final Seats seats = new Seats();
	private final Map<String, UserSpot> userSpots = new HashMap<>();
	private final Map<String, String> awaiting = new HashMap<>();
	private final Map<String, Integer> awaitingCounts = new HashMap<>();
	private StudioView view;
	private @Nullable ClientLevel level;
	private @Nullable AbstractClientPlayer owner;
	private long layoutRevision = -1;
	private long awaitingRevision = -1;
	private long ticks;
	private int pathFailures;

	/** Where a waiting agent stands near the player, and where the player was when it was chosen. */
	private record UserSpot(Anchor spot, Vec3 playerAt) {
	}

	StudioAgents(StudioView view, int entityIdBase) {
		this.view = view;
		this.id = view.id();
		this.slot = view.slot();
		this.ids = new IdPool(entityIdBase);
	}

	StudioId id() {
		return id;
	}

	int slot() {
		return slot;
	}

	boolean remote() {
		return !view.own();
	}

	StudioView view() {
		return view;
	}

	Anchors.Layout layout() {
		return view.layout();
	}

	/** Live agent entities by agent id (client thread). */
	Map<String, ClientAgentEntity> entities() {
		return entityView;
	}

	ClientAgentEntity entity(String agentId) {
		return entities.get(agentId);
	}

	ClientAgentEntity byEntityId(int entityId) {
		return byEntityId.get(entityId);
	}

	void updateView(StudioView next) {
		view = next;
	}

	/**
	 * A snapshot rebuilds the view: forget sticky slots so the assignment depends only on the state
	 * (Foreman order), not on the history of this session. Agents that change slot walk there.
	 */
	void onSnapshot() {
		assigner.clear();
		awaitingRevision = -1;
	}

	void tick(Minecraft mc, MpMode mode) {
		ClientLevel lvl = mc.level;
		if (lvl != level) {
			entities.clear(); // the old level and its entities are gone
			byEntityId.clear();
			assigner.clear();
			seats.clear();
			userSpots.clear();
			level = lvl;
			owner = null;
			layoutRevision = -1;
			ids.reset();
		}
		if (lvl == null) {
			return;
		}
		ticks++;
		if (!view.own()) {
			tickRemote(lvl, view.publicState());
			return;
		}
		ForemanState st = Foreman.state();
		if (st == null || !st.hasData()) {
			removeAll();
			return;
		}
		Anchors.Layout layout = view.layout();
		if (!shouldRunOwnAgents(mode, layout)) {
			removeAll();
			return;
		}
		boolean relayout = layout.revision() != layoutRevision;
		layoutRevision = layout.revision();
		if (relayout) {
			seats.clear();
			userSpots.clear();
		}
		boolean spawnFallback = useSpawnFallback(mode, layout);
		List<Agent> agents = new ArrayList<>(st.agents().values());
		Map<String, Anchor> targets = spawnFallback ? fallbackTargets(agents, lvl) : assigner.assign(agents, layout);
		boolean stale = st.isStale();
		updateAwaiting(st);
		GridPathfinder pf = layout.isEmpty() ? null : new GridPathfinder(lvl, layout.bounds());
		Vec3 playerFeet = layout.isEmpty() ? null : playerInHq(mc, layout, pf);
		int waitingIndex = 0;
		int waitingCount = 0;
		if (playerFeet != null) {
			for (Agent a : agents) {
				if (followsPlayer(a)) {
					waitingCount++;
				}
			}
		}

		Set<String> keep = new HashSet<>();
		for (Agent a : agents) {
			Anchor target = targets.get(a.id());
			if (target == null) {
				continue;
			}
			keep.add(a.id());
			ClientAgentEntity e = entities.get(a.id());
			if (e == null || e.isRemoved() || e.level() != lvl) {
				if (e != null) {
					releaseId(e);
				}
				e = spawn(lvl, a, target);
				if (e == null) {
					continue;
				}
				entities.put(a.id(), e);
				byEntityId.put(e.getId(), e);
				showRecentSay(st, e);
			} else if (!e.getSkin().equals(AgentSkins.get(a.id(), a.skin()))) {
				e.setSkin(AgentSkins.get(a.id(), a.skin()));
			}
			AgentView v = e.view();
			v.attach(this, false, "");
			v.update(a, stale, awaiting.get(a.id()), awaitingCounts.getOrDefault(a.id(), 0));
			v.station = StationAssigner.stationKey(a);
			v.anchor = target.name();
			if (playerFeet != null && !stale && followsPlayer(a)) {
				Anchor near = userSpot(a.id(), e, playerFeet, waitingIndex++, waitingCount, pf);
				if (near != null) {
					target = near;
				}
			} else {
				userSpots.remove(a.id());
			}
			Seats.Seat seat = pf == null ? null : seats.at(lvl, target, ticks, pf, layout);
			Anchor effective = seat != null ? seat.target() : target;
			if (relayout) {
				e.life().setSeat(seat);
				place(e, effective);
			} else if (!stale) {
				retarget(lvl, layout, e, effective, seat);
			}
		}
		for (var it = entities.entrySet().iterator(); it.hasNext();) {
			var en = it.next();
			if (!keep.contains(en.getKey())) {
				remove(lvl, en.getValue());
				it.remove();
			}
		}
	}

	private void tickRemote(ClientLevel lvl, @Nullable PublicStudioState state) {
		if (state == null) {
			removeAll();
			return;
		}
		Anchors.Layout layout = view.layout();
		boolean relayout = layout.revision() != layoutRevision;
		layoutRevision = layout.revision();
		if (relayout) {
			seats.clear();
			userSpots.clear();
		}
		List<PublicAgent> agents = state.agents();
		Map<String, Anchor> targets = layout.isEmpty() ? Map.of() : assigner.assignPublic(agents, layout);
		boolean stale = !view.online() || !state.foremanOnline();
		GridPathfinder pf = layout.isEmpty() ? null : new GridPathfinder(lvl, layout.bounds());
		Plot plot = Studios.plot(id).orElse(null);
		owner = ownerPlayer(lvl, plot);
		int waitingCount = 0;
		for (PublicAgent a : agents) {
			if (followsOwner(a)) {
				waitingCount++;
			}
		}
		int waitingIndex = 0;

		Set<String> keep = new HashSet<>();
		for (PublicAgent a : agents) {
			Anchor target = targets.get(a.id());
			if (target == null) {
				continue;
			}
			keep.add(a.id());
			ClientAgentEntity e = entities.get(a.id());
			if (e == null || e.isRemoved() || e.level() != lvl) {
				if (e != null) {
					releaseId(e);
				}
				e = spawnRemote(lvl, a, target);
				if (e == null) {
					continue;
				}
				entities.put(a.id(), e);
				byEntityId.put(e.getId(), e);
			} else if (e.view().skinChanged(a.skin())) {
				e.setSkin(AgentSkins.get(a.id(), a.skin()));
			}
			AgentView v = e.view();
			v.attach(this, true, view.ownerName());
			v.updatePublic(a, stale);
			v.station = StationAssigner.stationKey(a);
			v.anchor = target.name();
			if (!stale && followsOwner(a)) {
				Anchor waitAnchor = waitingAnchor(layout, waitingIndex);
				if (owner != null && pf != null) {
					Vec3 ownerFeet = ownerFloor(owner, pf);
					if (ownerFeet != null) {
						Anchor aroundOwner = userSpot(a.id(), e, ownerFeet, waitingIndex, waitingCount, pf);
						if (aroundOwner != null) {
							target = aroundOwner;
						}
					}
				}
				if (target == targets.get(a.id()) && waitAnchor != null) {
					target = waitAnchor;
				}
				waitingIndex++;
			} else {
				userSpots.remove(a.id());
			}
			v.anchor = target.name();
			Seats.Seat seat = pf == null ? null : seats.at(lvl, target, ticks, pf, layout);
			Anchor effective = seat != null ? seat.target() : target;
			if (relayout) {
				e.life().setSeat(seat);
				place(e, effective);
			} else if (!stale) {
				retarget(lvl, layout, e, effective, seat);
			}
		}
		removeMissing(lvl, keep);
	}

	private @Nullable ClientAgentEntity spawn(ClientLevel lvl, Agent a, Anchor target) {
		int entityId = ids.allocate();
		if (entityId == 0) {
			return null;
		}
		ClientAgentEntity e = new ClientAgentEntity(lvl, a.id(), AgentSkins.get(a.id(), a.skin()));
		// Negative ids never collide with server-assigned entity ids.
		e.setId(entityId);
		e.view().attach(this, false, "");
		Seats.Seat seat = seats.at(lvl, target, ticks, new GridPathfinder(lvl, view.layout().bounds()), view.layout());
		e.life().setSeat(seat);
		place(e, seat != null ? seat.target() : target);
		lvl.addEntity(e);
		AgentCraft.LOGGER.info("Agent {} appeared at {}", a.id(), target.name());
		return e;
	}

	private @Nullable ClientAgentEntity spawnRemote(ClientLevel lvl, PublicAgent a, Anchor target) {
		int entityId = ids.allocate();
		if (entityId == 0) {
			return null;
		}
		ClientAgentEntity e = new ClientAgentEntity(lvl, a.id(), AgentSkins.get(a.id(), a.skin()));
		e.setId(entityId);
		e.view().skinName = a.skin();
		e.view().attach(this, true, view.ownerName());
		Anchors.Layout layout = view.layout();
		GridPathfinder pf = layout.isEmpty() ? null : new GridPathfinder(lvl, layout.bounds());
		Seats.Seat seat = pf == null ? null : seats.at(lvl, target, ticks, pf, layout);
		e.life().setSeat(seat);
		place(e, seat != null ? seat.target() : target);
		lvl.addEntity(e);
		return e;
	}

	private void removeMissing(ClientLevel lvl, Set<String> keep) {
		for (var it = entities.entrySet().iterator(); it.hasNext();) {
			var en = it.next();
			if (!keep.contains(en.getKey())) {
				remove(lvl, en.getValue());
				userSpots.remove(en.getKey()); // ids come from the remote studio: keep nothing for an agent that left
				it.remove();
			}
		}
	}

	private void updateAwaiting(ForemanState st) {
		if (st.revision() == awaitingRevision) {
			return;
		}
		awaitingRevision = st.revision();
		awaiting.clear();
		awaitingCounts.clear();
		List<Protocol.Decision> open = st.openDecisions();
		for (int pass = 0; pass < 2; pass++) {
			for (Protocol.Decision d : open) {
				boolean merge = d.kind() == Protocol.DecisionKind.MERGE;
				if (merge != (pass == 1)) {
					continue;
				}
				String owner = AgentManager.owner(st, d);
				awaiting.putIfAbsent(owner, d.id());
				awaitingCounts.merge(owner, 1, Integer::sum);
			}
		}
	}

	static boolean useSpawnFallback(MpMode mode, Anchors.Layout layout) {
		return mode != MpMode.MULTIPLAYER && layout.isEmpty();
	}

	static boolean shouldRunOwnAgents(MpMode mode, Anchors.Layout layout) {
		return mode != MpMode.MULTIPLAYER || !layout.isEmpty();
	}

	private static boolean followsPlayer(Agent a) {
		return a.state() == AgentState.WAITING_USER && a.isActive() && !a.isPaused();
	}

	private static boolean followsOwner(PublicAgent a) {
		return a.state() == AgentStateWire.WAITING_USER && a.active() && !a.paused();
	}

	/** The player's feet on the HQ floor when they are inside the HQ and not spectating, else null. */
	private static @Nullable Vec3 playerInHq(Minecraft mc, Anchors.Layout layout, @Nullable GridPathfinder pf) {
		LocalPlayer p = mc.player;
		Anchors.Bounds b = layout.bounds();
		if (p == null || pf == null || b == null || p.isSpectator()) {
			return null;
		}
		// feet a hair below a block top (64.99999) belong to the block above
		BlockPos bp = BlockPos.containing(p.getX(), p.getY() + 0.05, p.getZ());
		if (!b.contains(bp.getX(), bp.getY(), bp.getZ()) && !b.contains(bp.getX(), bp.getY() - 2, bp.getZ())) {
			return null;
		}
		for (int dy = 0; dy <= 3; dy++) {
			double f = pf.floor(bp.getX(), bp.getY() - dy, bp.getZ());
			if (!Double.isNaN(f)) {
				return new Vec3(p.getX(), f, p.getZ());
			}
		}
		return null;
	}

	@Nullable AbstractClientPlayer ownerPlayer(ClientLevel current) {
		return current == level ? owner : null;
	}

	private @Nullable AbstractClientPlayer ownerPlayer(ClientLevel current, @Nullable Plot plot) {
		if (plot == null) {
			return null;
		}
		for (AbstractClientPlayer player : current.players()) {
			if (player.getUUID().equals(id.owner()) && !player.isSpectator()
				&& plot.contains(BlockPos.containing(player.getX(), player.getY(), player.getZ()))) {
				return player;
			}
		}
		return null;
	}

	private static @Nullable Vec3 ownerFloor(AbstractClientPlayer player, GridPathfinder pathfinder) {
		return floorNear(pathfinder, player.getX(), player.getY(), player.getZ());
	}

	private static @Nullable Vec3 floorNear(GridPathfinder pathfinder, double x, double y, double z) {
		BlockPos pos = BlockPos.containing(x, y + 0.05, z);
		for (int dy = 0; dy <= 3; dy++) {
			double floor = pathfinder.floor(pos.getX(), pos.getY() - dy, pos.getZ());
			if (!Double.isNaN(floor)) {
				return new Vec3(x, floor, z);
			}
		}
		return null;
	}

	/**
	 * A free walkable spot about three blocks from the player, on the agent's side (several waiting
	 * agents fan out), facing the player. Three blocks is a conversation distance: the agent, its
	 * plate and its "!" fit on screen at eye level (at two blocks the plate filled the upper middle
	 * of the view and the "!" was cut off). Sticky until the player moves {@value #FOLLOW_SLACK}
	 * blocks away from where they were when it was chosen.
	 */
	private @Nullable Anchor userSpot(String agentId, ClientAgentEntity e, Vec3 player, int index, int count, GridPathfinder pf) {
		UserSpot prev = userSpots.get(agentId);
		if (prev != null && prev.playerAt().distanceTo(player) < FOLLOW_SLACK) {
			return prev.spot();
		}
		if (remote()) {
			Vec3 preferred = ownerApproach(player, e.position(), index, count);
			Anchor direct = ownerSpot(agentId, player, preferred.x, preferred.z, pf);
			if (direct != null) {
				return direct;
			}
		}
		double base = Math.atan2(e.getZ() - player.z, e.getX() - player.x);
		if (e.position().distanceToSqr(player) < 0.25) {
			base = 0;
		}
		double spread = Math.toRadians(42);
		double fan = (index - (count - 1) / 2.0) * spread;
		double[] radii = {USER_DISTANCE, USER_DISTANCE + 0.5, USER_DISTANCE - 0.6};
		double[] offs = {0, 0.45, -0.45, 0.9, -0.9, 1.4, -1.4, 2.0, -2.0, Math.PI};
		for (double r : radii) {
			for (double o : offs) {
				double ang = base + fan + o;
				double x = player.x + Math.cos(ang) * r;
				double z = player.z + Math.sin(ang) * r;
				int bx = (int) Math.floor(x);
				int bz = (int) Math.floor(z);
				int by = (int) Math.floor(player.y + 0.01);
				for (int dy : new int[] {0, 1, -1}) {
					double f = pf.floor(bx, by + dy, bz);
					if (Double.isNaN(f)) {
						continue;
					}
					Vec3 at = new Vec3(x, f, z);
					if (!pf.clear(at, at) || taken(agentId, at)) {
						continue;
					}
					float yaw = (float) Math.toDegrees(Math.atan2(-(player.x - x), player.z - z));
					Anchor spot = new Anchor(userSpotName(remote()), x, f, z, yaw, 0);
					userSpots.put(agentId, new UserSpot(spot, player));
					return spot;
				}
			}
		}
		return null;
	}

	static String userSpotName(boolean remote) {
		return AnchorNames.USER + (remote ? "@owner" : "@player");
	}

	private @Nullable Anchor ownerSpot(String agentId, Vec3 owner, double x, double z, GridPathfinder pathfinder) {
		int bx = (int) Math.floor(x);
		int bz = (int) Math.floor(z);
		int by = (int) Math.floor(owner.y + 0.01);
		for (int dy : new int[] {0, 1, -1}) {
			double floor = pathfinder.floor(bx, by + dy, bz);
			if (Double.isNaN(floor)) {
				continue;
			}
			Vec3 at = new Vec3(x, floor, z);
			if (!pathfinder.clear(at, at) || taken(agentId, at)) {
				continue;
			}
			float yaw = (float) Math.toDegrees(Math.atan2(-(owner.x - x), owner.z - z));
			Anchor spot = new Anchor(userSpotName(remote()), x, floor, z, yaw, 0);
			userSpots.put(agentId, new UserSpot(spot, owner));
			return spot;
		}
		return null;
	}

	private boolean taken(String agentId, Vec3 at) {
		for (var en : userSpots.entrySet()) {
			if (!en.getKey().equals(agentId) && en.getValue().spot().pos().distanceToSqr(at) < 1.2 * 1.2) {
				return true;
			}
		}
		for (ClientAgentEntity o : entities.values()) {
			if (!o.agentId().equals(agentId) && !o.motion().walking() && o.position().distanceToSqr(at) < 0.9 * 0.9) {
				return true;
			}
		}
		return false;
	}

	static @Nullable Anchor waitingAnchor(Anchors.Layout layout, int index) {
		Anchor podium = layout.get("podium_user");
		if (index == 0 && podium != null) {
			return podium;
		}
		List<Anchor> slots = StationAssigner.slots(layout, AnchorNames.USER);
		if (slots.isEmpty()) {
			return null;
		}
		int slotIndex = podium == null ? index : Math.max(0, index - 1);
		if (slotIndex < slots.size()) {
			return slots.get(slotIndex);
		}
		Anchor primary = slots.get(0);
		int overflow = slotIndex - slots.size() + 1;
		return StationAssigner.overflowSpot(primary, overflow);
	}

	static Vec3 ownerApproach(Vec3 ownerFeet, Vec3 agentFeet, int index, int count) {
		double base = Math.atan2(agentFeet.z - ownerFeet.z, agentFeet.x - ownerFeet.x);
		if (agentFeet.distanceToSqr(ownerFeet) < 0.25) {
			base = 0;
		}
		double fan = (index - (count - 1) / 2.0) * Math.toRadians(42);
		double angle = base + fan;
		return new Vec3(ownerFeet.x + Math.cos(angle) * USER_DISTANCE, ownerFeet.y, ownerFeet.z + Math.sin(angle) * USER_DISTANCE);
	}

	/** A fresh agent shows what it said in the last few seconds (e.g. after a reconnect). */
	private static void showRecentSay(ForemanState st, ClientAgentEntity e) {
		Protocol.AgentSay say = st.lastSay(e.agentId());
		if (say == null) {
			return;
		}
		long ago = System.currentTimeMillis() - say.ts();
		if (ago >= 0 && ago < 8000) {
			e.life().bubble.showLate(say, e.life().age(), (int) (ago / 50));
		}
	}

	void onEvent(PublicEvent event) {
		if (event instanceof PublicEvent.Say say) {
			ClientAgentEntity speaker = entities.get(say.agentId());
			if (speaker == null) {
				return;
			}
			PublicStudioState state = view.publicState();
			Map<String, String> names = new HashMap<>();
			if (state != null) {
				for (PublicAgent agent : state.agents()) {
					names.put(agent.id(), agent.name());
				}
			}
			speaker.life().bubble.showRemote(say, view.ownerName(), names, speaker.life().age());
			if (say.to() != null) {
				ClientAgentEntity listener = entities.get(say.to());
				if (listener != null && listener != speaker) {
					listener.life().listen(say.agentId(), 60 + Math.min(120, say.length()));
				}
			}
		} else if (event instanceof PublicEvent.TaskDone done) {
			ClientAgentEntity e = entities.get(done.agentId());
			if (e != null) {
				e.life().onTaskDone();
			}
		}
	}

	int movingCount() {
		int n = 0;
		for (ClientAgentEntity e : entities.values()) {
			if (e.motion().walking()) {
				n++;
			}
		}
		return n;
	}

	/** Snap every agent to its target now (QA: no one mid-walk in a screenshot). */
	int settle() {
		int n = 0;
		for (ClientAgentEntity e : entities.values()) {
			Anchor t = e.motion().target();
			if (t != null && e.motion().walking()) {
				place(e, t);
				n++;
			}
		}
		return n;
	}

	int pathFailures() {
		return pathFailures;
	}

	void removeAll() {
		if (level != null) {
			for (ClientAgentEntity e : entities.values()) {
				remove(level, e);
			}
		}
		entities.clear();
		byEntityId.clear();
		if (remote()) {
			userSpots.clear();
		}
	}

	void detach() {
		removeAll();
		assigner.clear();
		seats.clear();
	}

	private void retarget(ClientLevel lvl, Anchors.Layout layout, ClientAgentEntity e, Anchor target, Seats.@Nullable Seat seat) {
		Anchor current = e.motion().target();
		if (current != null && current.name().equals(target.name()) && current.pos().distanceToSqr(target.pos()) < 1e-4) {
			return;
		}
		GridPathfinder pf = new GridPathfinder(lvl, layout.bounds());
		AgentLife life = e.life();
		List<Vec3> route = new ArrayList<>();
		Vec3 start = e.position();
		int delay = 0;
		Seats.Seat from = life.seat();
		if (from != null && life.sitAmount() > 0f && !e.motion().walking()) {
			// get up first, then step out of the seat to its free side
			delay = STAND_UP_TICKS;
			if (from.approach() != null) {
				route.add(start);
				start = from.approach();
			}
		}
		Vec3 dest = seat != null && seat.approach() != null ? seat.approach() : target.pos();
		List<Vec3> path = start.distanceToSqr(dest) < 1e-6 ? List.of(start, dest) : pf.find(start, dest);
		if (path == null || length(path) > MAX_WALK) {
			pathFailures++;
			AgentCraft.LOGGER.info("Agent {}: no walkable route to {} ({}), teleporting", e.agentId(), target.name(),
				path == null ? "no path" : "too far");
			life.setSeat(seat);
			place(e, target);
			return;
		}
		route.addAll(path);
		if (seat != null && seat.approach() != null) {
			route.add(target.pos()); // the last step: onto the seat
		}
		life.setSeat(seat);
		e.motion().walkTo(target, route, delay);
	}

	private static double length(List<Vec3> route) {
		double d = 0;
		for (int i = 1; i < route.size(); i++) {
			d += route.get(i).distanceTo(route.get(i - 1));
		}
		return d;
	}

	private static void place(ClientAgentEntity e, Anchor target) {
		Vec3 p = e.motion().placeAt(target);
		e.snapTo(p, target.yaw());
	}

	private void remove(ClientLevel lvl, ClientAgentEntity e) {
		lvl.removeEntity(e.getId(), Entity.RemovalReason.DISCARDED);
		releaseId(e);
	}

	private void releaseId(ClientAgentEntity entity) {
		byEntityId.remove(entity.getId());
		ids.release(entity.getId());
	}

	/** No layout yet: a row in front of the world spawn, facing it. */
	private static Map<String, Anchor> fallbackTargets(List<Agent> agents, ClientLevel lvl) {
		Map<String, Anchor> out = new LinkedHashMap<>();
		BlockPos spawn = lvl.getRespawnData().pos();
		int n = agents.size();
		for (int i = 0; i < n; i++) {
			double x = spawn.getX() + 0.5 + (i - (n - 1) / 2.0) * 1.4;
			out.put(agents.get(i).id(), new Anchor(AnchorNames.LOUNGE + "@spawn" + i, x, spawn.getY(), spawn.getZ() + 4.5, 180, 0));
		}
		return out;
	}

	static void logAttached(StudioView view, int agents, int plot, long revision, @Nullable UUID player) {
		if (player == null) {
			MpLog.event(MpEvents.AGENTS_STUDIO_ATTACHED, "studio", view.id().owner(), "plot", plot, "rev", revision, "slot", view.slot(), "agents", agents);
		} else {
			MpLog.event(MpEvents.AGENTS_STUDIO_ATTACHED, "player", player, "studio", view.id().owner(), "plot", plot, "rev", revision,
				"slot", view.slot(), "agents", agents);
		}
	}

	static void logDetached(StudioView view, int agents, int plot, long revision, @Nullable UUID player) {
		if (player == null) {
			MpLog.event(MpEvents.AGENTS_STUDIO_DETACHED, "studio", view.id().owner(), "plot", plot, "rev", revision, "slot", view.slot(), "agents", agents);
		} else {
			MpLog.event(MpEvents.AGENTS_STUDIO_DETACHED, "player", player, "studio", view.id().owner(), "plot", plot, "rev", revision,
				"slot", view.slot(), "agents", agents);
		}
	}

	static int plotIndex(StudioView view) {
		return Studios.plot(view.id()).map(Plot::index).orElse(UNKNOWN_PLOT);
	}

	static final class IdPool {
		private final int base;
		private int next;
		private final ArrayDeque<Integer> released = new ArrayDeque<>();
		private final Set<Integer> allocated = new HashSet<>();

		IdPool(int base) {
			if (base >= 0) {
				throw new IllegalArgumentException("entity id base must be negative");
			}
			this.base = base;
			this.next = base;
		}

		int allocate() {
			Integer reused = released.pollFirst();
			if (reused != null) {
				allocated.add(reused);
				return reused;
			}
			if ((long) base - next >= ID_WINDOW) {
				return 0;
			}
			int id = next--;
			allocated.add(id);
			return id;
		}

		void release(int id) {
			if (allocated.remove(id)) {
				released.addLast(id);
			}
		}

		void reset() {
			next = base;
			released.clear();
			allocated.clear();
		}
	}
}
