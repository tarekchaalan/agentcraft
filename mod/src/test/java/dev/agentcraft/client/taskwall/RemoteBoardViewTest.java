package dev.agentcraft.client.taskwall;

import static org.junit.jupiter.api.Assertions.*;

import dev.agentcraft.mp.state.Counts;
import dev.agentcraft.mp.state.GoalStatusWire;
import dev.agentcraft.mp.state.GoalSummary;
import dev.agentcraft.mp.state.PublicPolicy;
import dev.agentcraft.mp.state.PublicStudioState;
import dev.agentcraft.mp.state.PublicTask;
import dev.agentcraft.mp.state.TaskStatusWire;
import java.util.List;
import org.junit.jupiter.api.Test;

/** MP-09: the remote task wall view is decided by the public record alone. */
class RemoteBoardViewTest {

	private static PublicStudioState noTasks() {
		return new PublicStudioState(2, true, List.of(), new Counts(2, 1, 4, 3, 5, 6, 7),
			new GoalSummary(GoalStatusWire.NONE, 0f, null), List.of(), PublicPolicy.DEFAULT, null);
	}

	private static PublicStudioState withTasks(List<PublicTask> tasks) {
		return new PublicStudioState(2, true, List.of(), new Counts(2, 1, 4, 3, 5, 6, 7),
			new GoalSummary(GoalStatusWire.NONE, 0f, null), List.of(), new PublicPolicy(false, false, true, false), tasks);
	}

	@Test
	void counts_are_per_status_and_todo_counts_its_blocked_subset() {
		RemoteBoardView v = RemoteBoardView.of(noTasks(), true);
		assertTrue(v.present());
		assertEquals(4, v.columns().size());
		// todo 2 + blocked 5: a blocked public task sits in the Todo lane, as on the own wall
		assertEquals(new RemoteBoardView.Column(RemoteBoardView.Lane.TODO, 7, 5), v.columns().get(0));
		assertEquals(new RemoteBoardView.Column(RemoteBoardView.Lane.DOING, 1, 0), v.columns().get(1));
		assertEquals(new RemoteBoardView.Column(RemoteBoardView.Lane.REVIEW, 4, 0), v.columns().get(2));
		assertEquals(new RemoteBoardView.Column(RemoteBoardView.Lane.DONE, 3, 0), v.columns().get(3));
		assertEquals(0, v.cards().size());
	}

	@Test
	void tasks_present_produce_title_cards_and_blocked_cards_sit_in_todo() {
		RemoteBoardView v = RemoteBoardView.of(withTasks(List.of(
			new PublicTask("t1", "First", TaskStatusWire.TODO, null),
			new PublicTask("t2", "Doing it", TaskStatusWire.DOING, null),
			new PublicTask("t3", "Stuck", TaskStatusWire.BLOCKED, null),
			new PublicTask("t4", "Shipped", TaskStatusWire.DONE, null),
			new PublicTask("t5", "Gone", TaskStatusWire.CANCELLED, null))), true);
		assertEquals(4, v.cards().size());
		assertEquals("First", v.cards().get(0).title());
		assertEquals(RemoteBoardView.Lane.DOING, v.cards().get(1).lane());
		assertEquals(RemoteBoardView.Lane.TODO, v.cards().get(2).lane());
		assertEquals("Stuck", v.cards().get(2).title());
		assertEquals(TaskStatusWire.BLOCKED, v.cards().get(2).status());
		assertEquals(RemoteBoardView.Lane.DONE, v.cards().get(3).lane());
	}

	@Test
	void no_state_is_not_present_with_zero_counts_and_no_cards() {
		RemoteBoardView v = RemoteBoardView.of(null, false);
		assertFalse(v.present());
		assertFalse(v.online());
		assertEquals(4, v.columns().size());
		for (RemoteBoardView.Column c : v.columns()) {
			assertEquals(0, c.count());
			assertEquals(0, c.blocked());
		}
		assertTrue(v.cards().isEmpty());
	}

	@Test
	void online_needs_both_the_presence_and_the_owners_foreman_link() {
		// the relay keeps the last state with foremanOnline cleared when the owner's Foreman drops
		PublicStudioState foremanDown = new PublicStudioState(2, false, List.of(), new Counts(2, 1, 4, 3, 5, 6, 7),
			new GoalSummary(GoalStatusWire.NONE, 0f, null), List.of(), PublicPolicy.DEFAULT, null);
		RemoteBoardView kept = RemoteBoardView.of(foremanDown, true);
		assertTrue(kept.present());
		assertFalse(kept.online());
		// the last counts stay under the veil
		assertEquals(new RemoteBoardView.Column(RemoteBoardView.Lane.TODO, 7, 5), kept.columns().get(0));
		assertFalse(RemoteBoardView.of(foremanDown, false).online());
		assertFalse(RemoteBoardView.of(noTasks(), false).online());
		assertTrue(RemoteBoardView.of(noTasks(), true).online());
	}

	@Test
	void cancelled_tasks_are_hidden_and_unknown_status_has_no_lane() {
		assertNull(RemoteBoardView.lane(TaskStatusWire.CANCELLED));
		RemoteBoardView v = RemoteBoardView.of(withTasks(List.of(new PublicTask("t", "Gone", TaskStatusWire.CANCELLED, null))), true);
		assertTrue(v.cards().isEmpty());
	}
}
