package com.walletradar.common;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Token-bucket rate limiter. Used for CoinGecko API: 45 req/min (leaving headroom for spot).
 */
public class RateLimiter {

    private final long minIntervalNanos;
    private final AtomicLong nextFreeAtNanos = new AtomicLong(0);

    /**
     * @param permitsPerMinute e.g. 45 for 45 requests per minute
     */
    public RateLimiter(int permitsPerMinute) {
        if (permitsPerMinute <= 0) {
            throw new IllegalArgumentException("permitsPerMinute must be positive");
        }
        this.minIntervalNanos = 60_000_000_000L / permitsPerMinute; // 60e9 ns per minute
    }

    /**
     * Blocks until a permit is available, then returns.
     */
    public void acquire() {
        long now;
        long next;
        do {
            now = System.nanoTime();
            next = nextFreeAtNanos.get();
            if (now >= next) {
                if (nextFreeAtNanos.compareAndSet(next, now + minIntervalNanos)) {
                    return;
                }
            } else {
                long sleepNanos = next - now;
                try {
                    Thread.sleep(sleepNanos / 1_000_000, (int) (sleepNanos % 1_000_000));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Rate limiter interrupted", e);
                }
            }
        } while (true);
    }

    /**
     * Non-blocking: returns true if a permit was taken, false if would block.
     */
    public boolean tryAcquire() {
        long now = System.nanoTime();
        long next = nextFreeAtNanos.get();
        if (now >= next && nextFreeAtNanos.compareAndSet(next, now + minIntervalNanos)) {
            return true;
        }
        return false;
    }
}
