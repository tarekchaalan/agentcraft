package dev.agentcraft.client.dev;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import dev.agentcraft.layout.Anchors;
import dev.agentcraft.mp.StudioId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** MP-12: {@code dev.camera} anchor resolution through an optional named studio. */
class DevAnchorStudioTest {
	@Test void an_anchor_resolves_from_the_named_studio_and_a_missing_one_is_refused() {
		StudioId remote = StudioId.of(UUID.fromString("11111111-2222-3333-4444-555555555555"));
		Anchors.publish(remote, Anchors.builder("studio").bounds(-46, 60, -36, 46, 100, 54).spot("desk_kit", 1, 66, 2, 90).build());
		try {
			JsonObject request = new JsonObject();
			request.addProperty("anchor", "desk_kit");
			request.addProperty("studio", remote.owner().toString());
			JsonObject resolved = DevCommands.resolveAnchor(request);
			assertEquals(1.5, resolved.get("x").getAsDouble(), 1e-9);
			assertEquals(66.0, resolved.get("y").getAsDouble(), 1e-9);
			assertEquals(2.5, resolved.get("z").getAsDouble(), 1e-9);
			assertTrue(resolved.get("feet").getAsBoolean());
			assertFalse(resolved.has("anchor"));

			JsonObject missing = new JsonObject();
			missing.addProperty("anchor", "desk_kit");
			missing.addProperty("studio", UUID.randomUUID().toString());
			DevBridge.DevException error = assertThrows(DevBridge.DevException.class, () -> DevCommands.resolveAnchor(missing));
			assertTrue(error.getMessage().contains("unknown anchor"), error.getMessage());

			// No anchor field: nothing to resolve, the request is returned untouched.
			JsonObject plain = new JsonObject();
			plain.addProperty("x", 1.0);
			assertSame(plain, DevCommands.resolveAnchor(plain));
		} finally {
			Anchors.remove(remote);
		}
	}
}
