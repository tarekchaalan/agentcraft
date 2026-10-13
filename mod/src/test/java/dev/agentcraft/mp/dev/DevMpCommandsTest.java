package dev.agentcraft.mp.dev;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.agentcraft.client.dev.DevBridge.DevException;
import dev.agentcraft.client.dev.Fields;
import dev.agentcraft.client.mp.Studios;
import dev.agentcraft.client.mp.dev.MpDevCommands;
import dev.agentcraft.layout.Anchors;
import dev.agentcraft.mp.CodecTest;
import dev.agentcraft.mp.StudioId;
import dev.agentcraft.mp.net.HelloC2S;
import dev.agentcraft.mp.net.PublicStateC2S;
import dev.agentcraft.mp.net.StudioEventC2S;
import dev.agentcraft.mp.net.WorldIntentC2S;
import dev.agentcraft.mp.state.*;
import io.netty.buffer.Unpooled;
import java.lang.reflect.Method;
import java.util.*;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.junit.jupiter.api.Test;

/** MP-12: the pure parts of the new dev tools, request parsing and studio resolution. */
class DevMpCommandsTest {
	@Test void dev_mp_send_builds_each_c2s_payload_through_the_same_json_and_codec_the_network_uses() {
		// public_state
		PublicStudioState state = CodecTest.state();
		JsonObject request = new JsonObject();
		request.addProperty("payload", "public_state");
		request.add("state", PublicJson.toJson(state));
		PublicStateC2S publicState = assertInstanceOf(PublicStateC2S.class, MpDevCommands.parseSend(Fields.of(request)));
		assertEquals(state, round(PublicStateC2S.CODEC, publicState).state());
		// studio_event
		PublicEvent event = new PublicEvent.Say("kit", "user", null, 500);
		request = new JsonObject();
		request.addProperty("payload", "studio_event");
		request.add("event", PublicJson.toJson(event));
		StudioEventC2S studioEvent = assertInstanceOf(StudioEventC2S.class, MpDevCommands.parseSend(Fields.of(request)));
		assertEquals(event, round(StudioEventC2S.CODEC, studioEvent).event());
		// world_intent
		WorldIntent intent = new WorldIntent(1, Map.of("agent:kit", LampStatusWire.WORKING), true, false, Set.of("kit"));
		request = new JsonObject();
		request.addProperty("payload", "world_intent");
		request.add("intent", PublicJson.toJson(intent));
		WorldIntentC2S worldIntent = assertInstanceOf(WorldIntentC2S.class, MpDevCommands.parseSend(Fields.of(request)));
		assertEquals(intent, round(WorldIntentC2S.CODEC, worldIntent).intent());
		// hello, with the local version bounded like the handshake reply
		request = new JsonObject();
		request.addProperty("payload", "hello");
		request.addProperty("protocol", 1);
		request.addProperty("modVersion", "x".repeat(40));
		HelloC2S hello = assertInstanceOf(HelloC2S.class, MpDevCommands.parseSend(Fields.of(request)));
		assertEquals(1, hello.protocol());
		assertEquals(32, hello.modVersion().length());
		assertEquals(hello, round(HelloC2S.CODEC, hello));
	}

	@Test void dev_mp_send_refuses_a_payload_the_codec_would_refuse() {
		JsonObject state = PublicJson.toJson(CodecTest.state());
		state.addProperty("privateData", "secret");
		JsonObject badState = new JsonObject();
		badState.addProperty("payload", "public_state");
		badState.add("state", state);
		DevException stateError = assertThrows(DevException.class, () -> MpDevCommands.parseSend(Fields.of(badState)));
		assertTrue(stateError.getMessage().startsWith("invalid 'public_state' payload:"), stateError.getMessage());

		JsonObject intent = PublicJson.toJson(new WorldIntent(1, Map.of(), false, false, Set.of()));
		intent.getAsJsonObject("lamps").addProperty("ci:repo", "off"); // a repo id must never be a binding
		JsonObject badIntent = new JsonObject();
		badIntent.addProperty("payload", "world_intent");
		badIntent.add("intent", intent);
		DevException intentError = assertThrows(DevException.class, () -> MpDevCommands.parseSend(Fields.of(badIntent)));
		assertTrue(intentError.getMessage().contains("invalid binding"), intentError.getMessage());

		JsonObject unknown = new JsonObject();
		unknown.addProperty("payload", "nope");
		assertThrows(DevException.class, () -> MpDevCommands.parseSend(Fields.of(unknown)));

		assertThrows(DevException.class, () -> MpDevCommands.parseSend(Fields.of(new JsonObject())));
	}

	@Test void studio_resolution_defaults_to_own_and_names_a_studio_by_its_uuid() {
		StudioId own = StudioId.of(UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"));
		Anchors.setSelf(own);
		try {
			assertEquals(own, MpDevCommands.resolveStudio(null));
			assertEquals(own, MpDevCommands.resolveStudio(""));
			assertEquals(own, MpDevCommands.resolveStudio("   "));
			assertEquals(own, MpDevCommands.resolveStudio("own"));
			assertEquals(own, MpDevCommands.resolveStudio("OWN"));
			StudioId other = StudioId.of(UUID.fromString("11111111-2222-3333-4444-555555555555"));
			assertEquals(other, MpDevCommands.resolveStudio(other.owner().toString()));
			DevException error = assertThrows(DevException.class, () -> MpDevCommands.resolveStudio("not-a-uuid"));
			assertTrue(error.getMessage().contains("studio UUID or 'own'"), error.getMessage());
		} finally {
			Anchors.setSelf(StudioId.LOCAL);
		}
	}

	@Test void dev_state_reports_the_own_plot_from_the_hello_and_null_without_one() {
		Studios.reset(); // no server ever allocated a plot for the own studio
		try {
			assertTrue(MpDevCommands.devState().get("plot").isJsonNull());
			Studios.setPlot(Anchors.self(), 1, 128);
			JsonObject plot = MpDevCommands.devState().getAsJsonObject("plot");
			assertEquals(1, plot.get("index").getAsInt());
			assertEquals(128, plot.get("x").getAsInt());
			assertEquals(0, plot.get("y").getAsInt());
			assertEquals(0, plot.get("z").getAsInt());
		} finally {
			Studios.reset();
		}
	}

	@Test void dev_mp_relay_keeps_only_the_last_32_received_events() throws Exception {
		MpDevCommands.register(); // outside a client: plain handler map puts and listeners
		StudioId own = Anchors.self();
		for (int i = 0; i < 40; i++) {
			Studios.fireEvent(own, new PublicEvent.Say("kit", "user", null, i));
		}
		JsonArray events = relayEvents();
		assertEquals(32, events.size());
		// the oldest eight were dropped: what is left is events 8 to 39, in order
		assertEquals(8, events.get(0).getAsJsonObject().get("length").getAsInt());
		assertEquals(39, events.get(31).getAsJsonObject().get("length").getAsInt());
	}

	@Test void dev_mp_relay_is_emptied_by_the_clear_the_join_and_disconnect_hooks_call() throws Exception {
		MpDevCommands.register();
		// like dev.mp.fake in singleplayer: the mode is SINGLEPLAYER before and after, so no mode change follows
		Studios.fireEvent(Anchors.self(), new PublicEvent.Say("kit", "user", null, 7));
		assertFalse(relayEvents().isEmpty());
		Method clear = MpDevCommands.class.getDeclaredMethod("clearEvents");
		clear.setAccessible(true);
		clear.invoke(null);
		assertEquals(0, relayEvents().size());
	}

	/** The {@code events} array of a {@code dev.mp.relay} reply. */
	private static JsonArray relayEvents() throws Exception {
		Method relay = MpDevCommands.class.getDeclaredMethod("relay");
		relay.setAccessible(true);
		return ((JsonObject) relay.invoke(null)).getAsJsonArray("events");
	}

	private static RegistryFriendlyByteBuf buf() {
		return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
	}

	private static <T extends CustomPacketPayload> T round(StreamCodec<RegistryFriendlyByteBuf, T> codec, T value) {
		RegistryFriendlyByteBuf b = buf();
		try {
			codec.encode(b, value);
			T decoded = codec.decode(b);
			assertEquals(0, b.readableBytes());
			return decoded;
		} finally {
			b.release();
		}
	}
}
