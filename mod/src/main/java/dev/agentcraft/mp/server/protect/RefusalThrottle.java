package dev.agentcraft.mp.server.protect;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * At most one {@code plot_edit_refused} line per (player, action, plot) every
 * {@link #WINDOW_NANOS}. The clock is injected so tests can drive it; the
 * default reads {@link System#nanoTime()}.
 *
 * <p>The refusal itself never depends on this: callers decide to deny first and
 * use {@link #shouldLog} only to decide whether to emit telemetry.</p>
 */
public final class RefusalThrottle {
    /** One line per player, action and plot per five seconds. */
    public static final long WINDOW_NANOS = 5_000_000_000L;

    private final LongSupplier clock;
    private final Map<Key, Long> last = new HashMap<>();

    public RefusalThrottle() {
        this(System::nanoTime);
    }

    public RefusalThrottle(LongSupplier clock) {
        this.clock = clock;
    }

    /** True when a line may be logged now; records the time for that key. */
    public boolean shouldLog(UUID player, String action, int plot) {
        long now = clock.getAsLong();
        Key key = new Key(player, action, plot);
        Long previous = last.get(key);
        if (previous != null && now - previous < WINDOW_NANOS) {
            return false;
        }
        last.put(key, now);
        return true;
    }

    /** Drop every entry for a player who left. */
    public void forget(UUID player) {
        last.keySet().removeIf(key -> key.player().equals(player));
    }

    /** Drop everything, e.g. on server stop. */
    public void clear() {
        last.clear();
    }

    public record Key(UUID player, String action, int plot) {}
}
