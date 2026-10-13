package dev.agentcraft.mp.server.intent;

import dev.agentcraft.block.DecisionPodiumBlock;
import dev.agentcraft.block.LampStatus;
import dev.agentcraft.block.MergeStationBlock;
import dev.agentcraft.block.MonitorBlock;
import dev.agentcraft.block.StatusLampBlock;
import dev.agentcraft.block.entity.DecisionPodiumBlockEntity;
import dev.agentcraft.block.entity.MergeStationBlockEntity;
import dev.agentcraft.block.entity.MonitorBlockEntity;
import dev.agentcraft.block.entity.StatusLampBlockEntity;
import dev.agentcraft.layout.Anchor;
import dev.agentcraft.layout.AnchorNames;
import dev.agentcraft.layout.Anchors;
import dev.agentcraft.mp.MpEvents;
import dev.agentcraft.mp.MpLog;
import dev.agentcraft.mp.MpReasons;
import dev.agentcraft.mp.Plot;
import dev.agentcraft.mp.StudioId;
import dev.agentcraft.mp.state.LampStatusWire;
import dev.agentcraft.mp.state.WorldIntent;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CopperBulbBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * Applies a {@link WorldIntent} inside the sender's plot box only. The caller resolves the plot and
 * the studio; this class never reads a position or a studio from the payload.
 */
public final class IntentApplier {
	/** Reach of a station's signal bulbs around its anchor (blocks). */
	private static final int SIGNAL_REACH = 3;

	private IntentApplier() {}

	/**
	 * Set lamp, podium, merge, monitor and signal-bulb block states inside {@code plot} to match
	 * {@code intent}. {@code loggedPlots} holds the plot indexes that already logged an unknown
	 * binding server-wide, so the warning is emitted once per plot (across reconnects and owners).
	 *
	 * @return the number of blocks changed
	 */
	public static int apply(ServerLevel level, Plot plot, StudioId studio, WorldIntent intent, Set<Integer> loggedPlots) {
		List<BlockPos> pos = new ArrayList<>();
		List<BlockState> to = new ArrayList<>();
		AABB box = plot.box();
		int x0 = (int) Math.floor(box.minX) >> 4;
		int x1 = ((int) Math.ceil(box.maxX) - 1) >> 4;
		int z0 = (int) Math.floor(box.minZ) >> 4;
		int z1 = ((int) Math.ceil(box.maxZ) - 1) >> 4;
		for (int cx = x0; cx <= x1; cx++) {
			for (int cz = z0; cz <= z1; cz++) {
				LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
				if (chunk == null) {
					continue;
				}
				for (BlockEntity be : chunk.getBlockEntities().values()) {
					BlockPos p = be.getBlockPos();
					if (!plot.contains(p)) {
						continue;
					}
					BlockState s = be.getBlockState();
					BlockState want = wantedState(be, s, intent, studio, plot, intent.rev(), loggedPlots);
					if (want != null && want != s) {
						pos.add(p);
						to.add(want);
					}
				}
			}
		}
		// Signal bulbs: copper bulbs around the podium and merge anchors, clipped to the plot box.
		Anchors.Layout layout = Anchors.forStudio(studio);
		signals(level, layout, AnchorNames.DECISION_PODIUM, intent.podiumOpen(), plot, pos, to);
		signals(level, layout, AnchorNames.MERGESTATION, intent.mergeActive(), plot, pos, to);
		for (int i = 0; i < pos.size(); i++) {
			level.setBlock(pos.get(i), to.get(i), Block.UPDATE_CLIENTS);
		}
		return pos.size();
	}

	private static @Nullable BlockState wantedState(BlockEntity be, BlockState s, WorldIntent intent,
			StudioId studio, Plot plot, int rev, Set<Integer> loggedPlots) {
		if (be instanceof StatusLampBlockEntity lamp && s.getBlock() instanceof StatusLampBlock) {
			String binding = lamp.binding();
			if (!WorldIntent.isBinding(binding)) {
				if (loggedPlots.add(plot.index())) {
					UUID owner = studio.owner();
					MpLog.event(MpEvents.WORLD_INTENT_REJECTED, "player", owner, "studio", owner,
						"plot", plot.index(), "rev", rev, "reason", MpReasons.UNKNOWN_BINDING);
				}
				return null;
			}
			LampStatusWire want = intent.lamps().get(binding);
			if (want == null) {
				// bound to something the sender's Foreman did not include: an agent that left goes dark,
				// an unused CI slot shows idle grey rather than a dead lamp
				LampStatus fallback = binding.startsWith("ci:") ? LampStatus.IDLE
					: binding.startsWith("agent:") ? LampStatus.OFF : null;
				if (fallback == null) {
					return null;
				}
				return s.getValue(StatusLampBlock.STATUS) == fallback ? null : s.setValue(StatusLampBlock.STATUS, fallback);
			}
			LampStatus lampStatus;
			try {
				lampStatus = LampStatus.valueOf(want.name());
			} catch (IllegalArgumentException e) {
				return null;
			}
			return s.getValue(StatusLampBlock.STATUS) == lampStatus ? null : s.setValue(StatusLampBlock.STATUS, lampStatus);
		}
		if (be instanceof DecisionPodiumBlockEntity && s.getBlock() instanceof DecisionPodiumBlock) {
			boolean current = s.getValue(DecisionPodiumBlock.OPEN);
			return current == intent.podiumOpen() ? null : s.setValue(DecisionPodiumBlock.OPEN, intent.podiumOpen());
		}
		if (be instanceof MergeStationBlockEntity && s.getBlock() instanceof MergeStationBlock) {
			boolean current = s.getValue(MergeStationBlock.ACTIVE);
			return current == intent.mergeActive() ? null : s.setValue(MergeStationBlock.ACTIVE, intent.mergeActive());
		}
		if (be instanceof MonitorBlockEntity mon && s.getBlock() instanceof MonitorBlock && !mon.binding().isEmpty()) {
			boolean lit = intent.litMonitors().contains(mon.binding());
			boolean current = s.getValue(MonitorBlock.LIT);
			return current == lit ? null : s.setValue(MonitorBlock.LIT, lit);
		}
		return null;
	}

	/** Copper bulbs around the given station anchors follow {@code on} (no redstone involved). */
	private static void signals(ServerLevel level, Anchors.Layout layout, String station, boolean on, Plot plot, List<BlockPos> pos, List<BlockState> to) {
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (Anchor a : layout.anchors().values()) {
			String n = a.name();
			if (!n.equals(station) && !(n.startsWith(station + "_") && n.substring(station.length() + 1).chars().allMatch(Character::isDigit))) {
				continue;
			}
			BlockPos c = BlockPos.containing(a.x(), a.y(), a.z());
			for (int dx = -SIGNAL_REACH; dx <= SIGNAL_REACH; dx++) {
				for (int dz = -SIGNAL_REACH; dz <= SIGNAL_REACH; dz++) {
					for (int dy = -1; dy <= 6; dy++) {
						m.set(c.getX() + dx, c.getY() + dy, c.getZ() + dz);
						if (!plot.contains(m)) {
							continue;
						}
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
}
