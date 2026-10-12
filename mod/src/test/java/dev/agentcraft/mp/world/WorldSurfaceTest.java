package dev.agentcraft.mp.world;

import static org.junit.jupiter.api.Assertions.*;

import dev.agentcraft.mp.server.world.ServerWorldFeature;
import org.junit.jupiter.api.Test;

/** The multiplayer surface rule is pure and independent of a running server. */
class WorldSurfaceTest {
	@Test
	void surface_rule_requires_a_flat_generator_with_grass_at_y_64() {
		assertNull(ServerWorldFeature.surfaceProblem(true, 64, true), "flat grass at y=64 is accepted");
		assertEquals("not_flat", ServerWorldFeature.surfaceProblem(false, 64, true));
		assertEquals("top_y_3", ServerWorldFeature.surfaceProblem(true, 3, true));
		assertEquals("top_y_65", ServerWorldFeature.surfaceProblem(true, 65, true));
		assertEquals("top_not_grass", ServerWorldFeature.surfaceProblem(true, 64, false));
	}
}
