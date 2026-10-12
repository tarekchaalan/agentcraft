package dev.agentcraft.client.mp.publish;

import java.util.ArrayDeque;
import java.util.Deque;

/** Client-side sliding window: no more than {@code rate} sends in any one-second window. */
final class SendWindow {
    private static final long WINDOW_NANOS = 1_000_000_000L;
    private final Deque<Long> sends = new ArrayDeque<>();

    boolean tryTake(int rate, long nowNanos) {
        while (!sends.isEmpty() && nowNanos - sends.peekFirst() >= WINDOW_NANOS) sends.removeFirst();
        if (rate < 1 || sends.size() >= rate) return false;
        sends.addLast(nowNanos);
        return true;
    }

    void clear() { sends.clear(); }
}
