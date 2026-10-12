package dev.agentcraft.world;

import dev.agentcraft.AgentCraft;
import dev.agentcraft.mp.MpEvents;
import dev.agentcraft.mp.MpLog;
import dev.agentcraft.mp.MpServerConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.LevelResource;

/**
 * The "AgentCraft HQ" world: identity + rules. The client creates/loads it automatically
 * (see client AutoWorld); this class makes sure that whenever the HQ world runs, its rules are
 * the calm, building-friendly ones the HQ needs. Other worlds are never touched.
 *
 * <p>On a dedicated server, "multiplayer" means {@code isDedicatedServer() && MpServerConfig
 * .current().enabled()} and replaces the level-name gate: the same rules apply by config, without
 * the HQ's LOCAL build, anchor load or global spawn move. The level-name path is untouched when the
 * flag is off and in singleplayer (the rollback lever).
 */
public final class HqWorld {
	/** Folder name and level name of the HQ world. */
	public static final String LEVEL_NAME = "AgentCraft HQ";
	/** Surface Y of the superflat meadow: grass top is at y=64, so you stand at y=65. */
	public static final int SURFACE_Y = 64;
	/** Golden-hour-ish day time set once when the world is created. */
	public static final long INITIAL_TIME = 12000L;
	private static final String MARKER_FILE = "agentcraft-world.json";

	private HqWorld() {
	}

	public static void init() {
		ServerLifecycleEvents.SERVER_STARTED.register(HqWorld::onServerStarted);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> onPlayerJoin(server, handler.getPlayer()));
	}

	/** Enabled dedicated server, read from the live config at call time (never cached). */
	public static boolean multiplayer(MinecraftServer server) {
		return server.isDedicatedServer() && MpServerConfig.current().enabled();
	}

	/** The HQ world (players switched to creative, rules rewritten, so never a Hardcore world); multiplayer turns it off so no LOCAL studio is built. */
	public static boolean isHq(MinecraftServer server) {
		return !multiplayer(server) && LEVEL_NAME.equals(server.getWorldData().getLevelName()) && !server.getWorldData().isHardcore();
	}

	private static void onServerStarted(MinecraftServer server) {
		if (multiplayer(server)) {
			// Rule profile and first-start block live under worldRules; setworldspawn never runs.
			if (applyServerRules(server) > 0) {
				firstStart(server, false);
			}
			return;
		}
		if (!isHq(server)) {
			return;
		}
		applyRules(server);
		firstStart(server, true);
	}

	/**
	 * Applies the multiplayer rule profile when the live config asks for it; returns the number of
	 * rules written (0 when not multiplayer or {@code worldRules} is off). Public as the game-test seam.
	 */
	public static int applyServerRules(MinecraftServer server) {
		if (!multiplayer(server) || !MpServerConfig.current().worldRules()) {
			return 0;
		}
		int rules = applyRules(server, true);
		MpLog.event(MpEvents.WORLD_RULES_APPLIED, "rules", rules);
		return rules;
	}

	/** Joins in singleplayer HQ or multiplayer: force creative only where the mode asks for it. */
	public static void onPlayerJoin(MinecraftServer server, ServerPlayer player) {
		if (multiplayer(server)) {
			if (!MpServerConfig.current().forceCreative()) {
				return;
			}
			GameType from = player.gameMode();
			if ((from == GameType.SURVIVAL || from == GameType.SPECTATOR) && player.setGameMode(GameType.CREATIVE)) {
				// from_mode is a closed enum name, never anything the player supplied.
				MpLog.event(MpEvents.CREATIVE_FORCED, "player", player.getUUID(), "from_mode", from.getName());
			}
			return;
		}
		if (!isHq(server)) {
			return;
		}
		// DevBridge camera shots switch to spectator; normal play should resume in creative.
		if (player.gameMode() == GameType.SPECTATOR || player.gameMode() == GameType.SURVIVAL) {
			player.setGameMode(GameType.CREATIVE);
		}
	}

	private static void firstStart(MinecraftServer server, boolean moveWorldSpawn) {
		Path marker = server.getWorldPath(LevelResource.ROOT).resolve(MARKER_FILE);
		if (Files.exists(marker)) {
			return;
		}
		// First start of a fresh HQ world.
		run(server, "time set " + INITIAL_TIME);
		run(server, "weather clear");
		if (moveWorldSpawn) {
			// Singleplayer only: a shared world's spawn belongs to MP-03's per-plot respawn.
			run(server, "setworldspawn 0 " + (SURFACE_Y + 1) + " 0");
		}
		try {
			Files.writeString(marker, "{\"createdBy\":\"agentcraft\",\"created\":\"" + Instant.now() + "\"}\n", StandardCharsets.UTF_8);
		} catch (IOException e) {
			AgentCraft.LOGGER.warn("Could not write HQ world marker {}", marker, e);
		}
		AgentCraft.LOGGER.info("Initialised fresh HQ world (time {}, clear weather)", INITIAL_TIME);
	}

	/** Idempotent; the legacy integrated profile, kept byte-for-byte as it was. */
	public static void applyRules(MinecraftServer server) {
		applyRules(server, false);
	}

	/** Idempotent; applied on every start so rule changes in code reach existing worlds. */
	private static int applyRules(MinecraftServer server, boolean multiplayer) {
		GameRules rules = server.getGameRules();
		rules.set(GameRules.ADVANCE_TIME, false, server);
		rules.set(GameRules.ADVANCE_WEATHER, false, server);
		rules.set(GameRules.KEEP_INVENTORY, true, server);
		rules.set(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, 0, server);
		rules.set(GameRules.SPAWN_MOBS, false, server);
		rules.set(GameRules.SPAWN_MONSTERS, false, server);
		rules.set(GameRules.SPAWN_PATROLS, false, server);
		rules.set(GameRules.SPAWN_PHANTOMS, false, server);
		rules.set(GameRules.SPAWN_WANDERING_TRADERS, false, server);
		rules.set(GameRules.SPAWN_WARDENS, false, server);
		rules.set(GameRules.MOB_GRIEFING, false, server);
		// Keep the built HQ exactly as built: no vine growth, no snow layers.
		rules.set(GameRules.SPREAD_VINES, false, server);
		rules.set(GameRules.MAX_SNOW_ACCUMULATION_HEIGHT, 0, server);
		rules.set(GameRules.RESPAWN_RADIUS, 0, server);
		rules.set(GameRules.LOCATOR_BAR, false, server);
		rules.set(GameRules.SHOW_ADVANCEMENT_MESSAGES, false, server);
		rules.set(GameRules.COMMAND_BLOCK_OUTPUT, false, server);
		// Shared servers keep op commands auditable; the integrated profile turns it off as before.
		rules.set(GameRules.LOG_ADMIN_COMMANDS, multiplayer, server);
		// Allow large /fill operations for the HQ builder.
		rules.set(GameRules.MAX_BLOCK_MODIFICATIONS, 1_000_000, server);
		return 19; // the rules set above; world_rules_applied reports it
	}

	private static void run(MinecraftServer server, String command) {
		server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
	}
}
