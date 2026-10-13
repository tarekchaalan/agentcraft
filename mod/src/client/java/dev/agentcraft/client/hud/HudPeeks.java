package dev.agentcraft.client.hud;

import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanListener;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.Protocol.Decision;
import dev.agentcraft.client.foreman.Protocol.Goal;
import dev.agentcraft.client.foreman.Protocol.Task;
import dev.agentcraft.hud.HudPeek;
import org.jspecify.annotations.Nullable;

/**
 * Peek-on-change for the overlay: Foreman upserts that finish a task, open a decision or finish a goal become
 * {@link HudPeek} entries ("Done: Parse tags", "Decision: Merge t3?", "Goal done: Add export"). Snapshots (a join, a
 * reconnect) never peek: only per-item changes with a known previous state do, and nothing for 3 s after a snapshot.
 * Off with the peek setting. Client thread.
 */
public final class HudPeeks {
	private static final HudPeek PEEK = new HudPeek();
	private static final long QUIET_AFTER_SNAPSHOT_MS = 3_000;
	private static long snapshotAt;

	private HudPeeks() {
	}

	public static void init() {
		Foreman.addListener(new ForemanListener() {
			@Override
			public void onSnapshot(ForemanState state) {
				snapshotAt = System.currentTimeMillis();
			}

			@Override
			public void onTask(@Nullable Task previous, Task task) {
				String kind = HudPeek.taskChange(previous == null ? null : previous.status().wire(), task.status().wire());
				if (kind != null) {
					push(kind, "Done: " + HudPeek.firstWords(UiBits.oneLine(task.title()), 48));
				}
			}

			@Override
			public void onDecision(@Nullable Decision previous, Decision decision) {
				String kind = HudPeek.decisionChange(previous == null ? null : previous.isOpen(), decision.isOpen());
				if (kind != null) {
					push(kind, "Decision: " + HudPeek.firstWords(UiBits.oneLine(decision.question()), 48));
				}
			}

			@Override
			public void onGoal(@Nullable Goal previous, Goal goal) {
				String kind = HudPeek.goalChange(previous == null ? null : previous.status().wire(), goal.status().wire());
				if (kind != null) {
					push(kind, "Goal done: " + HudPeek.firstWords(UiBits.oneLine(goal.text()), 48));
				}
			}
		});
	}

	/** Queues a peek (skipped while the setting is off or right after a snapshot). */
	public static void push(String kind, String text) {
		long now = System.currentTimeMillis();
		if (!HudConfig.get().peek() || now - snapshotAt < QUIET_AFTER_SNAPSHOT_MS) {
			return;
		}
		PEEK.push(kind, text, now);
	}

	/** QA ({@code dev.hud.peek}): queues a peek whatever the setting. */
	public static void force(String kind, String text) {
		PEEK.push(kind, text, System.currentTimeMillis());
	}

	public static HudPeek.@Nullable Peek current(long now) {
		return PEEK.current(now);
	}

	public static long remaining(long now) {
		return PEEK.remaining(now);
	}

	public static int waiting() {
		return PEEK.waiting();
	}

	public static void clear() {
		PEEK.clear();
	}

	/** The status dot family a peek is drawn with. */
	public static String family(String kind) {
		return switch (kind) {
			case HudPeek.DECISION -> "waiting";
			default -> "done";
		};
	}
}
