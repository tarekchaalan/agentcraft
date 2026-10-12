package dev.agentcraft.hq;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.agentcraft.AgentCraft;
import dev.agentcraft.command.AgentCraftCommands;
import dev.agentcraft.layout.Anchor;
import dev.agentcraft.layout.AnchorNames;
import dev.agentcraft.layout.Anchors;
import dev.agentcraft.mp.Plot;
import dev.agentcraft.mp.Plots;
import dev.agentcraft.mp.StudioId;
import dev.agentcraft.mp.server.plot.PlotFeature;
import dev.agentcraft.mp.server.plot.PlotSpawn;
import dev.agentcraft.world.HqWorld;
import java.util.Locale;
import java.util.Optional;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * HQ feature (common side): {@code /agentcraft hq [builder] [force]} builds the HQ and publishes its
 * anchors. The studio builder keeps cells the player changed since its last build; {@code force}
 * resets them too.
 */
public final class HqFeature {
	private HqFeature() {
	}

	/** The report of the last build (for QA: {@code dev.state.hq.lastBuild}). */
	private static volatile @Nullable String lastReport;

	public static @Nullable String lastReport() {
		return lastReport;
	}

	public static void init() {
		HqBuilders.register(new TestRoomBuilder());
		HqBuilders.register(new StudioHqBuilder());
		HqBuilders.setDefault(StudioHqBuilder.ID);
		// A fresh HQ world (no saved layout yet) gets the default HQ built before the player joins, so
		// the first launch walks straight into it. AGENTCRAFT_HQ_AUTOBUILD=0 turns this off.
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			// PlotFeature owns multiplayer builds. This path would paint a LOCAL studio at the origin.
			if (PlotFeature.multiplayer(server)) {
				return;
			}
			if (!HqWorld.isHq(server) || !Anchors.current().isEmpty() || !autoBuild()) {
				return;
			}
			HqBuilder builder = HqBuilders.get(HqBuilders.defaultId());
			if (builder != null) {
				try {
					buildAndPublish(server.overworld(), builder, HqBuilder.Options.DEFAULT);
					AgentCraft.LOGGER.info("Fresh HQ world: built the default HQ '{}'", builder.id());
				} catch (RuntimeException e) {
					AgentCraft.LOGGER.error("Auto-building the HQ failed (run /agentcraft hq)", e);
				}
			}
		});
		AgentCraftCommands.sub(root -> root.then(Commands.literal("hq")
			.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
			.executes(ctx -> build(ctx, HqBuilders.defaultId(), false))
			.then(Commands.literal("force").executes(ctx -> build(ctx, HqBuilders.defaultId(), true)))
			.then(Commands.argument("builder", StringArgumentType.word())
				.suggests((ctx, b) -> {
					HqBuilders.ids().forEach(b::suggest);
					return b.buildFuture();
				})
				.executes(ctx -> build(ctx, StringArgumentType.getString(ctx, "builder"), false))
				.then(Commands.literal("force").executes(ctx -> build(ctx, StringArgumentType.getString(ctx, "builder"), true))))));
	}

	private static boolean autoBuild() {
		String v = System.getProperty("agentcraft.hq.autobuild");
		if (v == null) {
			v = System.getenv("AGENTCRAFT_HQ_AUTOBUILD");
		}
		return v == null || !(v.trim().equals("0") || v.trim().equalsIgnoreCase("false") || v.trim().equalsIgnoreCase("off"));
	}

	/**
	 * Building rewrites a large area of terrain and moves the world spawn, so it only runs in the
	 * AgentCraft HQ world (or, on a multiplayer server, the caller's own plot) unless AGENTCRAFT_HQ_ANYWORLD=1
	 * (-Dagentcraft.hq.anyworld=1) opts in, and
	 * never in a Hardcore world.
	 */
	private static @Nullable String refusal(CommandSourceStack source) {
		var server = source.getServer();
		if (server.getWorldData().isHardcore()) {
			return "/agentcraft hq never builds in a Hardcore world (it rewrites terrain and moves the spawn)";
		}
		if (!HqWorld.isHq(server) && !PlotFeature.multiplayer(server) && !anyWorld()) {
			return "/agentcraft hq only builds in the \"" + HqWorld.LEVEL_NAME + "\" world; to build into this world on purpose,"
				+ " back it up and start the game with -Dagentcraft.hq.anyworld=1";
		}
		return null;
	}

	private static boolean anyWorld() {
		String v = System.getProperty("agentcraft.hq.anyworld");
		if (v == null) {
			v = System.getenv("AGENTCRAFT_HQ_ANYWORLD");
		}
		return v != null && (v.trim().equals("1") || v.trim().equalsIgnoreCase("true") || v.trim().equalsIgnoreCase("on"));
	}

	private static int build(CommandContext<CommandSourceStack> ctx, String id, boolean force) {
		String refused = refusal(ctx.getSource());
		if (refused != null) {
			ctx.getSource().sendFailure(Component.literal(refused));
			return 0;
		}
		HqBuilder builder = HqBuilders.get(id);
		if (builder == null) {
			ctx.getSource().sendFailure(Component.literal("Unknown HQ builder '" + id + "' (known: " + HqBuilders.ids() + ")"));
			return 0;
		}
		CommandSourceStack source = ctx.getSource();
		HqBuilder.Options options;
		ServerPlayer owner = null;
		if (PlotFeature.multiplayer(source.getServer())) {
			// Only the default builder builds at a plot's origin. Another one would build at the world
			// origin, over plot 0, and be published as the caller's studio.
			if (!id.equals(HqBuilders.defaultId())) {
				source.sendFailure(Component.literal("Only the default studio can be built on a multiplayer server."));
				return 0;
			}
			// The caller's own plot. There is no plot argument on this command.
			owner = source.getPlayer();
			if (owner == null) {
				source.sendFailure(Component.literal("A player is required to build a plot."));
				return 0;
			}
			Optional<Plot> plot = Plots.directory().plotOf(StudioId.of(owner.getUUID()));
			if (plot.isEmpty()) {
				source.sendFailure(Component.literal("You have no plot."));
				return 0;
			}
			options = new HqBuilder.Options(force, plot.get().origin(), plot.get().owner());
		} else {
			options = new HqBuilder.Options(force);
		}
		Anchors.Layout layout;
		// In multiplayer the plot is always in the overworld, wherever the caller stands.
		ServerLevel level = PlotFeature.multiplayer(source.getServer()) ? source.getServer().overworld() : source.getLevel();
		try {
			layout = buildAndPublish(level, builder, options);
		} catch (RuntimeException e) {
			AgentCraft.LOGGER.error("HQ builder '{}' failed", id, e);
			ctx.getSource().sendFailure(Component.literal("HQ builder '" + id + "' failed: " + e));
			return 0;
		}
		if (owner != null) PlotSpawn.applyRespawn(owner, layout);
		String report = lastReport;
		ctx.getSource().sendSuccess(() -> Component.literal("Built HQ '" + id + "': " + layout.anchors().size() + " anchors"
			+ (report == null ? "" : ". " + report)), true);
		return layout.anchors().size();
	}

	/** Builds {@code studio}'s plot at {@code origin}. The world spawn is not moved. */
	public static Anchors.Layout buildPlot(ServerLevel level, HqBuilder builder, boolean force, BlockPos origin, StudioId studio) {
		return buildAndPublish(level, builder, new HqBuilder.Options(force, origin, studio));
	}

	/** Runs {@code builder} (server thread) and publishes its layout. Singleplayer also moves the world spawn. */
	public static Anchors.Layout buildAndPublish(ServerLevel level, HqBuilder builder) {
		return buildAndPublish(level, builder, HqBuilder.Options.DEFAULT);
	}

	public static Anchors.Layout buildAndPublish(ServerLevel level, HqBuilder builder, HqBuilder.Options options) {
		long t0 = System.nanoTime();
		// another builder rewrites the same ground without a record: the studio's memory of its last
		// build no longer describes the world (each plot keeps its own plan file)
		PlanStore.invalidateUnless(level.getServer(), options.studio(), builder.id());
		Anchors.Builder anchors = Anchors.builder(builder.id());
		lastReport = builder.build(level, anchors, options);
		Anchors.Layout layout = anchors.build();
		if (PlotFeature.multiplayer(level.getServer())) {
			layout = PlotFeature.publishBuilt(level.getServer(), options.studio(), layout);
		} else {
			Anchors.publish(level.getServer(), layout);
			Anchor spawn = layout.get(AnchorNames.SPAWN);
			if (spawn != null) {
				level.getServer().getCommands().performPrefixedCommand(level.getServer().createCommandSourceStack().withSuppressedOutput(),
					String.format(Locale.ROOT, "setworldspawn %d %d %d %.1f 0", (int) Math.floor(spawn.x()), (int) Math.floor(spawn.y()),
						(int) Math.floor(spawn.z()), spawn.yaw()));
			}
		}
		AgentCraft.LOGGER.info("HQ '{}' built in {} ms{}", builder.id(), (System.nanoTime() - t0) / 1_000_000,
			lastReport == null ? "" : ": " + lastReport);
		return layout;
	}
}
