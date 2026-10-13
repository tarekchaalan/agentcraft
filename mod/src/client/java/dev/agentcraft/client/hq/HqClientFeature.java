package dev.agentcraft.client.hq;

import com.google.gson.JsonObject;
import dev.agentcraft.block.DecisionPodiumBlock;
import dev.agentcraft.block.LampStatus;
import dev.agentcraft.block.StatusLampBlock;
import dev.agentcraft.block.entity.DecisionPodiumBlockEntity;
import dev.agentcraft.block.entity.ModBlockEntities;
import dev.agentcraft.block.entity.StatusLampBlockEntity;
import dev.agentcraft.client.dev.DevBridge;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.ui.Guard;
import dev.agentcraft.client.mp.StudioView;
import dev.agentcraft.client.mp.Studios;
import dev.agentcraft.client.ui.UiStyle;
import dev.agentcraft.layout.Anchors;
import dev.agentcraft.mp.StudioId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Client side of the HQ (owner: HQ specialist, with {@code dev.agentcraft.hq} in main):
 * <ul>
 *   <li>{@link HqWorldDriver}: lamps / podium / merge station / monitors follow the Foreman state;</li>
 *   <li>{@link StatusLampRenderer}: waiting lamps breathe, the Goal Atrium hologram;</li>
 *   <li>ambient particles: clay motes rise from waiting lamps and an open decision podium;</li>
 *   <li>{@code dev.state.hq}: layout name, driven lamp states, blocks changed by the last apply;</li>
 *   <li>{@code dev.hq.check}: A* reachability of every station anchor and interior light levels ({@link HqCheck}).</li>
 * </ul>
 */
public final class HqClientFeature {
	private static final int SCAN_TICKS = 20;
	/** Own-studio ambience sources (also what {@code dev.state.hq} reports). */
	private static final List<BlockPos> waitingLamps = new ArrayList<>();
	private static final List<BlockPos> openPodiums = new ArrayList<>();
	/** Remote studios' ambience sources, one list per studio; gated on that studio's online flag. */
	private static final Map<StudioId, List<BlockPos>> remoteWaitingLamps = new LinkedHashMap<>();
	private static final Map<StudioId, List<BlockPos>> remoteOpenPodiums = new LinkedHashMap<>();
	private static final RandomSource RANDOM = RandomSource.create();
	private static int ticks;

	private HqClientFeature() {
	}

	public static void init() {
		BlockEntityRenderers.register(ModBlockEntities.STATUS_LAMP, ctx -> new StatusLampRenderer());
		ClientTickEvents.END_CLIENT_TICK.register(mc -> Guard.run("hq.tick", () -> {
			HqWorldDriver.tick(mc);
			ambience(mc);
		}));
		DevBridge.addStateContributor((mc, state) -> state.add("hq", stateJson()));
		HqCheck.register();
	}

	private static JsonObject stateJson() {
		JsonObject o = new JsonObject();
		Anchors.Layout l = Anchors.current();
		o.addProperty("layout", l.name());
		o.addProperty("revision", l.revision());
		o.addProperty("lastChanged", HqWorldDriver.lastChanged());
		String report = dev.agentcraft.hq.HqFeature.lastReport();
		if (report != null) {
			o.addProperty("lastBuild", report);
		}
		o.addProperty("waitingLamps", waitingLamps.size());
		o.addProperty("openPodiums", openPodiums.size());
		HqWorldDriver.Wanted w = HqWorldDriver.wanted();
		if (w != null) {
			JsonObject lamps = new JsonObject();
			w.lamps().forEach((k, v) -> lamps.addProperty(k, v.getSerializedName()));
			o.add("lamps", lamps);
			o.addProperty("podiumOpen", w.podiumOpen());
			o.addProperty("mergeActive", w.mergeActive());
		}
		return o;
	}

	private static void ambience(Minecraft mc) {
		ClientLevel level = mc.level;
		if (level == null || mc.isPaused()) {
			return;
		}
		if (++ticks % SCAN_TICKS == 0) {
			scan(level);
		}
		ForemanState st = Foreman.state();
		if (st != null && !st.isStale()) {
			spawnMotes(level, waitingLamps, 0.18f);
			spawnPodiumMotes(level, openPodiums, 0.25f);
		}
		// Remote studios are gated on their own presence, never on the viewer's Foreman: an offline
		// owner's studio stays quiet even while the viewer's own studio works.
		for (Map.Entry<StudioId, List<BlockPos>> e : remoteWaitingLamps.entrySet()) {
			if (online(e.getKey())) {
				spawnMotes(level, e.getValue(), 0.18f);
			}
		}
		for (Map.Entry<StudioId, List<BlockPos>> e : remoteOpenPodiums.entrySet()) {
			if (online(e.getKey())) {
				spawnPodiumMotes(level, e.getValue(), 0.25f);
			}
		}
	}

	private static boolean online(StudioId id) {
		Optional<StudioView> view = Studios.view(id);
		return view.isPresent() && view.get().online();
	}

	private static void spawnMotes(ClientLevel level, List<BlockPos> lamps, float chance) {
		for (BlockPos p : lamps) {
			if (RANDOM.nextFloat() < chance) {
				motes(level, p, 0.55f);
			}
		}
	}

	private static void spawnPodiumMotes(ClientLevel level, List<BlockPos> podiums, float chance) {
		for (BlockPos p : podiums) {
			if (RANDOM.nextFloat() < chance) {
				level.addParticle(new DustParticleOptions(UiStyle.CLAY & 0xFFFFFF, 0.7f), p.getX() + 0.2 + RANDOM.nextDouble() * 0.6,
					p.getY() + 1.05, p.getZ() + 0.2 + RANDOM.nextDouble() * 0.6, 0, 0.02, 0);
			}
		}
	}

	/** A clay mote drifting up in front of an exposed face of a waiting lamp. */
	private static void motes(ClientLevel level, BlockPos p, float size) {
		List<Direction> open = new ArrayList<>(4);
		for (Direction d : Direction.Plane.HORIZONTAL) {
			if (level.getBlockState(p.relative(d)).isAir()) {
				open.add(d);
			}
		}
		if (open.isEmpty()) {
			return;
		}
		Direction d = open.get(RANDOM.nextInt(open.size()));
		double x = p.getX() + 0.5 + d.getStepX() * 0.62 + (d.getStepX() == 0 ? (RANDOM.nextDouble() - 0.5) * 0.7 : 0);
		double z = p.getZ() + 0.5 + d.getStepZ() * 0.62 + (d.getStepZ() == 0 ? (RANDOM.nextDouble() - 0.5) * 0.7 : 0);
		double y = p.getY() + 0.15 + RANDOM.nextDouble() * 0.7;
		level.addParticle(new DustParticleOptions(UiStyle.CLAY & 0xFFFFFF, size), x, y, z, 0, 0.015, 0);
	}

	/**
	 * Collect the ambience sources of every studio in range. A station belongs to the studio
	 * {@link Studios#at} resolves for it, so under the singleplayer overlay the own area feeds the
	 * remote studio, exactly as its renderer does. Each studio's bounds stay small; a position is
	 * visited once even when two views (own + overlay) share bounds.
	 */
	private static void scan(ClientLevel level) {
		waitingLamps.clear();
		openPodiums.clear();
		remoteWaitingLamps.clear();
		remoteOpenPodiums.clear();
		Set<Long> seen = new HashSet<>();
		for (StudioView view : Studios.all()) {
			Anchors.Bounds b = view.layout().bounds();
			if (b == null) {
				continue;
			}
			for (int cx = (b.minX() - 3) >> 4; cx <= (b.maxX() + 3) >> 4; cx++) {
				for (int cz = (b.minZ() - 3) >> 4; cz <= (b.maxZ() + 3) >> 4; cz++) {
					LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
					if (chunk == null) {
						continue;
					}
					for (BlockEntity be : chunk.getBlockEntities().values()) {
						BlockState s = be.getBlockState();
						boolean waiting = be instanceof StatusLampBlockEntity && s.getBlock() instanceof StatusLampBlock
							&& s.getValue(StatusLampBlock.STATUS) == LampStatus.WAITING;
						boolean open = be instanceof DecisionPodiumBlockEntity && s.getBlock() instanceof DecisionPodiumBlock
							&& s.getValue(DecisionPodiumBlock.OPEN);
						if (!waiting && !open) {
							continue;
						}
						BlockPos p = be.getBlockPos();
						if (!seen.add(p.asLong())) {
							continue;
						}
						StudioView owner = Studios.at(p).orElse(view);
						if (owner.own()) {
							if (waiting) {
								waitingLamps.add(p);
							}
							if (open) {
								openPodiums.add(p);
							}
						} else {
							if (waiting) {
								remoteWaitingLamps.computeIfAbsent(owner.id(), k -> new ArrayList<>()).add(p);
							}
							if (open) {
								remoteOpenPodiums.computeIfAbsent(owner.id(), k -> new ArrayList<>()).add(p);
							}
						}
					}
				}
			}
		}
	}
}
