package dev.agentcraft.client.hq;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import dev.agentcraft.block.LampStatus;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.ForemanStates;
import dev.agentcraft.client.foreman.Protocol;
import dev.agentcraft.layout.Anchor;
import dev.agentcraft.layout.Anchors;
import dev.agentcraft.mp.net.ServerInfo;
import dev.agentcraft.mp.state.LampStatusWire;
import dev.agentcraft.mp.state.PublicJson;
import dev.agentcraft.mp.state.WorldIntent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.*;

/**
 * Client-side behaviour of {@link HqWorldDriver} through its test seam: the mode/canSend flag, the
 * {@link ServerInfo} rate, the send function, the clock and the application callback are all passed
 * in, so no client, network or world is needed.
 */
class HqWorldDriverTickTest {
	private static final ServerInfo INFO = new ServerInfo(128, 12, 4, 10);
	private static final Anchors.Layout LAYOUT = new Anchors.Layout("test", 1,
		new Anchors.Bounds(0, 0, 0, 10, 10, 10),
		Map.of("decision_podium", new Anchor("decision_podium", 0, 0, 0, 0, 0)));

	@BeforeEach
	void setUp() {
		HqWorldDriver.resetForTest();
	}

	// ------------------------------------------------------------------ application scheduling

	@Test
	void changedVisibleStateAppliesThroughTheCallback() {
		ForemanState st = ForemanStates.showcase();
		List<HqWorldDriver.Wanted> applied = new ArrayList<>();
		HqWorldDriver.Applier recorder = (w, b, p, m) -> applied.add(w);

		HqWorldDriver.tick(st, LAYOUT, false, null, pacer(), recorder);
		assertEquals(1, applied.size(), "the first visible state is applied once");

		patch(st, "agent", "marlow", "state", "error");
		HqWorldDriver.tick(st, LAYOUT, false, null, pacer(), recorder);
		assertEquals(2, applied.size(), "a changed visible state applies again");
		assertEquals(dev.agentcraft.block.LampStatus.ERROR, applied.get(1).lamps().get("agent:marlow"),
			"the applied Wanted carries the new state");
	}

	@Test
	void revisionThatChangesNothingVisibleAppliesAndSendsNothing() {
		ForemanState st = ForemanStates.showcase();
		List<HqWorldDriver.Wanted> applied = new ArrayList<>();
		List<WorldIntent> sent = new ArrayList<>();
		HqWorldDriver.Applier recorder = (w, b, p, m) -> applied.add(w);
		HqWorldDriver.Pacer pacer = new HqWorldDriver.Pacer(System::nanoTime, sent::add);

		HqWorldDriver.tick(st, LAYOUT, true, INFO, pacer, recorder);
		assertEquals(1, applied.size());

		// Bump the revision without changing anything compute() reads.
		patch(st, "agent", "marlow", "activity", "a different activity text");
		HqWorldDriver.tick(st, LAYOUT, true, INFO, pacer, recorder);
		assertEquals(1, applied.size(), "a revision with no visible change must not apply");
		assertEquals(1, sent.size(), "a revision with no visible change must not send");
	}

	@Test
	void resyncAppliesAndSendsTheSameStateAgain() {
		ForemanState st = ForemanStates.showcase();
		List<HqWorldDriver.Wanted> applied = new ArrayList<>();
		List<WorldIntent> sent = new ArrayList<>();
		HqWorldDriver.Applier recorder = (w, b, p, m) -> applied.add(w);
		HqWorldDriver.Pacer pacer = new HqWorldDriver.Pacer(System::nanoTime, sent::add);

		for (int i = 0; i < 40; i++) {
			HqWorldDriver.tick(st, LAYOUT, true, INFO, pacer, recorder);
		}
		assertEquals(2, applied.size(), "the 40-tick resync applies the same state once more");
		assertEquals(2, sent.size(), "the 40-tick resync sends the same snapshot once more");
		assertEquals(sent.get(0), sent.get(1), "the resync sends the same snapshot");
	}

	@Test
	void noServerInfoSendsNothing() {
		ForemanState st = ForemanStates.showcase();
		List<WorldIntent> sent = new ArrayList<>();
		HqWorldDriver.Pacer pacer = new HqWorldDriver.Pacer(System::nanoTime, sent::add);

		HqWorldDriver.tick(st, LAYOUT, true, null, pacer, (w, b, p, m) -> {});
		assertTrue(sent.isEmpty(), "without server info there is no send");
	}

	@Test
	void canSendFalseSendsNothing() {
		ForemanState st = ForemanStates.showcase();
		List<WorldIntent> sent = new ArrayList<>();
		HqWorldDriver.Pacer pacer = new HqWorldDriver.Pacer(System::nanoTime, sent::add);

		HqWorldDriver.tick(st, LAYOUT, false, INFO, pacer, (w, b, p, m) -> {});
		assertTrue(sent.isEmpty(), "when the channel is closed there is no send");
	}

	// ------------------------------------------------------------------ pacer

	@Test
	void pacerSendsAtMostTheRateAndKeepsTheNewest() {
		List<WorldIntent> sent = new ArrayList<>();
		long[] now = {0L};
		HqWorldDriver.Pacer pacer = new HqWorldDriver.Pacer(() -> now[0], sent::add);
		WorldIntent a = intent(1, Set.of("a"));
		WorldIntent b = intent(2, Set.of("b"));
		WorldIntent c = intent(3, Set.of("c"));

		pacer.offer(a);
		pacer.flush(1);
		pacer.offer(b); // window full: kept
		pacer.flush(1);
		pacer.offer(c); // replaces b
		pacer.flush(1);
		assertEquals(List.of(a), sent, "only one send fits the one-second window");

		now[0] = 1_100_000_000L; // the window has passed
		pacer.flush(1);
		assertEquals(List.of(a, c), sent, "the newest unsent intent must arrive when the window opens");
	}

	@Test
	void pacerSendsTheNewestComputedStateAfterAWindow() {
		ForemanState st = ForemanStates.showcase();
		List<WorldIntent> sent = new ArrayList<>();
		long[] now = {0L};
		HqWorldDriver.Pacer pacer = new HqWorldDriver.Pacer(() -> now[0], sent::add);
		ServerInfo info = new ServerInfo(128, 12, 4, 1); // one send per second
		HqWorldDriver.Applier noop = (w, b, p, m) -> {};

		HqWorldDriver.tick(st, LAYOUT, true, info, pacer, noop); // A sent
		assertEquals(1, sent.size());

		patch(st, "agent", "marlow", "state", "error"); // B
		HqWorldDriver.tick(st, LAYOUT, true, info, pacer, noop);
		assertEquals(1, sent.size(), "B is queued, not sent");

		// The window passes while B is still queued, and the visible state changes to C before the
		// next tick: that tick must spend its free slot on C. Flushing before computing would send B.
		now[0] = 1_100_000_000L;
		patch(st, "agent", "marlow", "state", "thinking"); // C
		HqWorldDriver.tick(st, LAYOUT, true, info, pacer, noop);
		assertEquals(2, sent.size(), "the free slot is spent on this tick");
		assertEquals(LampStatusWire.THINKING, sent.get(1).lamps().get("agent:marlow"),
			"the newest state (C) is sent, not the obsolete queued B (ERROR)");

		now[0] = 2_200_000_000L; // another window: nothing is left to send
		HqWorldDriver.tick(st, LAYOUT, true, info, pacer, noop);
		assertEquals(2, sent.size(), "B was replaced, not kept behind C");
	}

	// ------------------------------------------------------------------ podium override

	@Test
	void podiumOverrideClosesOnTheSameTick() {
		ForemanState st = ForemanStates.showcase(); // has open decisions: compute says open
		List<HqWorldDriver.Wanted> applied = new ArrayList<>();
		HqWorldDriver.Applier recorder = (w, b, p, m) -> applied.add(w);

		HqWorldDriver.tick(st, LAYOUT, false, null, pacer(), recorder);
		assertTrue(applied.get(0).podiumOpen());

		HqWorldDriver.setPodiumOverride(false);
		HqWorldDriver.tick(st, LAYOUT, false, null, pacer(), recorder); // no new revision
		assertEquals(2, applied.size(), "the override must apply on the very next tick");
		assertFalse(applied.get(1).podiumOpen(), "the override closed the podium");
	}

	@Test
	void podiumOverrideReopensOnTheSameTickWhenCleared() {
		ForemanState st = ForemanStates.showcase();
		List<HqWorldDriver.Wanted> applied = new ArrayList<>();
		HqWorldDriver.Applier recorder = (w, b, p, m) -> applied.add(w);

		HqWorldDriver.tick(st, LAYOUT, false, null, pacer(), recorder);
		HqWorldDriver.setPodiumOverride(false);
		HqWorldDriver.tick(st, LAYOUT, false, null, pacer(), recorder);
		assertFalse(applied.get(1).podiumOpen());

		HqWorldDriver.setPodiumOverride(null);
		HqWorldDriver.tick(st, LAYOUT, false, null, pacer(), recorder); // no new revision
		assertEquals(3, applied.size(), "clearing the override must apply on the very next tick");
		assertTrue(applied.get(2).podiumOpen(), "clearing the override reopened the podium");
	}

	@Test
	void podiumOverrideIsDroppedWhenItEqualsTheComputedValue() {
		ForemanState st = ForemanStates.showcaseLate(); // no open decisions: compute says closed
		List<HqWorldDriver.Wanted> applied = new ArrayList<>();
		HqWorldDriver.Applier recorder = (w, b, p, m) -> applied.add(w);

		HqWorldDriver.tick(st, LAYOUT, false, null, pacer(), recorder);
		assertFalse(applied.get(0).podiumOpen());

		// Setting an override equal to compute is redundant: it is dropped, no world work.
		HqWorldDriver.setPodiumOverride(false);
		HqWorldDriver.tick(st, LAYOUT, false, null, pacer(), recorder);
		assertEquals(1, applied.size(), "an equal override does not change the world");

		// A later open override still takes effect, proving the equal one was dropped.
		HqWorldDriver.setPodiumOverride(true);
		HqWorldDriver.tick(st, LAYOUT, false, null, pacer(), recorder);
		assertEquals(2, applied.size());
		assertTrue(applied.get(1).podiumOpen(), "the override still opens the podium");
	}

	@Test
	void aWishThatAgreesWithWhatIsShownIsNotStoredAsAnOverride() {
		ForemanState st = ForemanStates.showcase(); // open decisions: the podium is shown open
		HqWorldDriver.tick(st, LAYOUT, false, null, pacer(), (w, b, p, m) -> {});

		// The renderer repeats "open" every frame. Had it been stored, it would force a closed intent open.
		HqWorldDriver.setPodiumOverride(true);
		WorldIntent closed = intent(1, Set.of());
		assertFalse(HqWorldDriver.applyPodiumOverride(closed).podiumOpen(), "an agreeing wish is not an override");

		// A wish that differs is stored.
		HqWorldDriver.setPodiumOverride(false);
		WorldIntent open = new WorldIntent(1, Map.of("goal", LampStatusWire.IDLE), true, false, Set.of());
		assertFalse(HqWorldDriver.applyPodiumOverride(open).podiumOpen(), "a differing wish overrides");
	}

	// ------------------------------------------------------------------ full state locally, filtered state on the wire

	@Test
	void singleplayerAppliesEverythingTheWireCannotCarry() {
		ForemanState st = oddState();
		List<HqWorldDriver.Wanted> applied = new ArrayList<>();
		List<WorldIntent> sent = new ArrayList<>();
		HqWorldDriver.Pacer pacer = new HqWorldDriver.Pacer(System::nanoTime, sent::add);

		assertDoesNotThrow(() -> HqWorldDriver.tick(st, LAYOUT, false, null, pacer, (w, b, p, m) -> applied.add(w)));
		assertEquals(1, applied.size());
		assertHasEverything(st, applied.get(0));
		assertEquals(applied.get(0), HqWorldDriver.wanted(), "dev.state.hq shows what singleplayer applies");
		assertTrue(sent.isEmpty(), "singleplayer sends nothing");
	}

	@Test
	void multiplayerOffersOnlyTheFilteredIntent() {
		ForemanState st = oddState();
		List<HqWorldDriver.Wanted> applied = new ArrayList<>();
		List<WorldIntent> sent = new ArrayList<>();
		HqWorldDriver.Pacer pacer = new HqWorldDriver.Pacer(System::nanoTime, sent::add);

		assertDoesNotThrow(() -> HqWorldDriver.tick(st, LAYOUT, true, INFO, pacer, (w, b, p, m) -> applied.add(w)));
		assertEquals(1, applied.size());
		assertHasEverything(st, applied.get(0)); // the Applier seam is not narrowed by the wire

		assertEquals(1, sent.size());
		WorldIntent intent = sent.get(0);
		assertEquals(64, intent.lamps().size(), "the lamps fill the wire cap and stop there");
		assertEquals(64, intent.litMonitors().size(), "the lit monitors fill the wire cap and stop there");
		String encoded = PublicJson.toJson(intent).toString();
		for (String repoId : st.repos().keySet()) {
			assertFalse(intent.lamps().containsKey("ci:" + repoId), "ci:<repoId> must not be sent");
			assertFalse(encoded.contains(repoId), "the encoded intent must not contain the repo id " + repoId);
		}
		for (int n = 1; n <= 12; n++) {
			assertEquals(n <= 8, intent.lamps().containsKey("ci:#" + n), "ci:#" + n + " on the wire");
		}
		for (String key : intent.lamps().keySet()) {
			assertTrue(WorldIntent.isBinding(key), "lamp key '" + key + "' is not a legal binding");
		}
		assertFalse(intent.lamps().containsKey("agent:" + LONG_ID), "a 17-char id must not reach lamps");
		assertFalse(intent.litMonitors().contains(LONG_ID), "a 17-char id must not reach litMonitors");
		assertEquals(intent, PublicJson.intentFromJson(PublicJson.toJson(intent)), "the codec accepts it");

		HqWorldDriver.Wanted shown = HqWorldDriver.wanted();
		assertNotNull(shown);
		assertEquals(intent.lamps().keySet(), shown.lamps().keySet(), "dev.state.hq shows what the server will show");
		assertEquals(intent.litMonitors(), shown.monitorLit().keySet());
	}

	@Test
	void aChangePastTheWireCapsAppliesLocallyAndSendsNothingNew() {
		ForemanState st = oddState();
		List<HqWorldDriver.Wanted> applied = new ArrayList<>();
		List<WorldIntent> sent = new ArrayList<>();
		HqWorldDriver.Applier recorder = (w, b, p, m) -> applied.add(w);
		HqWorldDriver.Pacer pacer = new HqWorldDriver.Pacer(System::nanoTime, sent::add);

		HqWorldDriver.tick(st, LAYOUT, true, INFO, pacer, recorder);
		assertEquals(1, applied.size());
		assertEquals(1, sent.size());
		assertFalse(sent.get(0).lamps().containsKey("agent:bulk190"), "agent 91 of 100 is past the lamp cap");
		assertFalse(sent.get(0).litMonitors().contains("bulk190"), "agent 91 of 100 is past the monitor cap");

		// idle -> done changes this agent's lamp only (neither state moves the beacon).
		patch(st, "agent", "bulk190", "state", "done");
		HqWorldDriver.tick(st, LAYOUT, true, INFO, pacer, recorder);
		assertEquals(2, applied.size(), "a change the wire does not carry is still applied locally");
		assertEquals(LampStatus.DONE, applied.get(1).lamps().get("agent:bulk190"), "the applied Wanted carries the new state");
		assertEquals(1, sent.size(), "the filtered intent did not change, so nothing new is sent");
	}

	// ------------------------------------------------------------------ content compare

	@Test
	void contentEqualIgnoresRevision() {
		WorldIntent a = new WorldIntent(1, Map.of("goal", LampStatusWire.IDLE), true, false, Set.of("x"));
		WorldIntent b = new WorldIntent(2, Map.of("goal", LampStatusWire.IDLE), true, false, Set.of("x"));
		assertTrue(HqWorldDriver.contentEqual(a, b), "differing only in rev is equal content");

		WorldIntent c = new WorldIntent(1, Map.of("goal", LampStatusWire.WORKING), true, false, Set.of("x"));
		assertFalse(HqWorldDriver.contentEqual(a, c), "a different lamp is different content");
	}

	// ------------------------------------------------------------------ helpers

	private static HqWorldDriver.Pacer pacer() {
		return new HqWorldDriver.Pacer(System::nanoTime, intent -> {});
	}

	/** 17 characters: one more than the wire carries. */
	private static final String LONG_ID = "0123456789abcdefg";

	/**
	 * The busy showcase plus data past every wire limit: 12 repos, an active agent with a 17-character
	 * id, an off-shift agent and 100 more active agents (108 in all).
	 */
	private static ForemanState oddState() {
		ForemanState st = ForemanStates.showcase();
		for (int i = 2; i <= 12; i++) {
			JsonObject repo = new JsonObject();
			repo.addProperty("id", "extra-repo-" + i);
			repo.addProperty("ci", i % 2 == 0 ? "pass" : "running");
			upsert(st, "repo", repo);
		}
		upsert(st, "agent", agent(LONG_ID, true));
		upsert(st, "agent", agent("offshift", false));
		for (int i = 100; i < 200; i++) {
			upsert(st, "agent", agent("bulk" + i, true));
		}
		return st;
	}

	private static JsonObject agent(String id, boolean active) {
		JsonObject agent = new JsonObject();
		agent.addProperty("id", id);
		agent.addProperty("state", "idle");
		agent.addProperty("active", active);
		return agent;
	}

	private static void upsert(ForemanState st, String kind, JsonObject value) {
		JsonObject msg = new JsonObject();
		msg.addProperty("v", 1);
		msg.addProperty("type", kind + ".upsert");
		msg.add(kind, value);
		assertTrue(st.inject(kind + ".upsert", msg), kind + " " + value.get("id") + " should be accepted into the state");
	}

	/** The pre-MP-07 singleplayer state: nothing is capped, renamed or left out. */
	private static void assertHasEverything(ForemanState st, HqWorldDriver.Wanted w) {
		HqWorldDriverReference.Reference ref = HqWorldDriverReference.compute(st);
		assertEquals(new HqWorldDriver.Wanted(ref.lamps(), ref.podiumOpen(), ref.mergeActive(), ref.monitorLit()), w,
			"the applied Wanted differs from the reference");
		assertEquals(12, st.repos().size());
		int n = 0;
		for (Protocol.Repo r : st.repos().values()) {
			LampStatus ci = LampStatus.forCi(r.ci().wire());
			assertEquals(ci, w.lamps().get("ci:#" + (++n)), "ci:#" + n);
			assertEquals(ci, w.lamps().get("ci:" + r.id()), "ci:" + r.id());
		}
		assertEquals(108, st.agents().size());
		for (Protocol.Agent a : st.agents().values()) {
			assertTrue(w.lamps().containsKey("agent:" + a.id()), "lamp of agent " + a.id());
			assertEquals(a.isActive(), w.monitorLit().get(a.id()), "monitor of agent " + a.id());
		}
		assertEquals(LampStatus.IDLE, w.lamps().get("agent:" + LONG_ID), "the 17-char agent keeps its lamp");
		assertEquals(LampStatus.OFF, w.lamps().get("agent:offshift"), "an off-shift agent's lamp is off");
	}

	private static void patch(ForemanState st, String kind, String id, String key, String value) {
		JsonObject set = new JsonObject();
		set.addProperty(key, value);
		st.patch(kind, id, set);
	}

	private static WorldIntent intent(int rev, Set<String> lit) {
		return new WorldIntent(rev, Map.of("goal", LampStatusWire.IDLE), false, false, lit);
	}
}
