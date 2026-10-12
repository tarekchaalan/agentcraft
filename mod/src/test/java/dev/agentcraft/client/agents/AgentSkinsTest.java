package dev.agentcraft.client.agents;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.world.entity.player.PlayerSkin;
import org.junit.jupiter.api.Test;

class AgentSkinsTest {
	@Test
	void a_skin_name_that_is_not_a_texture_name_gets_the_default_skin_without_throwing() {
		PlayerSkin fallback = DefaultPlayerSkin.get(UUID.nameUUIDFromBytes("kit".getBytes(StandardCharsets.UTF_8)));
		for (String skin : new String[] {"Wren", "a b", "../x", "", "x".repeat(200), "wén", null}) {
			assertFalse(AgentSkins.validName(skin), String.valueOf(skin));
			assertSame(fallback, AgentSkins.get("kit", skin), String.valueOf(skin));
		}
		assertNotNull(AgentSkins.get(null, null));
		assertTrue(AgentSkins.validName("wren"));
		assertTrue(AgentSkins.validName("kit_2-b.v1"));
		assertTrue(AgentSkins.validName("x".repeat(AgentSkins.MAX_NAME)));
		assertFalse(AgentSkins.validName("x".repeat(AgentSkins.MAX_NAME + 1)));
	}

	@Test
	void cycling_skin_names_leaves_the_cache_at_its_bound() {
		AgentSkins.clear();
		for (int i = 0; i < 10_000; i++) {
			AgentSkins.get("agent" + i, "Skin " + i);
		}
		assertEquals(AgentSkins.MAX_CACHED, AgentSkins.cached());

		AgentSkins.clear();
		assertEquals(0, AgentSkins.cached());
	}
}
