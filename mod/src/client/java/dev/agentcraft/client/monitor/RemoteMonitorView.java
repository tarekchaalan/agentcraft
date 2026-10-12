package dev.agentcraft.client.monitor;

import dev.agentcraft.mp.state.AgentStateWire;
import dev.agentcraft.mp.state.Counts;
import dev.agentcraft.mp.state.GoalStatusWire;
import dev.agentcraft.mp.state.PublicAgent;
import dev.agentcraft.mp.state.PublicStudioState;
import dev.agentcraft.mp.state.StationWire;
import org.jspecify.annotations.Nullable;

/**
 * The facts a monitor in a <b>remote</b> studio shows, derived only from a {@link PublicStudioState}.
 * Pure: no Minecraft, no {@code ForemanState}, no log, diff or path. The own studio keeps
 * {@link MonitorScreen}'s existing Foreman path.
 *
 * <p>{@code activity} is null whenever the owner did not opt the activity text in; the screen shows
 * "activity hidden" for that. {@link #of} reads only the public record; the in-game overlay check
 * is the leak test.
 */
public record RemoteMonitorView(int rev, Kind kind, String binding, boolean online,
	@Nullable String agentId, @Nullable String name, @Nullable AgentStateWire state, @Nullable StationWire station,
	boolean active, boolean paused, boolean awaitingUser, @Nullable String activity,
	@Nullable Counts counts, @Nullable GoalStatusWire goalStatus, float goalProgress, @Nullable String goalText) {

	public enum Kind {
		AGENT, FEED, NO_AGENT, NO_STUDIO
	}

	/**
	 * The remote view for a panel bound to {@code binding}. {@code online} is the studio's presence;
	 * the result is offline when presence is down or the owner's Foreman link is down. A null state
	 * is {@link Kind#NO_STUDIO} (the studio is known but nothing has arrived), never a fallback to the
	 * viewer's own Foreman.
	 */
	public static RemoteMonitorView of(@Nullable PublicStudioState state, boolean online, String binding) {
		if (state == null) {
			return new RemoteMonitorView(0, Kind.NO_STUDIO, binding, online, null, null, null, null, false, false, false, null, null, null, 0f, null);
		}
		boolean on = online && state.foremanOnline();
		if ("feed".equals(binding)) {
			GoalStatusWire gs = state.goal().status();
			boolean none = gs == GoalStatusWire.NONE;
			return new RemoteMonitorView(state.rev(), Kind.FEED, binding, on, null, null, null, null, false, false, false, null, state.counts(),
				none ? GoalStatusWire.NONE : gs, none ? 0f : state.goal().progress(), none ? null : state.goal().text());
		}
		for (PublicAgent a : state.agents()) {
			if (a.id().equals(binding)) {
				return new RemoteMonitorView(state.rev(), Kind.AGENT, binding, on, a.id(), a.name(), a.state(), a.station(),
					a.active(), a.paused(), a.awaitingUser(), a.activity(), null, null, 0f, null);
			}
		}
		return new RemoteMonitorView(state.rev(), Kind.NO_AGENT, binding, on, null, null, null, null, false, false, false, null, null, null, 0f, null);
	}

	/** Dot / colour family of the agent's public state (idle, thinking, working, waiting, error, done). */
	public String family() {
		if (state == null) {
			return "idle";
		}
		return switch (state) {
			case THINKING -> "thinking";
			case READING, EDITING, RUNNING, TESTING -> "working";
			case WAITING_USER -> "waiting";
			case BLOCKED, ERROR -> "error";
			case DONE -> "done";
			default -> "idle";
		};
	}

	/** True when this agent has no public activity to show, so the screen prints the hidden line. */
	public boolean activityHidden() {
		return kind == Kind.AGENT && activity == null;
	}
}
