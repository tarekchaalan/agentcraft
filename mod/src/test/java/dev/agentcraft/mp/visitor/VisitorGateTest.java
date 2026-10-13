package dev.agentcraft.mp.visitor;

import static org.junit.jupiter.api.Assertions.*;

import dev.agentcraft.client.mp.MpMode;
import dev.agentcraft.client.mp.StudioView;
import dev.agentcraft.client.mp.Studios;
import dev.agentcraft.client.mp.visitor.VisitorGate;
import dev.agentcraft.client.mp.visitor.VisitorGate.Route;
import dev.agentcraft.client.mp.visitor.VisitorGate.Station;
import dev.agentcraft.layout.Anchors;
import dev.agentcraft.mp.MpEvents;
import dev.agentcraft.mp.MpLog;
import dev.agentcraft.mp.StudioId;
import dev.agentcraft.mp.net.HelloS2C;
import dev.agentcraft.mp.net.MpProtocol;
import dev.agentcraft.mp.net.ServerInfo;
import dev.agentcraft.mp.state.AgentStateWire;
import dev.agentcraft.mp.state.Counts;
import dev.agentcraft.mp.state.GoalStatusWire;
import dev.agentcraft.mp.state.GoalSummary;
import dev.agentcraft.mp.state.PublicAgent;
import dev.agentcraft.mp.state.PublicPolicy;
import dev.agentcraft.mp.state.PublicStudioState;
import dev.agentcraft.mp.state.PublicTask;
import dev.agentcraft.mp.state.StationWire;
import dev.agentcraft.mp.state.TaskStatusWire;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/** MP-11: the pure visitor routing table, the public-only copy, the panel fit and the telemetry capture. */
class VisitorGateTest {
	private static StudioView own() {
		return new StudioView(StudioId.LOCAL, true, "", true, Anchors.Layout.EMPTY, null, 0);
	}

	private static StudioView remote(PublicStudioState state) {
		return new StudioView(StudioId.of(UUID.randomUUID()), false, "Bob", true, Anchors.Layout.EMPTY, state, 1);
	}

	/** Every opt-in text at once, so a leaked field is visible in the assertion. */
	private static PublicStudioState leakyState() {
		PublicPolicy policy = new PublicPolicy(true, true, true, true);
		List<PublicAgent> agents = List.of(new PublicAgent("kit", "SECRET-NAME", "kit",
			AgentStateWire.WAITING_USER, StationWire.USER, true, false, true, "editing SECRET/path.ts"));
		List<PublicTask> tasks = List.of(new PublicTask("t1", "SECRET-TASK", TaskStatusWire.DOING, "kit"));
		return new PublicStudioState(7, true, agents, new Counts(1, 2, 3, 4, 5, 6, 2),
			new GoalSummary(GoalStatusWire.ACTIVE, 0.4f, "SECRET-GOAL"), List.of(), policy, tasks);
	}

	@Test void singleplayer_routing_is_todays_handler_except_for_the_overlay_studio() {
		assertEquals(Route.HANDLER, VisitorGate.route(false, Optional.empty()));
		assertEquals(Route.HANDLER, VisitorGate.route(false, Optional.of(own())));
		assertEquals(Route.VISITOR, VisitorGate.route(false, Optional.of(remote(null))));
		assertEquals(Route.VISITOR, VisitorGate.route(false, Optional.of(remote(leakyState()))));
	}

	@Test void multiplayer_routing_takes_the_own_handler_only_in_the_own_studio() {
		assertEquals(Route.HANDLER, VisitorGate.route(true, Optional.of(own())));
		assertEquals(Route.VISITOR, VisitorGate.route(true, Optional.of(remote(null))));
		// no known studio: never the own handler
		assertEquals(Route.CONSUMED, VisitorGate.route(true, Optional.empty()));
	}

	@Test void multiplayer_knows_the_own_plot_from_the_hello_and_consumes_an_unsynced_neighbour() {
		UUID player = UUID.randomUUID();
		StudioId bob = StudioId.of(UUID.randomUUID());
		BlockPos ownPlot = new BlockPos(0, 66, 0);
		BlockPos bobPlot = new BlockPos(128, 66, 0);
		try {
			MpMode.joined(false, "test");
			MpMode.receiveHello(new HelloS2C(MpProtocol.VERSION, StudioId.of(player), 0, new ServerInfo(128, 12, 4, 10)), false, player);
			boolean multiplayer = MpMode.current() == MpMode.MULTIPLAYER;
			// the hello's plot is enough: the own stations work before the own layout arrives
			assertEquals(Route.HANDLER, VisitorGate.route(multiplayer, Studios.at(ownPlot)));
			// a neighbour's plot with nothing synced, then with the plot alone: consumed
			assertEquals(Route.CONSUMED, VisitorGate.route(multiplayer, Studios.at(bobPlot)));
			Studios.setPlot(bob, 1, 128);
			assertEquals(Route.CONSUMED, VisitorGate.route(multiplayer, Studios.at(bobPlot)));
			Studios.updatePresence(bob, "Bob", true);
			assertEquals(Route.VISITOR, VisitorGate.route(multiplayer, Studios.at(bobPlot)));
		} finally {
			MpMode.disconnected();
		}
	}

	@Test void agent_lookup_resolves_from_the_current_public_state() {
		// found: the agent is in the view's current state
		Optional<PublicAgent> found = VisitorGate.findAgent(remote(leakyState()), "kit");
		assertTrue(found.isPresent());
		assertEquals("kit", found.get().id());
		// no state: nothing to resolve
		assertTrue(VisitorGate.findAgent(remote(null), "kit").isEmpty());
		// a state without that agent: nothing to resolve
		PublicStudioState noAgents = new PublicStudioState(8, true, List.of(), new Counts(1, 2, 3, 4, 5, 6, 2),
			new GoalSummary(GoalStatusWire.NONE, 0, null), List.of(), PublicPolicy.DEFAULT, null);
		assertTrue(VisitorGate.findAgent(remote(noAgents), "kit").isEmpty());
		assertTrue(VisitorGate.findAgent(null, "kit").isEmpty());
	}

	@Test void the_gate_fails_closed_for_a_remote_block_with_a_handler() {
		StudioView remote = remote(leakyState());
		// remote and mapped: the mapped kind
		assertEquals(Optional.of(Station.PODIUM), VisitorGate.visitorKind(Optional.of(remote), Station.PODIUM));
		// remote and not mapped: the generic kind, still consumed
		assertEquals(Optional.of(Station.STATION), VisitorGate.visitorKind(Optional.of(remote), null));
		// own studio and no studio: today's handler, unchanged
		assertEquals(Optional.empty(), VisitorGate.visitorKind(Optional.of(own()), Station.PODIUM));
		assertEquals(Optional.empty(), VisitorGate.visitorKind(Optional.empty(), Station.PODIUM));
		assertEquals(Optional.empty(), VisitorGate.visitorKind(Optional.empty(), null));
	}

	@Test void station_lines_use_counts_and_opt_in_titles_only() {
		PublicStudioState state = leakyState();
		for (Station kind : List.of(Station.PODIUM, Station.MERGE, Station.ARCHIVE, Station.CATALOG, Station.LECTERN, Station.CONSOLE,
			Station.STATION)) {
			String text = String.join("\n", VisitorGate.stationLines(state, kind));
			assertFalse(text.contains("SECRET-TASK"), "task title leaked into " + kind);
			assertFalse(text.contains("SECRET-GOAL"), "goal text leaked into " + kind);
			assertFalse(text.contains("SECRET/path.ts"), "activity leaked into " + kind);
			assertFalse(text.contains("SECRET-NAME"), "agent name leaked into " + kind);
		}
		assertEquals(List.of("6 decisions waiting"), VisitorGate.stationLines(state, Station.PODIUM));
		assertEquals(List.of("A merge is waiting."), VisitorGate.stationLines(state, Station.MERGE));
		assertEquals(List.of("Memory is private to its owner."), VisitorGate.stationLines(state, Station.ARCHIVE));

		// the wall shows titles only when the owner opted in; with policy off the record carries no tasks
		String optedIn = String.join("\n", VisitorGate.stationLines(state, Station.TASK_WALL));
		assertTrue(optedIn.contains("SECRET-TASK"));
		PublicStudioState withheld = new PublicStudioState(8, true, List.of(), new Counts(1, 2, 3, 4, 5, 6, 2),
			new GoalSummary(GoalStatusWire.NONE, 0, null), List.of(), PublicPolicy.DEFAULT, null);
		String countsOnly = String.join("\n", VisitorGate.stationLines(withheld, Station.TASK_WALL));
		assertFalse(countsOnly.contains("SECRET-TASK"));
		assertTrue(countsOnly.contains("Todo 1"));
		assertFalse(String.join("\n", VisitorGate.stationLines(null, Station.PODIUM)).contains("SECRET"));
	}

	// The panel's fixed part with the paper panel's padding (8 + 14 + 16 + 12 + 12 + 9) and its row
	// height; 262 is a 960x540 window at GUI scale 2 (270 high) less the 4 px margins.
	private static final int CHROME = 71;
	private static final int ROW = 10;

	@Test void a_full_task_wall_is_cut_to_the_screen_with_the_remaining_count() {
		List<PublicTask> tasks = new ArrayList<>();
		for (int i = 0; i < 32; i++) {
			tasks.add(new PublicTask("t" + i, "Task " + i, TaskStatusWire.TODO, null));
		}
		PublicStudioState full = new PublicStudioState(9, true, List.of(), new Counts(32, 0, 0, 0, 0, 0, 0),
			new GoalSummary(GoalStatusWire.NONE, 0, null), List.of(), new PublicPolicy(false, false, true, false), tasks);
		int total = VisitorGate.stationLines(full, Station.TASK_WALL).size();
		assertEquals(34, total);

		int available = VisitorGate.rowsAvailable(262, CHROME, ROW);
		assertEquals(19, available);
		VisitorGate.Fit fit = VisitorGate.fit(total, available);
		assertEquals(new VisitorGate.Fit(18, 16, true), fit);
		assertEquals(19, fit.rows());
		assertTrue(CHROME + fit.rows() * ROW <= 262, "the panel is taller than the screen");
		assertEquals("+ 16 more", VisitorGate.moreLine(fit.remaining()));
	}

	@Test void a_body_that_fits_is_shown_whole_with_no_indicator() {
		assertEquals(new VisitorGate.Fit(2, 0, false), VisitorGate.fit(2, 19));
		assertEquals(new VisitorGate.Fit(19, 0, false), VisitorGate.fit(19, 19));
		assertEquals(new VisitorGate.Fit(0, 0, false), VisitorGate.fit(0, 19));
		assertEquals(new VisitorGate.Fit(34, 0, false), VisitorGate.fit(34, VisitorGate.rowsAvailable(1080, CHROME, ROW)));
		// one row too many: the last row becomes the indicator, and it counts two lines
		assertEquals(new VisitorGate.Fit(18, 2, true), VisitorGate.fit(20, 19));
	}

	@Test void a_screen_too_short_for_a_row_never_gives_a_negative_count() {
		// shorter than the header and footer: no row, no indicator
		assertEquals(0, VisitorGate.rowsAvailable(40, CHROME, ROW));
		assertEquals(0, VisitorGate.rowsAvailable(CHROME + ROW - 1, CHROME, ROW));
		assertEquals(0, VisitorGate.rowsAvailable(262, CHROME, 0));
		assertEquals(new VisitorGate.Fit(0, 34, false), VisitorGate.fit(34, 0));
		assertEquals(new VisitorGate.Fit(0, 34, false), VisitorGate.fit(34, -3));
		// room for exactly one row: the indicator alone
		assertEquals(1, VisitorGate.rowsAvailable(CHROME + ROW, CHROME, ROW));
		assertEquals(new VisitorGate.Fit(0, 34, true), VisitorGate.fit(34, 1));
		for (int available = -2; available <= 40; available++) {
			VisitorGate.Fit fit = VisitorGate.fit(34, available);
			assertTrue(fit.shown() >= 0 && fit.remaining() >= 0, "negative count at " + available);
			assertEquals(34, fit.shown() + fit.remaining());
			assertTrue(fit.rows() <= Math.max(0, available), "more rows than fit at " + available);
		}
	}

	@Test void agent_lines_say_the_owner_and_never_the_viewer() {
		PublicAgent waiting = new PublicAgent("kit", "Kit", "kit", AgentStateWire.WAITING_USER, StationWire.USER,
			true, false, true, null);
		String text = String.join("\n", VisitorGate.agentLines(waiting, "Bob"));
		assertTrue(text.contains("Waiting for Bob"));
		assertFalse(text.toLowerCase(Locale.ROOT).contains("you"));
		assertFalse(String.join("\n", VisitorGate.agentLines(waiting, null)).contains("Waiting for null"));

		PublicAgent active = new PublicAgent("kit", "Kit", "kit", AgentStateWire.EDITING, StationWire.DESK,
			true, false, false, "editing SECRET/path.ts");
		String activeText = String.join("\n", VisitorGate.agentLines(active, "Bob"));
		assertTrue(activeText.contains("Editing · desk"));
		assertFalse(activeText.contains("SECRET/path.ts"));
	}

	@Test void visitor_readonly_capture_has_correlators_and_closed_tokens() {
		UUID viewer = UUID.fromString("11111111-1111-1111-1111-111111111111");
		StudioView studio = remote(leakyState());
		try (MpLog.Capture capture = MpLog.capture()) {
			assertFalse(VisitorGate.logOpen(Route.HANDLER, Station.PODIUM, remote(leakyState()), viewer));
			VisitorGate.logOpen(Station.PODIUM, studio, viewer);
			VisitorGate.logOpen(Route.VISITOR, Station.AGENT, studio, viewer);
			VisitorGate.logOpen(Route.VISITOR, Station.STATION, studio, viewer);
			assertEquals(List.of(
				"event=visitor_readonly player=" + viewer + " studio=" + studio.id().owner()
					+ " plot=-1 rev=7 station=podium action=open",
				"event=visitor_readonly player=" + viewer + " studio=" + studio.id().owner()
					+ " plot=-1 rev=7 station=agent action=open",
				"event=visitor_readonly player=" + viewer + " studio=" + studio.id().owner()
					+ " plot=-1 rev=7 station=station action=open"), capture.lines());
			assertFalse(capture.lines().toString().contains("SECRET"));
		}
		assertEquals("debug", MpEvents.CATALOG.get(MpEvents.VISITOR_READONLY));
	}
}
