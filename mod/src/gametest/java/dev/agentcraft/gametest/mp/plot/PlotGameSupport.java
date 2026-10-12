package dev.agentcraft.gametest.mp.plot;

import com.mojang.authlib.GameProfile;
import dev.agentcraft.layout.Anchors;
import dev.agentcraft.mp.MpServerConfig;
import dev.agentcraft.mp.PlotDirectory;
import dev.agentcraft.mp.Plots;
import dev.agentcraft.mp.server.plot.PlotFeature;
import dev.agentcraft.mp.server.plot.PlotRebuilds;
import dev.agentcraft.mp.server.plot.PlotRegistry;
import dev.agentcraft.mp.server.plot.PlotStore;
import io.netty.channel.embedded.EmbeddedChannel;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelData;

/**
 * Installs a plot registry for one server-thread test and puts the shared world back.
 * The game-test server has already started, so this does the load a dedicated server does at start.
 *
 * <p>The studio a test builds at plot 0 may stay in the world: the foundation's smoke test builds
 * the same studio there. Everything else this class touched is restored in {@link #close}, whose
 * state restores run in an unconditional {@code finally} so a file-cleanup failure cannot leave a
 * test config or registry installed.
 */
final class PlotGameSupport {
    static final MpServerConfig ENABLED = new MpServerConfig(true, 128, true, false, true, true, true, 12, 4, 10);
    /** Multiplayer with {@code autoBuild} on, for the queued-build lifecycle tests. */
    static final MpServerConfig BUILDING = new MpServerConfig(true, 128, true, true, true, true, true, 12, 4, 10);
    /** Multiplayer with {@code autoAllocate} off, so an online mock can join without a plot. */
    static final MpServerConfig NO_AUTO = new MpServerConfig(true, 128, false, false, true, true, true, 12, 4, 10);

    /** A mock player placed with a connection, tracked so {@link #close} can remove it again. */
    record Online(ServerPlayer player, EmbeddedChannel channel) {}

    record Session(MpServerConfig config, PlotDirectory directory, LevelData.RespawnData spawn,
                   byte[] plotsFile, Map<Path, byte[]> plotsFiles, Set<Path> plotsDirs,
                   Map<java.util.UUID, Integer> rebuilds, List<Online> online) {}

    private PlotGameSupport() {}

    static Session open(GameTestHelper helper, MpServerConfig config) {
        MinecraftServer server = helper.getLevel().getServer();
        MpServerConfig previousConfig = MpServerConfig.install(config);
        PlotDirectory previousDirectory = Plots.directory();
        try {
            LevelData.RespawnData spawn = server.getWorldData().overworldData().getRespawnData();
            Path root = PlotFeature.worldRoot(server);
            Path file = PlotStore.plotsFile(root);
            Path plots = root.resolve("agentcraft").resolve("plots");
            byte[] bytes = Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
            Map<Path, byte[]> files = new HashMap<>();
            Set<Path> dirs = new HashSet<>();
            if (Files.isDirectory(plots)) {
                try (var walk = Files.walk(plots)) {
                    for (Path path : walk.toList()) {
                        if (Files.isDirectory(path)) dirs.add(path);
                        else if (Files.isRegularFile(path)) files.put(path, Files.readAllBytes(path));
                    }
                }
            }
            // Remember the shared bookkeeping before clearing it, so close() can put it back.
            var rebuilds = PlotRebuilds.snapshot();
            var online = new ArrayList<Online>();
            // Start every test from clean, shared bookkeeping.
            PlotFeature.resetPending();
            PlotRebuilds.clear();
            Plots.install(new PlotRegistry());
            return new Session(previousConfig, previousDirectory, spawn, bytes, files, dirs, rebuilds, online);
        } catch (IOException | UncheckedIOException e) {
            // A Files.walk read failure throws UncheckedIOException from the stream; both paths must
            // put the previous config and directory back before the test sees the failure.
            MpServerConfig.install(previousConfig);
            Plots.install(previousDirectory);
            if (e instanceof UncheckedIOException unchecked) throw unchecked;
            throw new UncheckedIOException((IOException) e);
        }
    }

    static void close(GameTestHelper helper, Session session) {
        MinecraftServer server = helper.getLevel().getServer();
        Path root = PlotFeature.worldRoot(server);
        Path file = PlotStore.plotsFile(root);
        Path plots = root.resolve("agentcraft").resolve("plots");
        try {
            // Restore the plots directory to exactly what open() found, then the registry file.
            if (Files.exists(plots)) {
                try (var walk = Files.walk(plots)) {
                    for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                }
            }
            for (Path dir : session.plotsDirs().stream().sorted().toList()) Files.createDirectories(dir);
            for (Map.Entry<Path, byte[]> entry : session.plotsFiles().entrySet()) {
                Files.createDirectories(entry.getKey().getParent());
                Files.write(entry.getKey(), entry.getValue());
            }
            if (session.plotsFile() == null) Files.deleteIfExists(file);
            else {
                Files.createDirectories(file.getParent());
                Files.write(file, session.plotsFile());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            // Always put the shared state back, even when the file cleanup above failed.
            for (Online online : session.online()) disposeOnlineMock(server, online);
            PlotFeature.resetPending();
            PlotRebuilds.install(session.rebuilds());
            if (Plots.directory() instanceof PlotRegistry registry) {
                for (dev.agentcraft.mp.Plot plot : registry.all()) Anchors.remove(plot.owner());
            }
            Plots.install(session.directory());
            MpServerConfig.install(session.config());
            LevelData.RespawnData now = server.getWorldData().overworldData().getRespawnData();
            if (!now.equals(session.spawn())) server.getWorldData().overworldData().setSpawn(session.spawn());
        }
    }

    /** Removes a mock the test placed into the shared player list and disposes its embedded channel. */
    private static void disposeOnlineMock(MinecraftServer server, Online online) {
        ServerPlayer player = online.player();
        try {
            if (player.connection != null) player.connection.disconnect(Component.literal("game test over"));
        } catch (RuntimeException ignored) {
            // The player is already gone; fall through to the hard cleanup.
        }
        try {
            if (server.getPlayerList().getPlayer(player.getUUID()) == player) server.getPlayerList().remove(player);
        } catch (RuntimeException ignored) {
        }
        try {
            online.channel().close();
        } catch (RuntimeException ignored) {
        }
        player.discard();
    }

    static ServerPlayer mock(GameTestHelper helper) {
        return (ServerPlayer) helper.makeMockServerPlayer(GameType.CREATIVE);
    }

    /** A mock player in {@code level}, for the dimension rules. Has no connection. */
    static ServerPlayer mockIn(MinecraftServer server, ServerLevel level) {
        return new ServerPlayer(server, level, new GameProfile(java.util.UUID.randomUUID(), "test-mock-player"), ClientInformation.createDefault()) {
            @Override
            public GameType gameMode() {
                return GameType.CREATIVE;
            }

            @Override
            public boolean isClientAuthoritative() {
                return false;
            }
        };
    }

    /**
     * A mock player with a connection, placed in the player list so the join tick resolves it by uuid.
     * Mirrors the deprecated {@code GameTestHelper#makeMockServerPlayerInLevel} without the warning.
     * Tracked on {@code session} so {@link #close} removes it from the shared server and disposes it.
     */
    static ServerPlayer onlineMock(GameTestHelper helper, Session session) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(
            new GameProfile(java.util.UUID.randomUUID(), "mock" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 6)), false);
        ServerPlayer player = new ServerPlayer(server, level, cookie.gameProfile(), cookie.clientInformation()) {
            @Override
            public GameType gameMode() {
                return GameType.CREATIVE;
            }
        };
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        EmbeddedChannel channel = new EmbeddedChannel(connection);
        session.online().add(new Online(player, channel));
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
        return player;
    }

    static net.minecraft.commands.CommandSourceStack player(MinecraftServer server, ServerPlayer player, boolean op) {
        return server.createCommandSourceStack().withEntity(player).withSuppressedOutput()
            .withPermission(op ? PermissionSet.ALL_PERMISSIONS : PermissionSet.NO_PERMISSIONS);
    }
}
