package dev.agentcraft.gametest.mp.plot;

import dev.agentcraft.layout.Anchor;
import dev.agentcraft.layout.AnchorNames;
import dev.agentcraft.layout.Anchors;
import dev.agentcraft.mp.MpLog;
import dev.agentcraft.mp.MpServerConfig;
import dev.agentcraft.mp.Plot;
import dev.agentcraft.mp.PlotGrid;
import dev.agentcraft.mp.Plots;
import dev.agentcraft.mp.StudioId;
import dev.agentcraft.mp.net.HelloS2C;
import dev.agentcraft.mp.net.MpPayloads;
import dev.agentcraft.mp.server.plot.PlotCommands;
import dev.agentcraft.mp.server.plot.PlotFeature;
import dev.agentcraft.mp.server.plot.PlotRebuilds;
import dev.agentcraft.mp.server.plot.PlotRegistry;
import dev.agentcraft.mp.server.plot.PlotSpawn;
import dev.agentcraft.mp.server.plot.PlotStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelData;

public final class PlotLifecycleGameTest {
    @GameTest
    public void allocatesLowestFreeIndexAndSkipsASecondJoin(GameTestHelper helper) {
        var session = PlotGameSupport.open(helper, PlotGameSupport.ENABLED);
        try {
            MinecraftServer server = helper.getLevel().getServer();
            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();
            try (var capture = MpLog.capture()) {
                helper.assertTrue(PlotFeature.allocate(server, first), "first player is allocated");
                helper.assertTrue(PlotFeature.allocate(server, second), "second player is allocated");
                helper.assertTrue(!PlotFeature.allocate(server, first), "the same player is not allocated again");
                long allocated = capture.lines().stream().filter(line -> line.startsWith("event=plot_allocated ")).count();
                helper.assertValueEqual(allocated, 2L, "plot_allocated lines");
            }
            PlotRegistry registry = (PlotRegistry) Plots.directory();
            var plot0 = registry.plotOf(StudioId.of(first)).orElseThrow();
            var plot1 = registry.plotOf(StudioId.of(second)).orElseThrow();
            helper.assertValueEqual(plot0.index(), 0, "first plot index");
            helper.assertValueEqual(plot0.origin(), BlockPos.ZERO, "first plot origin");
            helper.assertValueEqual(plot1.index(), 1, "second plot index");
            helper.assertValueEqual(plot1.origin(), PlotGrid.originOf(1, 128), "second plot origin");
            helper.assertTrue(registry.plotAt(new BlockPos(0, 64, 0)).isPresent(), "the site resolves to a plot");
            helper.assertTrue(registry.plotAt(new BlockPos(64, 64, 0)).isEmpty(), "the road between sites is not a plot");
            HelloS2C hello = MpPayloads.helloFor(first, MpServerConfig.current(), true, true).orElseThrow();
            helper.assertValueEqual(hello.plotIndex(), 0, "hello plot index");
            helper.succeed();
        } finally {
            PlotGameSupport.close(helper, session);
        }
    }

    @GameTest
    public void autoAllocateOffDoesNotCreateAPlot(GameTestHelper helper) {
        var config = new MpServerConfig(true, 128, false, false, true, true, true, 12, 4, 10);
        var session = PlotGameSupport.open(helper, config);
        try {
            UUID player = UUID.randomUUID();
            try (var capture = MpLog.capture()) {
                helper.assertTrue(!PlotFeature.allocate(helper.getLevel().getServer(), player), "autoAllocate is off");
                helper.assertTrue(capture.lines().stream().noneMatch(line -> line.startsWith("event=plot_")), "no plot telemetry");
            }
            helper.assertTrue(Plots.directory().plotOf(StudioId.of(player)).isEmpty(), "no plot was stored");
            helper.succeed();
        } finally {
            PlotGameSupport.close(helper, session);
        }
    }

    @GameTest
    public void disabledGateLogsNothing(GameTestHelper helper) {
        var session = PlotGameSupport.open(helper, PlotGameSupport.ENABLED);
        try {
            MinecraftServer server = helper.getLevel().getServer();
            ServerPlayer player = PlotGameSupport.mock(helper);
            helper.assertTrue(PlotFeature.allocate(server, player.getUUID()), "the player has a plot while enabled");
            MpServerConfig.install(MpServerConfig.DEFAULT);
            try (var capture = MpLog.capture()) {
                helper.assertTrue(!PlotFeature.allocate(server, UUID.randomUUID()), "a disabled server does not allocate");
                helper.assertValueEqual(PlotCommands.info(PlotGameSupport.player(server, player, false)), 0, "info while disabled");
                helper.assertTrue(capture.lines().stream().noneMatch(line -> line.startsWith("event=plot_")), "a disabled server logs no plot events");
            }
            helper.succeed();
        } finally {
            PlotGameSupport.close(helper, session);
        }
    }

    @GameTest
    public void saveThenLoadKeepsOwnerOriginAndSpawn(GameTestHelper helper) {
        var session = PlotGameSupport.open(helper, PlotGameSupport.ENABLED);
        try {
            MinecraftServer server = helper.getLevel().getServer();
            UUID owner = UUID.randomUUID();
            helper.assertTrue(PlotFeature.allocate(server, owner), "allocate persists a row");
            Anchor spawn = new Anchor(AnchorNames.SPAWN, 1.5, 70, 3.5, 90, 0);
            Anchors.Layout layout = new Anchors.Layout("studio", 4, null, java.util.Map.of(AnchorNames.SPAWN, spawn));
            helper.assertTrue(PlotStore.saveAnchors(PlotFeature.worldRoot(server), 0, layout), "anchors are saved");

            PlotRegistry loaded = new PlotRegistry();
            var rows = PlotStore.load(PlotFeature.worldRoot(server));
            helper.assertTrue(!rows.failed(), "plots.json loads");
            loaded.replaceAll(rows.plots());
            var plot = loaded.plotOf(StudioId.of(owner)).orElseThrow();
            helper.assertValueEqual(plot.index(), 0, "loaded index");
            helper.assertValueEqual(plot.origin(), BlockPos.ZERO, "loaded origin");
            Anchor back = PlotStore.loadAnchors(PlotFeature.worldRoot(server), 0).orElseThrow().get(AnchorNames.SPAWN);
            helper.assertValueEqual(back.x(), spawn.x(), "loaded spawn x");
            helper.assertValueEqual(back.y(), spawn.y(), "loaded spawn y");
            helper.assertValueEqual(back.z(), spawn.z(), "loaded spawn z");
            helper.succeed();
        } finally {
            PlotGameSupport.close(helper, session);
        }
    }

    @GameTest
    public void queuedBuildDoesNotRunInsideAllocate(GameTestHelper helper) {
        var config = new MpServerConfig(true, 128, true, true, true, true, true, 12, 4, 10);
        var session = PlotGameSupport.open(helper, config);
        try {
            UUID owner = UUID.randomUUID();
            helper.assertTrue(PlotFeature.allocate(helper.getLevel().getServer(), owner), "allocate queues a build");
            helper.assertTrue(PlotFeature.buildPending(0), "the build is still queued");
            helper.assertTrue(Anchors.forStudio(StudioId.of(owner)).isEmpty(), "allocate did not build");
            helper.succeed();
        } finally {
            PlotGameSupport.close(helper, session);
        }
    }

    @GameTest(maxTicks = 200)
    public void ownerRebuildSetsRespawnAndLeavesWorldSpawn(GameTestHelper helper) {
        var session = PlotGameSupport.open(helper, PlotGameSupport.ENABLED);
        try {
            MinecraftServer server = helper.getLevel().getServer();
            ServerPlayer player = PlotGameSupport.mock(helper);
            helper.assertTrue(PlotFeature.allocate(server, player.getUUID()), "the owner has plot 0");
            long localRevision = Anchors.forStudio(StudioId.LOCAL).revision();
            LevelData.RespawnData worldSpawn = server.getWorldData().overworldData().getRespawnData();
            int result = PlotCommands.rebuild(PlotGameSupport.player(server, player, false), 0, false, false);
            helper.assertValueEqual(result, 69, "owner rebuild anchor count");
            Anchors.Layout layout = Anchors.forStudio(StudioId.of(player.getUUID()));
            helper.assertValueEqual(layout.anchors().size(), 69, "the owner's studio, not only LOCAL");
            helper.assertValueEqual(Anchors.forStudio(StudioId.LOCAL).revision(), localRevision, "LOCAL revision is unchanged");
            helper.assertValueEqual(server.getWorldData().overworldData().getRespawnData(), worldSpawn, "world spawn is unchanged");
            Anchor spawn = layout.get(AnchorNames.SPAWN);
            if (spawn == null) helper.fail("rebuild did not publish spawn");
            var respawn = player.getRespawnConfig();
            if (respawn == null) helper.fail("the owner has no respawn");
            helper.assertTrue(respawn.forced(), "plot respawn is forced");
            helper.assertValueEqual(respawn.respawnData().pos(), spawn.blockPos(), "respawn block");
            dev.agentcraft.mp.server.plot.PlotSpawn.teleportHome(player, layout);
            helper.assertValueEqual(player.getX(), spawn.x(), "teleport x");
            helper.assertValueEqual(player.getY(), spawn.y(), "teleport y");
            helper.assertValueEqual(player.getZ(), spawn.z(), "teleport z");
            helper.succeed();
        } finally {
            PlotGameSupport.close(helper, session);
        }
    }

    @GameTest
    public void homeAndRespawnUseTheOverworld(GameTestHelper helper) {
        var session = PlotGameSupport.open(helper, PlotGameSupport.ENABLED);
        try {
            MinecraftServer server = helper.getLevel().getServer();
            var nether = server.getLevel(Level.NETHER);
            if (nether == null) {
                helper.fail("the game-test server has no Nether dimension");
                return;
            }
            ServerPlayer player = PlotGameSupport.mockIn(server, nether);
            helper.assertValueEqual(player.level().dimension(), Level.NETHER, "the mock starts in the Nether");
            helper.assertTrue(PlotFeature.allocate(server, player.getUUID()), "the player has a plot");
            publishSpawn(StudioId.of(player.getUUID()), 12.5, 70, 12.5);
            helper.assertValueEqual(PlotCommands.home(PlotGameSupport.player(server, player, false)), 1, "home succeeds");
            // The connection-less mock keeps its own level; the chosen home level and the respawn record
            // are the observable rules. Production players have a connection and a real cross-level move.
            helper.assertTrue(PlotSpawn.homeLevel(player) == server.overworld(), "home targets the overworld");
            var respawn = player.getRespawnConfig();
            if (respawn == null) {
                helper.fail("home set no respawn");
                return;
            }
            helper.assertValueEqual(respawn.respawnData().dimension(), Level.OVERWORLD, "respawn dimension");
            helper.assertValueEqual(respawn.respawnData().pos(), BlockPos.containing(12.5, 70, 12.5), "respawn position");
            helper.succeed();
        } finally {
            PlotGameSupport.close(helper, session);
        }
    }

    @GameTest
    public void startPublishesLayoutsAndQueuesUnbuilt(GameTestHelper helper) {
        var session = PlotGameSupport.open(helper, PlotGameSupport.BUILDING);
        try {
            MinecraftServer server = helper.getLevel().getServer();
            UUID a = UUID.randomUUID();
            UUID b = UUID.randomUUID();
            PlotRegistry seeded = new PlotRegistry();
            seeded.restore(new Plot(0, StudioId.of(a), BlockPos.ZERO));
            seeded.restore(new Plot(1, StudioId.of(b), PlotGrid.originOf(1, 128)));
            helper.assertTrue(PlotStore.save(PlotFeature.worldRoot(server), seeded.all()), "plots.json written");
            Anchor spawn = new Anchor(AnchorNames.SPAWN, 1.5, 70, 3.5, 90, 0);
            Anchors.Layout saved = new Anchors.Layout("studio", 7, null, java.util.Map.of(AnchorNames.SPAWN, spawn));
            helper.assertTrue(PlotStore.saveAnchors(PlotFeature.worldRoot(server), 0, saved), "anchors written for plot 0");

            PlotFeature.start(server);
            helper.assertTrue(Plots.directory() instanceof PlotRegistry, "the registry is installed");
            PlotRegistry registry = (PlotRegistry) Plots.directory();
            helper.assertValueEqual(registry.all().size(), 2, "both rows loaded");
            helper.assertTrue(!Anchors.forStudio(StudioId.of(a)).isEmpty(), "A's layout is published");
            helper.assertValueEqual(Anchors.forStudio(StudioId.of(a)).get(AnchorNames.SPAWN).y(), 70.0, "the saved layout was loaded");
            helper.assertValueEqual(Anchors.forStudio(StudioId.of(a)).revision(), 7L, "the saved revision is preserved");
            helper.assertTrue(PlotFeature.buildPending(1), "the plot without anchors is queued");
            helper.assertFalse(PlotFeature.buildPending(0), "the built plot is not queued");

            // INIT for the same owner queues the build again without allocating or logging again.
            PlotFeature.resetPending();
            try (var capture = MpLog.capture()) {
                helper.assertFalse(PlotFeature.allocate(server, b), "an existing owner is not allocated again");
                helper.assertTrue(PlotFeature.buildPending(1), "INIT resumes the build");
                helper.assertTrue(capture.lines().stream().noneMatch(line -> line.startsWith("event=plot_allocated ")),
                    "no second plot_allocated line");
            }
            helper.succeed();
        } finally {
            PlotGameSupport.close(helper, session);
        }
    }

    @GameTest
    public void startRefusesARegistryItCannotTrust(GameTestHelper helper) throws Exception {
        var session = PlotGameSupport.open(helper, PlotGameSupport.BUILDING);
        try {
            MinecraftServer server = helper.getLevel().getServer();
            Path file = PlotStore.plotsFile(PlotFeature.worldRoot(server));
            var installed = Plots.directory();
            String row = "{\"index\":0,\"owner\":\"" + UUID.randomUUID() + "\",\"x\":0,\"y\":0,\"z\":0}";
            // Unreadable, the wrong shape, and one rejected row beside a valid one that has no layout.
            for (String text : List.of("{", "{\"plots\":7}", "{\"plots\":[" + row + ",7]}")) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, text);
                helper.assertTrue(startRefusal(server) != null, "start refuses " + text.length() + " bytes of bad registry");
                helper.assertTrue(Plots.directory() == installed, "a refused start installs no registry");
                helper.assertFalse(PlotFeature.buildPending(0), "a refused start queues no build");
                helper.assertTrue(Arrays.equals(Files.readAllBytes(file), text.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    "a refused start leaves plots.json as it is");
            }
            // Written at stride 128, started at 256: a client would draw plot 1 somewhere else.
            List<Plot> plots = List.of(new Plot(0, StudioId.of(UUID.randomUUID()), BlockPos.ZERO),
                new Plot(1, StudioId.of(UUID.randomUUID()), PlotGrid.originOf(1, 128)));
            helper.assertTrue(PlotStore.save(PlotFeature.worldRoot(server), plots), "plots.json written");
            byte[] saved = Files.readAllBytes(file);
            MpServerConfig.install(new MpServerConfig(true, 256, true, true, true, true, true, 12, 4, 10));
            String reason = startRefusal(server);
            helper.assertTrue(reason != null && reason.contains("another plotStride"), "start refuses a changed stride: " + reason);
            helper.assertTrue(Plots.directory() == installed, "a refused stride installs no registry");
            helper.assertTrue(Arrays.equals(Files.readAllBytes(file), saved), "a refused stride leaves plots.json as it is");
            // The same file at the stride it was written with starts.
            MpServerConfig.install(PlotGameSupport.BUILDING);
            helper.assertTrue(startRefusal(server) == null, "the stored stride starts");
            helper.assertValueEqual(Plots.directory().all().size(), 2, "both rows are installed");
            helper.succeed();
        } finally {
            PlotGameSupport.close(helper, session);
        }
    }

    /** The message {@code PlotFeature.start} threw, or null when it started. */
    private static String startRefusal(MinecraftServer server) {
        try {
            PlotFeature.start(server);
            return null;
        } catch (IllegalStateException refused) {
            return refused.getMessage();
        }
    }

    @GameTest
    public void allocatedJoinTeleportsAndRejoinDoesNot(GameTestHelper helper) {
        var session = PlotGameSupport.open(helper, PlotGameSupport.ENABLED);
        try {
            MinecraftServer server = helper.getLevel().getServer();
            ServerPlayer player = PlotGameSupport.onlineMock(helper, session);
            // The login fires the real INIT handler, which allocates plot 0 for this player.
            helper.assertTrue(Plots.directory().plotOf(StudioId.of(player.getUUID())).isPresent(), "the joining player is allocated");
            publishSpawn(StudioId.of(player.getUUID()), 10.5, 70, 10.5);
            player.absSnapTo(0, 64, 0, 0, 0);

            PlotFeature.onJoin(server, player.getUUID());
            PlotFeature.tick(server);
            helper.assertValueEqual(player.getX(), 10.5, "the allocating join moved the player home");

            player.absSnapTo(0, 64, 0, 0, 0);
            PlotFeature.onJoin(server, player.getUUID());
            PlotFeature.tick(server);
            helper.assertValueEqual(player.getX(), 0.0, "a later join did not move the player");
            var respawn = player.getRespawnConfig();
            if (respawn == null) {
                helper.fail("the returning join set no respawn");
                return;
            }
            helper.assertValueEqual(respawn.respawnData().dimension(), Level.OVERWORLD, "respawn dimension");
            helper.assertValueEqual(respawn.respawnData().pos(), BlockPos.containing(10.5, 70, 10.5), "respawn position");
            helper.succeed();
        } finally {
            PlotGameSupport.close(helper, session);
        }
    }

    @GameTest
    public void unbuiltJoinThenLaterLayoutDoesNotTeleport(GameTestHelper helper) {
        var session = PlotGameSupport.open(helper, PlotGameSupport.ENABLED);
        try {
            MinecraftServer server = helper.getLevel().getServer();
            ServerPlayer player = PlotGameSupport.onlineMock(helper, session);
            helper.assertTrue(Plots.directory().plotOf(StudioId.of(player.getUUID())).isPresent(), "the joining player is allocated");
            player.absSnapTo(0, 64, 0, 0, 0);

            // The first join finds no layout and no pending build (autoBuild is off): the allocating
            // mark must be dropped here, not kept for a later join.
            PlotFeature.onJoin(server, player.getUUID());
            PlotFeature.tick(server);
            publishSpawn(StudioId.of(player.getUUID()), 10.5, 70, 10.5);

            PlotFeature.onJoin(server, player.getUUID());
            PlotFeature.tick(server);
            helper.assertValueEqual(player.getX(), 0.0, "the later join did not move the player");
            var respawn = player.getRespawnConfig();
            if (respawn == null) {
                helper.fail("the later join set no respawn");
                return;
            }
            helper.assertValueEqual(respawn.respawnData().dimension(), Level.OVERWORLD, "respawn dimension");
            helper.succeed();
        } finally {
            PlotGameSupport.close(helper, session);
        }
    }

    @GameTest
    public void openAndCloseRestoreTheRebuildSnapshot(GameTestHelper helper) {
        var original = PlotRebuilds.snapshot();
        try {
            UUID id = UUID.randomUUID();
            PlotRebuilds.note(id, 5);
            var session = PlotGameSupport.open(helper, PlotGameSupport.ENABLED);
            boolean closed = false;
            try {
                helper.assertFalse(PlotRebuilds.snapshot().containsKey(id), "open clears the shared bookkeeping");
                closed = true;
                PlotGameSupport.close(helper, session);
                helper.assertTrue(PlotRebuilds.snapshot().containsKey(id), "close restores what open found");
                helper.assertValueEqual(PlotRebuilds.snapshot().get(id), 5, "the stored tick is restored");
                helper.succeed();
            } finally {
                if (!closed) PlotGameSupport.close(helper, session);
            }
        } finally {
            // Put back exactly the records that existed before this test, not an empty map, whatever
            // open or close threw.
            PlotRebuilds.install(original);
        }
    }

    @GameTest(environment = "agentcraft:plot_multiplayer", maxTicks = 400)
    public void queuedBuildFinishesAcrossTicks(GameTestHelper helper) {
        var session = PlotGameSupport.open(helper, PlotGameSupport.BUILDING);
        MinecraftServer server = helper.getLevel().getServer();
        UUID owner = UUID.randomUUID();
        try {
            helper.assertTrue(PlotFeature.allocate(server, owner), "allocate queues a build");
            helper.assertTrue(Anchors.forStudio(StudioId.of(owner)).isEmpty(), "no layout in the same tick");
            helper.assertTrue(PlotFeature.buildPending(0), "the build is queued");
        } catch (RuntimeException e) {
            PlotGameSupport.close(helper, session);
            throw e;
        }
        // Its own environment keeps this multi-tick test out of the singleplayer batch.
        helper.runAfterDelay(120, () -> {
            try {
                helper.assertFalse(PlotFeature.buildPending(0), "the queue is empty");
                Anchors.Layout layout = Anchors.forStudio(StudioId.of(owner));
                helper.assertValueEqual(layout.anchors().size(), 69, "69 anchors published");
                helper.assertTrue(PlotStore.loadAnchors(PlotFeature.worldRoot(server), 0).isPresent(), "anchors saved");
            } finally {
                PlotGameSupport.close(helper, session);
            }
            helper.succeed();
        });
    }

    private static void publishSpawn(StudioId studio, double x, double y, double z) {
        Anchors.publish(studio, new Anchors.Layout("studio", 5, null,
            java.util.Map.of(AnchorNames.SPAWN, new Anchor(AnchorNames.SPAWN, x, y, z, 0, 0))));
    }
}
