package dev.agentcraft.mp;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class RateBucketTest {
    private static void takeExactly(RateBucket bucket,int count,long time) {
        for(int i=0;i<count;i++) assertTrue(bucket.tryTake(time),"take "+i);
        assertFalse(bucket.tryTake(time));
    }
    @Test void starts_full_and_refills_at_the_rate() {
        RateBucket bucket=new RateBucket(4);
        takeExactly(bucket,8,100);
        takeExactly(bucket,4,1_000_000_100L);
    }
    @Test void fractional_refills_accumulate_without_rounding_away_time() {
        RateBucket bucket=new RateBucket(3);
        takeExactly(bucket,6,0);
        assertFalse(bucket.tryTake(333_333_333));
        assertTrue(bucket.tryTake(333_333_334));
        assertFalse(bucket.tryTake(666_666_666));
        assertTrue(bucket.tryTake(666_666_667));
        takeExactly(bucket,1,1_000_000_000);
    }
    @Test void backwards_time_does_not_refill_or_move_the_watermark() {
        RateBucket bucket=new RateBucket(1);
        takeExactly(bucket,2,2_000_000_000);
        assertFalse(bucket.tryTake(-1));
        assertFalse(bucket.tryTake(2_000_000_000));
        assertFalse(bucket.tryTake(2_999_999_999L));
        takeExactly(bucket,1,3_000_000_000L);
    }
    @Test void long_idle_is_capped_even_when_time_subtraction_would_overflow() {
        RateBucket bucket=new RateBucket(3);
        takeExactly(bucket,6,Long.MIN_VALUE);
        takeExactly(bucket,6,Long.MAX_VALUE);
        assertFalse(bucket.tryTake(Long.MIN_VALUE));
        // Even the largest legal rate has a representable capacity/refill.
        RateBucket fast=new RateBucket(Integer.MAX_VALUE);
        assertTrue(fast.tryTake(0)); assertTrue(fast.tryTake(Long.MAX_VALUE));
    }
    @Test void rejects_nonpositive_rates() {
        assertThrows(IllegalArgumentException.class,()->new RateBucket(0));
        assertThrows(IllegalArgumentException.class,()->new RateBucket(-1));
    }
}
