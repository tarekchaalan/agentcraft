package dev.agentcraft.client.hud;

import dev.agentcraft.client.decisions.DecisionQueue;
import dev.agentcraft.client.decisions.DecisionScreen;
import dev.agentcraft.client.decisions.DecisionsFeature;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.Protocol.Agent;
import dev.agentcraft.client.foreman.Protocol.AgentState;
import dev.agentcraft.client.foreman.Protocol.Decision;
import dev.agentcraft.client.foreman.Protocol.Goal;
import dev.agentcraft.client.foreman.Protocol.GoalStatus;
import dev.agentcraft.client.foreman.Protocol.Task;
import dev.agentcraft.hud.HudPeek;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/**
 * What the overlay shows, gathered once per frame from the Foreman state: the latest goal with its task counts,
 * decisions waiting and the next one, who is working, and the peek showing. Every style draws from this. Client
 * thread.
 */
public final class HudModel {
	/** The goal shown. */
	public record GoalView(String id, String text, GoalStatus status, double progress, long updatedAt) {
		public boolean open() {
			return status == GoalStatus.PLANNING || status == GoalStatus.ACTIVE;
		}

		public int pct() {
			return (int) Math.round(Math.max(0, Math.min(1, progress)) * 100);
		}
	}

	public boolean hasData;
	public boolean stale;
	public @Nullable GoalView goal;
	/** doing review todo blocked done */
	public final int[] counts = new int[5];
	public int totalTasks;
	public String planningLine = "";
	/** Decisions waiting (0 while the decision screen is open: the badge would point at itself). */
	public int waiting;
	public @Nullable String nextDecision;
	/** Agent ids at work right now. */
	public List<String> working = List.of();
	public HudPeek.@Nullable Peek peek;

	/** An open goal (planning or working). */
	public boolean activeGoal() {
		return goal != null && goal.open();
	}

	/** Something needs the player: a decision waiting. */
	public boolean needsPlayer() {
		return waiting > 0;
	}

	/** Open tasks: doing + review + todo + blocked. */
	public int openTasks() {
		return counts[0] + counts[1] + counts[2] + counts[3];
	}

	// ------------------------------------------------------------------ live

	private static long countRevision = -1;
	private static @Nullable String countGoal;
	private static final int[] COUNTS = new int[5];
	private static int total;

	/** The model of this frame from the Foreman state (no data: {@link #hasData} false). */
	public static HudModel live() {
		HudModel m = new HudModel();
		ForemanState s = Foreman.state();
		if (s == null || !s.hasData()) {
			return m;
		}
		m.hasData = true;
		m.stale = s.isStale();
		Goal goal = s.goal();
		if (goal != null) {
			m.goal = new GoalView(goal.id(), UiBits.oneLine(goal.text()), goal.status(), goal.progress(), goal.updatedAt());
			recount(s, goal);
			System.arraycopy(COUNTS, 0, m.counts, 0, 5);
			m.totalTasks = total;
			if (goal.status() == GoalStatus.PLANNING) {
				m.planningLine = GoalBar.planningLine(s);
			}
		}
		boolean decisionScreen = Minecraft.getInstance().gui.screen() instanceof DecisionScreen;
		m.waiting = decisionScreen ? 0 : DecisionsFeature.waitingCount();
		Decision next = DecisionQueue.first();
		m.nextDecision = next == null ? null : UiBits.oneLine(next.question());
		List<String> work = new ArrayList<>();
		for (Agent a : s.agents().values()) {
			if (!Boolean.FALSE.equals(a.active()) && !Boolean.TRUE.equals(a.paused()) && working(a.state())) {
				work.add(a.id());
			}
		}
		m.working = List.copyOf(work);
		m.peek = HudPeeks.current(System.currentTimeMillis());
		return m;
	}

	static boolean working(AgentState st) {
		return switch (st) {
			case THINKING, READING, EDITING, RUNNING, TESTING -> true;
			default -> false;
		};
	}

	private static void recount(ForemanState s, Goal goal) {
		if (s.revision() == countRevision && java.util.Objects.equals(goal.id(), countGoal)) {
			return;
		}
		countRevision = s.revision();
		countGoal = goal.id();
		java.util.Arrays.fill(COUNTS, 0);
		total = 0;
		for (Task t : s.tasks().values()) {
			if (goal.id() != null && t.goalId() != null && !t.goalId().equals(goal.id())) {
				continue;
			}
			int i = switch (t.status()) {
				case DOING -> 0;
				case REVIEW -> 1;
				case TODO -> 2;
				case BLOCKED -> 3;
				case DONE -> 4;
				default -> -1;
			};
			if (i >= 0) {
				COUNTS[i]++;
				total++;
			}
		}
	}
}
