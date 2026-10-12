package dev.agentcraft.mp.server.plot;

import dev.agentcraft.AgentCraft;
import dev.agentcraft.command.AgentCraftCommands;
import dev.agentcraft.hq.HqBuilders;
import dev.agentcraft.hq.HqFeature;
import dev.agentcraft.layout.Anchors;
import dev.agentcraft.mp.MpServerConfig;
import dev.agentcraft.mp.Plot;
import dev.agentcraft.mp.PlotDirectory;
import dev.agentcraft.mp.Plots;
import dev.agentcraft.mp.StudioId;
import dev.agentcraft.mp.server.StudioRange;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Loads the plot registry on a dedicated server with multiplayer enabled, allocates on play init
 * (before the join hello), and builds one queued plot per later tick.
 */
public final class PlotFeature {
    private record Pending(int index, int readyTick) {}

    private static final ArrayDeque<Pending> BUILDS = new ArrayDeque<>();
    private static final ArrayDeque<UUID> SPAWNS = new ArrayDeque<>();
    /** Players whose plot was allocated in this session and who have not been moved in yet. */
    private static final Set<UUID> JUST_ALLOCATED = new HashSet<>();

    private PlotFeature() {}

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(PlotFeature::start);
        ServerLifecycleEvents.SERVER_STOPPED.register(PlotFeature::onStopped);
        // INIT runs from the play-listener constructor, before JOIN. The uuid is the connection's player.
        ServerPlayConnectionEvents.INIT.register((handler, server) -> {
            ServerPlayer player = handler.getPlayer();
            if (player != null) allocate(server, player.getUUID());
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            if (player != null) onJoin(server, player.getUUID());
        });
        ServerTickEvents.END_SERVER_TICK.register(PlotFeature::tick);
        AgentCraftCommands.sub(PlotCommands::register);
    }

    /** Dedicated and enabled, read at call time. Singleplayer never enters this. */
    public static boolean multiplayer(MinecraftServer server) {
        return multiplayer(server.isDedicatedServer(), MpServerConfig.current().enabled());
    }

    public static boolean multiplayer(boolean dedicated, boolean enabled) {
        return dedicated && enabled;
    }

    public static Path worldRoot(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT);
    }

    /** Records that {@code playerId} joined; the spawn is applied one player per tick. */
    public static void onJoin(MinecraftServer server, UUID playerId) {
        if (!multiplayer(server) || playerId == null) return;
        SPAWNS.add(playerId);
    }

    /**
     * Makes the registry row for {@code playerId} when {@code autoAllocate} is on. The build is
     * queued, never run here. A player who already owns a plot is not allocated again, but a plot
     * whose build never ran is queued so it is not lost for good.
     */
    public static boolean allocate(MinecraftServer server, UUID playerId) {
        if (!multiplayer(server) || playerId == null) return false;
        PlotRegistry registry = registry();
        if (registry == null) return false;
        StudioId studio = StudioId.of(playerId);
        Plot existing = registry.plotOf(studio).orElse(null);
        if (existing != null) {
            queueBuildIfUnbuilt(server, existing);
            return false;
        }
        if (!MpServerConfig.current().autoAllocate()) return false;
        var result = registry.allocateStored(worldRoot(server), studio, MpServerConfig.current().plotStride());
        if (!result.created() || result.plot() == null) return false;
        JUST_ALLOCATED.add(playerId);
        StudioRange.refresh(server);
        queueBuildIfUnbuilt(server, result.plot());
        return true;
    }

    private static void queueBuildIfUnbuilt(MinecraftServer server, Plot plot) {
        if (MpServerConfig.current().autoBuild() && Anchors.forStudio(plot.owner()).isEmpty()) {
            enqueueBuild(server, plot.index());
        }
    }

    public static void enqueueBuild(MinecraftServer server, int index) {
        if (!multiplayer(server) || !MpServerConfig.current().autoBuild()) return;
        for (Pending pending : BUILDS) {
            if (pending.index == index) return;
        }
        // Two ticks out so this tick can finish the join hello before the build runs.
        BUILDS.add(new Pending(index, server.getTickCount() + 2));
    }

    public static boolean buildPending(int index) {
        for (Pending pending : BUILDS) {
            if (pending.index == index) return true;
        }
        return false;
    }

    public static void resetPending() {
        BUILDS.clear();
        SPAWNS.clear();
        JUST_ALLOCATED.clear();
    }

    /** Publishes {@code layout} for {@code studio} and stores that plot's anchors. Does not move the world spawn. */
    public static Anchors.Layout publishBuilt(MinecraftServer server, StudioId studio, Anchors.Layout layout) {
        Anchors.Layout previous = Anchors.forStudio(studio);
        Anchors.Layout published = new Anchors.Layout(layout.name(), previous.revision() + 1, layout.bounds(), layout.anchors());
        Anchors.publish(studio, published);
        PlotRegistry registry = registry();
        if (registry != null) {
            Plot plot = registry.plotOf(studio).orElse(null);
            if (plot != null && !PlotStore.saveAnchors(worldRoot(server), plot.index(), published)) {
                AgentCraft.LOGGER.warn("Could not save anchors for plot {}", plot.index());
            }
        }
        return published;
    }

    /**
     * Loads the registry, publishes the layouts that exist and queues the plots that were never built.
     * Fails closed: on a registry file it cannot trust it throws, which stops the server before its
     * first tick, so nobody joins a world whose plots have no owner. Nothing is installed or written.
     */
    public static void start(MinecraftServer server) {
        if (!multiplayer(server)) return;
        PlotStore.Loaded loaded = PlotStore.load(worldRoot(server));
        String refusal = PlotStore.startRefusal(loaded, MpServerConfig.current().plotStride()).orElse(null);
        if (refusal != null) {
            // The frozen mp catalog has no event for the registry file, so this is a plain log line.
            AgentCraft.LOGGER.error("Refusing to start with multiplayer enabled: {}. agentcraft/plots.json in the world folder was left as it is.", refusal);
            throw new IllegalStateException("AgentCraft multiplayer cannot start: " + refusal);
        }
        PlotRegistry registry = new PlotRegistry();
        registry.replaceAll(loaded.plots());
        for (Plot plot : registry.all()) {
            PlotStore.loadAnchors(worldRoot(server), plot.index()).ifPresent(layout -> Anchors.publish(plot.owner(), layout));
        }
        Plots.install(registry);
        // A server that stopped between an allocation and its queued build leaves a plot without a
        // layout. Resume those builds now, without allocating or logging an allocation again.
        for (Plot plot : registry.all()) {
            if (Anchors.forStudio(plot.owner()).isEmpty()) enqueueBuild(server, plot.index());
        }
    }

    private static void onStopped(MinecraftServer server) {
        resetPending();
        PlotRebuilds.clear();
        if (Plots.directory() instanceof PlotRegistry registry) {
            for (Plot plot : registry.all()) Anchors.remove(plot.owner());
        }
        Plots.install(Plots.SINGLEPLAYER);
    }

    /** One queued build or one queued spawn per tick. */
    public static void tick(MinecraftServer server) {
        if (!multiplayer(server)) {
            resetPending();
            return;
        }
        if (!BUILDS.isEmpty() && server.getTickCount() >= BUILDS.peek().readyTick) {
            buildOne(server, BUILDS.remove().index());
            return;
        }
        if (SPAWNS.isEmpty()) return;
        UUID playerId = SPAWNS.remove();
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player != null) placeSpawn(server, player);
    }

    private static void buildOne(MinecraftServer server, int index) {
        PlotRegistry registry = registry();
        if (registry == null) return;
        Plot plot = registry.byIndex(index);
        if (plot == null || !Anchors.forStudio(plot.owner()).isEmpty()) return;
        var builder = HqBuilders.get(HqBuilders.defaultId());
        if (builder == null) return;
        UUID ownerId = plot.owner().owner();
        // This attempt is over, whether it builds or throws: never teleport this player on a later join.
        JUST_ALLOCATED.remove(ownerId);
        try {
            Anchors.Layout layout = HqFeature.buildPlot(server.overworld(), builder, false, plot.origin(), plot.owner());
            ServerPlayer owner = server.getPlayerList().getPlayer(ownerId);
            if (owner != null) {
                PlotSpawn.applyRespawn(owner, layout);
                // First build while the owner is online: move them in.
                PlotSpawn.teleportHome(owner, layout);
            }
        } catch (RuntimeException e) {
            AgentCraft.LOGGER.error("Plot {} failed to build", index, e);
        }
    }

    /**
     * Sets the owner's respawn point. Only a plot allocated by this join, or one whose first build
     * is still to finish, teleports; a returning player with a layout stays where they logged out.
     */
    public static void placeSpawn(MinecraftServer server, ServerPlayer player) {
        UUID playerId = player.getUUID();
        PlotRegistry registry = registry();
        if (registry == null) return;
        Plot plot = registry.plotOf(StudioId.of(playerId)).orElse(null);
        if (plot == null) return;
        Anchors.Layout layout = Anchors.forStudio(plot.owner());
        if (layout.isEmpty()) {
            if (buildPending(plot.index())) SPAWNS.addFirst(playerId);
            else JUST_ALLOCATED.remove(playerId);
            return;
        }
        PlotSpawn.applyRespawn(player, layout);
        if (JUST_ALLOCATED.remove(playerId)) PlotSpawn.teleportHome(player, layout);
    }

    private static PlotRegistry registry() {
        PlotDirectory directory = Plots.directory();
        return directory instanceof PlotRegistry registry ? registry : null;
    }
}
