package dev.agentcraft.client.foreman;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.agentcraft.client.foreman.Protocol.AgentState;
import dev.agentcraft.client.foreman.Protocol.TaskStatus;
import org.junit.jupiter.api.Test;

class ForemanJsonTest {
	private record Holder(AgentState state, String name) {
	}

	@Test
	void wireEnumsReadCaseInsensitively() {
		Holder h = ForemanJson.GSON.fromJson("{\"state\":\"Waiting_User\",\"name\":\"x\"}", Holder.class);
		assertEquals(AgentState.WAITING_USER, h.state());
	}

	@Test
	void unknownEnumValueFallsBackToUnknown() {
		Holder h = ForemanJson.GSON.fromJson("{\"state\":\"levitating\"}", Holder.class);
		assertEquals(AgentState.UNKNOWN, h.state());
		h = ForemanJson.GSON.fromJson("{\"state\":42}", Holder.class);
		assertEquals(AgentState.UNKNOWN, h.state());
	}

	@Test
	void nullEnumStaysNull() {
		assertNull(ForemanJson.GSON.fromJson("{\"state\":null}", Holder.class).state());
	}

	@Test
	void enumsWriteAsLowerCaseWireNames() {
		assertEquals("{\"state\":\"thinking\",\"name\":\"a<b\"}",
			ForemanJson.GSON.toJson(new Holder(AgentState.THINKING, "a<b")));
		assertEquals("\"todo\"", ForemanJson.GSON.toJson(TaskStatus.TODO));
	}

	@Test
	void agentStateFamilies() {
		assertEquals("working", AgentState.EDITING.family());
		assertEquals("waiting", AgentState.WAITING_USER.family());
		assertEquals("error", AgentState.BLOCKED.family());
		assertEquals("idle", AgentState.UNKNOWN.family());
	}

	@Test
	void msgBuilderSetsEnvelopeAndSkipsNulls() {
		JsonObject o = ForemanJson.msg("goal.submit").put("text", "hi").put("n", 3).put("ok", true)
			.put("missing", null).json();
		assertEquals(Protocol.VERSION, o.get("v").getAsInt());
		assertEquals("goal.submit", o.get("type").getAsString());
		assertEquals("hi", o.get("text").getAsString());
		assertEquals(3, o.get("n").getAsInt());
		assertTrue(o.get("ok").getAsBoolean());
		assertFalse(o.has("missing"));
	}

	@Test
	void msgBuilderAcceptsJsonElementsAndObjects() {
		JsonObject inner = JsonParser.parseString("{\"a\":1}").getAsJsonObject();
		JsonObject o = ForemanJson.msg("t").put("el", inner).put("h", new Holder(AgentState.DONE, "n")).json();
		assertEquals(1, o.getAsJsonObject("el").get("a").getAsInt());
		assertEquals("done", o.getAsJsonObject("h").get("state").getAsString());
	}

	@Test
	void readDelegatesToGson() {
		Holder h = ForemanJson.read(JsonParser.parseString("{\"name\":\"z\"}"), Holder.class);
		assertEquals("z", h.name());
		assertNull(h.state());
	}
}
