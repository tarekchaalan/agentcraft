package dev.agentcraft.mp.server.plot;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.agentcraft.AgentCraft;
import dev.agentcraft.layout.Anchors;
import dev.agentcraft.mp.MpEvents;
import dev.agentcraft.mp.MpLog;
import dev.agentcraft.mp.MpServerConfig;
import dev.agentcraft.mp.Plot;
import dev.agentcraft.mp.Plots;
import dev.agentcraft.mp.StudioId;
import dev.agentcraft.mp.server.StudioRange;
import java.lang.reflect.Field;
import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.UserNameToIdResolver;
import net.minecraft.util.StringUtil;
import org.jspecify.annotations.Nullable;

/**
 * {@code /agentcraft plot}. The acting player is always {@link CommandSourceStack#getPlayer()}.
 * {@code assign}'s name is a target, and only when that name is already online or already in the
 * profile cache. {@code UserNameToIdResolver#get(String)} is not used: on a miss it resolves a new
 * offline or network profile.
 */
public final class PlotCommands {
    static final String INFO = "info";
    static final String HOME = "home";
    static final String REBUILD = "rebuild";
    static final String LIST = "list";
    static final String ASSIGN = "assign";
    static final String FREE = "free";

    private PlotCommands() {}

    public static void register(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("plot")
            // Null-server sources are the packet builder's no-permission probe; the real filter already
            // ran with the player's own source. Do not mark the node restricted there.
            .requires(src -> src.getServer() == null || PlotFeature.multiplayer(src.getServer()))
            .then(Commands.literal(INFO).executes(ctx -> info(ctx.getSource())))
            .then(Commands.literal(HOME).executes(ctx -> home(ctx.getSource())))
            .then(Commands.literal(REBUILD)
                .executes(ctx -> rebuild(ctx.getSource(), 0, false, false))
                .then(Commands.literal("force").executes(ctx -> rebuild(ctx.getSource(), 0, false, true)))
                .then(Commands.argument("index", IntegerArgumentType.integer(0)).requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .executes(ctx -> rebuild(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "index"), true, false))
                    .then(Commands.literal("force").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(ctx -> rebuild(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "index"), true, true)))))
            .then(Commands.literal(LIST).requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(ctx -> list(ctx.getSource())))
            .then(Commands.literal(ASSIGN).requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.argument("player", StringArgumentType.word())
                    .then(Commands.argument("index", IntegerArgumentType.integer(0))
                        .executes(ctx -> assign(ctx.getSource(), StringArgumentType.getString(ctx, "player"),
                            IntegerArgumentType.getInteger(ctx, "index"))))))
            .then(Commands.literal(FREE).requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.argument("index", IntegerArgumentType.integer(0))
                    .executes(ctx -> free(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "index"))))));
    }

    public static int info(CommandSourceStack source) {
        if (!open(source)) return 0;
        ServerPlayer actor = source.getPlayer();
        Plot plot = own(source);
        if (actor == null || plot == null) return refuse(source, INFO, -1, null, "You have no plot.");
        source.sendSuccess(() -> Component.literal("Plot " + plot.index() + " at " + plot.origin().getX() + " "
            + plot.origin().getY() + " " + plot.origin().getZ()), false);
        log(source, INFO, plot.index(), true, plot.owner().owner());
        return plot.index() + 1;
    }

    public static int home(CommandSourceStack source) {
        if (!open(source)) return 0;
        ServerPlayer actor = source.getPlayer();
        Plot plot = own(source);
        if (actor == null || plot == null) return refuse(source, HOME, -1, null, "You have no plot.");
        Anchors.Layout layout = Anchors.forStudio(plot.owner());
        if (!PlotSpawn.teleportHome(actor, layout)) return refuse(source, HOME, plot.index(), plot.owner().owner(), "This plot has no spawn yet.");
        PlotSpawn.applyRespawn(actor, layout);
        log(source, HOME, plot.index(), true, plot.owner().owner());
        source.sendSuccess(() -> Component.literal("Teleported to plot " + plot.index()), false);
        return 1;
    }

    /** {@code hasIndex} is the ops-only form. Without it, the caller's own plot is rebuilt. */
    public static int rebuild(CommandSourceStack source, int index, boolean hasIndex, boolean force) {
        if (!open(source)) return 0;
        PlotRegistry registry = registry();
        if (registry == null) return refuse(source, REBUILD, hasIndex ? index : -1, null, "Plots are not available.");
        Plot plot;
        if (hasIndex) {
            if (!op(source)) return refuse(source, REBUILD, index, null, "Only operators can rebuild another plot.");
            plot = registry.byIndex(index);
            if (plot == null) return refuse(source, REBUILD, index, null, "That plot does not exist.");
        } else {
            plot = own(source);
            if (plot == null) return refuse(source, REBUILD, -1, null, "You have no plot.");
        }
        var builder = dev.agentcraft.hq.HqBuilders.get(dev.agentcraft.hq.HqBuilders.defaultId());
        if (builder == null) return refuse(source, REBUILD, plot.index(), plot.owner().owner(), "No HQ builder is registered.");
        ServerPlayer actor = source.getPlayer();
        boolean limited = !op(source) && actor != null;
        int now = source.getServer().getTickCount();
        if (limited && !PlotRebuilds.allowed(actor.getUUID(), now)) {
            int seconds = PlotRebuilds.remainingSeconds(actor.getUUID(), now);
            return refuse(source, REBUILD, plot.index(), plot.owner().owner(),
                "You can rebuild again in " + seconds + " seconds.");
        }
        Anchors.Layout layout;
        try {
            // Plots always live in the overworld in multiplayer, wherever the caller stands.
            layout = dev.agentcraft.hq.HqFeature.buildPlot(source.getServer().overworld(), builder, force, plot.origin(), plot.owner());
        } catch (RuntimeException e) {
            AgentCraft.LOGGER.error("Plot {} failed to rebuild", plot.index(), e);
            return refuse(source, REBUILD, plot.index(), plot.owner().owner(), "Rebuild failed.");
        }
        // The window starts only once the build has succeeded: a build that threw must not lock the
        // player out of the retry.
        if (limited) PlotRebuilds.note(actor.getUUID(), now);
        ServerPlayer owner = source.getServer().getPlayerList().getPlayer(plot.owner().owner());
        if (owner != null) PlotSpawn.applyRespawn(owner, layout);
        else if (source.getPlayer() != null && plot.owner().equals(StudioId.of(source.getPlayer().getUUID()))) {
            PlotSpawn.applyRespawn(source.getPlayer(), layout);
        }
        log(source, REBUILD, plot.index(), true, plot.owner().owner());
        int anchors = layout.anchors().size();
        source.sendSuccess(() -> Component.literal("Rebuilt plot " + plot.index() + ": " + anchors + " anchors"), true);
        return anchors;
    }

    public static int list(CommandSourceStack source) {
        if (!open(source)) return 0;
        if (!op(source)) return refuse(source, LIST, -1, null, "Only operators can list plots.");
        PlotRegistry registry = registry();
        if (registry == null) return refuse(source, LIST, -1, null, "Plots are not available.");
        source.sendSuccess(() -> Component.literal(registry.all().size() + " plots"), false);
        for (Plot plot : registry.all()) {
            source.sendSuccess(() -> Component.literal(plot.index() + " " + plot.owner().owner() + " "
                + plot.origin().getX() + " " + plot.origin().getY() + " " + plot.origin().getZ()), false);
        }
        log(source, LIST, -1, true, null);
        return 1;
    }

    public static int assign(CommandSourceStack source, String name, int index) {
        if (!open(source)) return 0;
        if (!op(source)) return refuse(source, ASSIGN, index, null, "Only operators can assign plots.");
        PlotRegistry registry = registry();
        if (registry == null) return refuse(source, ASSIGN, index, null, "Plots are not available.");
        if (!StringUtil.isValidPlayerName(name)) return refuse(source, ASSIGN, index, null, "Unknown player.");
        UUID target = resolveTarget(source.getServer(), name);
        if (target == null) return refuse(source, ASSIGN, index, null, "Unknown player.");
        StudioId studio = StudioId.of(target);
        var result = registry.assignStored(PlotFeature.worldRoot(source.getServer()), studio, index, MpServerConfig.current().plotStride());
        if (result.plot() == null) {
            PlotRegistry.Refuse refuse = result.refuse();
            String message = refuse == null || refuse == PlotRegistry.Refuse.IO
                ? "Could not save the plot."
                : switch (refuse) {
                    case HAS_PLOT -> "That player already has a plot.";
                    case TAKEN -> "That plot is taken.";
                    case BAD_INDEX, OVERLAP, LOCAL -> "That plot index is not available.";
                    case IO -> "Could not save the plot.";
                };
            return refuse(source, ASSIGN, index, target, message);
        }
        Anchors.remove(studio);
        PlotFeature.enqueueBuild(source.getServer(), result.plot().index());
        StudioRange.refresh(source.getServer());
        log(source, ASSIGN, index, true, target);
        source.sendSuccess(() -> Component.literal("Assigned plot " + index), true);
        return 1;
    }

    public static int free(CommandSourceStack source, int index) {
        if (!open(source)) return 0;
        if (!op(source)) return refuse(source, FREE, index, null, "Only operators can free plots.");
        PlotRegistry registry = registry();
        if (registry == null) return refuse(source, FREE, index, null, "Plots are not available.");
        Plot plot = registry.byIndex(index);
        if (plot == null) return refuse(source, FREE, index, null, "That plot does not exist.");
        UUID owner = plot.owner().owner();
        if (!release(PlotFeature.worldRoot(source.getServer()), registry, index)) return refuse(source, FREE, index, owner, "Could not free that plot.");
        StudioRange.refresh(source.getServer());
        log(source, FREE, index, true, owner);
        source.sendSuccess(() -> Component.literal("Freed plot " + index), true);
        return 1;
    }

    /**
     * A name already stored on this resolver, matched without resolving a new profile.
     * The game-test server keeps names in {@code savedIds}. A dedicated server keeps them in
     * {@code profilesByName}. Anything else is a miss.
     */
    public static Optional<UUID> knownCached(UserNameToIdResolver cache, String name) {
        if (cache == null || name == null) return Optional.empty();
        try {
            Field saved = cache.getClass().getDeclaredField("savedIds");
            saved.setAccessible(true);
            if (saved.get(cache) instanceof Set<?> ids) {
                for (Object id : ids) {
                    if (id instanceof NameAndId named && named.name().equalsIgnoreCase(name)) return Optional.of(named.id());
                }
                return Optional.empty();
            }
        } catch (NoSuchFieldException ignored) {
            // Dedicated servers use the file-backed cache below.
        } catch (IllegalAccessException | InaccessibleObjectException e) {
            return Optional.empty();
        }
        try {
            Field profiles = cache.getClass().getDeclaredField("profilesByName");
            profiles.setAccessible(true);
            if (!(profiles.get(cache) instanceof Map<?, ?> map)) return Optional.empty();
            Object info = map.get(name.toLowerCase(Locale.ROOT));
            if (info == null) return Optional.empty();
            Method expiration = info.getClass().getMethod("expirationDate");
            Method nameAndId = info.getClass().getMethod("nameAndId");
            expiration.setAccessible(true);
            nameAndId.setAccessible(true);
            if (expiration.invoke(info) instanceof Date expires && System.currentTimeMillis() >= expires.getTime()) return Optional.empty();
            if (nameAndId.invoke(info) instanceof NameAndId named) return Optional.of(named.id());
        } catch (ReflectiveOperationException e) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    /** File operations of {@link #release}. A seam so a test can drive the rollback-save failure. */
    interface ReleaseIo {
        boolean save(Path root, Collection<Plot> plots);
        boolean delete(Path root, int index);
    }

    private static final ReleaseIo REAL_IO = new ReleaseIo() {
        @Override
        public boolean save(Path root, Collection<Plot> plots) {
            return PlotStore.save(root, plots);
        }

        @Override
        public boolean delete(Path root, int index) {
            return PlotStore.deletePlotDirectory(root, index);
        }
    };

    static boolean release(Path root, PlotRegistry registry, int index) {
        return release(root, registry, index, REAL_IO);
    }

    static boolean release(Path root, PlotRegistry registry, int index, ReleaseIo io) {
        Plot plot = registry.byIndex(index);
        if (plot == null || registry.frozen()) return false;
        registry.take(index);
        if (!io.save(root, registry.all())) {
            registry.restore(plot);
            return false;
        }
        if (!io.delete(root, index)) {
            registry.restore(plot);
            if (!io.save(root, registry.all())) {
                // Memory says the plot is owned, the file says it is free. Freeze so nothing else
                // writes over the file and nothing is allocated until an operator looks.
                registry.freeze();
                AgentCraft.LOGGER.error("Could not rewrite the plot registry after failing to free plot {}", index);
            }
            return false;
        }
        Anchors.remove(plot.owner());
        return true;
    }

    private static @Nullable UUID resolveTarget(MinecraftServer server, String name) {
        ServerPlayer online = server.getPlayerList().getPlayerByName(name);
        if (online != null) return online.getUUID();
        return knownCached(server.services().nameToIdCache(), name).orElse(null);
    }

    private static boolean op(CommandSourceStack source) {
        return source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    /** Singleplayer and a disabled server do not run plot commands and do not log them. */
    private static boolean open(CommandSourceStack source) {
        return PlotFeature.multiplayer(source.getServer());
    }

    private static @Nullable Plot own(CommandSourceStack source) {
        ServerPlayer actor = source.getPlayer();
        PlotRegistry registry = registry();
        if (actor == null || registry == null) return null;
        return registry.plotOf(StudioId.of(actor.getUUID())).orElse(null);
    }

    private static @Nullable PlotRegistry registry() {
        return Plots.directory() instanceof PlotRegistry registry ? registry : null;
    }

    private static int refuse(CommandSourceStack source, String command, int targetPlot, @Nullable UUID studio, String message) {
        source.sendFailure(Component.literal(message));
        log(source, command, targetPlot, false, studio);
        return 0;
    }

    private static void log(CommandSourceStack source, String command, int targetPlot, boolean ok, @Nullable UUID studio) {
        if (!PlotFeature.multiplayer(source.getServer())) return;
        int count = 6 + (source.getPlayer() == null ? 0 : 2) + (studio == null ? 0 : 2) + (targetPlot < 0 ? 0 : 2);
        Object[] fields = new Object[count];
        int i = 0;
        ServerPlayer actor = source.getPlayer();
        if (actor != null) {
            fields[i++] = "player";
            fields[i++] = actor.getUUID();
        }
        if (studio != null) {
            fields[i++] = "studio";
            fields[i++] = studio;
        }
        if (targetPlot >= 0) {
            fields[i++] = "plot";
            fields[i++] = targetPlot;
        }
        fields[i++] = "command";
        fields[i++] = command;
        fields[i++] = "target_plot";
        fields[i++] = targetPlot;
        fields[i++] = "ok";
        fields[i] = ok;
        MpLog.event(MpEvents.PLOT_COMMAND, fields);
    }
}
