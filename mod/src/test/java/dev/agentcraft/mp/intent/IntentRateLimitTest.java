package dev.agentcraft.mp.intent;

import static org.junit.jupiter.api.Assertions.*;

import dev.agentcraft.mp.RateBucket;
import org.junit.jupiter.api.*;

/**
 * The pure token bucket that backs the server's {@code world_intent_rejected reason=rate_limited}
 * decision. The per-player, per-clock behaviour of {@code WorldIntentFeature} is proven in the game
 * tests (which install a fake clock).
 */
class IntentRateLimitTest {

	@Test
	void rateLimiter_allowsBurstUpToTwiceTheRate() {
		RateBucket bucket = new RateBucket(10); // 10/s, capacity 20 tokens
		long now = 1_000_000_000L; // 1 s past epoch

		// First 20 tokens should all succeed (capacity = 2 * rate)
		for (int i = 0; i < 20; i++) {
			assertTrue(bucket.tryTake(now), "token " + (i + 1) + " should succeed");
		}
		// 21st token fails without refill
		assertFalse(bucket.tryTake(now), "21st token should fail at capacity");
	}

	@Test
	void rateLimiter_refillsOverOneSecond() {
		RateBucket bucket = new RateBucket(10);
		long t0 = 1_000_000_000L;
		// Exhaust the bucket
		for (int i = 0; i < 20; i++) {
			bucket.tryTake(t0);
		}
		// After 1 second, 10 more should be available
		long t1 = t0 + 1_000_000_000L;
		for (int i = 0; i < 10; i++) {
			assertTrue(bucket.tryTake(t1), "should refill " + (i + 1) + " after 1 s");
		}
		assertFalse(bucket.tryTake(t1), "no more after refill");
	}

	@Test
	void rateLimiter_exhaustsAndRejects() {
		RateBucket bucket = new RateBucket(1); // 1/s, capacity 2
		long now = 1_000_000_000L;

		assertTrue(bucket.tryTake(now));
		assertTrue(bucket.tryTake(now));
		assertFalse(bucket.tryTake(now), "the bucket is empty within the same window");
	}

	@Test
	void rateLimiter_zeroRateThrows() {
		assertThrows(IllegalArgumentException.class, () -> new RateBucket(0));
	}

	@Test
	void rateLimiter_backwardsTimeDoesNotRefill() {
		RateBucket bucket = new RateBucket(10);
		long t0 = 1_000_000_000L;
		for (int i = 0; i < 20; i++) {
			bucket.tryTake(t0);
		}
		assertFalse(bucket.tryTake(t0));
		// backwards time does not refill
		assertFalse(bucket.tryTake(t0 - 100));
	}
}