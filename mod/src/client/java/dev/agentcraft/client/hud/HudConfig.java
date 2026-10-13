package dev.agentcraft.client.hud;

import dev.agentcraft.AgentCraft;
import dev.agentcraft.hud.HudSettings;
import java.io.IOException;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;

/**
 * The client's one {@link HudSettings} ({@code <gameDir>/agentcraft/hud.json}): the overlay style, position, size,
 * peek, auto-hide and hide-in-combat. Changes apply at once and are saved right away. Client thread.
 */
public final class HudConfig {
	private static @Nullable HudSettings settings;

	private HudConfig() {
	}

	public static Path file() {
		return FabricLoader.getInstance().getGameDir().resolve("agentcraft").resolve(HudSettings.FILE);
	}

	public static HudSettings get() {
		if (settings == null) {
			settings = HudSettings.load(file());
		}
		return settings;
	}

	/** Saves when anything changed. */
	public static void save() {
		HudSettings s = settings;
		if (s == null || !s.dirty()) {
			return;
		}
		try {
			s.save(file());
		} catch (IOException e) {
			AgentCraft.LOGGER.warn("Could not save {}", file(), e);
		}
	}
}
