package dev.agentcraft.gametest.mp.offset;

import dev.agentcraft.block.ModBlocks;
import dev.agentcraft.block.entity.StationBlockEntity;
import dev.agentcraft.hq.HqBuilder;
import dev.agentcraft.hq.HqFeature;
import dev.agentcraft.hq.StudioHqBuilder;
import dev.agentcraft.layout.Anchor;
import dev.agentcraft.layout.Anchors;
import dev.agentcraft.mp.MpEvents;
import dev.agentcraft.mp.MpLog;
import dev.agentcraft.mp.MpReasons;
import dev.agentcraft.mp.Plot;
import dev.agentcraft.mp.PlotDirectory;
import dev.agentcraft.mp.PlotGrid;
import dev.agentcraft.mp.Plots;
import dev.agentcraft.mp.StudioId;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;

public final class BuildAtOffsetTest {
	private static final BlockPos TOVE_LAMP_PLOT0 = new BlockPos(-15, 69, -10);
	private static final BlockPos MARKER_PLOT0 = new BlockPos(0, 66, 0);
	private static final int SITE_MIN_X = -46;
	private static final int SITE_MIN_Y = 60;
	private static final int SITE_MIN_Z = -36;
	private static final int SITE_MAX_X = 46;
	private static final int SITE_MAX_Y = 100;
	private static final int SITE_MAX_Z = 54;
	private static final int FEET = 66;
	/** The last cell inside the site box along x, and the first one outside it (plot coordinates). */
	private static final BlockPos DROP_INSIDE = new BlockPos(SITE_MAX_X, FEET, 0);
	private static final BlockPos DROP_OUTSIDE = new BlockPos(SITE_MAX_X + 1, FEET, 0);
	private static final String LOCAL_PLAN_FILE = "agentcraft-hq-plan.dat";
	private static final StudioId STUDIO_A = StudioId.of(UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"));
	private static final StudioId STUDIO_B = StudioId.of(UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"));
	private static final StudioId STUDIO_C = StudioId.of(UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc"));
	private static final StudioId STUDIO_SHELL = StudioId.of(UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"));
	private static final StudioId STUDIO_UNKNOWN = StudioId.of(UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd"));
	private static final BlockPos ORIGIN_PLOT1 = PlotGrid.originOf(1, PlotGrid.DEFAULT_STRIDE);
	private static final BlockPos ORIGIN_PLOT2 = PlotGrid.originOf(2, PlotGrid.DEFAULT_STRIDE);
	private static final BlockPos ORIGIN_PLOT3 = PlotGrid.originOf(3, PlotGrid.DEFAULT_STRIDE);
	private static final BlockPos ORIGIN_PLOT4 = PlotGrid.originOf(4, PlotGrid.DEFAULT_STRIDE);
	/** Block updates that touch nothing else: no neighbour updates, no shape updates. */
	private static final int QUIET = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

	@GameTest(maxTicks = 120)
	public void offsetBuildLeavesTwoBlockShellUntouched(GameTestHelper helper) {
		runWithFakePlots(helper, () -> {
			ServerLevel level = helper.getLevel();
			List<BlockSnapshot> shell = shellAroundSite(ORIGIN_PLOT4);
			snapshotStates(level, shell);
			// Plot 4 is this test's own, so this is its first build in any test order: the shell is
			// compared across a build that really wrote the whole studio.
			try (MpLog.Capture capture = MpLog.capture()) {
				buildAtPlot(level, STUDIO_SHELL, ORIGIN_PLOT4, false);
				assertPlotBuiltLine(helper, capture, STUDIO_SHELL, 4);
				helper.assertTrue(capture.lines().stream().noneMatch(l -> l.contains(" changed=0 ")), "the build wrote blocks: " + capture.lines());
			}
			assertSnapshotsUnchanged(helper, level, shell, "shell");
		});
	}

	@GameTest(maxTicks = 120)
	public void forceBuildAtPlotOneDoesNotWriteUntranslatedPlotZero(GameTestHelper helper) {
		runWithFakePlots(helper, () -> {
			ServerLevel level = helper.getLevel();
			ensurePlotZeroBuilt(level);
			BlockState before = level.getBlockState(MARKER_PLOT0);
			try {
				level.setBlock(MARKER_PLOT0, Blocks.GOLD_BLOCK.defaultBlockState(), QUIET);
				buildAtPlot(level, STUDIO_A, ORIGIN_PLOT1, true);
				helper.assertTrue(level.getBlockState(MARKER_PLOT0).is(Blocks.GOLD_BLOCK), "plot 0 marker must survive a force build at plot 1");
			} finally {
				level.setBlock(MARKER_PLOT0, before, QUIET);
			}
		});
	}

	@GameTest(maxTicks = 120)
	public void plotOneMatchesPlotZeroTranslated(GameTestHelper helper) {
		runWithFakePlots(helper, () -> {
			ServerLevel level = helper.getLevel();
			ensurePlotZeroBuilt(level);
			buildAtPlot(level, STUDIO_A, ORIGIN_PLOT1, false);
			int ox = ORIGIN_PLOT1.getX();
			int oy = ORIGIN_PLOT1.getY();
			int oz = ORIGIN_PLOT1.getZ();
			for (int y = SITE_MIN_Y; y <= SITE_MAX_Y; y++) {
				for (int z = SITE_MIN_Z; z <= SITE_MAX_Z; z++) {
					for (int x = SITE_MIN_X; x <= SITE_MAX_X; x++) {
						BlockPos pos0 = new BlockPos(x, y, z);
						BlockPos pos1 = pos0.offset(ox, oy, oz);
						BlockState at0 = level.getBlockState(pos0);
						BlockState at1 = level.getBlockState(pos1);
						if (!at0.equals(at1)) {
							helper.fail("plot 1 cell (" + x + "," + y + "," + z + ") differs from plot 0 at offset: " + at0 + " vs " + at1);
						}
						if (level.getBlockEntity(pos0) instanceof StationBlockEntity be0) {
							if (!(level.getBlockEntity(pos1) instanceof StationBlockEntity be1)) {
								helper.fail("station missing at translated " + pos1.toShortString());
							} else if (!be0.binding().equals(be1.binding())) {
								helper.fail("binding mismatch " + be0.binding() + " vs " + be1.binding() + " at " + pos0.toShortString());
							}
						}
					}
				}
			}
		});
	}

	@GameTest(maxTicks = 120)
	public void plotOnePublishesSixtyNineOffsetAnchors(GameTestHelper helper) {
		runWithFakePlots(helper, () -> {
			ServerLevel level = helper.getLevel();
			ensurePlotZeroBuilt(level);
			try (MpLog.Capture capture = MpLog.capture()) {
				buildAtPlot(level, STUDIO_A, ORIGIN_PLOT1, false);
				assertPlotBuiltLine(helper, capture, STUDIO_A, 1);
			}
			Anchors.Layout base = Anchors.forStudio(StudioId.LOCAL);
			Anchors.Layout remote = Anchors.forStudio(STUDIO_A);
			helper.assertValueEqual(remote.anchors().size(), 69, "remote anchor count");
			int ox = ORIGIN_PLOT1.getX();
			int oy = ORIGIN_PLOT1.getY();
			int oz = ORIGIN_PLOT1.getZ();
			Anchors.Bounds b0 = base.bounds();
			Anchors.Bounds b1 = remote.bounds();
			if (b0 == null || b1 == null) {
				helper.fail("layout bounds missing");
			}
			helper.assertValueEqual(b1.minX(), b0.minX() + ox, "bounds minX");
			helper.assertValueEqual(b1.minY(), b0.minY() + oy, "bounds minY");
			helper.assertValueEqual(b1.minZ(), b0.minZ() + oz, "bounds minZ");
			helper.assertValueEqual(b1.maxX(), b0.maxX() + ox, "bounds maxX");
			helper.assertValueEqual(b1.maxY(), b0.maxY() + oy, "bounds maxY");
			helper.assertValueEqual(b1.maxZ(), b0.maxZ() + oz, "bounds maxZ");
			for (String name : base.anchors().keySet()) {
				Anchor a0 = base.get(name);
				Anchor a1 = remote.get(name);
				if (a0 == null || a1 == null) {
					helper.fail("missing anchor " + name);
				}
				helper.assertValueEqual(a1.x(), a0.x() + ox, name + " x");
				helper.assertValueEqual(a1.y(), a0.y() + oy, name + " y");
				helper.assertValueEqual(a1.z(), a0.z() + oz, name + " z");
				helper.assertValueEqual(a1.yaw(), a0.yaw(), name + " yaw");
				helper.assertValueEqual(a1.pitch(), a0.pitch(), name + " pitch");
			}
		});
	}

	@GameTest(maxTicks = 120)
	public void rebuildKeepsPlotTwoPlayerEditsAndLocalPlanFile(GameTestHelper helper) {
		runWithFakePlots(helper, () -> {
			ServerLevel level = helper.getLevel();
			MinecraftServer server = level.getServer();
			ensurePlotZeroBuilt(level);
			byte[] localPlanBefore = readLocalPlan(server);
			// without a plan file to compare, the equality below would hold between two missing files
			helper.assertTrue(localPlanBefore != null && localPlanBefore.length > 0, "plot 0 has a plan file before the build at plot 2");
			buildAtPlot(level, STUDIO_B, ORIGIN_PLOT2, false);
			BlockPos edit = new BlockPos(ORIGIN_PLOT2.getX(), FEET, ORIGIN_PLOT2.getZ());
			BlockState beforeEdit = level.getBlockState(edit);
			try {
				level.setBlock(edit, Blocks.DIAMOND_BLOCK.defaultBlockState(), QUIET);
				buildAtPlot(level, STUDIO_B, ORIGIN_PLOT2, false);
				helper.assertTrue(level.getBlockState(edit).is(Blocks.DIAMOND_BLOCK), "player edit kept on rebuild");
				helper.assertTrue(Files.exists(planPath(server, 2)), "plot 2 plan file exists");
				byte[] localPlanAfter = readLocalPlan(server);
				helper.assertTrue(Arrays.equals(localPlanBefore, localPlanAfter), "plot 0 plan file unchanged");
			} finally {
				level.setBlock(edit, beforeEdit, QUIET);
			}
		});
	}

	@GameTest(maxTicks = 120)
	public void buildAtPlotOneSweepsDropsOnlyInsideItsOwnBox(GameTestHelper helper) {
		runWithFakePlots(helper, () -> {
			ServerLevel level = helper.getLevel();
			List<ItemEntity> drops = new ArrayList<>();
			try {
				ItemEntity inside = drop(level, DROP_INSIDE.offset(ORIGIN_PLOT1), drops);
				ItemEntity atPlotZero = drop(level, DROP_INSIDE, drops);
				ItemEntity outside = drop(level, DROP_OUTSIDE.offset(ORIGIN_PLOT1), drops);
				for (ItemEntity drop : drops) {
					// a drop the level does not return could not be swept by any build
					helper.assertTrue(level.getEntitiesOfClass(ItemEntity.class, drop.getBoundingBox()).contains(drop),
						"the level returns the drop at " + drop.blockPosition().toShortString());
				}
				buildAtPlot(level, STUDIO_A, ORIGIN_PLOT1, false);
				helper.assertTrue(inside.isRemoved(), "a build at plot 1 sweeps the drop inside its box");
				helper.assertFalse(atPlotZero.isRemoved(), "a build at plot 1 leaves the drop at the same place in plot 0");
				helper.assertFalse(outside.isRemoved(), "a build at plot 1 leaves the drop one block outside its box");
			} finally {
				drops.forEach(ItemEntity::discard);
			}
		});
	}

	@GameTest(maxTicks = 120)
	public void unknownStudioPlotBuildFailedNoPlot(GameTestHelper helper) {
		runWithFakePlots(helper, () -> {
			ServerLevel level = helper.getLevel();
			assertBuildRefused(helper, () -> buildAtPlot(level, STUDIO_UNKNOWN, ORIGIN_PLOT1, false), STUDIO_UNKNOWN, Optional.empty(),
				MpReasons.NO_PLOT, IllegalArgumentException.class);
		});
	}

	@GameTest(maxTicks = 120)
	public void wrongOriginPlotBuildFailedBadOrigin(GameTestHelper helper) {
		runWithFakePlots(helper, () -> {
			ServerLevel level = helper.getLevel();
			assertBuildRefused(helper, () -> buildAtPlot(level, STUDIO_A, ORIGIN_PLOT2, false), STUDIO_A, Optional.of(1), MpReasons.BAD_ORIGIN,
				IllegalArgumentException.class);
		});
	}

	@GameTest(maxTicks = 120)
	public void misalignedOriginPlotBuildFailed(GameTestHelper helper) {
		runWithFakePlots(helper, () -> {
			ServerLevel level = helper.getLevel();
			BlockPos bad = ORIGIN_PLOT1.offset(1, 0, 0);
			assertBuildRefused(helper, () -> buildAtPlot(level, STUDIO_A, bad, false), STUDIO_A, Optional.of(1), MpReasons.BAD_ORIGIN,
				IllegalArgumentException.class);
		});
	}

	@GameTest(maxTicks = 120)
	public void unreadablePlanPathPlotBuildFailedIoError(GameTestHelper helper) {
		PlotDirectory previous = Plots.directory();
		Plot plot1 = new Plot(1, STUDIO_A, ORIGIN_PLOT1);
		Plot plot2 = new Plot(2, STUDIO_B, ORIGIN_PLOT2);
		Plot plot3 = new Plot(3, STUDIO_C, ORIGIN_PLOT3);
		Plot local = new Plot(0, StudioId.LOCAL, BlockPos.ZERO);
		Plots.install(directoryOf(local, plot1, plot2, plot3));
		try {
			ServerLevel level = helper.getLevel();
			MinecraftServer server = level.getServer();
			java.nio.file.Path blocker = planPath(server, 3);
			try {
				Files.createDirectories(blocker);
				try (MpLog.Capture capture = MpLog.capture()) {
					try {
						buildAtPlot(level, STUDIO_C, ORIGIN_PLOT3, false);
						helper.fail("expected UncheckedIOException");
					} catch (UncheckedIOException expected) {
						// the blocks of plot 3 are written; only the plan file could not be saved
					}
					String line = plotFailedLine(STUDIO_C, 3, MpReasons.IO_ERROR);
					helper.assertTrue(capture.lines().contains(line), "telemetry: " + capture.lines());
					helper.assertTrue(capture.lines().stream().noneMatch(l -> l.startsWith("event=" + MpEvents.PLOT_BUILT + " ")), "no plot_built");
				}
			} catch (IOException e) {
				helper.fail("could not set up blocked plan path: " + e);
			} finally {
				try {
					Files.deleteIfExists(blocker.resolveSibling("hq-plan.dat.tmp"));
					Files.deleteIfExists(blocker);
				} catch (IOException ignored) {
					// the run directory is deleted before the next run anyway
				}
			}
			helper.succeed();
		} finally {
			Plots.install(previous);
			Anchors.remove(STUDIO_C);
		}
	}

	private static void runWithFakePlots(GameTestHelper helper, Runnable body) {
		PlotDirectory previous = Plots.directory();
		Plot plot1 = new Plot(1, STUDIO_A, ORIGIN_PLOT1);
		Plot plot2 = new Plot(2, STUDIO_B, ORIGIN_PLOT2);
		Plot plot4 = new Plot(4, STUDIO_SHELL, ORIGIN_PLOT4);
		Plot local = new Plot(0, StudioId.LOCAL, BlockPos.ZERO);
		Plots.install(directoryOf(local, plot1, plot2, plot4));
		try {
			body.run();
			helper.succeed();
		} finally {
			Plots.install(previous);
			Anchors.remove(STUDIO_A);
			Anchors.remove(STUDIO_B);
			Anchors.remove(STUDIO_SHELL);
		}
	}

	private static PlotDirectory directoryOf(Plot local, Plot... plots) {
		return new PlotDirectory() {
			@Override
			public Optional<Plot> plotOf(StudioId id) {
				if (id.equals(StudioId.LOCAL)) {
					return Optional.of(local);
				}
				for (Plot p : plots) {
					if (p.owner().equals(id)) {
						return Optional.of(p);
					}
				}
				return Optional.empty();
			}

			@Override
			public Optional<Plot> plotAt(BlockPos pos) {
				if (local.contains(pos)) {
					return Optional.of(local);
				}
				for (Plot p : plots) {
					if (p.contains(pos)) {
						return Optional.of(p);
					}
				}
				return Optional.empty();
			}

			@Override
			public Collection<Plot> all() {
				List<Plot> out = new ArrayList<>();
				out.add(local);
				out.addAll(Arrays.asList(plots));
				return out;
			}
		};
	}

	private static void ensurePlotZeroBuilt(ServerLevel level) {
		if (level.getBlockState(TOVE_LAMP_PLOT0).is(ModBlocks.STATUS_LAMP)) {
			return;
		}
		HqFeature.buildAndPublish(level, new StudioHqBuilder(), HqBuilder.Options.DEFAULT);
	}

	private static void buildAtPlot(ServerLevel level, StudioId studio, BlockPos origin, boolean force) {
		StudioHqBuilder builder = new StudioHqBuilder();
		Anchors.Builder anchors = Anchors.builder(builder.id());
		builder.build(level, anchors, new HqBuilder.Options(force, origin, studio));
		Anchors.publish(studio, anchors.build());
	}

	/** A resting dropped item in the middle of the cell {@code pos}, added to {@code drops} for the caller's clean-up. */
	private static ItemEntity drop(ServerLevel level, BlockPos pos, List<ItemEntity> drops) {
		level.getChunkAt(pos);
		// A chunk loaded in this tick shows its entities only after its promotion ran, and that is a
		// queued server-thread task (ChunkHolder.scheduleFullChunkPromotion).
		while (level.getChunkSource().pollTask()) {
			// run what is queued
		}
		ItemEntity item = new ItemEntity(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, new ItemStack(Items.STICK), 0, 0, 0);
		level.addFreshEntity(item);
		drops.add(item);
		return item;
	}

	private static byte[] readLocalPlan(MinecraftServer server) {
		try {
			var path = server.getWorldPath(LevelResource.ROOT).resolve(LOCAL_PLAN_FILE);
			return Files.exists(path) ? Files.readAllBytes(path) : null;
		} catch (Exception e) {
			return null;
		}
	}

	private static java.nio.file.Path planPath(MinecraftServer server, int index) {
		return server.getWorldPath(LevelResource.ROOT).resolve("agentcraft/plots/" + index + "/hq-plan.dat");
	}

	private static void assertBuildRefused(GameTestHelper helper, Runnable attempt, StudioId studio, Optional<Integer> plotIndex, String reason,
		Class<? extends Throwable> type) {
		try (MpLog.Capture capture = MpLog.capture()) {
			try {
				attempt.run();
				helper.fail("expected " + type.getSimpleName());
			} catch (Throwable e) {
				if (!type.isInstance(e)) {
					helper.fail("expected " + type.getSimpleName() + " but got " + e.getClass().getName());
				}
			}
			String expected = plotIndex.isPresent() ? plotFailedLine(studio, plotIndex.get(), reason) : plotFailedLineNoPlot(studio, reason);
			helper.assertTrue(capture.lines().contains(expected), "telemetry: " + capture.lines());
			helper.assertTrue(capture.lines().stream().noneMatch(l -> l.startsWith("event=" + MpEvents.PLOT_BUILT + " ")), "no plot_built");
		}
	}

	private static void assertPlotBuiltLine(GameTestHelper helper, MpLog.Capture capture, StudioId studio, int plotIndex) {
		boolean found = capture.lines().stream().anyMatch(line -> line.startsWith(plotBuiltPrefix(studio, plotIndex)) && line.contains(" ms=")
			&& line.contains(" changed=") && line.contains(" kept="));
		helper.assertTrue(found, "plot_built telemetry: " + capture.lines());
	}

	private static String plotBuiltPrefix(StudioId studio, int plotIndex) {
		return "event=" + MpEvents.PLOT_BUILT + " studio=" + studio.owner() + " plot=" + plotIndex;
	}

	private static String plotFailedLine(StudioId studio, int plotIndex, String reason) {
		return "event=" + MpEvents.PLOT_BUILD_FAILED + " studio=" + studio.owner() + " plot=" + plotIndex + " reason=" + reason;
	}

	private static String plotFailedLineNoPlot(StudioId studio, String reason) {
		return "event=" + MpEvents.PLOT_BUILD_FAILED + " studio=" + studio.owner() + " reason=" + reason;
	}

	private record BlockSnapshot(BlockPos pos, BlockState state) {
	}

	private static List<BlockSnapshot> shellAroundSite(BlockPos origin) {
		int ox = origin.getX();
		int oy = origin.getY();
		int oz = origin.getZ();
		int ix0 = SITE_MIN_X + ox;
		int ix1 = SITE_MAX_X + ox;
		int iy0 = SITE_MIN_Y + oy;
		int iy1 = SITE_MAX_Y + oy;
		int iz0 = SITE_MIN_Z + oz;
		int iz1 = SITE_MAX_Z + oz;
		List<BlockSnapshot> out = new ArrayList<>();
		for (int x = ix0 - 2; x <= ix1 + 2; x++) {
			for (int y = iy0 - 2; y <= iy1 + 2; y++) {
				for (int z = iz0 - 2; z <= iz1 + 2; z++) {
					int dx = x < ix0 ? ix0 - x : (x > ix1 ? x - ix1 : 0);
					int dy = y < iy0 ? iy0 - y : (y > iy1 ? y - iy1 : 0);
					int dz = z < iz0 ? iz0 - z : (z > iz1 ? z - iz1 : 0);
					int dist = Math.max(dx, Math.max(dy, dz));
					if (dist >= 1 && dist <= 2) {
						out.add(new BlockSnapshot(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState()));
					}
				}
			}
		}
		return out;
	}

	private static void snapshotStates(ServerLevel level, List<BlockSnapshot> cells) {
		for (int i = 0; i < cells.size(); i++) {
			BlockSnapshot s = cells.get(i);
			cells.set(i, new BlockSnapshot(s.pos, level.getBlockState(s.pos)));
		}
	}

	private static void assertSnapshotsUnchanged(GameTestHelper helper, ServerLevel level, List<BlockSnapshot> cells, String label) {
		for (BlockSnapshot s : cells) {
			BlockState now = level.getBlockState(s.pos);
			if (!now.equals(s.state)) {
				helper.fail(label + " changed at " + s.pos.toShortString() + ": was " + s.state + " now " + now);
			}
		}
	}
}
