package dev.agentcraft.mp.server.plot;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per-player rebuild throttle. Server thread only. A non-operator may rebuild once every
 * {@link #WINDOW_TICKS} server ticks, counted from the last accepted rebuild: one that passed the
 * check and whose build succeeded. A build that threw is not recorded. Operators are not recorded
 * and not limited. Cleared on server stop.
 */
public final class PlotRebuilds {
    /** 60 seconds at 20 ticks per second. */
    public static final int WINDOW_TICKS = 1200;

    private static final Map<UUID, Integer> LAST = new HashMap<>();

    private PlotRebuilds() {}

    public static boolean allowed(UUID player, int now) {
        Integer last = LAST.get(player);
        return last == null || (long) now - last >= WINDOW_TICKS;
    }

    /** Whole seconds until {@code player} may rebuild again; 0 when the window has passed. */
    public static int remainingSeconds(UUID player, int now) {
        Integer last = LAST.get(player);
        if (last == null) return 0;
        long remaining = (long) last + WINDOW_TICKS - now;
        if (remaining <= 0) return 0;
        return (int) ((remaining + 19) / 20);
    }

    public static void note(UUID player, int now) {
        LAST.put(player, now);
    }

    public static void clear() {
        LAST.clear();
    }

    public static Map<UUID, Integer> snapshot() {
        return Map.copyOf(LAST);
    }

    public static void install(Map<UUID, Integer> state) {
        LAST.clear();
        LAST.putAll(state);
    }
}
