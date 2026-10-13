package dev.agentcraft.client.taskwall;

import dev.agentcraft.mp.state.PublicStudioState;
import dev.agentcraft.mp.state.PublicTask;
import dev.agentcraft.mp.state.TaskStatusWire;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The facts a Task Wall in a <b>remote</b> studio shows, derived only from a {@link PublicStudioState}.
 * Pure: no Minecraft, no {@code ForemanState}, no {@code Task}. The own studio keeps {@link TaskBoard}'s
 * existing Foreman path.
 *
 * <p>Counts are authoritative and per status. A blocked public task sits in the {@link Lane#TODO} lane,
 * so the Todo count is {@code Counts.todo() + Counts.blocked()} (as the own wall's Todo column counts
 * its whole list) and the Todo lane also carries {@code Counts.blocked} so the header can show the red
 * "N blocked" count. A task list is present only when the owner opted task titles in; then it is a
 * partial detail list under the counts.
 *
 * <p>{@code online} is false when the studio's presence is down or the owner's Foreman link is down
 * ({@code foremanOnline}): the relay keeps the last state with that flag cleared, and the wall then
 * shows the "Foreman offline" veil over it, as {@code RemoteMonitorView} does for a monitor.
 */
public record RemoteBoardView(int rev, List<Column> columns, List<Card> cards, boolean online, boolean present) {

	public enum Lane {
		TODO, DOING, REVIEW, DONE
	}

	/** One of the four lanes: its public count and, in Todo, the blocked subset. */
	public record Column(Lane lane, int count, int blocked) {
	}

	/** A titled card. Only the public id, title and status; no assignee, reason or worktree. */
	public record Card(String id, String title, Lane lane, TaskStatusWire status) {
	}

	public RemoteBoardView {
		columns = List.copyOf(columns);
		cards = List.copyOf(cards);
	}

	public static RemoteBoardView of(@Nullable PublicStudioState state, boolean online) {
		if (state == null) {
			List<Column> zeros = new ArrayList<>(4);
			for (Lane lane : Lane.values()) {
				zeros.add(new Column(lane, 0, 0));
			}
			return new RemoteBoardView(0, zeros, List.of(), online, false);
		}
		var c = state.counts();
		List<Column> columns = List.of(
			new Column(Lane.TODO, c.todo() + c.blocked(), c.blocked()),
			new Column(Lane.DOING, c.doing(), 0),
			new Column(Lane.REVIEW, c.review(), 0),
			new Column(Lane.DONE, c.done(), 0));
		List<Card> cards = new ArrayList<>();
		if (state.tasks() != null) {
			for (PublicTask t : state.tasks()) {
				Lane lane = lane(t.status());
				if (lane != null) {
					cards.add(new Card(t.id(), t.title(), lane, t.status()));
				}
			}
		}
		return new RemoteBoardView(state.rev(), columns, cards, online && state.foremanOnline(), true);
	}

	/** The lane a public task shows in; cancelled and unknown tasks are hidden (null). */
	public static @Nullable Lane lane(TaskStatusWire status) {
		return switch (status) {
			case TODO, BLOCKED -> Lane.TODO;
			case DOING -> Lane.DOING;
			case REVIEW -> Lane.REVIEW;
			case DONE -> Lane.DONE;
			case CANCELLED -> null;
		};
	}
}
