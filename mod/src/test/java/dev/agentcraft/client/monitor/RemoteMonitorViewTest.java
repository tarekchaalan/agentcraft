package dev.agentcraft.client.monitor;

import static org.junit.jupiter.api.Assertions.*;

import dev.agentcraft.mp.state.AgentStateWire;
import dev.agentcraft.mp.state.CiStatusWire;
import dev.agentcraft.mp.state.CiSlot;
import dev.agentcraft.mp.state.Counts;
import dev.agentcraft.mp.state.GoalStatusWire;
import dev.agentcraft.mp.state.GoalSummary;
import dev.agentcraft.mp.state.PublicAgent;
import dev.agentcraft.mp.state.PublicPolicy;
import dev.agentcraft.mp.state.PublicStudioState;
import dev.agentcraft.mp.state.StationWire;
import java.util.List;
import org.junit.jupiter.api.Test;

/** MP-09: the remote monitor view is decided by the public record alone. */
class RemoteMonitorViewTest {

	private static PublicStudioState withAgent(AgentStateWire state, boolean activityText, String activity) {
		return new PublicStudioState(3, true,
			List.of(new PublicAgent("kit", "Kit", "kit", state, StationWire.DESK, true, false, false, activity)),
			new Counts(1, 2, 3, 4, 5, 6, 7),
			new GoalSummary(GoalStatusWire.ACTIVE, 0.5f, null),
			List.of(new CiSlot(0, CiStatusWire.PASS)),
			new PublicPolicy(activityText, false, false, false), null);
	}

	@Test
	void agent_shows_public_name_and_state_and_hides_absent_activity() {
		RemoteMonitorView v = RemoteMonitorView.of(withAgent(AgentStateWire.EDITING, false, null), true, "kit");
		assertEquals(RemoteMonitorView.Kind.AGENT, v.kind());
		assertEquals("kit", v.agentId());
		assertEquals("Kit", v.name());
		assertEquals(AgentStateWire.EDITING, v.state());
		assertEquals(StationWire.DESK, v.station());
		assertTrue(v.active());
		assertTrue(v.activityHidden());
		assertNull(v.activity());
		assertEquals("working", v.family());
	}

	@Test
	void opted_in_activity_is_surfaced_once_and_only_from_the_public_field() {
		RemoteMonitorView v = RemoteMonitorView.of(withAgent(AgentStateWire.EDITING, true, "editing src/x.ts"), true, "kit");
		assertEquals("editing src/x.ts", v.activity());
		assertFalse(v.activityHidden());
	}

	@Test
	void missing_agent_and_absent_state_are_distinct_and_never_fall_back() {
		assertEquals(RemoteMonitorView.Kind.NO_AGENT, RemoteMonitorView.of(withAgent(AgentStateWire.IDLE, false, null), true, "ghost").kind());
		RemoteMonitorView none = RemoteMonitorView.of(null, true, "kit");
		assertEquals(RemoteMonitorView.Kind.NO_STUDIO, none.kind());
		assertNull(none.name());
		assertNull(none.state());
		assertNull(none.activity());
	}

	@Test
	void feed_carries_counts_and_goal_status_but_text_only_when_opted_in() {
		PublicStudioState state = new PublicStudioState(1, true, List.of(), new Counts(1, 2, 3, 4, 5, 6, 7),
			new GoalSummary(GoalStatusWire.PLANNING, 0.25f, null), List.of(), PublicPolicy.DEFAULT, null);
		RemoteMonitorView v = RemoteMonitorView.of(state, true, "feed");
		assertEquals(RemoteMonitorView.Kind.FEED, v.kind());
		assertEquals(1, v.counts().todo());
		assertEquals(5, v.counts().blocked());
		assertEquals(7, v.counts().openMerges());
		assertEquals(GoalStatusWire.PLANNING, v.goalStatus());
		assertEquals(0.25f, v.goalProgress());
		assertNull(v.goalText());
	}

	@Test
	void opted_in_goal_text_is_surfaced() {
		PublicStudioState state = new PublicStudioState(1, true, List.of(), new Counts(0, 0, 0, 0, 0, 0, 0),
			new GoalSummary(GoalStatusWire.ACTIVE, 0.5f, "ship the displays"), List.of(), new PublicPolicy(false, false, false, true), null);
		assertEquals("ship the displays", RemoteMonitorView.of(state, true, "feed").goalText());
	}

	@Test
	void none_goal_is_a_goal_without_text_or_progress() {
		PublicStudioState state = new PublicStudioState(1, true, List.of(), new Counts(0, 0, 0, 0, 0, 0, 0),
			new GoalSummary(GoalStatusWire.NONE, 0f, null), List.of(), PublicPolicy.DEFAULT, null);
		RemoteMonitorView v = RemoteMonitorView.of(state, true, "feed");
		assertEquals(GoalStatusWire.NONE, v.goalStatus());
		assertEquals(0f, v.goalProgress());
		assertNull(v.goalText());
	}

	@Test
	void offline_when_presence_or_the_owners_foreman_is_down() {
		assertTrue(RemoteMonitorView.of(withAgent(AgentStateWire.IDLE, false, null), true, "kit").online());
		assertFalse(RemoteMonitorView.of(withAgent(AgentStateWire.IDLE, false, null), false, "kit").online());
		PublicStudioState down = new PublicStudioState(1, false, List.of(), new Counts(0, 0, 0, 0, 0, 0, 0),
			new GoalSummary(GoalStatusWire.NONE, 0f, null), List.of(), PublicPolicy.DEFAULT, null);
		assertFalse(RemoteMonitorView.of(down, true, "feed").online());
	}

	@Test
	void every_public_agent_state_maps_to_a_known_family() {
		for (AgentStateWire s : AgentStateWire.values()) {
			String family = RemoteMonitorView.of(withAgent(s, false, null), true, "kit").family();
			assertTrue(List.of("idle", "thinking", "working", "waiting", "error", "done").contains(family), s + " -> " + family);
		}
		assertEquals("thinking", RemoteMonitorView.of(withAgent(AgentStateWire.THINKING, false, null), true, "kit").family());
		assertEquals("waiting", RemoteMonitorView.of(withAgent(AgentStateWire.WAITING_USER, false, null), true, "kit").family());
		assertEquals("error", RemoteMonitorView.of(withAgent(AgentStateWire.BLOCKED, false, null), true, "kit").family());
		assertEquals("done", RemoteMonitorView.of(withAgent(AgentStateWire.DONE, false, null), true, "kit").family());
	}
}
