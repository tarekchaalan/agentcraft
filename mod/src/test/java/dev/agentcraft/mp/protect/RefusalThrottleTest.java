package dev.agentcraft.mp.protect;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.agentcraft.mp.server.protect.RefusalThrottle;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class RefusalThrottleTest {
    private static final UUID PLAYER_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID PLAYER_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @Test
    void one_line_per_player_action_and_plot_every_five_seconds() {
        AtomicLong clock = new AtomicLong(0);
        RefusalThrottle throttle = new RefusalThrottle(clock::get);

        assertTrue(throttle.shouldLog(PLAYER_A, "break", 1), "first refusal logs");
        clock.set(4_999_999_999L);
        assertFalse(throttle.shouldLog(PLAYER_A, "break", 1), "inside the window is suppressed");
        clock.set(5_000_000_000L);
        assertTrue(throttle.shouldLog(PLAYER_A, "break", 1), "at the window it logs again");
    }

    @Test
    void another_action_or_another_plot_is_not_suppressed() {
        AtomicLong clock = new AtomicLong(0);
        RefusalThrottle throttle = new RefusalThrottle(clock::get);

        assertTrue(throttle.shouldLog(PLAYER_A, "break", 1));
        assertTrue(throttle.shouldLog(PLAYER_A, "place", 1), "another action logs");
        assertTrue(throttle.shouldLog(PLAYER_A, "break", 2), "another plot logs");
        assertTrue(throttle.shouldLog(PLAYER_B, "break", 1), "another player logs");
        assertFalse(throttle.shouldLog(PLAYER_A, "break", 1), "the original key stays suppressed");
    }

    @Test
    void forget_drops_one_player_and_clear_drops_everything() {
        AtomicLong clock = new AtomicLong(0);
        RefusalThrottle throttle = new RefusalThrottle(clock::get);

        assertTrue(throttle.shouldLog(PLAYER_A, "break", 1));
        assertTrue(throttle.shouldLog(PLAYER_B, "break", 1));
        throttle.forget(PLAYER_A);
        assertTrue(throttle.shouldLog(PLAYER_A, "break", 1), "forgotten player logs again");
        assertFalse(throttle.shouldLog(PLAYER_B, "break", 1), "other player still suppressed");
        throttle.clear();
        assertTrue(throttle.shouldLog(PLAYER_B, "break", 1), "clear logs again");
    }

    @Test
    void the_default_clock_does_not_throw() {
        RefusalThrottle throttle = new RefusalThrottle();
        assertTrue(throttle.shouldLog(PLAYER_A, "use", 0));
        assertFalse(throttle.shouldLog(PLAYER_A, "use", 0));
    }
}
