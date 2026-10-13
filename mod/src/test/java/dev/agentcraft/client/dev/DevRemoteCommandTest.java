package dev.agentcraft.client.dev;

import static org.junit.jupiter.api.Assertions.*;

import dev.agentcraft.client.mp.dev.MpDevCommands;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** MP-12: the remote opt-in, the command text a remote server may receive, and the plain-decimal numbers of its /tp. */
class DevRemoteCommandTest {
	private final String savedOptIn = DevCommands.remoteOptInValue;

	@AfterEach void restore_the_opt_in() {
		DevCommands.remoteOptInValue = savedOptIn;
	}

	@Test void the_remote_paths_are_refused_unless_the_opt_in_is_exactly_1() {
		for (String off : new String[] {null, "", "0", "true", "yes", "on", " 1"}) {
			DevCommands.remoteOptInValue = off;
			assertNamesTheOptIn(assertThrows(DevBridge.DevException.class, DevCommands::requireRemoteOptIn, "value: " + off));
		}
		DevCommands.remoteOptInValue = "1";
		assertDoesNotThrow(DevCommands::requireRemoteOptIn);
	}

	@Test void a_remote_command_is_refused_before_the_client_or_its_connection_is_touched() {
		DevCommands.remoteOptInValue = null;
		// no Minecraft at all: without the refusal in front of the send this is a NullPointerException
		assertNamesTheOptIn(assertThrows(DevBridge.DevException.class, () -> DevCommands.remoteCommand(null, "time set 0")));
	}

	@Test void dev_mp_send_keeps_its_singleplayer_refusal_and_needs_the_opt_in_on_a_remote_server() {
		for (String value : new String[] {null, "1"}) {
			DevCommands.remoteOptInValue = value; // singleplayer never consults the opt-in
			DevBridge.DevException e = assertThrows(DevBridge.DevException.class, () -> MpDevCommands.checkSend(true));
			assertTrue(e.getMessage().contains("singleplayer"), e.getMessage());
		}
		assertDoesNotThrow(() -> MpDevCommands.checkSend(false));
		DevCommands.remoteOptInValue = "true";
		assertNamesTheOptIn(assertThrows(DevBridge.DevException.class, () -> MpDevCommands.checkSend(false)));
	}

	@Test void dev_release_needs_the_opt_in_only_to_put_a_remote_spectator_back_in_creative() {
		assertTrue(DevCommands.releaseSendsRemote("creative", false, true));
		assertFalse(DevCommands.releaseSendsRemote("keep", false, true)); // local only: FOV pin and HUD
		assertFalse(DevCommands.releaseSendsRemote("creative", false, false));
		assertFalse(DevCommands.releaseSendsRemote("creative", true, true)); // the integrated server never needs it
	}

	private static void assertNamesTheOptIn(DevBridge.DevException e) {
		assertTrue(e.getMessage().contains("AGENTCRAFT_DEV_REMOTE=1"), e.getMessage());
		assertTrue(e.getMessage().contains("shared server with the player's own rights"), e.getMessage());
	}

	@Test void one_leading_slash_is_stripped_and_plain_command_text_is_unchanged() {
		assertEquals("time set 0", DevCommands.remoteCommandText("/time set 0"));
		assertEquals("time set 0", DevCommands.remoteCommandText("time set 0"));
		assertEquals("a".repeat(256), DevCommands.remoteCommandText("a".repeat(256)));
		// the limit counts the text that is sent, not the slash in front of it
		assertEquals("a".repeat(256), DevCommands.remoteCommandText("/" + "a".repeat(256)));
	}

	@Test void a_remote_command_is_refused_when_it_is_blank_too_long_or_not_chat_text() {
		// The server disconnects a client that sends any of these, so they never leave the client.
		refused("");
		refused("   ");
		refused("/"); // the slash alone leaves nothing to run
		refused("a".repeat(257));
		refused("say a\nb"); // §, a control character or 127 all fail isAllowedChatCharacter
		refused("say §c");
	}

	@Test void the_remote_tp_arguments_are_plain_decimals_with_no_exponent_notation() {
		String big = DevCommands.tpArg(1e7);
		String small = DevCommands.tpArg(1e-4);
		assertFalse(big.contains("E"), big);
		assertFalse(small.contains("E"), small);
		assertEquals("64.38", DevCommands.tpArg(64.38));
	}

	/** Refuse {@code cmd} with a {@link DevBridge.DevException} that names the field. */
	private static String refused(String cmd) {
		DevBridge.DevException e = assertThrows(DevBridge.DevException.class, () -> DevCommands.remoteCommandText(cmd));
		assertTrue(e.getMessage().contains("'cmd'"), e.getMessage());
		return e.getMessage();
	}
}
