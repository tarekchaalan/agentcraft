package dev.agentcraft.mp.station;

import static org.junit.jupiter.api.Assertions.*;

import dev.agentcraft.client.console.ConsoleTerminalRenderer;
import dev.agentcraft.client.decisions.DecisionQueue;
import dev.agentcraft.client.diff.MergeStationRenderer;
import dev.agentcraft.client.hq.StatusLampRenderer;
import dev.agentcraft.client.library.MemoryIndex;
import dev.agentcraft.client.mp.StudioView;
import dev.agentcraft.layout.Anchors;
import dev.agentcraft.mp.StudioId;
import dev.agentcraft.mp.state.AgentStateWire;
import dev.agentcraft.mp.state.Counts;
import dev.agentcraft.mp.state.GoalStatusWire;
import dev.agentcraft.mp.state.GoalSummary;
import dev.agentcraft.mp.state.PublicAgent;
import dev.agentcraft.mp.state.PublicPolicy;
import dev.agentcraft.mp.state.PublicStudioState;
import dev.agentcraft.mp.state.StationWire;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The studio-aware station view models: each takes a public state (or a public view) and must expose
 * only public data. The models never read the viewer's Foreman, decision queue, logs, memory shelves
 * or task titles, so a wrong implementation fails these assertions.
 */
class StationRemoteViewsTest {
	private static final StudioId REMOTE = StudioId.of(UUID.fromString("00000000-0000-0000-0000-0000000000aa"));

	private static PublicAgent agent(String id, String name, boolean active, boolean awaiting) {
		return new PublicAgent(id, name, id, awaiting ? AgentStateWire.WAITING_USER : AgentStateWire.EDITING, StationWire.DESK, active, false, awaiting, null);
	}

	private static PublicStudioState state(List<PublicAgent> agents, Counts counts, GoalSummary goal, PublicPolicy policy) {
		return new PublicStudioState(7, true, agents, counts, goal, List.of(), policy, null);
	}

	private static StudioView view(PublicStudioState state, boolean online) {
		return new StudioView(REMOTE, false, "Public Bob", online, Anchors.Layout.EMPTY, state, 1);
	}

	@Test
	void podium_remote_model_uses_the_public_count_and_first_awaiting_agent() {
		PublicAgent working = agent("kit", "Public Kit", true, false);
		PublicAgent waiting = agent("wren", "Public Wren", true, true);
		PublicStudioState s = state(List.of(working, waiting), new Counts(3, 2, 1, 4, 1, 2, 1), new GoalSummary(GoalStatusWire.ACTIVE, 0.5f, null),
			PublicPolicy.DEFAULT);
		DecisionQueue.RemotePodium m = DecisionQueue.remote(s);
		assertEquals(2, m.count());
		assertEquals("wren", m.agentId());
		// the public name, not the viewer's cast name for the same id
		assertEquals("Public Wren", m.agentName());
		assertEquals("2 decisions waiting", m.header());
	}

	@Test
	void podium_remote_model_without_a_waiting_agent_has_no_face() {
		PublicStudioState s = state(List.of(agent("kit", "Public Kit", true, false)), new Counts(0, 0, 0, 0, 0, 3, 0),
			new GoalSummary(GoalStatusWire.NONE, 0, null), PublicPolicy.DEFAULT);
		DecisionQueue.RemotePodium m = DecisionQueue.remote(s);
		assertEquals(3, m.count());
		assertNull(m.agentId());
		assertNull(m.agentName());
		assertEquals("3 decisions waiting", m.header());
		assertEquals(new DecisionQueue.RemotePodium(0, null, null), DecisionQueue.remote(null));
	}

	@Test
	void merge_remote_card_reports_open_merges_and_never_a_task_title() {
		PublicStudioState s = state(List.of(agent("kit", "Public Kit", true, false)), new Counts(0, 0, 0, 0, 0, 0, 3),
			new GoalSummary(GoalStatusWire.NONE, 0, null), PublicPolicy.DEFAULT);
		MergeStationRenderer.RemoteCard card = MergeStationRenderer.remoteCard(s);
		assertEquals(3, card.count());
		assertFalse(card.empty());
		assertEquals("merge waiting", card.title());
		MergeStationRenderer.RemoteCard none = MergeStationRenderer.remoteCard(state(List.of(), new Counts(0, 0, 0, 0, 0, 0, 0),
			new GoalSummary(GoalStatusWire.NONE, 0, null), PublicPolicy.DEFAULT));
		assertTrue(none.empty());
		assertEquals(0, none.count());
		assertEquals("", none.title());
		assertEquals(new MergeStationRenderer.RemoteCard(0, true, ""), MergeStationRenderer.remoteCard(null));
	}

	@Test
	void archive_remote_model_is_a_closed_shelf() {
		PublicStudioState s = state(List.of(agent("kit", "Public Kit", true, false)), new Counts(0, 0, 0, 0, 0, 0, 0),
			new GoalSummary(GoalStatusWire.NONE, 0, null), PublicPolicy.DEFAULT);
		MemoryIndex.RemoteArchive archive = MemoryIndex.remoteArchive(s);
		assertEquals("Archive", archive.label());
		assertFalse(archive.countShown());
		assertFalse(archive.fresh());
		assertEquals(new MemoryIndex.RemoteArchive("Archive", false, false), MemoryIndex.remoteArchive(null));
	}

	@Test
	void console_remote_model_is_idle_with_no_local_waiting() {
		PublicStudioState s = state(List.of(agent("kit", "Public Kit", true, true)), new Counts(0, 0, 0, 0, 0, 5, 0),
			new GoalSummary(GoalStatusWire.ACTIVE, 0.5f, null), PublicPolicy.DEFAULT);
		ConsoleTerminalRenderer.RemoteConsole online = ConsoleTerminalRenderer.remoteConsole(view(s, true));
		assertTrue(online.online());
		// the visitor terminal never shows the viewer's or even the public decision count
		assertEquals(0, online.waiting());
		assertTrue(online.idle());
		assertFalse(ConsoleTerminalRenderer.remoteConsole(view(s, false)).online());
	}

	@Test
	void hologram_remote_model_respects_status_and_the_goal_text_policy() {
		PublicPolicy optIn = new PublicPolicy(false, false, false, true);
		StatusLampRenderer.RemoteHologram active = StatusLampRenderer.remoteHologram(state(List.of(agent("kit", "Public Kit", true, false),
			agent("wren", "Public Wren", true, false)), new Counts(3, 2, 1, 4, 1, 2, 1), new GoalSummary(GoalStatusWire.ACTIVE, 0.5f, "Ship it"), optIn));
		assertTrue(active.hasGoal());
		assertTrue(active.goalTextVisible());
		assertEquals("Ship it", active.goalText());
		assertEquals("ACTIVE", active.statusWord());
		assertEquals("working", active.statusFamily());
		assertEquals(0.5, active.progress());
		assertEquals("50%", active.percent());
		assertEquals("4 of 11 tasks done  ·  2 in progress", active.line1());
		assertEquals("2 agents active  ·  1 in review", active.line2a());
		// remote decisions wait for the owner, so the line never says "need you"
		assertEquals("  ·  2 decisions waiting", active.line2b());
		StatusLampRenderer.RemoteHologram one = StatusLampRenderer.remoteHologram(state(List.of(agent("kit", "Public Kit", true, false)),
			new Counts(0, 0, 0, 0, 0, 1, 0), new GoalSummary(GoalStatusWire.ACTIVE, 0.5f, null), PublicPolicy.DEFAULT));
		assertEquals("  ·  1 decision waiting", one.line2b());
	}

	@Test
	void hologram_remote_model_withholds_unopted_goal_text_and_handles_none() {
		StatusLampRenderer.RemoteHologram hidden = StatusLampRenderer.remoteHologram(state(List.of(),
			new Counts(0, 0, 0, 0, 0, 0, 0), new GoalSummary(GoalStatusWire.ACTIVE, 0.25f, null), PublicPolicy.DEFAULT));
		assertTrue(hidden.hasGoal());
		assertFalse(hidden.goalTextVisible());
		assertNull(hidden.goalText());
		assertEquals("25%", hidden.percent());
		StatusLampRenderer.RemoteHologram none = StatusLampRenderer.remoteHologram(state(List.of(), new Counts(0, 0, 0, 0, 0, 0, 0),
			new GoalSummary(GoalStatusWire.NONE, 0, null), PublicPolicy.DEFAULT));
		assertFalse(none.hasGoal());
		assertEquals("NO GOAL", none.statusWord());
		assertEquals("idle", none.statusFamily());
		assertEquals(0.0, none.progress());
		assertEquals("–", none.percent());
		assertNull(none.goalText());
		StatusLampRenderer.RemoteHologram missing = StatusLampRenderer.remoteHologram(null);
		assertFalse(missing.hasGoal());
		assertEquals("NO GOAL", missing.statusWord());
	}
}
