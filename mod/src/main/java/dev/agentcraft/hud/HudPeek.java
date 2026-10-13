package dev.agentcraft.hud;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Peek-on-change (pure): when something changes (a task done, a new decision, a goal done) the overlay widens for
 * {@link #SHOW_MS} with what changed. Changes that arrive while one shows wait their turn (at most {@link #MAX_QUEUE};
 * the oldest waiting one is dropped), and a peek with others waiting behind it shows for {@link #MIN_MS} only. The
 * same change twice in a row is shown once. Not thread-safe (client thread).
 */
public final class HudPeek {
	public static final long SHOW_MS = 5_000;
	public static final long MIN_MS = 2_500;
	public static final int MAX_QUEUE = 4;

	public static final String TASK_DONE = "task_done";
	public static final String DECISION = "decision";
	public static final String GOAL_DONE = "goal_done";

	/** One change: its kind (see the constants), the text the overlay shows, when it happened. */
	public record Peek(String kind, String text, long at) {
	}

	private final Deque<Peek> queue = new ArrayDeque<>();
	private @Nullable Peek current;
	private long shownAt;

	/** Queues a change (ignored when it repeats the one showing or the last one waiting). */
	public void push(String kind, String text, long now) {
		Peek last = queue.peekLast() != null ? queue.peekLast() : current;
		if (last != null && last.kind().equals(kind) && last.text().equals(text)) {
			return;
		}
		queue.addLast(new Peek(kind, text, now));
		while (queue.size() > MAX_QUEUE) {
			queue.pollFirst();
		}
	}

	/** The peek to show at {@code now} (advances the queue), null = none. */
	public @Nullable Peek current(long now) {
		if (current != null && now - shownAt >= (queue.isEmpty() ? SHOW_MS : MIN_MS)) {
			current = null;
		}
		if (current == null && !queue.isEmpty()) {
			current = queue.pollFirst();
			shownAt = now;
		}
		return current;
	}

	/** How long the current peek still shows (0 = none). */
	public long remaining(long now) {
		if (current == null) {
			return 0;
		}
		return Math.max(0, shownAt + (queue.isEmpty() ? SHOW_MS : MIN_MS) - now);
	}

	public int waiting() {
		return queue.size();
	}

	public void clear() {
		queue.clear();
		current = null;
	}

	// ------------------------------------------------------------------ which changes peek

	/**
	 * A task's change: {@link #TASK_DONE} when it became done, null for anything else (also a task first seen already
	 * done: a snapshot or a late join). Statuses are the wire names ("todo", "doing", "done"...).
	 */
	public static @Nullable String taskChange(@Nullable String previous, String now) {
		if (previous == null || is(previous, "done") || !is(now, "done")) {
			return null;
		}
		return TASK_DONE;
	}

	/** A decision's change: {@link #DECISION} when it is open now and was not (or is new). */
	public static @Nullable String decisionChange(@Nullable Boolean previousOpen, boolean open) {
		return open && (previousOpen == null || !previousOpen) ? DECISION : null;
	}

	/** A goal's change: {@link #GOAL_DONE} when it became done (not when first seen done). */
	public static @Nullable String goalChange(@Nullable String previous, String now) {
		return previous != null && !is(previous, "done") && is(now, "done") ? GOAL_DONE : null;
	}

	private static boolean is(String s, String wire) {
		return s.toLowerCase(Locale.ROOT).equals(wire);
	}

	/** The first words of {@code text} within {@code maxChars} (cut at a word, "…" when cut). */
	public static String firstWords(@Nullable String text, int maxChars) {
		if (text == null) {
			return "";
		}
		String s = text.strip().replaceAll("\\s+", " ");
		if (s.length() <= maxChars) {
			return s;
		}
		int cut = s.lastIndexOf(' ', maxChars);
		if (cut < maxChars / 2) {
			cut = maxChars;
		}
		return s.substring(0, cut).stripTrailing() + "…";
	}
}
