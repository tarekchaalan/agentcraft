package dev.agentcraft.mp.world;

import static org.junit.jupiter.api.Assertions.*;

import dev.agentcraft.client.ClientEnv;
import org.junit.jupiter.api.Test;

/** AutoWorld must skip only a real multiplayer quick-play launch, address or blank value or not. */
class WorldLaunchTest {
	@Test
	void quick_play_parser_accepts_only_multiplayer_with_a_value() {
		assertTrue(ClientEnv.quickPlayMultiplayer(new String[] {"--quickPlayMultiplayer", "localhost:25565"}));
		assertTrue(ClientEnv.quickPlayMultiplayer(new String[] {"--quickPlayMultiplayer=localhost:25565"}));
		assertTrue(ClientEnv.quickPlayMultiplayer(
			new String[] {"--username", "x", "--quickPlayMultiplayer", "127.0.0.1:1", "--width", "1920"}));

		assertFalse(ClientEnv.quickPlayMultiplayer(new String[] {"--quickPlaySingleplayer", "world"}));
		assertFalse(ClientEnv.quickPlayMultiplayer(new String[] {"--quickPlayRealms", "1"}));
		assertFalse(ClientEnv.quickPlayMultiplayer(new String[] {"--quickPlayMultiplayer"}));
		assertFalse(ClientEnv.quickPlayMultiplayer(new String[] {"--quickPlayMultiplayer", ""}));
		assertFalse(ClientEnv.quickPlayMultiplayer(new String[] {"--quickPlayMultiplayer="}));
		assertFalse(ClientEnv.quickPlayMultiplayer(new String[] {"--quickPlayMultiplayerX", "localhost"}));
		assertFalse(ClientEnv.quickPlayMultiplayer(new String[0]));
		assertFalse(ClientEnv.quickPlayMultiplayer(null));
	}
}
