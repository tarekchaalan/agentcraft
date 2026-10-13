package dev.agentcraft.mp;

/** Caller-clock token bucket. One token costs a billion credits; each nanosecond adds rate credits. */
public final class RateBucket {
    private static final long TOKEN = 1_000_000_000L;
    private final int rate;
    private final long capacity;
    private long credits;
    private long lastNanos;
    private boolean started;

    public RateBucket(int ratePerSecond) {
        if(ratePerSecond<1) throw new IllegalArgumentException("rate must be positive");
        rate=ratePerSecond;
        credits=capacity=2L*rate*TOKEN;
    }
    public boolean tryTake(long nowNanos) {
        if(!started) { started=true; lastNanos=nowNanos; }
        else if(nowNanos>lastNanos) {
            long elapsed=nowNanos-lastNanos, missing=capacity-credits;
            // A positive interval can overflow subtraction across the long range. Either way,
            // clamp before multiplying so a long idle interval cannot overflow the refill.
            if(elapsed<0 || elapsed>=(missing+rate-1)/rate) credits=capacity;
            else credits+=elapsed*rate;
            lastNanos=nowNanos;
        }
        if(credits<TOKEN) return false;
        credits-=TOKEN;
        return true;
    }
}
