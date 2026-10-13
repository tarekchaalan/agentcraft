package dev.agentcraft.client;

import static org.junit.jupiter.api.Assertions.*;

import dev.agentcraft.mp.MpLog;
import dev.agentcraft.mp.MpReasons;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * AutoWorld's skip is one structured {@code agentcraft.mp} line per session and carries only a reason
 * constant, so no launch argument or server address can leak. Same-package access to the seam.
 */
class WorldAutoWorldTest {
	@Test
	void autoworld_skip_logs_once_with_a_reason_and_never_an_address() {
		try (var capture = MpLog.capture()) {
			AutoWorld.skip(MpReasons.DISABLED);
			AutoWorld.skip(MpReasons.QUICKPLAY_MULTIPLAYER); // must not add a second line
			assertEquals(List.of("event=autoworld_skipped reason=disabled"), capture.lines());
			assertFalse(capture.lines().getFirst().contains("."), "no address or free text in the line");
		}
	}
}
