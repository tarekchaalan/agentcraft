package dev.agentcraft.client.agents;

import dev.agentcraft.Cast;
import dev.agentcraft.client.foreman.Protocol.Agent;
import dev.agentcraft.client.foreman.Protocol.AgentState;
import dev.agentcraft.client.ui.UiStyle;
import dev.agentcraft.mp.StudioId;
import dev.agentcraft.mp.state.PublicAgent;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * What an agent entity shows, derived from its protocol {@link Agent} by {@link AgentManager} every
 * tick (client thread). Renderers and Phase 3 hooks read it; only the manager writes it.
 */
public final class AgentView {
	public final String id;
	/** Render-layout identity; cast ids remain in {@link #id} for anchors and skin lookup. */
	public String plateKey;
	public StudioId studioId = StudioId.LOCAL;
	public boolean remote;
	public String ownerName = "";
	StudioAgents studioAgents;
	/** The public skin name this agent's entity was last given (remote studios only). */
	@Nullable String skinName;
	public String name;
	/** Identity colour (scarf/badge), ARGB. */
	public int color;
	/** Name colour on dark surfaces (nameplate), ARGB. */
	public int nameColor;
	public AgentState state = AgentState.IDLE;
	/**
	 * Status family to <b>show</b> for this agent (nameplate dot, lamps, monitors, the "!" marker):
	 * idle thinking working waiting error done. Already accounts for everything: {@code waiting}
	 * when a decision waits on the user for this agent ({@link #awaitingUser}), {@code idle} while it
	 * is off shift or the Foreman is offline. Lamp/monitor owners: read this, not
	 * {@code state.family()}.
	 */
	public String family = "idle";
	/** The family while the agent is on shift and the link is live (no off-shift/offline override); for effects. */
	public String liveFamily = "idle";
	public String activity = "";
	/** The station key the agent is at or walking to ("desk", "library", ..., "lounge"). */
	public String station = "lounge";
	public @Nullable String anchor;
	public boolean active = true;
	public boolean paused;
	/** The Foreman link is down: show the last known state, dimmed. */
	public boolean stale;
	public AgentPose pose = AgentPose.STAND;
	public @Nullable String taskId;
	/**
	 * An open decision is waiting on the user for this agent's work (a merge of its task, a question it
	 * asked, a permission prompt), even when the Foreman shows the agent as idle meanwhile.
	 */
	public boolean awaitingUser;
	/**
	 * The decision behind {@link #awaitingUser} (for the agent card), or null. Every open decision
	 * has exactly one owner agent ({@link AgentManager}): a merge belongs to the worker whose task it
	 * merges, a question or permission prompt to the agent that asked. So the number of "!" markers
	 * in the HQ matches the number of decisions waiting on the user.
	 */
	public @Nullable String awaitingDecision;
	/** How many open decisions this agent owns (the card shows the first, "+n more"). */
	public int awaitingCount;
	public String role = "";
	public @Nullable String title;
	/** Which engine runs this agent ("claude", "codex"; null: the sim) and its model for display ("Opus 5.5"). */
	public @Nullable String engine;
	public @Nullable String model;
	/** Laid-out nameplates, rebuilt by {@link Nameplate#of} / {@link Nameplate#compactOf} only when their text changes. */
	Nameplate.@Nullable Data plateCache;
	Nameplate.@Nullable Data compactCache;

	public AgentView(String id) {
		this.id = id;
		this.plateKey = id;
		this.name = id;
	}

	/**
	 * Remember the skin name a remote studio publishes for this agent; true when it differs from the
	 * one its entity was last given, so the entity's skin is resolved again only on a change.
	 */
	boolean skinChanged(@Nullable String published) {
		if (Objects.equals(skinName, published)) {
			return false;
		}
		skinName = published;
		return true;
	}

	void attach(StudioAgents studio, boolean remote, String ownerName) {
		if (this.studioAgents == studio && this.remote == remote && this.ownerName.equals(ownerName)) {
			return;
		}
		this.studioAgents = studio;
		this.studioId = studio.id();
		this.remote = remote;
		this.ownerName = ownerName;
		this.plateKey = studio.id().equals(StudioId.LOCAL) ? id : studio.id().owner() + "/" + id;
	}

	void update(Agent a, boolean staleLink, @Nullable String awaitingDecisionId, int awaitingDecisions) {
		name = a.name();
		color = 0xFF000000 | dev.agentcraft.Cast.parseColor(a.color(), 0x9C9488);
		nameColor = UiStyle.agentOnDark(a.id());
		state = a.state();
		awaitingDecision = awaitingDecisionId;
		awaitingCount = awaitingDecisionId == null ? 0 : Math.max(1, awaitingDecisions);
		awaitingUser = awaitingDecisionId != null;
		role = a.role().wire();
		title = a.title();
		engine = a.engine();
		model = a.model();
		activity = a.activity();
		active = a.isActive();
		paused = a.isPaused();
		stale = staleLink;
		taskId = a.taskId();
		liveFamily = statusFamily(a.state(), awaitingUser);
		family = stale || !active ? "idle" : liveFamily;
	}

	/** Derive a remote plate strictly from the public allowlist. */
	void updatePublic(PublicAgent a, boolean offline) {
		name = a.name();
		Cast.Member member = Cast.get(a.id());
		color = 0xFF000000 | (member == null ? 0x9C9488 : member.color());
		nameColor = UiStyle.agentOnDark(a.id());
		try {
			state = AgentState.valueOf(a.state().name());
		} catch (IllegalArgumentException ignored) {
			state = AgentState.UNKNOWN;
		}
		awaitingDecision = null;
		awaitingCount = 0;
		awaitingUser = a.awaitingUser();
		role = member == null ? "" : member.role();
		title = member == null || member.title().isEmpty() ? null : member.title();
		activity = a.activity() == null ? "" : a.activity();
		active = a.active();
		paused = a.paused();
		stale = offline;
		taskId = null;
		liveFamily = statusFamily(state, awaitingUser);
		family = stale || !active ? "idle" : liveFamily;
	}

	/**
	 * The status family shown for an agent (dot, lamp colour, "!" marker). The Foreman reports a
	 * worker whose finished task waits for the user's merge as {@code idle} ("t4 awaiting your merge");
	 * that is the user's turn, so it shows as {@code waiting} (clay), like {@code waiting_user}.
	 * Errors and real work keep their own family.
	 */
	public static String statusFamily(AgentState state, boolean awaitingUser) {
		String f = state.family();
		if (awaitingUser && (f.equals("idle") || f.equals("done"))) {
			return "waiting";
		}
		return f;
	}

	/** One line for the nameplate under the name. */
	public String activityLine() {
		if (stale) {
			return "Foreman offline";
		}
		if (!active) {
			return "off shift";
		}
		if (paused) {
			return activity.isEmpty() ? "paused" : "paused · " + activity;
		}
		if (remote && activity.isEmpty()) {
			String wire = state.wire().replace('_', ' ');
			return wire.isEmpty() ? "idle" : wire;
		}
		return activity;
	}

	/** Dot family shown on the nameplate: the same as {@link #family} (kept for callers of the Phase 3 API). */
	public String dotFamily() {
		return family;
	}

	/** Paused by the user (and otherwise live): the plate shows a pause glyph instead of the dot. */
	public boolean showsPaused() {
		return paused && active && !stale;
	}

	/**
	 * How much the plate matters when plates compete for screen space (higher = placed first, keeps
	 * its activity line): waiting 6, error 5, working/thinking 4, paused 3 (you paused it, so it
	 * keeps saying so), done 2, idle 1, off shift or Foreman offline 0. Plates at 2 or below collapse
	 * to the name when they would overlap another.
	 */
	public int plateWeight() {
		if (stale || !active) {
			return 0;
		}
		int w = switch (family) {
			case "waiting" -> 6;
			case "error" -> 5;
			case "working", "thinking" -> 4;
			case "done" -> 2;
			default -> 1;
		};
		if (needsYou()) {
			w = 6;
		}
		return paused ? Math.max(w, 3) : w;
	}

	/**
	 * Show the pulsing clay "!" above this agent: it owns an open decision ({@link #awaitingUser},
	 * also while it already works on something else) or it is asking you ({@code waiting_user}).
	 * Never while off shift or while the Foreman is offline, and never in another player's studio:
	 * that agent waits for its owner, not for you (its plate still says that it waits).
	 */
	public boolean needsYou() {
		return !remote && active && !stale && (awaitingUser || state == AgentState.WAITING_USER);
	}
}
