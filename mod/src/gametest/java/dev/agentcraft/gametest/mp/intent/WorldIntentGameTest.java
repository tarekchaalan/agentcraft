package dev.agentcraft.gametest.mp.intent;

import dev.agentcraft.block.LampStatus;
import dev.agentcraft.block.ModBlocks;
import dev.agentcraft.block.MonitorBlock;
import dev.agentcraft.block.StatusLampBlock;
import dev.agentcraft.block.entity.StationBlockEntity;
import dev.agentcraft.layout.Anchor;
import dev.agentcraft.layout.AnchorNames;
import dev.agentcraft.layout.Anchors;
import dev.agentcraft.mp.MpLog;
import dev.agentcraft.mp.MpServerConfig;
import dev.agentcraft.mp.Plot;
import dev.agentcraft.mp.PlotDirectory;
import dev.agentcraft.mp.Plots;
import dev.agentcraft.mp.StudioId;
import dev.agentcraft.mp.server.intent.IntentApplier;
import dev.agentcraft.mp.server.intent.WorldIntentFeature;
import dev.agentcraft.mp.state.LampStatusWire;
import dev.agentcraft.mp.state.WorldIntent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Game tests for server-side {@link WorldIntentFeature} and {@link IntentApplier}: plot-box
 * clipping, attribution to the connection's player, the dedicated/enabled gate, per-player rate
 * limiting, unknown-binding handling, disconnect cleanup and telemetry capture.
 *
 * <p>Every fixture lives in plot 1 or plot 2 (never plot 0, which every packet's tests share).
 * Positions are recorded before the test changes them (including full block-entity data) and
 * restored in {@code finally}; the handler's per-player rate and server-wide logged-plot state is
 * cleared there too.
 */
public final class WorldIntentGameTest {
	private static final BlockPos PLOT1_ORIGIN = new BlockPos(128, 0, 0);
	private static final BlockPos PLOT2_ORIGIN = new BlockPos(128, 0, 128);

	// Plot 1's box is x 82..174, y 60..100, z -36..54 (Plot.box, inclusive).
	private static final BlockPos INSIDE_1 = new BlockPos(128, 69, 0);
	private static final BlockPos OUT_WEST = new BlockPos(81, 69, 0);
	private static final BlockPos OUT_EAST = new BlockPos(175, 69, 0);
	private static final BlockPos OUT_NORTH = new BlockPos(128, 69, -37);
	private static final BlockPos OUT_SOUTH = new BlockPos(128, 69, 55);
	private static final BlockPos OUT_ABOVE = new BlockPos(128, 101, 0);
	private static final BlockPos OUT_BELOW = new BlockPos(128, 59, 0);
	/** A decision_podium anchor at plot 1's east edge, so bulbs just outside are within reach. */
	private static final BlockPos PODIUM_ANCHOR = new BlockPos(174, 69, 0);
	private static final BlockPos BULB_INSIDE = new BlockPos(173, 70, 0);
	private static final BlockPos BULB_OUTSIDE = new BlockPos(175, 70, 0);

	// ------------------------------------------------------------------ plot-box clipping (item 9)

	@GameTest(maxTicks = 20)
	public void intentAppliesOnlyInsideTheSendersPlot(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		ServerLevel level = server.overworld();
		UUID player = UUID.randomUUID();
		Plot plot = new Plot(1, StudioId.of(player), PLOT1_ORIGIN);

		try (Scene scene = new Scene(level)) {
			scene.install(onePlot(plot));
			scene.publish(StudioId.of(player), layoutWith(PODIUM_ANCHOR));

			// The intent targets agent:shared. The sentinels start ERROR, a state the applier would
			// change to WORKING if it failed to clip to the plot box, so their staying ERROR proves the
			// clip. The inside lamp shares the binding and must change.
			scene.lampWithState(INSIDE_1, "agent:shared", LampStatus.ERROR);
			scene.lampWithState(OUT_WEST, "agent:shared", LampStatus.ERROR);
			scene.lampWithState(OUT_EAST, "agent:shared", LampStatus.ERROR);
			scene.lampWithState(OUT_NORTH, "agent:shared", LampStatus.ERROR);
			scene.lampWithState(OUT_SOUTH, "agent:shared", LampStatus.ERROR);
			scene.lampWithState(OUT_ABOVE, "agent:shared", LampStatus.ERROR);
			scene.lampWithState(OUT_BELOW, "agent:shared", LampStatus.ERROR);
			scene.bulb(BULB_INSIDE);
			scene.bulb(BULB_OUTSIDE);

			WorldIntent intent = new WorldIntent(1,
				Map.of("agent:shared", LampStatusWire.WORKING), true, false, Set.of());
			IntentApplier.apply(level, plot, StudioId.of(player), intent, new HashSet<>());

			helper.assertValueEqual(status(level, INSIDE_1), LampStatus.WORKING, "inside lamp follows the intent");
			helper.assertValueEqual(status(level, OUT_WEST), LampStatus.ERROR, "west-outside sentinel untouched");
			helper.assertValueEqual(status(level, OUT_EAST), LampStatus.ERROR, "east-outside sentinel untouched");
			helper.assertValueEqual(status(level, OUT_NORTH), LampStatus.ERROR, "north-outside sentinel untouched");
			helper.assertValueEqual(status(level, OUT_SOUTH), LampStatus.ERROR, "south-outside sentinel untouched");
			helper.assertValueEqual(status(level, OUT_ABOVE), LampStatus.ERROR, "above-outside sentinel untouched");
			helper.assertValueEqual(status(level, OUT_BELOW), LampStatus.ERROR, "below-outside sentinel untouched");
			helper.assertValueEqual(level.getBlockState(BULB_INSIDE).getValue(net.minecraft.world.level.block.CopperBulbBlock.LIT),
				Boolean.TRUE, "signal bulb inside the box lights");
			helper.assertValueEqual(level.getBlockState(BULB_OUTSIDE).getValue(net.minecraft.world.level.block.CopperBulbBlock.LIT),
				Boolean.FALSE, "signal bulb outside the box must not light");
		}
		helper.succeed();
	}

	// ------------------------------------------------------------------ forged/broken attribution (item 12)

	@GameTest(maxTicks = 20)
	public void intentForAnotherPlotChangesNothing(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		ServerLevel level = server.overworld();
		UUID ownerA = UUID.randomUUID();
		UUID ownerB = UUID.randomUUID();
		Plot plotA = new Plot(1, StudioId.of(ownerA), PLOT1_ORIGIN);
		Plot plotB = new Plot(2, StudioId.of(ownerB), PLOT2_ORIGIN);
		BlockPos aLamp = INSIDE_1;
		BlockPos bLamp = PLOT2_ORIGIN.offset(0, 69, 0);

		try (Scene scene = new Scene(level)) {
			scene.install(twoPlots(plotA, plotB));
			scene.publish(StudioId.of(ownerA), Anchors.Layout.EMPTY);
			scene.publish(StudioId.of(ownerB), Anchors.Layout.EMPTY);
			scene.lamp(aLamp, "agent:shared");
			scene.lamp(bLamp, "agent:shared");

			// A's intent changes A's lamp, and must not reach B's.
			WorldIntent intent = new WorldIntent(1,
				Map.of("agent:shared", LampStatusWire.ERROR), false, false, Set.of());
			WorldIntentFeature.handle(server, ownerA, intent);

			helper.assertValueEqual(status(level, aLamp), LampStatus.ERROR, "A's lamp follows A's intent");
			helper.assertValueEqual(status(level, bLamp), LampStatus.OFF, "B's lamp must not change");
		}
		helper.succeed();
	}

	// ------------------------------------------------------------------ dedicated/enabled gate (item 12)

	@GameTest(maxTicks = 20)
	public void gateRefusesWhenDisabledAndTakesEffectWhenEnabled(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		ServerLevel level = server.overworld();
		UUID player = UUID.randomUUID();
		Plot plot = new Plot(1, StudioId.of(player), PLOT1_ORIGIN);
		MpServerConfig savedCfg = MpServerConfig.current();

		try (Scene scene = new Scene(level)) {
			scene.install(onePlot(plot), disabledConfig());
			scene.publish(StudioId.of(player), Anchors.Layout.EMPTY);
			scene.lamp(INSIDE_1, "agent:gated");
			WorldIntent intent = new WorldIntent(1,
				Map.of("agent:gated", LampStatusWire.WORKING), false, false, Set.of());

			try (MpLog.Capture capture = MpLog.capture()) {
				WorldIntentFeature.handle(server, player, intent);
				helper.assertTrue(capture.lines().isEmpty(), "disabled server logs nothing: " + capture.lines());
			}
			helper.assertValueEqual(status(level, INSIDE_1), LampStatus.OFF, "disabled server changes nothing");

			// Switch multiplayer on between the two calls.
			MpServerConfig.install(enabledConfig(10));
			try (MpLog.Capture capture = MpLog.capture()) {
				WorldIntentFeature.handle(server, player, intent);
				helper.assertTrue(!capture.lines().isEmpty(), "enabled server logs the apply");
			}
			helper.assertValueEqual(status(level, INSIDE_1), LampStatus.WORKING, "enabled server changes the lamp");
		} finally {
			MpServerConfig.install(savedCfg);
		}
		helper.succeed();
	}

	// ------------------------------------------------------------------ stations follow the intent (item 12)

	@GameTest(maxTicks = 20)
	public void podiumMergeMonitorAndSignalsFollowIntent(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		ServerLevel level = server.overworld();
		UUID player = UUID.randomUUID();
		Plot plot = new Plot(1, StudioId.of(player), PLOT1_ORIGIN);
		BlockPos podium = new BlockPos(128, 69, 2);
		BlockPos merge = new BlockPos(128, 69, 4);
		BlockPos monitor = new BlockPos(128, 69, 6);
		BlockPos insideBulb = new BlockPos(173, 70, 0);
		BlockPos outsideBulb = new BlockPos(175, 70, 0);

		try (Scene scene = new Scene(level)) {
			scene.install(onePlot(plot));
			scene.publish(StudioId.of(player), layoutWith(PODIUM_ANCHOR));
			scene.podium(podium);
			scene.merge(merge);
			scene.monitor(monitor, "agent:shown");
			scene.lamp(INSIDE_1, "agent:shown");
			scene.bulb(insideBulb);
			scene.bulb(outsideBulb);

			WorldIntent intent = new WorldIntent(1,
				Map.of("agent:shown", LampStatusWire.WORKING), true, true, Set.of("agent:shown"));
			IntentApplier.apply(level, plot, StudioId.of(player), intent, new HashSet<>());

			helper.assertValueEqual(level.getBlockState(podium).getValue(dev.agentcraft.block.DecisionPodiumBlock.OPEN),
				Boolean.TRUE, "podium follows intent.podiumOpen");
			helper.assertValueEqual(level.getBlockState(merge).getValue(dev.agentcraft.block.MergeStationBlock.ACTIVE),
				Boolean.TRUE, "merge station follows intent.mergeActive");
			helper.assertValueEqual(level.getBlockState(monitor).getValue(MonitorBlock.LIT),
				Boolean.TRUE, "monitor follows intent.litMonitors");
			helper.assertValueEqual(level.getBlockState(insideBulb).getValue(net.minecraft.world.level.block.CopperBulbBlock.LIT),
				Boolean.TRUE, "inside signal bulb lights");
			helper.assertValueEqual(level.getBlockState(outsideBulb).getValue(net.minecraft.world.level.block.CopperBulbBlock.LIT),
				Boolean.FALSE, "outside signal bulb stays dark");
		}
		helper.succeed();
	}

	// ------------------------------------------------------------------ rate limit per player / disconnect (items 7, 12, 14)

	@GameTest(maxTicks = 20)
	public void rateLimitIsPerPlayerAndDisconnectClearsTheBucket(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		ServerLevel level = server.overworld();
		UUID ownerA = UUID.randomUUID();
		UUID ownerB = UUID.randomUUID();
		Plot plotA = new Plot(1, StudioId.of(ownerA), PLOT1_ORIGIN);
		Plot plotB = new Plot(2, StudioId.of(ownerB), PLOT2_ORIGIN);
		long[] now = {1_000_000_000L};

		try (Scene scene = new Scene(level)) {
			scene.install(twoPlots(plotA, plotB), enabledConfig(1));
			scene.publish(StudioId.of(ownerA), Anchors.Layout.EMPTY);
			scene.publish(StudioId.of(ownerB), Anchors.Layout.EMPTY);
			WorldIntentFeature.setClock(() -> now[0]);

			WorldIntent a1 = new WorldIntent(1, Map.of(), false, false, Set.of());
			WorldIntent a2 = new WorldIntent(2, Map.of(), false, false, Set.of());
			WorldIntent a3 = new WorldIntent(3, Map.of(), false, false, Set.of());
			WorldIntent b1 = new WorldIntent(1, Map.of(), false, false, Set.of());

			// Capacity is twice the rate: A's first two pass, the third is refused.
			WorldIntentFeature.handle(server, ownerA, a1);
			WorldIntentFeature.handle(server, ownerA, a2);
			try (MpLog.Capture capture = MpLog.capture()) {
				WorldIntentFeature.handle(server, ownerA, a3);
				String expected = "event=world_intent_rejected player=" + ownerA + " studio=" + ownerA
					+ " plot=1 rev=3 reason=rate_limited";
				helper.assertTrue(capture.lines().contains(expected),
					"exact rate_limited line, got: " + capture.lines());
			}

			// B is unaffected by A's exhausted bucket.
			try (MpLog.Capture capture = MpLog.capture()) {
				WorldIntentFeature.handle(server, ownerB, b1);
				helper.assertTrue(capture.lines().stream().anyMatch(l -> l.contains("event=world_intent_applied")),
					"B can still send while A is limited: " + capture.lines());
			}

			// A disconnect forgets A's bucket; the next intent gets fresh tokens.
			WorldIntentFeature.onPlayerDisconnect(ownerA);
			try (MpLog.Capture capture = MpLog.capture()) {
				WorldIntentFeature.handle(server, ownerA, a3);
				helper.assertTrue(capture.lines().stream().anyMatch(l -> l.contains("event=world_intent_applied")),
					"after disconnect A's bucket is fresh: " + capture.lines());
			}
		} finally {
			WorldIntentFeature.setClock(System::nanoTime);
		}
		helper.succeed();
	}

	// ------------------------------------------------------------------ unknown binding (items 4, 11, 14)

	@GameTest(maxTicks = 20)
	public void unknownBindingIsIgnoredAndLoggedOncePerPlot(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		ServerLevel level = server.overworld();
		UUID player = UUID.randomUUID();
		Plot plot = new Plot(1, StudioId.of(player), PLOT1_ORIGIN);
		BlockPos bad1 = new BlockPos(128, 71, 0);
		BlockPos bad2 = new BlockPos(128, 71, 2);
		BlockPos included = new BlockPos(128, 73, 0);
		BlockPos fallback = new BlockPos(128, 73, 2);

		try (Scene scene = new Scene(level)) {
			scene.install(onePlot(plot));
			scene.publish(StudioId.of(player), Anchors.Layout.EMPTY);
			scene.lamp(bad1, "ci:repo-one");
			scene.lamp(bad2, "ci:repo-two");
			scene.lamp(included, "agent:included");
			scene.lampWithState(fallback, "ci:#1", LampStatus.WORKING);

			WorldIntent intent = new WorldIntent(1,
				Map.of("agent:included", LampStatusWire.WORKING), false, false, Set.of());
			try (MpLog.Capture capture = MpLog.capture()) {
				// Apply twice: the first sees the unknown bindings, the second must not log again.
				WorldIntentFeature.handle(server, player, intent);
				WorldIntentFeature.handle(server, player, intent);

				String expected = "event=world_intent_rejected player=" + player + " studio=" + player
					+ " plot=1 rev=1 reason=unknown_binding";
				long matches = capture.lines().stream().filter(expected::equals).count();
				helper.assertValueEqual(matches, 1L,
					"exactly one whole unknown_binding line, got: " + capture.lines());

				// A disconnect must not re-log the plot on reconnect.
				WorldIntentFeature.onPlayerDisconnect(player);
				WorldIntentFeature.handle(server, player, intent);
				long again = capture.lines().stream().filter(expected::equals).count();
				helper.assertValueEqual(again, 1L, "a reconnect does not log the same plot again");
			}

			helper.assertValueEqual(status(level, included), LampStatus.WORKING, "a legal key in the intent changes");
			helper.assertValueEqual(status(level, fallback), LampStatus.IDLE,
				"a legal key the intent leaves out takes its ci: fallback from WORKING");
			helper.assertValueEqual(status(level, bad1), LampStatus.OFF, "an illegal binding is skipped");
			helper.assertValueEqual(status(level, bad2), LampStatus.OFF, "the second illegal binding is skipped");
		}
		helper.succeed();
	}

	// ------------------------------------------------------------------ no plot (items 12, 14)

	@GameTest(maxTicks = 20)
	public void noPlotIsRefusedWithAnExactLine(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		UUID noPlot = UUID.randomUUID();
		MpServerConfig savedCfg = MpServerConfig.current();

		try {
			MpServerConfig.install(enabledConfig(10));
			WorldIntentFeature.resetState();
			WorldIntent intent = new WorldIntent(7, Map.of(), false, false, Set.of());

			try (MpLog.Capture capture = MpLog.capture()) {
				WorldIntentFeature.handle(server, noPlot, intent);
				String expected = "event=world_intent_rejected player=" + noPlot + " studio=" + noPlot
					+ " rev=7 reason=no_plot";
				helper.assertTrue(capture.lines().contains(expected),
					"exact no_plot line, got: " + capture.lines());
			}
		} finally {
			MpServerConfig.install(savedCfg);
			WorldIntentFeature.resetState();
		}
		helper.succeed();
	}

	// ------------------------------------------------------------------ applied capture (item 14)

	@GameTest(maxTicks = 20)
	public void worldIntentAppliedIsCapturedWithCorrelators(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		ServerLevel level = server.overworld();
		UUID player = UUID.randomUUID();
		Plot plot = new Plot(1, StudioId.of(player), PLOT1_ORIGIN);

		try (Scene scene = new Scene(level)) {
			scene.install(onePlot(plot));
			scene.publish(StudioId.of(player), Anchors.Layout.EMPTY);
			scene.lamp(INSIDE_1, "agent:capture");

			WorldIntent intent = new WorldIntent(1,
				Map.of("agent:capture", LampStatusWire.WAITING), false, false, Set.of());
			try (MpLog.Capture capture = MpLog.capture()) {
				WorldIntentFeature.handle(server, player, intent);
				String expected = "event=world_intent_applied player=" + player + " studio=" + player
					+ " plot=1 rev=1 changed=1 lamps=1";
				helper.assertTrue(capture.lines().contains(expected),
					"exact applied line, got: " + capture.lines());
			}
			helper.assertValueEqual(status(level, INSIDE_1), LampStatus.WAITING, "the lamp changed");
		}
		helper.succeed();
	}

	// ------------------------------------------------------------------ helpers

	private static LampStatus status(ServerLevel level, BlockPos pos) {
		return level.getBlockState(pos).getValue(StatusLampBlock.STATUS);
	}

	private static MpServerConfig enabledConfig(int intentsPerSecond) {
		return new MpServerConfig(true, 128, true, true, true, true, true, 12, 4, intentsPerSecond);
	}

	private static MpServerConfig disabledConfig() {
		return new MpServerConfig(false, 128, true, true, true, true, true, 12, 4, 10);
	}

	private static Anchors.Layout layoutWith(BlockPos podium) {
		return new Anchors.Layout("test", 1,
			new Anchors.Bounds(podium.getX() - 20, podium.getY() - 10, podium.getZ() - 20,
				podium.getX() + 20, podium.getY() + 10, podium.getZ() + 20),
			Map.of(AnchorNames.DECISION_PODIUM,
				new Anchor(AnchorNames.DECISION_PODIUM, podium.getX() + 0.5, podium.getY(), podium.getZ() + 0.5, 0f, 0f)));
	}

	private static PlotDirectory onePlot(Plot plot) {
		MapPlotDirectory dir = new MapPlotDirectory();
		dir.add(plot);
		return dir;
	}

	private static PlotDirectory twoPlots(Plot a, Plot b) {
		MapPlotDirectory dir = new MapPlotDirectory();
		dir.add(a);
		dir.add(b);
		return dir;
	}

	/** A directory keyed by owner UUID with containment lookup. */
	private static final class MapPlotDirectory implements PlotDirectory {
		private final Map<UUID, Plot> byOwner = new LinkedHashMap<>();

		void add(Plot plot) {
			byOwner.put(plot.owner().owner(), plot);
		}

		public Optional<Plot> plotOf(StudioId id) {
			return Optional.ofNullable(byOwner.get(id.owner()));
		}

		public Optional<Plot> plotAt(BlockPos pos) {
			return byOwner.values().stream().filter(p -> p.contains(pos)).findFirst();
		}

		public Collection<Plot> all() {
			return List.copyOf(byOwner.values());
		}
	}

	/**
	 * Records every touched block (state plus full block-entity data) and restores it in
	 * {@code close()}; also restores the installed directory/config/published anchors and clears the
	 * handler's server-wide rate and logged-plot state. Use with try-with-resources.
	 */
	private static final class Scene implements AutoCloseable {
		private final ServerLevel level;
		private final Map<BlockPos, BlockState> savedStates = new LinkedHashMap<>();
		private final Map<BlockPos, CompoundTag> savedBeData = new LinkedHashMap<>();
		private final List<StudioId> published = new ArrayList<>();
		private @Nullable PlotDirectory savedDirectory;
		private @Nullable MpServerConfig savedConfig;

		Scene(ServerLevel level) {
			this.level = level;
		}

		void install(PlotDirectory directory) {
			install(directory, enabledConfig(10));
		}

		void install(PlotDirectory directory, MpServerConfig config) {
			savedDirectory = Plots.directory();
			savedConfig = MpServerConfig.current();
			Plots.install(directory);
			MpServerConfig.install(config);
			WorldIntentFeature.resetState();
		}

		void publish(StudioId studio, Anchors.Layout layout) {
			Anchors.publish(studio, layout);
			published.add(studio);
		}

		void lamp(BlockPos pos, String binding) {
			place(pos, ModBlocks.STATUS_LAMP, binding);
		}

		void lampWithState(BlockPos pos, String binding, LampStatus state) {
			save(pos);
			level.getChunkAt(pos);
			level.setBlock(pos, ModBlocks.STATUS_LAMP.defaultBlockState().setValue(StatusLampBlock.STATUS, state), 3);
			if (level.getBlockEntity(pos) instanceof StationBlockEntity sbe) {
				sbe.setBinding(binding);
			}
		}

		void podium(BlockPos pos) {
			place(pos, ModBlocks.DECISION_PODIUM, null);
		}

		void merge(BlockPos pos) {
			place(pos, ModBlocks.MERGE_STATION, null);
		}

		void monitor(BlockPos pos, String binding) {
			place(pos, ModBlocks.MONITOR, binding);
		}

		void bulb(BlockPos pos) {
			save(pos);
			level.getChunkAt(pos);
			level.setBlock(pos, Blocks.COPPER_BULB.waxed().unaffected().defaultBlockState(), 3);
		}

		private void place(BlockPos pos, Block block, @Nullable String binding) {
			save(pos);
			level.getChunkAt(pos);
			level.setBlock(pos, block.defaultBlockState(), 3);
			if (binding != null && level.getBlockEntity(pos) instanceof StationBlockEntity sbe) {
				sbe.setBinding(binding);
			}
		}

		private void save(BlockPos pos) {
			BlockPos key = pos.immutable();
			if (savedStates.containsKey(key)) {
				return;
			}
			savedStates.put(key, level.getBlockState(key));
			BlockEntity be = level.getBlockEntity(key);
			if (be != null) {
				savedBeData.put(key, be.saveWithFullMetadata(level.registryAccess()));
			}
		}

		public void close() {
			for (Map.Entry<BlockPos, BlockState> e : savedStates.entrySet()) {
				BlockPos pos = e.getKey();
				BlockState state = e.getValue();
				level.setBlock(pos, state, 3);
				CompoundTag data = savedBeData.get(pos);
				if (data != null) {
					BlockEntity restored = BlockEntity.loadStatic(pos, state, data, level.registryAccess());
					if (restored != null) {
						level.setBlockEntity(restored);
					}
				}
			}
			for (StudioId studio : published) {
				Anchors.remove(studio);
			}
			if (savedDirectory != null) {
				Plots.install(savedDirectory);
			}
			if (savedConfig != null) {
				MpServerConfig.install(savedConfig);
			}
			WorldIntentFeature.resetState();
		}
	}
}
