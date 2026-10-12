package dev.agentcraft.client.hq;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import dev.agentcraft.block.LampStatus;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.ForemanStates;
import dev.agentcraft.layout.Anchors;
import dev.agentcraft.mp.state.LampStatusWire;
import dev.agentcraft.mp.state.PublicJson;
import dev.agentcraft.mp.state.WorldIntent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.*;

/**
 * Golden tests for the driver's two pure computations, for both fixtures: the full
 * {@link HqWorldDriver#compute(ForemanState)} (what singleplayer applies) must equal the pre-MP-07
 * driver's state (see {@link HqWorldDriverReference}), and the wire
 * {@link HqWorldDriver#compute(ForemanState, Anchors.Layout)} must equal that state with the
 * {@code ci:<repoId>} keys removed.
 */
class WorldIntentComputeTest {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

	@Test
	void computeMatchesReference_showcase() {
		ForemanState st = ForemanStates.showcase();
		assertMatchesReference(st, "sim-demo-fixture-busy");
	}

	@Test
	void computeMatchesReference_showcaseLate() {
		ForemanState st = ForemanStates.showcaseLate();
		assertMatchesReference(st, "sim-demo-fixture-late");
	}

	@Test
	void fullWantedEqualsReference_showcase() {
		assertFullEqualsReference(ForemanStates.showcase(), "sim-demo-fixture-busy");
	}

	@Test
	void fullWantedEqualsReference_showcaseLate() {
		assertFullEqualsReference(ForemanStates.showcaseLate(), "sim-demo-fixture-late");
	}

	@Test
	void showcaseRepoIdIsAbsentFromEncodedIntent() {
		assertRepoIdsAbsent(ForemanStates.showcase(), "sim-demo-fixture-busy");
	}

	@Test
	void showcaseLateRepoIdIsAbsentFromEncodedIntent() {
		assertRepoIdsAbsent(ForemanStates.showcaseLate(), "sim-demo-fixture-late");
	}

	@Test
	void everyLampKeyIsALegalBinding() {
		assertAllKeysLegal(ForemanStates.showcase());
		assertAllKeysLegal(ForemanStates.showcaseLate());
	}

	@Test
	void activeAgentWith17CharIdIsLeftOutOfLitMonitors() {
		ForemanState st = ForemanStates.showcase();
		String longId = "0123456789abcdefg"; // 17 chars: the wire cannot carry it
		JsonObject agent = new JsonObject();
		agent.addProperty("id", longId);
		agent.addProperty("name", "Long");
		agent.addProperty("state", "idle");
		agent.addProperty("active", true);
		JsonObject msg = new JsonObject();
		msg.addProperty("v", 1);
		msg.addProperty("type", "agent.upsert");
		msg.add("agent", agent);
		assertTrue(st.inject("agent.upsert", msg), "the long-id agent should be accepted into the state");

		WorldIntent intent = assertDoesNotThrow(() -> HqWorldDriver.compute(st, Anchors.Layout.EMPTY));
		assertFalse(intent.litMonitors().contains(longId), "a 17-char id must not reach litMonitors");
		assertFalse(intent.lamps().containsKey("agent:" + longId), "a 17-char id must not reach lamps");
	}

	@Test
	void aHundredActiveAgentsStayInsideTheWireCaps() {
		ForemanState st = ForemanStates.showcase();
		for (int i = 100; i < 200; i++) {
			JsonObject agent = new JsonObject();
			agent.addProperty("id", "bulk" + i);
			agent.addProperty("name", "Bulk " + i);
			agent.addProperty("state", "idle");
			agent.addProperty("active", true);
			JsonObject msg = new JsonObject();
			msg.addProperty("v", 1);
			msg.addProperty("type", "agent.upsert");
			msg.add("agent", agent);
			assertTrue(st.inject("agent.upsert", msg), "agent bulk" + i + " should be accepted into the state");
		}

		WorldIntent intent = assertDoesNotThrow(() -> HqWorldDriver.compute(st, Anchors.Layout.EMPTY));
		assertEquals(64, intent.lamps().size(), "the lamps fill the wire cap and stop there");
		assertEquals(64, intent.litMonitors().size(), "the lit monitors fill the wire cap and stop there");
		for (String fixed : List.of("goal", "goal:atrium", "decisions", "merge", "beacon")) {
			assertTrue(intent.lamps().containsKey(fixed), "fixed key '" + fixed + "' must survive the cap");
		}
		int ci = Math.min(st.repos().size(), 8);
		assertTrue(ci > 0, "the fixture has a repo");
		for (int n = 1; n <= ci; n++) {
			assertTrue(intent.lamps().containsKey("ci:#" + n), "ci:#" + n + " must survive the cap");
		}
		// Agent lamps follow the Foreman's order until the cap is reached; the rest have no entry.
		int room = 64 - 5 - ci;
		int seen = 0;
		for (String id : st.agents().keySet()) {
			assertEquals(seen++ < room, intent.lamps().containsKey("agent:" + id), "lamp of agent " + id);
		}
		assertTrue(intent.lamps().containsKey("agent:bulk100"), "the first injected agent keeps its lamp");
		assertFalse(intent.lamps().containsKey("agent:bulk199"), "the last injected agent has no lamp");
		assertTrue(intent.litMonitors().contains("bulk100"), "the first injected agent keeps its monitor");
		assertFalse(intent.litMonitors().contains("bulk199"), "the last injected agent has no lit monitor");
		assertEquals(intent, PublicJson.intentFromJson(PublicJson.toJson(intent)), "the codec accepts it");
	}

	@Test
	void wireFilterKeepsOnlyWhatTheWireCarries() {
		String longId = "0123456789abcdefg"; // 17 chars
		Map<String, LampStatus> lamps = new LinkedHashMap<>();
		lamps.put("goal", LampStatus.WORKING);
		lamps.put("ci:#8", LampStatus.DONE);
		lamps.put("ci:#9", LampStatus.DONE);
		lamps.put("ci:my-repo", LampStatus.ERROR);
		lamps.put("lobby", LampStatus.IDLE);
		lamps.put("agent:kit", LampStatus.THINKING);
		lamps.put("agent:" + longId, LampStatus.IDLE);
		HqWorldDriver.Wanted full = new HqWorldDriver.Wanted(lamps, true, false, Map.of("kit", true, "wren", false, longId, true));

		WorldIntent intent = assertDoesNotThrow(() -> HqWorldDriver.wireIntent(full, List.of("kit", "wren", longId), 1L << 40));
		assertEquals(Map.of("goal", LampStatusWire.WORKING, "ci:#8", LampStatusWire.DONE, "agent:kit", LampStatusWire.THINKING),
			intent.lamps(), "only legal bindings pass: no ci:<repoId>, no ci:#9, no 17-char agent");
		assertEquals(Set.of("kit"), intent.litMonitors(), "only lit monitors with a wire-legal id pass");
		assertTrue(intent.podiumOpen(), "the podium flag is copied");
		assertFalse(intent.mergeActive(), "the merge flag is copied");
		assertEquals(Integer.MAX_VALUE, intent.rev(), "a revision past int is clamped, not thrown");
	}

	@Test
	void roundTripsThroughPublicJson() {
		WorldIntent i = HqWorldDriver.compute(ForemanStates.showcase(), Anchors.Layout.EMPTY);
		WorldIntent back = PublicJson.intentFromJson(PublicJson.toJson(i));
		assertEquals(i.lamps(), back.lamps());
		assertEquals(i.podiumOpen(), back.podiumOpen());
		assertEquals(i.mergeActive(), back.mergeActive());
		assertEquals(i.litMonitors(), back.litMonitors());
		assertEquals(i.rev(), back.rev());
	}

	// ------------------------------------------------------------------ helpers

	/** The full (singleplayer) state is the pre-MP-07 driver's, {@code ci:<repoId>} included. */
	private static void assertFullEqualsReference(ForemanState st, String repoId) {
		HqWorldDriverReference.Reference ref = HqWorldDriverReference.compute(st);
		HqWorldDriver.Wanted full = HqWorldDriver.compute(st);
		assertEquals(new HqWorldDriver.Wanted(ref.lamps(), ref.podiumOpen(), ref.mergeActive(), ref.monitorLit()), full,
			"the full Wanted differs from the reference");
		assertEquals(LampStatus.forCi(st.repo(repoId).ci().wire()), full.lamps().get("ci:" + repoId),
			"the documented ci:<repoId> binding carries the repo's CI status");
		assertEquals(st.agents().keySet(), full.monitorLit().keySet(), "one monitor entry per agent, lit or not");
	}

	private static void assertMatchesReference(ForemanState st, String repoId) {
		WorldIntent intent = HqWorldDriver.compute(st, Anchors.Layout.EMPTY);
		HqWorldDriverReference.Reference ref = HqWorldDriverReference.compute(st);

		// Expected lamps: the reference's, without the ci:<repoId> keys.
		Map<String, LampStatusWire> expectedLamps = new LinkedHashMap<>();
		ref.lamps().forEach((k, v) -> {
			if (!isRepoKey(k)) {
				expectedLamps.put(k, LampStatusWire.valueOf(v.name()));
			}
		});
		assertEquals(expectedLamps, intent.lamps(), "lamps differ from the reference (minus ci:<repoId>)");

		assertEquals(ref.podiumOpen(), intent.podiumOpen(), "podiumOpen differs from the reference");
		assertEquals(ref.mergeActive(), intent.mergeActive(), "mergeActive differs from the reference");

		Set<String> expectedLit = new java.util.HashSet<>();
		ref.monitorLit().forEach((id, lit) -> {
			if (lit) {
				expectedLit.add(id);
			}
		});
		assertEquals(expectedLit, intent.litMonitors(), "litMonitors differ from the reference");

		// The fixture's own repo id never appears, and every ci: key is a slot.
		assertFalse(intent.lamps().containsKey("ci:" + repoId), "ci:<repoId> must be gone");
		assertTrue(intent.lamps().keySet().stream().noneMatch(WorldIntentComputeTest::isRepoKey),
			"no ci:<repoId> key may remain");
	}

	private static void assertRepoIdsAbsent(ForemanState st, String repoId) {
		WorldIntent intent = HqWorldDriver.compute(st, Anchors.Layout.EMPTY);
		String encoded = GSON.toJson(PublicJson.toJson(intent));
		assertFalse(encoded.contains(repoId), "encoded intent must not contain the repo id " + repoId);
		assertFalse(encoded.contains("ci:" + repoId), "encoded intent must not contain a ci:<repoId> key");
	}

	private static void assertAllKeysLegal(ForemanState st) {
		WorldIntent intent = HqWorldDriver.compute(st, Anchors.Layout.EMPTY);
		for (String key : intent.lamps().keySet()) {
			assertTrue(WorldIntent.isBinding(key), "lamp key '" + key + "' is not a legal binding");
		}
	}

	private static boolean isRepoKey(String key) {
		return key.startsWith("ci:") && !key.matches("ci:#[1-8]");
	}
}