package dev.agentcraft.hq;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.agentcraft.AgentCraft;
import dev.agentcraft.command.AgentCraftCommands;
import dev.agentcraft.layout.Anchor;
import dev.agentcraft.layout.AnchorNames;
import dev.agentcraft.layout.Anchors;
import dev.agentcraft.world.HqSite;
import dev.agentcraft.world.HqWorld;
import dev.agentcraft.world.WorldProfile;
import java.util.Locale;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
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
		// A fresh AgentCraft world (no saved layout yet) gets the default HQ built before the player
		// joins, so the first launch walks straight into it. AGENTCRAFT_HQ_AUTOBUILD=0 turns this off.
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			if (!HqWorld.isHq(server) || !Anchors.current().isEmpty() || !autoBuild()) {
				return;
			}
			HqBuilder builder = HqBuilders.get(HqBuilders.defaultId());
			if (builder != null) {
				try {
					buildAndPublish(server.overworld(), builder, optionsFor(server, false));
					AgentCraft.LOGGER.info("Fresh HQ world: built the default HQ '{}'", builder.id());
				} catch (RuntimeException e) {
					AgentCraft.LOGGER.error("Auto-building the HQ failed (run /agentcraft hq)", e);
				}
			}
		});
		AgentCraftCommands.sub(root -> root.then(Commands.literal("hq")
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
	 * AgentCraft worlds (studio or survival) unless AGENTCRAFT_HQ_ANYWORLD=1 (-Dagentcraft.hq.anyworld=1)
	 * opts in, and never in a Hardcore world.
	 */
	private static @Nullable String refusal(CommandSourceStack source) {
		var server = source.getServer();
		if (server.getWorldData().isHardcore()) {
			return "/agentcraft hq never builds in a Hardcore world (it rewrites terrain and moves the spawn)";
		}
		if (!HqWorld.isHq(server) && !anyWorld()) {
			return "/agentcraft hq only builds in the \"" + WorldProfile.STUDIO.levelName() + "\" and \"" + WorldProfile.SURVIVAL.levelName()
				+ "\" worlds; to build into this world on purpose, back it up and start the game with -Dagentcraft.hq.anyworld=1";
		}
		return null;
	}

	/**
	 * How the HQ is built in this world. The studio world builds the plain studio at the origin. A
	 * natural world (the survival profile, or any other world opted in with AGENTCRAFT_HQ_ANYWORLD)
	 * builds the survival-ready studio on the world's recorded HQ site, choosing one near the spawn
	 * the first time and keeping it in the world's marker file, so every rebuild lands on the same ground.
	 */
	static HqBuilder.Options optionsFor(MinecraftServer server, boolean force) {
		WorldProfile profile = HqWorld.profileOf(server);
		if (profile != null && !profile.naturalTerrain()) {
			return new HqBuilder.Options(force, null, profile.survival());
		}
		HqSite site = HqWorld.site(server);
		if (site == null) {
			site = HqTerrain.findSite(server.overworld());
			HqWorld.saveSite(server, site);
		}
		return new HqBuilder.Options(force, site, profile == null || profile.survival());
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
		HqBuilder.Options options = optionsFor(ctx.getSource().getServer(), force);
		if (options.site() != null && !(builder instanceof StudioHqBuilder)) {
			// the test room builds at the origin whatever the world; on a natural site that is somewhere else entirely
			ctx.getSource().sendFailure(Component.literal("HQ builder '" + id + "' only builds in the studio world (it does not know the HQ site)"));
			return 0;
		}
		Anchors.Layout layout;
		try {
			layout = buildAndPublish(ctx.getSource().getLevel(), builder, options);
		} catch (RuntimeException e) {
			AgentCraft.LOGGER.error("HQ builder '{}' failed", id, e);
			ctx.getSource().sendFailure(Component.literal("HQ builder '" + id + "' failed: " + e));
			return 0;
		}
		String report = lastReport;
		ctx.getSource().sendSuccess(() -> Component.literal("Built HQ '" + id + "': " + layout.anchors().size() + " anchors"
			+ (report == null ? "" : ". " + report)), true);
		return layout.anchors().size();
	}

	/** Runs {@code builder} (server thread), publishes its layout and moves the world spawn to its spawn anchor. */
	public static Anchors.Layout buildAndPublish(ServerLevel level, HqBuilder builder) {
		return buildAndPublish(level, builder, HqBuilder.Options.DEFAULT);
	}

	public static Anchors.Layout buildAndPublish(ServerLevel level, HqBuilder builder, HqBuilder.Options options) {
		long t0 = System.nanoTime();
		// another builder rewrites the same ground without a record: the studio's memory of its last
		// build no longer describes the world
		PlanStore.invalidateUnless(level.getServer(), builder.id());
		Anchors.Builder anchors = Anchors.builder(builder.id());
		lastReport = builder.build(level, anchors, options);
		Anchors.Layout layout = anchors.build();
		Anchors.publish(level.getServer(), layout);
		Anchor spawn = layout.get(AnchorNames.SPAWN);
		if (spawn != null) {
			level.getServer().getCommands().performPrefixedCommand(level.getServer().createCommandSourceStack().withSuppressedOutput(),
				String.format(Locale.ROOT, "setworldspawn %d %d %d %.1f 0", (int) Math.floor(spawn.x()), (int) Math.floor(spawn.y()),
					(int) Math.floor(spawn.z()), spawn.yaw()));
		}
		AgentCraft.LOGGER.info("HQ '{}' built in {} ms{}", builder.id(), (System.nanoTime() - t0) / 1_000_000,
			lastReport == null ? "" : ": " + lastReport);
		return layout;
	}
}
