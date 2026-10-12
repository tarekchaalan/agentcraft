package dev.agentcraft.client.mp.visitor;

import dev.agentcraft.client.mp.StudioView;
import dev.agentcraft.client.mp.Studios;
import dev.agentcraft.mp.MpEvents;
import dev.agentcraft.mp.MpLog;
import dev.agentcraft.mp.Plot;
import dev.agentcraft.mp.state.Counts;
import dev.agentcraft.mp.state.PublicAgent;
import dev.agentcraft.mp.state.PublicStudioState;
import dev.agentcraft.mp.state.PublicTask;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The pure half of MP-11's visitor interactions: which studio a click routes to, the closed station
 * vocabulary, and the read-only copy built from a {@link PublicStudioState} only.
 *
 * <p>Nothing here reads {@code Foreman.state()} or any viewer-private value, so a unit test can drive
 * every line without a running client. The Minecraft-facing half (the Fabric callback, the screens,
 * the block map) lives in {@link VisitorFeature}, {@link VisitorStationScreen} and
 * {@code AgentCardScreen}'s visitor mode.
 */
public final class VisitorGate {
	/**
	 * {@code HANDLER} means "today's path, unchanged"; {@code VISITOR} means the read-only panel;
	 * {@code CONSUMED} means the click is swallowed and nothing opens.
	 */
	public enum Route {
		HANDLER, VISITOR, CONSUMED
	}

	/**
	 * The closed station vocabulary. The tokens are the telemetry values and the dev-command names;
	 * {@code agent} is the remote agent card and {@code station} is the generic fail-closed kind for
	 * a remote block that has a handler but no station mapping. This is the packet's own set, not a
	 * contract.
	 */
	public enum Station {
		PODIUM("podium", "Decision podium"),
		MERGE("merge", "Merge station"),
		TASK_WALL("task_wall", "Task wall"),
		ARCHIVE("archive", "Memory archive"),
		CATALOG("catalog", "Memory catalog"),
		LECTERN("lectern", "Memory lectern"),
		CONSOLE("console", "Console terminal"),
		AGENT("agent", "Agent"),
		STATION("station", "Station");

		private final String token;
		private final String label;

		Station(String token, String label) {
			this.token = token;
			this.label = label;
		}

		public String token() {
			return token;
		}

		public String label() {
			return label;
		}

		/** Parse a dev-command / telemetry token; null when it is not one of the closed set. */
		public static @Nullable Station byToken(String value) {
			for (Station s : values()) {
				if (s.token.equals(value)) {
					return s;
				}
			}
			return null;
		}
	}

	private VisitorGate() {
	}

	/**
	 * A studio's right-click is read-only only when the studio is present and not the viewer's own.
	 * The own studio keeps today's handler. Empty (no studio known at the position) keeps it in
	 * singleplayer only: in multiplayer the block may stand in a neighbour's plot whose layout has
	 * not arrived, where the own handler would open the viewer's private panels, so it is consumed.
	 */
	public static Route route(boolean multiplayer, Optional<StudioView> studio) {
		if (studio.isPresent()) {
			return studio.get().own() ? Route.HANDLER : Route.VISITOR;
		}
		return multiplayer ? Route.CONSUMED : Route.HANDLER;
	}

	/**
	 * The gate's decision for a click on a block that already has a handler. Empty means no panel
	 * (the viewer's own studio, or no studio at the position: see {@link #route}). Present means the
	 * read-only panel opens with that kind: the mapped station when the block is one of them,
	 * otherwise the generic {@link Station#STATION}. That fallback is the fail-closed rule: a remote
	 * block with a handler and no entry in the map is still consumed, so it can never reach the
	 * viewer's own feature handler with the remote studio's context.
	 */
	public static Optional<Station> visitorKind(Optional<StudioView> studio, @Nullable Station mapped) {
		if (studio.isEmpty() || studio.get().own()) {
			return Optional.empty();
		}
		return Optional.of(mapped != null ? mapped : Station.STATION);
	}

	/**
	 * The agent with this id in the view's current public state, or nothing. The lookup is pure and
	 * re-resolved from the view every call, so a card follows a state update and closes once the
	 * state or the agent is gone.
	 */
	public static Optional<PublicAgent> findAgent(@Nullable StudioView view, String agentId) {
		if (view == null) {
			return Optional.empty();
		}
		PublicStudioState state = view.publicState();
		if (state == null) {
			return Optional.empty();
		}
		for (PublicAgent agent : state.agents()) {
			if (agent.id().equals(agentId)) {
				return Optional.of(agent);
			}
		}
		return Optional.empty();
	}

	/**
	 * The panel body for a station, from the public state only. Opt-in text is shown only through the
	 * matching {@code PublicPolicy} flag (the record already refuses it otherwise).
	 */
	public static List<String> stationLines(@Nullable PublicStudioState state, Station kind) {
		if (kind == Station.AGENT) {
			return List.of();
		}
		if (kind == Station.STATION) {
			return List.of("Nothing public to show here.");
		}
		if (state == null) {
			return List.of("No public state yet.");
		}
		Counts c = state.counts();
		return switch (kind) {
			case CONSOLE -> List.of("The owner's console.", "A visitor cannot type here.");
			case PODIUM -> List.of(c.openDecisions() + (c.openDecisions() == 1 ? " decision waiting" : " decisions waiting"));
			case MERGE -> List.of(c.openMerges() == 0 ? "No merge waiting." : "A merge is waiting.");
			case ARCHIVE, CATALOG, LECTERN -> List.of("Memory is private to its owner.");
			case TASK_WALL -> taskWallLines(state);
			case AGENT -> List.of();
			case STATION -> List.of("Nothing public to show here.");
		};
	}

	private static List<String> taskWallLines(PublicStudioState state) {
		Counts c = state.counts();
		List<String> lines = new ArrayList<>();
		lines.add("Todo " + c.todo() + " · Doing " + c.doing() + " · Review " + c.review());
		lines.add("Done " + c.done() + " · Blocked " + c.blocked());
		// Titles are opt-in; nothing is shown when the owner withholds them.
		if (state.policy().taskTitles() && state.tasks() != null) {
			for (PublicTask t : state.tasks()) {
				lines.add(t.status().wire() + " · " + t.title());
			}
		}
		return List.copyOf(lines);
	}

	/**
	 * How a panel body fits the screen: the first {@code shown} lines are drawn, {@code remaining}
	 * lines are left out, and {@code indicator} says whether a last "+ N more" row is drawn for them.
	 */
	public record Fit(int shown, int remaining, boolean indicator) {
		/** The body rows the panel draws, the indicator row included. */
		public int rows() {
			return shown + (indicator ? 1 : 0);
		}
	}

	/**
	 * The body rows a panel can draw in {@code height} pixels once {@code chrome} (padding, header and
	 * footer) is taken off. Never negative: a screen shorter than the chrome has room for no row.
	 */
	public static int rowsAvailable(int height, int chrome, int rowHeight) {
		return rowHeight <= 0 ? 0 : Math.max(0, (height - chrome) / rowHeight);
	}

	/**
	 * Fits {@code total} body lines into {@code available} rows. A list that fits is shown whole.
	 * Otherwise the last available row is the indicator and the rows above it are the first lines;
	 * with no row at all nothing is drawn. {@code shown + remaining} is always {@code total}.
	 */
	public static Fit fit(int total, int available) {
		int lines = Math.max(0, total);
		int rows = Math.max(0, available);
		if (lines <= rows) {
			return new Fit(lines, 0, false);
		}
		if (rows == 0) {
			return new Fit(0, lines, false);
		}
		return new Fit(rows - 1, lines - rows + 1, true);
	}

	/** The indicator row for a truncated body. A count only: no public or private text. */
	public static String moreLine(int remaining) {
		return "+ " + remaining + " more";
	}

	/**
	 * The read-only card body for a public agent: state, station and — for a waiting agent — the
	 * <em>owner</em>, never the viewer. Activity stays out of the card: MP-11 shows name, state,
	 * station and awaiting.
	 */
	public static List<String> agentLines(PublicAgent agent, @Nullable String ownerName) {
		List<String> lines = new ArrayList<>();
		lines.add(capitalize(agent.state().wire()) + " · " + agent.station().wire());
		if (!agent.active()) {
			lines.add("Off shift");
		} else if (agent.paused()) {
			lines.add("Paused");
		}
		if (agent.awaitingUser()) {
			boolean named = ownerName != null && !ownerName.isBlank();
			lines.add("Waiting for " + (named ? ownerName : "the owner"));
		}
		return List.copyOf(lines);
	}

	/**
	 * The one telemetry event of this packet. Values are ids, numbers and closed tokens only: the
	 * owner's name, activity, task and goal text never reach this line.
	 */
	public static void logOpen(Station kind, StudioView studio, UUID viewer) {
		logOpen(Route.VISITOR, kind, studio, viewer);
	}

	/**
	 * Emits {@code visitor_readonly} only for a {@link Route#VISITOR} decision, so an own-studio or
	 * empty route can never write the event. Returns whether it logged.
	 */
	public static boolean logOpen(Route route, Station kind, StudioView studio, UUID viewer) {
		if (route != Route.VISITOR) {
			return false;
		}
		PublicStudioState state = studio.publicState();
		int plot = Studios.plot(studio.id()).map(Plot::index).orElse(-1);
		int rev = state == null ? -1 : state.rev();
		MpLog.event(MpEvents.VISITOR_READONLY,
			"player", viewer,
			"studio", studio.id().owner(),
			"plot", plot,
			"rev", rev,
			"station", kind.token(),
			"action", "open");
		return true;
	}

	private static String capitalize(String s) {
		return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase(Locale.ROOT);
	}
}
