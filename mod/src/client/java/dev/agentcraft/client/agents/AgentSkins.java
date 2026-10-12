package dev.agentcraft.client.agents;

import dev.agentcraft.AgentCraft;
import dev.agentcraft.Cast;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import org.jspecify.annotations.Nullable;

/**
 * Agent skins: {@code assets/agentcraft/textures/entity/agent/<skin>.png} (64x64, both layers) with
 * the arm model from cast.json ({@code slim} = 3 px arms). Unknown skins fall back to a vanilla
 * default skin so a new Foreman agent still renders.
 *
 * <p>A skin name may come from another player's studio, so it is checked before it reaches an
 * {@link Identifier}, which throws on any character outside {@code [a-z0-9/._-]}: a name that is
 * not a plain texture file name gets the default skin as well.
 */
public final class AgentSkins {
	/** Longest skin name that is looked up as a texture. */
	static final int MAX_NAME = 64;
	/** Most (agent, skin) pairs remembered; beyond that the least recently used pair is forgotten. */
	static final int MAX_CACHED = 256;
	private static final Map<String, PlayerSkin> CACHE = new LinkedHashMap<>(16, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, PlayerSkin> eldest) {
			return size() > MAX_CACHED;
		}
	};
	/** Default-skin warnings logged so far; capped so that a studio cycling names cannot fill the log. */
	private static int warnings;

	private AgentSkins() {
	}

	/** Never throws: a null, empty, overlong or otherwise unusable skin name gets a default skin. */
	public static PlayerSkin get(@Nullable String agentId, @Nullable String skinId) {
		return CACHE.computeIfAbsent(agentId + "|" + skinId, k -> create(agentId, skinId));
	}

	/** A plain texture file name: the characters of an identifier path without the separator. */
	static boolean validName(@Nullable String name) {
		if (name == null || name.isEmpty() || name.length() > MAX_NAME) {
			return false;
		}
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			if (c == '/' || !Identifier.validPathChar(c)) {
				return false;
			}
		}
		return true;
	}

	private static PlayerSkin create(@Nullable String agentId, @Nullable String skinId) {
		if (!validName(skinId)) {
			if (mayWarn()) {
				AgentCraft.LOGGER.warn("The skin name of agent {} is not a texture name; using a default skin", agentId);
			}
			return fallback(agentId);
		}
		Identifier asset = AgentCraft.id("entity/agent/" + skinId);
		ClientAsset.ResourceTexture tex = new ClientAsset.ResourceTexture(asset);
		Cast.Member m = Cast.get(agentId);
		if (m == null) {
			m = Cast.get(skinId);
		}
		boolean exists = Minecraft.getInstance().getResourceManager().getResource(tex.texturePath()).isPresent();
		if (!exists) {
			if (mayWarn()) {
				AgentCraft.LOGGER.warn("No skin texture {} for agent {}; using a default skin", tex.texturePath(), agentId);
			}
			return fallback(agentId);
		}
		return PlayerSkin.insecure(tex, null, null, m != null && m.slim() ? PlayerModelType.SLIM : PlayerModelType.WIDE);
	}

	private static PlayerSkin fallback(@Nullable String agentId) {
		return DefaultPlayerSkin.get(UUID.nameUUIDFromBytes(String.valueOf(agentId).getBytes(StandardCharsets.UTF_8)));
	}

	private static boolean mayWarn() {
		if (warnings >= MAX_CACHED) {
			return false;
		}
		warnings++;
		return true;
	}

	static int cached() {
		return CACHE.size();
	}

	public static void clear() {
		CACHE.clear();
	}
}
