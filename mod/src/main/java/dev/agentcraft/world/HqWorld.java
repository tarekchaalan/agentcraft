package dev.agentcraft.world;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.agentcraft.AgentCraft;
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
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

/**
 * The AgentCraft worlds: identity + rules. The client creates/loads one automatically (see client
 * AutoWorld, {@link WorldProfile}); this class makes sure that whenever an AgentCraft world runs,
 * its rules are the ones its profile needs: the calm, building-friendly ones for the studio, plain
 * vanilla for survival. Other worlds are never touched.
 *
 * <p>Every AgentCraft world carries a marker file {@code agentcraft-world.json} in its folder: who
 * created it, when, its profile and (natural worlds) the {@link HqSite} the HQ was built on.
 */
public final class HqWorld {
	/** Folder name and level name of the studio world (the original, creative HQ world). */
	public static final String LEVEL_NAME = WorldProfile.STUDIO.levelName();
	/** Surface Y of the superflat meadow: grass top is at y=64, so you stand at y=65. */
	public static final int SURFACE_Y = 64;
	/** Golden-hour-ish day time set once when a studio world is created. */
	public static final long INITIAL_TIME = 12000L;
	private static final String MARKER_FILE = "agentcraft-world.json";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private HqWorld() {
	}

	public static void init() {
		ServerLifecycleEvents.SERVER_STARTED.register(HqWorld::onServerStarted);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			WorldProfile profile = profileOf(server);
			if (profile == null || profile.survival()) {
				return;
			}
			ServerPlayer player = handler.getPlayer();
			// DevBridge camera shots switch to spectator; normal play in the studio should resume in creative.
			if (player.gameMode() == GameType.SPECTATOR || player.gameMode() == GameType.SURVIVAL) {
				player.setGameMode(GameType.CREATIVE);
			}
		});
	}

	/**
	 * An AgentCraft world (studio or survival): the HQ builds itself here, anchors load here and the
	 * rules are rewritten here, so never a Hardcore world.
	 */
	public static boolean isHq(MinecraftServer server) {
		return profileOf(server) != null;
	}

	/**
	 * The profile of the running world: what its marker file says, else what its level name says;
	 * null for a world that is not an AgentCraft world (or is Hardcore).
	 */
	public static @Nullable WorldProfile profileOf(MinecraftServer server) {
		if (server.getWorldData().isHardcore()) {
			return null;
		}
		JsonObject marker = readMarker(server);
		if (marker != null && marker.has("profile")) {
			WorldProfile p = WorldProfile.byId(marker.get("profile").getAsString());
			if (p != null) {
				return p;
			}
		}
		return WorldProfile.byLevelName(server.getWorldData().getLevelName());
	}

	/** The site the HQ was built on in this world (natural worlds), or null when none is recorded yet. */
	public static @Nullable HqSite site(MinecraftServer server) {
		JsonObject marker = readMarker(server);
		if (marker == null || !marker.has("site")) {
			return null;
		}
		try {
			JsonObject s = marker.getAsJsonObject("site");
			return new HqSite(s.get("x").getAsInt(), s.get("y").getAsInt(), s.get("z").getAsInt());
		} catch (RuntimeException e) {
			AgentCraft.LOGGER.warn("Unreadable HQ site in {}; a new site will be chosen", MARKER_FILE, e);
			return null;
		}
	}

	/** Records the HQ site in the marker file (created if the world has none). */
	public static void saveSite(MinecraftServer server, HqSite site) {
		JsonObject marker = readMarker(server);
		if (marker == null) {
			marker = newMarker(profileOf(server));
		}
		JsonObject s = new JsonObject();
		s.addProperty("x", site.x());
		s.addProperty("y", site.y());
		s.addProperty("z", site.z());
		marker.add("site", s);
		writeMarker(server, marker);
	}

	private static Path markerPath(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve(MARKER_FILE);
	}

	private static @Nullable JsonObject readMarker(MinecraftServer server) {
		Path marker = markerPath(server);
		if (!Files.exists(marker)) {
			return null;
		}
		try {
			return JsonParser.parseString(Files.readString(marker, StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (IOException | RuntimeException e) {
			AgentCraft.LOGGER.warn("Unreadable {}; treating the world by its name", marker, e);
			return new JsonObject();
		}
	}

	private static JsonObject newMarker(@Nullable WorldProfile profile) {
		JsonObject o = new JsonObject();
		o.addProperty("createdBy", "agentcraft");
		o.addProperty("created", Instant.now().toString());
		if (profile != null) {
			o.addProperty("profile", profile.id());
		}
		return o;
	}

	private static void writeMarker(MinecraftServer server, JsonObject marker) {
		Path path = markerPath(server);
		try {
			Files.writeString(path, GSON.toJson(marker) + "\n", StandardCharsets.UTF_8);
		} catch (IOException e) {
			AgentCraft.LOGGER.warn("Could not write HQ world marker {}", path, e);
		}
	}

	private static void onServerStarted(MinecraftServer server) {
		WorldProfile profile = profileOf(server);
		if (profile == null) {
			return;
		}
		applyRules(server, profile);
		if (!Files.exists(markerPath(server))) {
			// First start of a fresh AgentCraft world. The studio gets its golden hour and its spawn; a
			// survival world starts like any new vanilla world (the HQ build then moves its spawn to the atrium).
			if (!profile.survival()) {
				run(server, "time set " + INITIAL_TIME);
				run(server, "weather clear");
				run(server, "setworldspawn 0 " + (SURFACE_Y + 1) + " 0");
			}
			writeMarker(server, newMarker(profile));
			AgentCraft.LOGGER.info("Initialised fresh {} world '{}'{}", profile.id(), server.getWorldData().getLevelName(),
				profile.survival() ? " (vanilla rules)" : " (time " + INITIAL_TIME + ", clear weather)");
		}
	}

	/** Every rule the studio sets; survival resets exactly these to their vanilla defaults. */
	private static final GameRule<?>[] STUDIO_RULES = {
		GameRules.ADVANCE_TIME, GameRules.ADVANCE_WEATHER, GameRules.SPAWN_MOBS, GameRules.SPAWN_MONSTERS, GameRules.SPAWN_PATROLS,
		GameRules.SPAWN_PHANTOMS, GameRules.SPAWN_WANDERING_TRADERS, GameRules.SPAWN_WARDENS, GameRules.SHOW_ADVANCEMENT_MESSAGES,
		GameRules.KEEP_INVENTORY, GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, GameRules.MOB_GRIEFING, GameRules.SPREAD_VINES,
		GameRules.MAX_SNOW_ACCUMULATION_HEIGHT, GameRules.RESPAWN_RADIUS, GameRules.LOCATOR_BAR, GameRules.COMMAND_BLOCK_OUTPUT,
		GameRules.LOG_ADMIN_COMMANDS, GameRules.MAX_BLOCK_MODIFICATIONS,
	};

	/**
	 * Idempotent; applied on every start so rule changes in code reach existing worlds. The studio is
	 * a still life with the HQ kept exactly as built; survival is vanilla Minecraft with the HQ in it,
	 * so there every rule the studio touches goes back to its vanilla default (also in a survival
	 * world that once ran under studio rules).
	 */
	public static void applyRules(MinecraftServer server, WorldProfile profile) {
		GameRules rules = server.getGameRules();
		if (profile.survival()) {
			GameRules vanilla = new GameRules(server.getWorldData().enabledFeatures());
			for (GameRule<?> rule : STUDIO_RULES) {
				resetToDefault(rules, vanilla, rule, server);
			}
			return;
		}
		// no day, no weather, no mobs
		rules.set(GameRules.ADVANCE_TIME, false, server);
		rules.set(GameRules.ADVANCE_WEATHER, false, server);
		rules.set(GameRules.SPAWN_MOBS, false, server);
		rules.set(GameRules.SPAWN_MONSTERS, false, server);
		rules.set(GameRules.SPAWN_PATROLS, false, server);
		rules.set(GameRules.SPAWN_PHANTOMS, false, server);
		rules.set(GameRules.SPAWN_WANDERING_TRADERS, false, server);
		rules.set(GameRules.SPAWN_WARDENS, false, server);
		rules.set(GameRules.SHOW_ADVANCEMENT_MESSAGES, false, server);
		rules.set(GameRules.KEEP_INVENTORY, true, server);
		// the HQ stays exactly as built: no fire, no mob griefing, no vine growth, no snow layers
		rules.set(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, 0, server);
		rules.set(GameRules.MOB_GRIEFING, false, server);
		rules.set(GameRules.SPREAD_VINES, false, server);
		rules.set(GameRules.MAX_SNOW_ACCUMULATION_HEIGHT, 0, server);
		// respawn exactly at the HQ's spawn anchor
		rules.set(GameRules.RESPAWN_RADIUS, 0, server);
		rules.set(GameRules.LOCATOR_BAR, false, server);
		rules.set(GameRules.COMMAND_BLOCK_OUTPUT, false, server);
		rules.set(GameRules.LOG_ADMIN_COMMANDS, false, server);
		// Allow large /fill operations for the HQ builder.
		rules.set(GameRules.MAX_BLOCK_MODIFICATIONS, 1_000_000, server);
	}

	private static <T> void resetToDefault(GameRules rules, GameRules vanilla, GameRule<T> rule, MinecraftServer server) {
		T value = vanilla.get(rule);
		if (!value.equals(rules.get(rule))) {
			rules.set(rule, value, server);
		}
	}

	/** The rules of the running world's profile (the studio's for a world without one). */
	public static void applyRules(MinecraftServer server) {
		WorldProfile p = profileOf(server);
		applyRules(server, p == null ? WorldProfile.STUDIO : p);
	}

	private static void run(MinecraftServer server, String command) {
		server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
	}
}
