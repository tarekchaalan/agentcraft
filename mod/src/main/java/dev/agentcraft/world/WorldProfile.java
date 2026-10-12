package dev.agentcraft.world;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The two kinds of AgentCraft world. Both get the same studio HQ; they differ in what the world
 * around it is and how it plays.
 * <ul>
 *   <li>{@link #STUDIO} ("AgentCraft HQ"): the agents' world. Creative, peaceful, a superflat meadow
 *       with the HQ at the origin, time and weather frozen, nothing spawns. Made for watching the
 *       team work.</li>
 *   <li>{@link #SURVIVAL} ("AgentCraft Survival"): a normal, randomly generated world with vanilla
 *       rules: survival mode, normal difficulty, day and night, weather, mobs. The HQ is built on
 *       the flattest ground near the world spawn, blended into the terrain, with doors, a bedroom,
 *       farms and hidden lighting so nothing spawns on the site. Made for playing Minecraft while
 *       the team codes.</li>
 * </ul>
 * Selected with {@code AGENTCRAFT_WORLD=studio|survival} (or {@code -Dagentcraft.world=...}) when
 * the client starts; a world remembers its profile in {@code agentcraft-world.json}.
 */
public enum WorldProfile {
	STUDIO("studio", "AgentCraft HQ"),
	SURVIVAL("survival", "AgentCraft Survival");

	public static final String ENV = "AGENTCRAFT_WORLD";
	public static final String SEED_ENV = "AGENTCRAFT_WORLD_SEED";

	private final String id;
	private final String levelName;

	WorldProfile(String id, String levelName) {
		this.id = id;
		this.levelName = levelName;
	}

	public String id() {
		return id;
	}

	/** Folder name and level name of this profile's world. */
	public String levelName() {
		return levelName;
	}

	/** Terrain is the world's own (the HQ is placed on and blended into it) rather than the flat meadow. */
	public boolean naturalTerrain() {
		return this == SURVIVAL;
	}

	/** Survival play: doors, a survival corner, spawn-proof lighting; players keep their game mode. */
	public boolean survival() {
		return this == SURVIVAL;
	}

	/** The profile asked for at startup ({@code AGENTCRAFT_WORLD}); the studio when unset or unknown. */
	public static WorldProfile selected() {
		String v = raw(ENV);
		WorldProfile p = v == null ? null : byId(v);
		return p == null ? STUDIO : p;
	}

	public static @Nullable WorldProfile byId(String id) {
		for (WorldProfile p : values()) {
			if (p.id.equalsIgnoreCase(id.trim())) {
				return p;
			}
		}
		return null;
	}

	public static @Nullable WorldProfile byLevelName(String levelName) {
		for (WorldProfile p : values()) {
			if (p.levelName.equals(levelName)) {
				return p;
			}
		}
		return null;
	}

	/** {@code -Dagentcraft.xxx} system property first, then the {@code AGENTCRAFT_XXX} environment variable. */
	public static @Nullable String raw(String envName) {
		String prop = System.getProperty(envName.toLowerCase(Locale.ROOT).replace('_', '.'));
		if (prop != null && !prop.isBlank()) {
			return prop.trim();
		}
		String env = System.getenv(envName);
		return env == null || env.isBlank() ? null : env.trim();
	}
}
