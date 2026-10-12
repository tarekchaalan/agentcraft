package dev.agentcraft.gametest.mp.plot;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.agentcraft.block.entity.StationBlockEntity;
import dev.agentcraft.hq.HqBuilder;
import dev.agentcraft.hq.HqBuilders;
import dev.agentcraft.layout.Anchor;
import dev.agentcraft.layout.AnchorNames;
import dev.agentcraft.layout.Anchors;
import dev.agentcraft.mp.MpLog;
import dev.agentcraft.mp.MpServerConfig;
import dev.agentcraft.mp.Plot;
import dev.agentcraft.mp.Plots;
import dev.agentcraft.mp.StudioId;
import dev.agentcraft.mp.server.plot.PlotCommands;
import dev.agentcraft.mp.server.plot.PlotFeature;
import dev.agentcraft.mp.server.plot.PlotRebuilds;
import dev.agentcraft.mp.server.plot.PlotRegistry;
import dev.agentcraft.mp.server.plot.PlotStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public final class PlotCommandGameTest {
    @GameTest
    public void commandNodesFollowTheActor(GameTestHelper helper) {
        var session = PlotGameSupport.open(helper, PlotGameSupport.ENABLED);
        try {
            MinecraftServer server = helper.getLevel().getServer();
            ServerPlayer player = PlotGameSupport.mock(helper);
            CommandSourceStack nonOp = PlotGameSupport.player(server, player, false);
            CommandSourceStack op = PlotGameSupport.player(server, player, true);
            var dispatcher = server.getCommands().getDispatcher();
            helper.assertTrue(dispatcher.findNode(List.of("agentcraft")).canUse(nonOp), "the root is open");
            helper.assertTrue(dispatcher.findNode(List.of("agentcraft", "plot", "info")).canUse(nonOp), "info is open");
            helper.assertTrue(dispatcher.findNode(List.of("agentcraft", "plot", "home")).canUse(nonOp), "home is open");
            helper.assertTrue(!dispatcher.findNode(List.of("agentcraft", "hq")).canUse(nonOp), "hq stays an operator command");
            helper.assertTrue(!dispatcher.findNode(List.of("agentcraft", "anchors")).canUse(nonOp), "anchors stays an operator command");
            helper.assertTrue(!dispatcher.findNode(List.of("agentcraft", "plot", "list")).canUse(nonOp), "list is an operator command");
            helper.assertTrue(!dispatcher.findNode(List.of("agentcraft", "plot", "assign")).canUse(nonOp), "assign is an operator command");
            helper.assertTrue(!dispatcher.findNode(List.of("agentcraft", "plot", "free")).canUse(nonOp), "free is an operator command");
            helper.assertTrue(!dispatcher.findNode(List.of("agentcraft", "plot", "rebuild", "index")).canUse(nonOp), "rebuild by index is an operator command");
            helper.assertTrue(dispatcher.findNode(List.of("agentcraft", "plot", "list")).canUse(op), "an operator can list");
            helper.assertTrue(run(dispatcher, "agentcraft plot list", nonOp) == 0, "a non-op dispatcher cannot list");
            helper.succeed();
        } finally {
            PlotGameSupport.close(helper, session);
        }
    }

    @GameTest
    public void infoAndHomeUseTheSourcePlayer(GameTestHelper helper) {
        var session = PlotGameSupport.open(helper, PlotGameSupport.ENABLED);
        try {
            MinecraftServer server = helper.getLevel().getServer();
            ServerPlayer a = PlotGameSupport.mock(helper);
            ServerPlayer b = PlotGameSupport.mock(helper);
            helper.assertTrue(PlotFeature.allocate(server, a.getUUID()), "A is allocated");
            helper.assertTrue(PlotFeature.allocate(server, b.getUUID()), "B is allocated");
            CommandSourceStack sourceA = PlotGameSupport.player(server, a, false);
            CommandSourceStack sourceB = PlotGameSupport.player(server, b, false);
            try (var capture = MpLog.capture()) {
                helper.assertValueEqual(PlotCommands.info(sourceA), 1, "A's info is A's plot");
                helper.assertValueEqual(PlotCommands.info(sourceB), 2, "B's info is B's plot");
                String expectedA = "event=plot_command player=" + a.getUUID() + " studio=" + a.getUUID()
                    + " plot=0 command=info target_plot=0 ok=true";
                String expectedB = "event=plot_command player=" + b.getUUID() + " studio=" + b.getUUID()
                    + " plot=1 command=info target_plot=1 ok=true";
                helper.assertTrue(capture.lines().contains(expectedA), "A's whole info line: " + capture.lines());
                helper.assertTrue(capture.lines().contains(expectedB), "B's whole info line: " + capture.lines());
                helper.assertTrue(capture.lines().stream().noneMatch(line -> line.contains("test-mock-player")),
                    "info does not log the player name");
            }
            publishSpawn(StudioId.of(a.getUUID()), 10.5, 80, 10.5);
            publishSpawn(StudioId.of(b.getUUID()), 30.5, 80, 30.5);
            a.absSnapTo(0, 64, 0, 0, 0);
            b.absSnapTo(0, 64, 0, 0, 0);
            helper.assertValueEqual(PlotCommands.home(sourceA), 1, "A can go home");
            helper.assertValueEqual(a.getX(), 10.5, "A moved home");
            helper.assertValueEqual(b.getX(), 0.0, "B stayed put");
            helper.assertValueEqual(PlotCommands.home(server.createCommandSourceStack().withSuppressedOutput()), 0, "the console has no plot");
            helper.succeed();
        } finally {
            PlotGameSupport.close(helper, session);
        }
    }

    @GameTest(maxTicks = 200)
    public void nonOpMutationsAreRefused(GameTestHelper helper) {
        var session = PlotGameSupport.open(helper, PlotGameSupport.ENABLED);
        try {
            MinecraftServer server = helper.getLevel().getServer();
            ServerPlayer owner = PlotGameSupport.mock(helper);
            helper.assertTrue(PlotFeature.allocate(server, owner.getUUID()), "the owner has a plot");
            StudioId studio = StudioId.of(owner.getUUID());
            publishSpawn(studio, 4.5, 70, 4.5);
            long revision = Anchors.forStudio(studio).revision();
            CommandSourceStack nonOp = PlotGameSupport.player(server, owner, false);
            UUID other = UUID.randomUUID();
            server.services().nameToIdCache().add(new NameAndId(other, "Other"));
            PlotRegistry registry = (PlotRegistry) Plots.directory();
            try (var capture = MpLog.capture()) {
                helper.assertValueEqual(PlotCommands.list(nonOp), 0, "a non-op cannot list");
                helper.assertValueEqual(PlotCommands.assign(nonOp, "Other", 1), 0, "a non-op cannot assign");
                helper.assertValueEqual(PlotCommands.free(nonOp, 0), 0, "a non-op cannot free");
                helper.assertValueEqual(PlotCommands.rebuild(nonOp, 0, true, false), 0, "a non-op cannot rebuild by index");
                helper.assertTrue(registry.byIndex(0) != null, "the plot is still there");
                helper.assertTrue(registry.plotOf(StudioId.of(other)).isEmpty(), "assign did not create a plot");
                helper.assertValueEqual(Anchors.forStudio(studio).revision(), revision, "rebuild by index did not build");
                String ownerId = owner.getUUID().toString();
                helper.assertTrue(capture.lines().contains(
                    "event=plot_command player=" + ownerId + " command=list target_plot=-1 ok=false"), "list refusal line");
                helper.assertTrue(capture.lines().contains(
                    "event=plot_command player=" + ownerId + " plot=1 command=assign target_plot=1 ok=false"), "assign refusal line");
                helper.assertTrue(capture.lines().contains(
                    "event=plot_command player=" + ownerId + " plot=0 command=free target_plot=0 ok=false"), "free refusal line");
                helper.assertTrue(capture.lines().contains(
                    "event=plot_command player=" + ownerId + " plot=0 command=rebuild target_plot=0 ok=false"), "rebuild refusal line");
                helper.assertTrue(capture.lines().stream().noneMatch(line -> line.contains("Other")), "the target name is not logged");
            }
            long local = Anchors.forStudio(StudioId.LOCAL).revision();
            helper.assertTrue(run(server.getCommands().getDispatcher(), "agentcraft hq", nonOp) == 0, "a non-op cannot run hq");
            helper.assertValueEqual(Anchors.forStudio(studio).revision(), revision, "hq did not build the owner's plot");
            helper.assertValueEqual(Anchors.forStudio(StudioId.LOCAL).revision(), local, "hq did not build LOCAL");
            helper.succeed();
        } finally {
            PlotGameSupport.close(helper, session);
        }
    }

    @GameTest
    public void assignUsesTheCachedUuidNotAnOfflineProfile(GameTestHelper helper) {
        var session = PlotGameSupport.open(helper, PlotGameSupport.ENABLED);
        try {
            MinecraftServer server = helper.getLevel().getServer();
            ServerPlayer op = PlotGameSupport.mock(helper);
            CommandSourceStack source = PlotGameSupport.player(server, op, true);
            UUID real = UUID.randomUUID();
            server.services().nameToIdCache().add(new NameAndId(real, "CachedOwner"));
            helper.assertValueEqual(PlotCommands.assign(source, "cachedowner", 3), 1, "a cached name assigns");
            PlotRegistry registry = (PlotRegistry) Plots.directory();
            helper.assertValueEqual(registry.byIndex(3).owner(), StudioId.of(real), "the stored uuid is the cached one");
            UUID offline = NameAndId.createOffline("cachedowner").id();
            helper.assertTrue(!registry.byIndex(3).owner().owner().equals(offline), "the offline uuid was not invented");
            int before = registry.all().size();
            helper.assertValueEqual(PlotCommands.assign(source, "NotAPlayer", 4), 0, "an unknown name is refused");
            helper.assertValueEqual(PlotCommands.assign(source, "../../x", 4), 0, "a path-like name is refused");
            helper.assertValueEqual(registry.all().size(), before, "unknown names create no plot");
            helper.assertTrue(registry.plotOf(StudioId.of(NameAndId.createOffline("NotAPlayer").id())).isEmpty(), "no offline plot");
            helper.assertTrue(registry.plotOf(StudioId.of(NameAndId.createOffline("../../x").id())).isEmpty(), "no path plot");
            helper.succeed();
        } finally {
            PlotGameSupport.close(helper, session);
        }
    }

    @GameTest
    public void assignRefusesATakenIndexAndAnOwnerWhoAlreadyHasAPlot(GameTestHelper helper) {
        var session = PlotGameSupport.open(helper, PlotGameSupport.ENABLED);
        try {
            MinecraftServer server = helper.getLevel().getServer();
            UUID seated = UUID.randomUUID();
            helper.assertTrue(PlotFeature.allocate(server, seated), "a player already owns plot 0");
            CommandSourceStack source = server.createCommandSourceStack().withSuppressedOutput();
            UUID cached = UUID.randomUUID();
            server.services().nameToIdCache().add(new NameAndId(cached, "Newcomer"));
            helper.assertValueEqual(PlotCommands.assign(source, "Newcomer", 0), 0, "plot 0 is taken");
            server.services().nameToIdCache().add(new NameAndId(seated, "Seated"));
            helper.assertValueEqual(PlotCommands.assign(source, "Seated", 1), 0, "that player already has a plot");
            PlotRegistry registry = (PlotRegistry) Plots.directory();
            helper.assertTrue(registry.byIndex(1) == null, "index 1 was not filled");
            helper.assertValueEqual(registry.plotOf(StudioId.of(seated)).orElseThrow().index(), 0, "the owner kept plot 0");
            helper.assertTrue(registry.plotOf(StudioId.of(cached)).isEmpty(), "the newcomer got nothing");
            helper.succeed();
        } finally {
            PlotGameSupport.close(helper, session);
        }
    }

    @GameTest
    public void freeDropsTheRowAndTheDirectory(GameTestHelper helper) throws Exception {
        var session = PlotGameSupport.open(helper, PlotGameSupport.ENABLED);
        try {
            MinecraftServer server = helper.getLevel().getServer();
            UUID owner = UUID.randomUUID();
            helper.assertTrue(PlotFeature.allocate(server, owner), "a plot exists to free");
            var root = PlotFeature.worldRoot(server);
            var dir = dev.agentcraft.mp.server.plot.PlotStore.plotDirectory(root, 0).orElseThrow();
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Files.writeString(dir.resolve("anchors.json"), "{}");
            var keep = root.resolve("agentcraft").resolve("plots").resolve("keep.txt");
            java.nio.file.Files.writeString(keep, "stay");
            CommandSourceStack console = server.createCommandSourceStack().withSuppressedOutput();
            helper.assertValueEqual(PlotCommands.free(console, 9), 0, "an unknown index is refused");
            helper.assertTrue(((PlotRegistry) Plots.directory()).byIndex(0) != null, "the real plot survived");
            try (var capture = MpLog.capture()) {
                helper.assertValueEqual(PlotCommands.free(console, 0), 1, "an operator can free");
                helper.assertTrue(capture.lines().contains(
                    "event=plot_command studio=" + owner + " plot=0 command=free target_plot=0 ok=true"), "the free line");
                helper.assertTrue(Plots.directory().plotOf(StudioId.of(owner)).isEmpty(), "the row is gone");
                helper.assertTrue(!java.nio.file.Files.exists(dir), "the plot directory is gone");
                helper.assertTrue(java.nio.file.Files.exists(keep), "a sibling file stays");
                helper.assertTrue(java.nio.file.Files.exists(dev.agentcraft.mp.server.plot.PlotStore.plotsFile(root)), "plots.json stays");
                helper.assertTrue(PlotFeature.allocate(server, owner), "the freed owner can be allocated again");
                helper.assertTrue(capture.lines().stream().anyMatch(line -> line.startsWith("event=plot_allocated ")), "the new row is logged");
            }
            helper.succeed();
        } finally {
            PlotGameSupport.close(helper, session);
        }
    }

    @GameTest(maxTicks = 200)
    public void hqBuildsTheCallersPlot(GameTestHelper helper) {
        var session = PlotGameSupport.open(helper, PlotGameSupport.ENABLED);
        try {
            MinecraftServer server = helper.getLevel().getServer();
            ServerPlayer player = PlotGameSupport.mock(helper);
            helper.assertTrue(PlotFeature.allocate(server, player.getUUID()), "the caller owns plot 0");
            long localRevision = Anchors.forStudio(StudioId.LOCAL).revision();
            var worldSpawn = server.getWorldData().overworldData().getRespawnData();
            CommandSourceStack source = server.createCommandSourceStack().withEntity(player).withSuppressedOutput();
            int result;
            try {
                result = server.getCommands().getDispatcher().execute("agentcraft hq", source);
            } catch (CommandSyntaxException e) {
                helper.fail("the owner could not run hq: " + e.getMessage());
                return;
            }
            helper.assertValueEqual(result, 69, "/agentcraft hq result");
            helper.assertValueEqual(Anchors.forStudio(StudioId.of(player.getUUID())).anchors().size(), 69, "hq published the caller's studio");
            helper.assertValueEqual(Anchors.forStudio(StudioId.LOCAL).revision(), localRevision, "hq did not publish LOCAL");
            helper.assertValueEqual(server.getWorldData().overworldData().getRespawnData(), worldSpawn, "hq did not move the world spawn");
            helper.succeed();
        } finally {
            PlotGameSupport.close(helper, session);
        }
    }

    @GameTest(maxTicks = 200)
    public void hqRefusesAnotherBuilderInMultiplayer(GameTestHelper helper) throws CommandSyntaxException {
        var session = PlotGameSupport.open(helper, PlotGameSupport.ENABLED);
        try {
            MinecraftServer server = helper.getLevel().getServer();
            ServerPlayer op = PlotGameSupport.mock(helper);
            StudioId studio = StudioId.of(op.getUUID());
            helper.assertTrue(PlotFeature.allocate(server, op.getUUID()), "the operator owns plot 0");
            CommandSourceStack source = PlotGameSupport.player(server, op, true);
            var dispatcher = server.getCommands().getDispatcher();
            helper.assertValueEqual(dispatcher.execute("agentcraft hq", source), 69, "the default builder still builds the caller's plot");
            Anchors.Layout built = Anchors.forStudio(studio);
            helper.assertValueEqual(built.anchors().size(), 69, "hq published the caller's studio");
            // The test room ignores the plot origin: it would clear and rebuild this part of the origin site.
            List<BlockState> before = originSite(server.overworld());
            try (var capture = MpLog.capture()) {
                helper.assertValueEqual(dispatcher.execute("agentcraft hq test", source), 0, "hq test on a multiplayer server");
                helper.assertValueEqual(dispatcher.execute("agentcraft hq test force", source), 0, "hq test force on a multiplayer server");
                helper.assertTrue(capture.lines().isEmpty(), "the refusal logs no mp event: " + capture.lines());
            }
            helper.assertTrue(originSite(server.overworld()).equals(before), "no block of the origin site changed");
            helper.assertValueEqual(Anchors.forStudio(studio).revision(), built.revision(), "the caller's revision");
            helper.assertTrue(Anchors.forStudio(studio).equals(built), "the caller's published layout is unchanged");
            helper.succeed();
        } finally {
            PlotGameSupport.close(helper, session);
        }
    }

    private static List<BlockState> originSite(ServerLevel level) {
        List<BlockState> states = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(-13, 64, -13, 13, 66, 13)) states.add(level.getBlockState(pos));
        return states;
    }

    @GameTest(maxTicks = 200)
    public void nonOpRebuildIsThrottled(GameTestHelper helper) {
        var session = PlotGameSupport.open(helper, PlotGameSupport.ENABLED);
        try {
            MinecraftServer server = helper.getLevel().getServer();
            ServerPlayer owner = PlotGameSupport.mock(helper);
            helper.assertTrue(PlotFeature.allocate(server, owner.getUUID()), "the owner has plot 0");
            StudioId studio = StudioId.of(owner.getUUID());
            CommandSourceStack nonOp = PlotGameSupport.player(server, owner, false);
            helper.assertValueEqual(PlotCommands.rebuild(nonOp, 0, false, false), 69, "first non-op rebuild");
            long revision = Anchors.forStudio(studio).revision();
            String refusal = "event=plot_command player=" + owner.getUUID() + " studio=" + owner.getUUID()
                + " plot=0 command=rebuild target_plot=0 ok=false";
            try (var capture = MpLog.capture()) {
                helper.assertValueEqual(PlotCommands.rebuild(nonOp, 0, false, false), 0, "second rebuild inside the window");
                helper.assertValueEqual(Anchors.forStudio(studio).revision(), revision, "the layout revision did not change");
                helper.assertTrue(capture.lines().contains(refusal), "whole refusal line: " + capture.lines());
            }
            PlotRebuilds.note(owner.getUUID(), server.getTickCount() - PlotRebuilds.WINDOW_TICKS);
            helper.assertValueEqual(PlotCommands.rebuild(nonOp, 0, false, false), 69, "rebuild allowed after 1200 ticks");
            CommandSourceStack op = PlotGameSupport.player(server, owner, true);
            helper.assertValueEqual(PlotCommands.rebuild(op, 0, false, false), 69, "first op rebuild");
            helper.assertValueEqual(PlotCommands.rebuild(op, 0, false, false), 69, "an operator is not limited");
            helper.succeed();
        } finally {
            PlotGameSupport.close(helper, session);
        }
    }

    @GameTest(maxTicks = 200)
    public void failedRebuildDoesNotStartTheWindow(GameTestHelper helper) {
        var session = PlotGameSupport.open(helper, PlotGameSupport.ENABLED);
        try {
            MinecraftServer server = helper.getLevel().getServer();
            ServerPlayer owner = PlotGameSupport.mock(helper);
            helper.assertTrue(PlotFeature.allocate(server, owner.getUUID()), "the owner has plot 0");
            StudioId studio = StudioId.of(owner.getUUID());
            CommandSourceStack nonOp = PlotGameSupport.player(server, owner, false);
            long revision = Anchors.forStudio(studio).revision();
            String refusal = "event=plot_command player=" + owner.getUUID() + " studio=" + owner.getUUID()
                + " plot=0 command=rebuild target_plot=0 ok=false";
            HqBuilder real = HqBuilders.get(HqBuilders.defaultId());
            helper.assertTrue(real != null, "a default builder is registered");
            // Registered under the real builder's id, so the default id and the stored plan record are
            // untouched and registering the real builder again puts everything back.
            HqBuilders.register(new HqBuilder() {
                @Override
                public String id() {
                    return real.id();
                }

                @Override
                public String description() {
                    return real.description();
                }

                @Override
                public void build(ServerLevel level, Anchors.Builder anchors) {
                    throw new IllegalStateException("game test: this builder always fails");
                }
            });
            try (var capture = MpLog.capture()) {
                helper.assertValueEqual(PlotCommands.rebuild(nonOp, 0, false, false), 0, "a failing build is refused");
                helper.assertTrue(capture.lines().contains(refusal), "whole refusal line: " + capture.lines());
            } finally {
                HqBuilders.register(real);
            }
            helper.assertValueEqual(Anchors.forStudio(studio).revision(), revision, "the failed build published nothing");
            helper.assertFalse(PlotRebuilds.snapshot().containsKey(owner.getUUID()), "a failed build does not start the window");
            helper.assertValueEqual(PlotCommands.rebuild(nonOp, 0, false, false), 69, "the retry is not throttled");
            helper.assertValueEqual(PlotCommands.rebuild(nonOp, 0, false, false), 0, "the rebuild that built starts the window");
            helper.succeed();
        } finally {
            PlotGameSupport.close(helper, session);
        }
    }

    @GameTest
    public void assignRefusalCarriesTheResolvedTarget(GameTestHelper helper) {
        var session = PlotGameSupport.open(helper, PlotGameSupport.ENABLED);
        try {
            MinecraftServer server = helper.getLevel().getServer();
            ServerPlayer op = PlotGameSupport.mock(helper);
            CommandSourceStack source = PlotGameSupport.player(server, op, true);
            UUID seated = UUID.randomUUID();
            PlotRegistry registry = (PlotRegistry) Plots.directory();
            registry.restore(new Plot(0, StudioId.of(seated), BlockPos.ZERO));
            server.services().nameToIdCache().add(new NameAndId(seated, "RefusalTarget"));
            try (var capture = MpLog.capture()) {
                helper.assertValueEqual(PlotCommands.assign(source, "RefusalTarget", 1), 0, "assign refused");
                String expected = "event=plot_command player=" + op.getUUID() + " studio=" + seated
                    + " plot=1 command=assign target_plot=1 ok=false";
                helper.assertTrue(capture.lines().contains(expected), "whole refusal line: " + capture.lines());
                helper.assertTrue(!op.getUUID().equals(seated), "the actor and the target are different uuids");
            }
            helper.succeed();
        } finally {
            PlotGameSupport.close(helper, session);
        }
    }

    @GameTest
    public void plotNodeRequiresMultiplayer(GameTestHelper helper) {
        var session = PlotGameSupport.open(helper, PlotGameSupport.ENABLED);
        try {
            MinecraftServer server = helper.getLevel().getServer();
            ServerPlayer player = PlotGameSupport.mock(helper);
            CommandSourceStack source = PlotGameSupport.player(server, player, false);
            var dispatcher = server.getCommands().getDispatcher();
            var node = dispatcher.findNode(List.of("agentcraft", "plot"));
            helper.assertTrue(node.canUse(source), "plot is usable with multiplayer on");
            MpServerConfig previous = MpServerConfig.install(MpServerConfig.DEFAULT);
            try (var capture = MpLog.capture()) {
                helper.assertFalse(node.canUse(source), "plot is not usable with multiplayer off");
                helper.assertValueEqual(run(dispatcher, "agentcraft plot info", source), 0, "the dispatcher refuses plot info");
                helper.assertTrue(capture.lines().stream().noneMatch(line -> line.startsWith("event=plot_command")),
                    "nothing plot_command is logged while off");
            } finally {
                MpServerConfig.install(previous);
            }
            helper.assertTrue(node.canUse(source), "plot is usable again");
            helper.succeed();
        } finally {
            PlotGameSupport.close(helper, session);
        }
    }

    @GameTest
    public void refusalsLeaveStateUnchanged(GameTestHelper helper) throws Exception {
        var session = PlotGameSupport.open(helper, PlotGameSupport.ENABLED);
        try {
            MinecraftServer server = helper.getLevel().getServer();
            ServerPlayer noPlot = PlotGameSupport.mock(helper);
            ServerPlayer op = PlotGameSupport.mock(helper);
            CommandSourceStack noPlotSource = PlotGameSupport.player(server, noPlot, false);
            CommandSourceStack console = server.createCommandSourceStack().withSuppressedOutput();
            List<String> failures = new ArrayList<>();
            CommandSource recording = new CommandSource() {
                @Override
                public void sendSystemMessage(Component message) {
                    failures.add(message.getString());
                }

                @Override
                public boolean acceptsSuccess() {
                    return false;
                }

                @Override
                public boolean acceptsFailure() {
                    return true;
                }

                @Override
                public boolean shouldInformAdmins() {
                    return false;
                }
            };
            CommandSourceStack opRecording = server.createCommandSourceStack().withEntity(op)
                .withPermission(net.minecraft.server.permissions.PermissionSet.ALL_PERMISSIONS).withSource(recording);
            PlotRegistry registry = (PlotRegistry) Plots.directory();
            StudioId studio = StudioId.of(noPlot.getUUID());
            String player = noPlot.getUUID().toString();
            String opId = op.getUUID().toString();
            int size = registry.all().size();
            long revision = Anchors.forStudio(studio).revision();
            byte[] before = plotsFileBytes(server);
            try (var capture = MpLog.capture()) {
                helper.assertValueEqual(PlotCommands.info(noPlotSource), 0, "info without a plot");
                assertStateUnchanged(helper, registry, studio, size, revision, before, server);

                helper.assertValueEqual(PlotCommands.home(noPlotSource), 0, "home without a plot");
                assertStateUnchanged(helper, registry, studio, size, revision, before, server);

                helper.assertValueEqual(PlotCommands.rebuild(noPlotSource, 0, false, false), 0, "rebuild without a plot");
                assertStateUnchanged(helper, registry, studio, size, revision, before, server);

                helper.assertValueEqual(PlotCommands.info(console), 0, "console info");
                assertStateUnchanged(helper, registry, studio, size, revision, before, server);

                helper.assertValueEqual(PlotCommands.rebuild(console, 0, false, false), 0, "console rebuild");
                assertStateUnchanged(helper, registry, studio, size, revision, before, server);

                helper.assertValueEqual(PlotCommands.rebuild(opRecording, 7, true, false), 0, "a missing plot index");
                assertStateUnchanged(helper, registry, studio, size, revision, before, server);

                helper.assertTrue(capture.lines().contains(
                    "event=plot_command player=" + player + " command=info target_plot=-1 ok=false"), "no-plot info line");
                helper.assertTrue(capture.lines().contains(
                    "event=plot_command player=" + player + " command=home target_plot=-1 ok=false"), "no-plot home line");
                helper.assertTrue(capture.lines().contains(
                    "event=plot_command player=" + player + " command=rebuild target_plot=-1 ok=false"), "no-plot rebuild line");
                helper.assertTrue(capture.lines().contains(
                    "event=plot_command command=info target_plot=-1 ok=false"), "console info line");
                helper.assertTrue(capture.lines().contains(
                    "event=plot_command command=rebuild target_plot=-1 ok=false"), "console rebuild line");
                helper.assertTrue(capture.lines().contains(
                    "event=plot_command player=" + opId + " plot=7 command=rebuild target_plot=7 ok=false"), "missing-index line");
            }
            helper.assertTrue(failures.contains("That plot does not exist."), "the missing-index message: " + failures);
            helper.succeed();
        } finally {
            PlotGameSupport.close(helper, session);
        }
    }

    private static void assertStateUnchanged(GameTestHelper helper, PlotRegistry registry, StudioId studio,
            int size, long revision, byte[] before, MinecraftServer server) throws Exception {
        helper.assertValueEqual(registry.all().size(), size, "registry rows unchanged");
        helper.assertValueEqual(Anchors.forStudio(studio).revision(), revision, "published revision unchanged");
        helper.assertTrue(java.util.Arrays.equals(before, plotsFileBytes(server)), "plots.json unchanged");
    }

    @GameTest(maxTicks = 200)
    public void opRebuildAndForceAndFreePublished(GameTestHelper helper) {
        var session = PlotGameSupport.open(helper, PlotGameSupport.ENABLED);
        BlockPos lamp = new BlockPos(-15, 69, -10);
        BlockState plannedLamp = null;
        String plannedBinding = null;
        try {
            MinecraftServer server = helper.getLevel().getServer();
            ServerPlayer owner = PlotGameSupport.mock(helper);
            helper.assertTrue(PlotFeature.allocate(server, owner.getUUID()), "the owner has plot 0");
            ServerPlayer op = PlotGameSupport.mock(helper);
            CommandSourceStack opSource = PlotGameSupport.player(server, op, true);
            StudioId studio = StudioId.of(owner.getUUID());
            long opRevision = Anchors.forStudio(StudioId.of(op.getUUID())).revision();
            helper.assertValueEqual(PlotCommands.rebuild(opSource, 0, true, true), 69, "an op rebuilds another owner's plot");
            helper.assertValueEqual(Anchors.forStudio(studio).revision(), 1L, "the owner's layout advanced");
            helper.assertValueEqual(Anchors.forStudio(StudioId.of(op.getUUID())).revision(), opRevision, "the op's own layout is untouched");

            ServerLevel overworld = server.overworld();
            plannedLamp = overworld.getBlockState(lamp);
            if (overworld.getBlockEntity(lamp) instanceof StationBlockEntity station) plannedBinding = station.binding();
            overworld.setBlock(lamp, Blocks.AIR.defaultBlockState(), 3);
            CommandSourceStack ownerSource = PlotGameSupport.player(server, owner, false);
            helper.assertValueEqual(PlotCommands.rebuild(ownerSource, 0, false, true), 69, "the owner force-rebuilds");
            helper.assertValueEqual(overworld.getBlockState(lamp), plannedLamp, "force restored the changed cell");

            publishSpawn(studio, 4.5, 70, 4.5);
            helper.assertValueEqual(PlotCommands.free(opSource, 0), 1, "an op frees the plot");
            helper.assertTrue(Anchors.forStudio(studio).isEmpty(), "the published layout is removed");
            helper.succeed();
        } finally {
            // Restore the shared lamp from its known-good pre-damage state, independently of the rebuild
            // under test, so a force regression does not contaminate later tests.
            ServerLevel overworld = helper.getLevel().getServer().overworld();
            if (plannedLamp != null) {
                overworld.setBlock(lamp, plannedLamp, 3);
                if (plannedBinding != null && overworld.getBlockEntity(lamp) instanceof StationBlockEntity station) {
                    station.setBinding(plannedBinding);
                }
            }
            PlotGameSupport.close(helper, session);
        }
    }

    @GameTest
    public void assignAnOnlinePlayer(GameTestHelper helper) {
        var session = PlotGameSupport.open(helper, PlotGameSupport.NO_AUTO);
        try {
            MinecraftServer server = helper.getLevel().getServer();
            ServerPlayer online = PlotGameSupport.onlineMock(helper, session);
            ServerPlayer op = PlotGameSupport.mock(helper);
            CommandSourceStack opSource = PlotGameSupport.player(server, op, true);
            helper.assertValueEqual(PlotCommands.assign(opSource, online.getGameProfile().name(), 3), 1, "assign an online player");
            PlotRegistry registry = (PlotRegistry) Plots.directory();
            helper.assertValueEqual(registry.byIndex(3).owner(), StudioId.of(online.getUUID()), "the online uuid is stored");
            helper.succeed();
        } finally {
            PlotGameSupport.close(helper, session);
        }
    }

    @GameTest
    public void frozenRegistryRefusesAssignAndFree(GameTestHelper helper) throws Exception {
        var session = PlotGameSupport.open(helper, PlotGameSupport.ENABLED);
        try {
            MinecraftServer server = helper.getLevel().getServer();
            ServerPlayer owner = PlotGameSupport.mock(helper);
            helper.assertTrue(PlotFeature.allocate(server, owner.getUUID()), "the owner has plot 0");
            ServerPlayer op = PlotGameSupport.mock(helper);
            CommandSourceStack opSource = PlotGameSupport.player(server, op, true);
            UUID target = UUID.randomUUID();
            server.services().nameToIdCache().add(new NameAndId(target, "FrozenTarget"));
            PlotRegistry registry = (PlotRegistry) Plots.directory();
            registry.freeze();
            int size = registry.all().size();
            long revision = Anchors.forStudio(StudioId.of(owner.getUUID())).revision();
            byte[] before = plotsFileBytes(server);
            try (var capture = MpLog.capture()) {
                helper.assertValueEqual(PlotCommands.assign(opSource, "FrozenTarget", 1), 0, "assign on a frozen registry");
                helper.assertValueEqual(PlotCommands.free(opSource, 0), 0, "free on a frozen registry");
                helper.assertTrue(capture.lines().contains(
                    "event=plot_command player=" + op.getUUID() + " studio=" + target
                        + " plot=1 command=assign target_plot=1 ok=false"), "the frozen assign line: " + capture.lines());
                helper.assertTrue(capture.lines().contains(
                    "event=plot_command player=" + op.getUUID() + " studio=" + owner.getUUID()
                        + " plot=0 command=free target_plot=0 ok=false"), "the frozen free line: " + capture.lines());
            }
            helper.assertValueEqual(registry.all().size(), size, "registry size unchanged");
            helper.assertValueEqual(Anchors.forStudio(StudioId.of(owner.getUUID())).revision(), revision, "published revision unchanged");
            helper.assertTrue(java.util.Arrays.equals(before, plotsFileBytes(server)), "plots.json unchanged");
            helper.succeed();
        } finally {
            PlotGameSupport.close(helper, session);
        }
    }

    private static byte[] plotsFileBytes(MinecraftServer server) throws Exception {
        Path file = PlotStore.plotsFile(PlotFeature.worldRoot(server));
        return Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
    }

    private static void publishSpawn(StudioId studio, double x, double y, double z) {
        Anchors.publish(studio, new Anchors.Layout("studio", 5, null, java.util.Map.of(AnchorNames.SPAWN, new Anchor(AnchorNames.SPAWN, x, y, z, 0, 0))));
    }

    private static int run(com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher, String command, CommandSourceStack source) {
        try {
            return dispatcher.execute(command, source);
        } catch (CommandSyntaxException e) {
            return 0;
        }
    }
}
