package dev.agentcraft.client.hq;

import dev.agentcraft.block.DecisionPodiumBlock;
import dev.agentcraft.block.LampStatus;
import dev.agentcraft.block.MergeStationBlock;
import dev.agentcraft.block.MonitorBlock;
import dev.agentcraft.block.StatusLampBlock;
import dev.agentcraft.block.entity.DecisionPodiumBlockEntity;
import dev.agentcraft.block.entity.MergeStationBlockEntity;
import dev.agentcraft.block.entity.MonitorBlockEntity;
import dev.agentcraft.block.entity.StatusLampBlockEntity;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.Protocol;
import dev.agentcraft.client.foreman.Protocol.Agent;
import dev.agentcraft.client.foreman.Protocol.DecisionKind;
import dev.agentcraft.client.foreman.Protocol.Goal;
import dev.agentcraft.client.foreman.Protocol.Repo;
import dev.agentcraft.client.mp.MpMode;
import dev.agentcraft.client.world.ServerTasks;
import dev.agentcraft.layout.Anchor;
import dev.agentcraft.layout.AnchorNames;
import dev.agentcraft.layout.Anchors;
import dev.agentcraft.mp.net.ServerInfo;
import dev.agentcraft.mp.net.WorldIntentC2S;
import dev.agentcraft.mp.state.LampStatusWire;
import dev.agentcraft.mp.state.MpText;
import dev.agentcraft.mp.state.WorldIntent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CopperBulbBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;

/**
 * Drives the HQ's world blocks from the Foreman state (client thread computes, the integrated
 * server applies, see {@link ServerTasks}):
 * <ul>
 *   <li>status lamps by binding: {@code agent:<id>} (the agent's status family, the same one its
 *       nameplate shows: an idle/done agent with a decision waiting on you is {@code waiting}; off
 *       when the agent is off shift or gone), {@code ci:<repoId>} or {@code ci:#<n>} (the n-th repo
 *       in Foreman order), {@code goal} / {@code goal:atrium} (the current goal), {@code decisions}
 *       (waiting while any decision is open), {@code merge} (waiting while a merge decision is open);</li>
 *   <li>the decision podium {@code open} while any decision is open;</li>
 *   <li>merge stations {@code active} while a merge decision is open;</li>
 *   <li>monitors {@code lit} while their agent is on shift;</li>
 *   <li>signal bulbs (copper bulbs within 3 blocks of the {@code decision_podium} anchor or of a
 *       {@code mergestation} slot) lit while that station needs you.</li>
 * </ul>
 * Only blocks whose state differs are written. The set of wanted states is recomputed on every
 * Foreman change and re-applied every 2 s (so rebuilt or newly placed blocks pick it up). While the
 * Foreman link is down the blocks keep their last state (the view is stale, not wrong).
 *
 * <p>Singleplayer dispatches the world writes to the integrated server thread through
 * {@link ServerTasks}, with the full state of {@link #compute(ForemanState)}; multiplayer sends a
 * {@link WorldIntentC2S} snapshot instead, which carries only what {@link #wireIntent} lets
 * through (no {@code ci:<repoId>}, eight CI slots, wire-legal agent ids, 64 lamps and 64 lit
 * monitors). The decision logic lives in {@link #tick(ForemanState, Anchors.Layout, boolean, ServerInfo, Pacer, Applier)},
 * which takes every dependency as an argument so tests need no client, network or world.
 */
public final class HqWorldDriver {
	private static final int RESYNC_TICKS = 40;
	/** Blocks around the layout bounds that still belong to the HQ (lamps set into the walls). */
	private static final int MARGIN = 3;
	/** Reach of a station's signal bulbs around its anchor (blocks). */
	private static final int SIGNAL_REACH = 3;
	/** Most lamp keys in one intent; mirrors the wire cap on {@code lamps} in {@code PublicJson.intentFromJson}. */
	static final int MAX_LAMPS = 64;
	/** Most lit monitors in one intent; mirrors the wire cap on {@code litMonitors} in {@code PublicJson.intentFromJson}. */
	static final int MAX_LIT_MONITORS = 64;

	/** What the world should show, by binding. Immutable once built. */
	record Wanted(Map<String, LampStatus> lamps, boolean podiumOpen, boolean mergeActive, Map<String, Boolean> monitorLit) {
	}

	/** Applies a computed snapshot; production dispatches to the integrated server, tests record it. */
	@FunctionalInterface
	interface Applier {
		void apply(Wanted wanted, Anchors.Bounds bounds, List<BlockPos> podiumSignals, List<BlockPos> mergeSignals);
	}

	/** Podium override set by {@link dev.agentcraft.client.decisions.DecisionsFeature#syncPodium}:
	 *  {@code null} = none, {@code true} = force open, {@code false} = force closed. */
	private static @Nullable Boolean podiumOverride;
	private static @Nullable Boolean lastOverride;
	/** The full state handed to the {@link Applier} last. */
	private static @Nullable Wanted lastFull;
	/** The filtered state offered to the pacer last (computed on every recompute, sent or not). */
	private static @Nullable WorldIntent lastIntent;
	/** What {@link #wanted()} reports. */
	private static @Nullable Wanted lastWanted;
	private static long lastRevision = -1;
	private static long lastLayout = -1;
	private static int ticks;
	private static volatile int lastChanged;

	private static final Pacer PACER = new Pacer(System::nanoTime, intent -> ClientPlayNetworking.send(new WorldIntentC2S(intent)));

	private HqWorldDriver() {
	}

	/** Blocks changed by the last apply (QA / debugging). */
	public static int lastChanged() {
		return lastChanged;
	}

	/**
	 * What the world shows as of the last recompute, for {@link HqClientFeature} and
	 * {@code dev.state.hq}: the full state while nothing is sent (singleplayer applies all of it),
	 * and the filtered state while the driver sends to a server (what that server will show).
	 */
	public static @Nullable Wanted wanted() {
		return lastWanted;
	}

	/** Called from {@link dev.agentcraft.client.decisions.DecisionsFeature#syncPodium} with the
	 *  renderer's wish (open decisions minus the ones being answered). {@code null} clears it. */
	public static void setPodiumOverride(@Nullable Boolean override) {
		// The renderer repeats its wish every frame. One that agrees with what the driver already shows
		// is no override: storing it would make every tick recompute, only to drop it again.
		if (override != null && podiumOverride == null && lastIntent != null && override == lastIntent.podiumOpen()) {
			return;
		}
		podiumOverride = override;
	}

	/** Test seam: reset all static driver state. */
	static void resetForTest() {
		podiumOverride = null;
		lastOverride = null;
		lastFull = null;
		lastIntent = null;
		lastWanted = null;
		lastRevision = -1;
		lastLayout = -1;
		ticks = 0;
		lastChanged = 0;
		PACER.clear();
	}

	static void tick(Minecraft mc) {
		if (mc.level == null) {
			return;
		}
		boolean canSend = MpMode.current() == MpMode.MULTIPLAYER && ClientPlayNetworking.canSend(WorldIntentC2S.TYPE);
		tick(Foreman.state(), Anchors.current(), canSend, MpMode.serverInfo().orElse(null), PACER, HqWorldDriver::applyThroughServer);
	}

	/** Queue the snapshot on the integrated server thread; a no-op on a remote server. */
	private static void applyThroughServer(Wanted w, Anchors.Bounds b, List<BlockPos> podiumSignals, List<BlockPos> mergeSignals) {
		ServerTasks.run(level -> lastChanged = apply(level, w, b, podiumSignals, mergeSignals));
	}

	/**
	 * The driver's decision logic. {@code canSend} is true only in multiplayer after the server's
	 * hello and with the channel open; {@code applier} performs the world work (production dispatches
	 * it to the integrated server thread). Returns without doing anything when the state is missing,
	 * stale or the layout is empty.
	 */
	static void tick(@Nullable ForemanState st, Anchors.Layout layout, boolean canSend, @Nullable ServerInfo info,
			Pacer pacer, Applier applier) {
		if (st == null || !st.hasData() || st.isStale() || layout.isEmpty() || layout.bounds() == null) {
			return;
		}
		ticks++;
		boolean revisionChanged = st.revision() != lastRevision || layout.revision() != lastLayout;
		// A set, changed or cleared override passes the gate like a changed revision, so the podium
		// reacts on the very next tick without waiting for the Foreman to move.
		boolean overrideChanged = !Objects.equals(podiumOverride, lastOverride);
		boolean resync = ticks % RESYNC_TICKS == 0;
		if (!revisionChanged && !overrideChanged && !resync) {
			// Quiet tick: retry an intent the rate limiter could not send.
			if (canSend && info != null) {
				pacer.flush(info.intentsPerSecond());
			}
			return;
		}
		Wanted computed = compute(st);
		// The override is folded into the intent, and the full state takes the same podium value, so
		// the local world and the server agree on it on this tick.
		WorldIntent intent = applyPodiumOverride(wireIntent(computed, st.agents().keySet(), st.revision()));
		Wanted full = intent.podiumOpen() == computed.podiumOpen() ? computed
			: new Wanted(computed.lamps(), intent.podiumOpen(), computed.mergeActive(), computed.monitorLit());
		lastRevision = st.revision();
		lastLayout = layout.revision();
		lastOverride = podiumOverride;
		// The two are compared separately: a change the wire does not carry (a ci:<repoId> lamp, an
		// agent past a cap) is world work here and no news for the server.
		boolean fullDiffers = !full.equals(lastFull);
		boolean intentDiffers = lastIntent == null || !contentEqual(intent, lastIntent);
		lastFull = full;
		lastIntent = intent;
		lastWanted = canSend ? intentToWanted(intent) : full;
		if (fullDiffers || resync) {
			applier.apply(full, layout.bounds(),
				signalCenters(layout, AnchorNames.DECISION_PODIUM),
				signalCenters(layout, AnchorNames.MERGESTATION));
		}
		if (canSend && info != null) {
			// Replace the pending snapshot with this tick's before spending a send slot, so a free
			// slot always carries the newest state (not an older queued one).
			if (intentDiffers || resync) {
				pacer.offer(intent);
			}
			pacer.flush(info.intentsPerSecond());
		}
	}

	/**
	 * Replace the computed {@code podiumOpen} with the override while one is set; clear the
	 * override when it agrees with the computed value, restoring {@code compute}'s single authority.
	 */
	static WorldIntent applyPodiumOverride(WorldIntent intent) {
		Boolean override = podiumOverride;
		if (override == null) {
			return intent;
		}
		if (override.booleanValue() == intent.podiumOpen()) {
			podiumOverride = null;
			return intent;
		}
		return new WorldIntent(intent.rev(), intent.lamps(), override, intent.mergeActive(), intent.litMonitors());
	}

	/** True when the two intents show the same world, ignoring {@code rev}. */
	static boolean contentEqual(WorldIntent a, WorldIntent b) {
		return a.lamps().equals(b.lamps())
			&& a.podiumOpen() == b.podiumOpen()
			&& a.mergeActive() == b.mergeActive()
			&& a.litMonitors().equals(b.litMonitors());
	}

	/** The state a server shows for {@code intent} (only lit monitors have an entry). */
	private static Wanted intentToWanted(WorldIntent intent) {
		Map<String, LampStatus> lamps = new LinkedHashMap<>(intent.lamps().size() + intent.litMonitors().size());
		intent.lamps().forEach((k, v) -> {
			try {
				lamps.put(k, LampStatus.valueOf(v.name()));
			} catch (IllegalArgumentException ignored) {
			}
		});
		Map<String, Boolean> monitorLit = new LinkedHashMap<>();
		for (String id : intent.litMonitors()) {
			monitorLit.put(id, Boolean.TRUE);
		}
		return new Wanted(Map.copyOf(lamps), intent.podiumOpen(), intent.mergeActive(), Map.copyOf(monitorLit));
	}

	/** Block positions of the anchors of a station (all its slots). */
	private static List<BlockPos> signalCenters(Anchors.Layout layout, String station) {
		List<BlockPos> out = new ArrayList<>();
		for (Anchor a : layout.anchors().values()) {
			String n = a.name();
			if (n.equals(station) || n.startsWith(station + "_") && n.substring(station.length() + 1).chars().allMatch(Character::isDigit)) {
				out.add(BlockPos.containing(a.x(), a.y(), a.z()));
			}
		}
		return out;
	}

	/**
	 * Agent id -> an open decision waiting on the user for that agent: its own question / permission
	 * prompt first, then merges it asked for, then decisions about its task (the same rule as the
	 * agents' nameplates, so a lamp and its agent's status dot always agree).
	 */
	static Map<String, String> awaiting(ForemanState st) {
		Map<String, String> out = new HashMap<>();
		List<Protocol.Decision> open = st.openDecisions();
		for (Protocol.Decision d : open) {
			if (d.kind() != DecisionKind.MERGE) {
				out.putIfAbsent(d.agentId(), d.id());
			}
		}
		for (Protocol.Decision d : open) {
			out.putIfAbsent(d.agentId(), d.id());
		}
		for (Protocol.Decision d : open) {
			if (d.taskId() != null) {
				Protocol.Task t = st.task(d.taskId());
				if (t != null && t.assignee() != null) {
					out.putIfAbsent(t.assignee(), d.id());
				}
			}
		}
		return out;
	}

	/** The lamp an agent shows: its state family, or waiting when it idles on a decision of yours. */
	static LampStatus agentLamp(Agent a, boolean awaitingUser) {
		if (!a.isActive()) {
			return LampStatus.OFF;
		}
		String fam = a.state().family();
		if (awaitingUser && (fam.equals("idle") || fam.equals("done"))) {
			return LampStatus.WAITING;
		}
		return LampStatus.forAgentState(a.state().wire());
	}

	// ------------------------------------------------------------------ compute

	/**
	 * The full state the world should show, from the Foreman state alone: every agent, every repo
	 * (as {@code ci:<repoId>} and as {@code ci:#<n>}), nothing capped. This is what singleplayer
	 * applies; the wire carries only {@link #wireIntent a filtered part} of it.
	 */
	static Wanted compute(ForemanState st) {
		Map<String, LampStatus> lamps = new HashMap<>();
		Map<String, Boolean> lit = new HashMap<>();
		Map<String, String> waitingOn = awaiting(st);
		for (Agent a : st.agents().values()) {
			lamps.put("agent:" + a.id(), agentLamp(a, waitingOn.containsKey(a.id())));
			lit.put(a.id(), a.isActive());
		}
		int n = 0;
		for (Repo r : st.repos().values()) {
			LampStatus ci = LampStatus.forCi(r.ci().wire());
			lamps.put("ci:" + r.id(), ci);
			lamps.put("ci:#" + (++n), ci);
		}
		LampStatus goal = goalLamp(st.goal());
		lamps.put("goal", goal);
		lamps.put("goal:atrium", goal);
		boolean open = !st.openDecisions().isEmpty();
		lamps.put("decisions", open ? LampStatus.WAITING : LampStatus.OFF);
		boolean merge = st.oldestOpen(DecisionKind.MERGE) != null;
		lamps.put("merge", merge ? LampStatus.WAITING : LampStatus.OFF);
		lamps.put(BEACON_BINDING, beaconLamp(st, open, goal));
		return new Wanted(Map.copyOf(lamps), open, merge, Map.copyOf(lit));
	}

	/**
	 * The wire snapshot of the Foreman state: {@link #compute(ForemanState)} through
	 * {@link #wireIntent}. {@code layout} is part of the signature for future callers; lamp colours
	 * come only from {@link ForemanState}.
	 */
	public static WorldIntent compute(ForemanState st, Anchors.Layout layout) {
		return wireIntent(compute(st), st.agents().keySet(), st.revision());
	}

	/**
	 * The part of {@code full} that is sent to a server. Pure, and never throws. Only keys that pass
	 * {@link WorldIntent#isBinding} are kept: {@code ci:<repoId>} is never sent (a repo id is not
	 * public), {@code ci:#} stops at 8, and an agent whose id the wire cannot carry has neither a
	 * lamp nor a lit monitor. The intent never exceeds {@link #MAX_LAMPS} or
	 * {@link #MAX_LIT_MONITORS}: the fixed and CI keys always go in, then the agents in
	 * {@code agentOrder} (the Foreman's order) until a cap is reached.
	 */
	static WorldIntent wireIntent(Wanted full, Iterable<String> agentOrder, long revision) {
		Map<String, LampStatusWire> lamps = new LinkedHashMap<>();
		Set<String> lit = new HashSet<>();
		// The fixed keys and ci:#1..8: at most 13, so they never meet the cap.
		full.lamps().forEach((key, status) -> {
			if (status != null && WorldIntent.isBinding(key) && !key.startsWith("agent:")) {
				lamps.put(key, wire(status));
			}
		});
		// Agents go last, so the caps cut only the tail. The two caps are independent: an agent past
		// the lamp cap has no lamp entry, which the server treats as "agent not present" (its lamp
		// goes dark); an agent past the monitor cap has no monitor entry (its monitor is unlit). The
		// fixed keys take lamp places, so the lamp cap is reached a few agents before the monitor cap.
		for (String id : agentOrder) {
			if (id == null) {
				continue;
			}
			String binding = "agent:" + id;
			LampStatus lamp = full.lamps().get(binding);
			if (lamp != null && lamps.size() < MAX_LAMPS && WorldIntent.isBinding(binding)) {
				lamps.put(binding, wire(lamp));
			}
			if (lit.size() < MAX_LIT_MONITORS && Boolean.TRUE.equals(full.monitorLit().get(id)) && isSafeAgentId(id)) {
				lit.add(id);
			}
		}
		int rev;
		try {
			rev = Math.toIntExact(revision);
		} catch (ArithmeticException e) {
			rev = Integer.MAX_VALUE;
		}
		return new WorldIntent(rev, Map.copyOf(lamps), full.podiumOpen(), full.mergeActive(), Set.copyOf(lit));
	}

	/** An agent id safe to put in {@code litMonitors} (passes the record's constructor guard). */
	static boolean isSafeAgentId(@Nullable String id) {
		return id != null && !id.isEmpty() && id.length() <= 16 && id.equals(MpText.sanitize(id, 16));
	}

	private static LampStatusWire wire(LampStatus status) {
		return LampStatusWire.valueOf(status.name());
	}

	/** The cupola beacon's binding (the whole studio at a glance, seen from outside). */
	public static final String BEACON_BINDING = "beacon";

	/**
	 * The studio's aggregate state for the cupola beacon, most urgent first: anything waiting on you
	 * (clay), an agent in error/blocked (red), work going on (teal) or thinking (brass), the goal done
	 * (sage), else idle.
	 */
	static LampStatus beaconLamp(ForemanState st, boolean decisionOpen, LampStatus goal) {
		if (decisionOpen) {
			return LampStatus.WAITING;
		}
		boolean error = false;
		boolean working = false;
		boolean thinking = false;
		boolean waiting = false;
		for (Agent a : st.agents().values()) {
			if (!a.isActive()) {
				continue;
			}
			switch (a.state().family()) {
				case "waiting" -> waiting = true;
				case "error" -> error = true;
				case "working" -> working = true;
				case "thinking" -> thinking = true;
				default -> {
				}
			}
		}
		if (waiting) {
			return LampStatus.WAITING;
		}
		if (error) {
			return LampStatus.ERROR;
		}
		if (working) {
			return LampStatus.WORKING;
		}
		if (thinking) {
			return LampStatus.THINKING;
		}
		return goal == LampStatus.DONE ? LampStatus.DONE : LampStatus.IDLE;
	}

	static LampStatus goalLamp(@Nullable Goal g) {
		if (g == null) {
			return LampStatus.IDLE;
		}
		return switch (g.status()) {
			case PLANNING -> LampStatus.THINKING;
			case ACTIVE -> LampStatus.WORKING;
			case DONE -> LampStatus.DONE;
			case FAILED -> LampStatus.ERROR;
			default -> LampStatus.IDLE;
		};
	}

	/** Server thread: set every bound station block in the HQ region to its wanted state. */
	static int apply(ServerLevel level, Wanted w, Anchors.Bounds b, List<BlockPos> podiumSignals, List<BlockPos> mergeSignals) {
		List<BlockPos> pos = new ArrayList<>();
		List<BlockState> to = new ArrayList<>();
		int x0 = (b.minX() - MARGIN) >> 4;
		int x1 = (b.maxX() + MARGIN) >> 4;
		int z0 = (b.minZ() - MARGIN) >> 4;
		int z1 = (b.maxZ() + MARGIN) >> 4;
		for (int cx = x0; cx <= x1; cx++) {
			for (int cz = z0; cz <= z1; cz++) {
				LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
				if (chunk == null) {
					continue;
				}
				for (BlockEntity be : chunk.getBlockEntities().values()) {
					BlockPos p = be.getBlockPos();
					if (p.getX() < b.minX() - MARGIN || p.getX() > b.maxX() + MARGIN || p.getZ() < b.minZ() - MARGIN || p.getZ() > b.maxZ() + MARGIN) {
						continue;
					}
					BlockState s = be.getBlockState();
					BlockState want = wantedState(be, s, w);
					if (want != null && want != s) {
						pos.add(p);
						to.add(want);
					}
				}
			}
		}
		signals(level, podiumSignals, w.podiumOpen(), pos, to);
		signals(level, mergeSignals, w.mergeActive(), pos, to);
		for (int i = 0; i < pos.size(); i++) {
			level.setBlock(pos.get(i), to.get(i), Block.UPDATE_CLIENTS);
		}
		return pos.size();
	}

	/** Copper bulbs around the given station anchors follow {@code on} (no redstone involved). */
	private static void signals(ServerLevel level, List<BlockPos> centers, boolean on, List<BlockPos> pos, List<BlockState> to) {
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (BlockPos c : centers) {
			for (int dx = -SIGNAL_REACH; dx <= SIGNAL_REACH; dx++) {
				for (int dz = -SIGNAL_REACH; dz <= SIGNAL_REACH; dz++) {
					for (int dy = -1; dy <= 6; dy++) {
						m.set(c.getX() + dx, c.getY() + dy, c.getZ() + dz);
						if (!level.isLoaded(m)) {
							continue;
						}
						BlockState s = level.getBlockState(m);
						if (s.getBlock() instanceof CopperBulbBlock && s.getValue(CopperBulbBlock.LIT) != on) {
							BlockPos p = m.immutable();
							if (!pos.contains(p)) {
								pos.add(p);
								to.add(s.setValue(CopperBulbBlock.LIT, on));
							}
						}
					}
				}
			}
		}
	}

	private static @Nullable BlockState wantedState(BlockEntity be, BlockState s, Wanted w) {
		if (be instanceof StatusLampBlockEntity lamp && s.getBlock() instanceof StatusLampBlock) {
			LampStatus want = w.lamps().get(lamp.binding());
			if (want == null) {
				// bound to something the Foreman does not have: an agent that left goes dark, an unused CI
				// slot (no second repo yet) shows idle grey rather than a dead lamp
				want = lamp.binding().startsWith("ci:") ? LampStatus.IDLE : lamp.binding().startsWith("agent:") ? LampStatus.OFF : null;
			}
			return want == null ? null : s.setValue(StatusLampBlock.STATUS, want);
		}
		if (be instanceof DecisionPodiumBlockEntity && s.getBlock() instanceof DecisionPodiumBlock) {
			return s.setValue(DecisionPodiumBlock.OPEN, w.podiumOpen());
		}
		if (be instanceof MergeStationBlockEntity && s.getBlock() instanceof MergeStationBlock) {
			return s.setValue(MergeStationBlock.ACTIVE, w.mergeActive());
		}
		if (be instanceof MonitorBlockEntity mon && s.getBlock() instanceof MonitorBlock && !mon.binding().isEmpty()) {
			Boolean lit = w.monitorLit().get(mon.binding());
			return s.setValue(MonitorBlock.LIT, lit != null && lit);
		}
		return null;
	}

	/**
	 * Client-side send limiter and coalescer: keeps the newest unsent intent and sends it as soon as
	 * fewer than {@code rate} sends happened in the last second. Caller-supplied clock, so tests can
	 * advance time without sleeping. {@code offer} replaces the pending snapshot; {@code flush}
	 * spends a free slot on it.
	 */
	static final class Pacer {
		private final LongSupplier clock;
		private final Consumer<WorldIntent> sender;
		private final Deque<Long> sendTimes = new ArrayDeque<>();
		private @Nullable WorldIntent pending;

		Pacer(LongSupplier clock, Consumer<WorldIntent> sender) {
			this.clock = clock;
			this.sender = sender;
		}

		/** Replace the pending snapshot with {@code intent} (newest wins). */
		void offer(WorldIntent intent) {
			pending = intent;
		}

		/** Send the pending intent when the one-second window has room; keep it otherwise. */
		void flush(int ratePerSecond) {
			if (pending == null) {
				return;
			}
			long now = clock.getAsLong();
			long cutoff = now - 1_000_000_000L;
			while (!sendTimes.isEmpty() && sendTimes.peekFirst() < cutoff) {
				sendTimes.pollFirst();
			}
			if (sendTimes.size() >= ratePerSecond) {
				return;
			}
			sender.accept(pending);
			sendTimes.addLast(now);
			pending = null;
		}

		void clear() {
			sendTimes.clear();
			pending = null;
		}
	}
}
