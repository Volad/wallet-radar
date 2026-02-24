package com.walletradar.common;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Exponential backoff with ±20% jitter for RPC retries (02-architecture).
 */
public final class RetryPolicy {

    private final long baseDelayMs;
    private final double jitterFactor;
    private final int maxAttempts;

    public RetryPolicy(long baseDelayMs, double jitterFactor, int maxAttempts) {
        this.baseDelayMs = baseDelayMs;
        this.jitterFactor = jitterFactor;
        this.maxAttempts = maxAttempts;
    }

    /**
     * Delay in milliseconds for the given zero-based attempt.
     * Formula: baseDelay * 2^attempt, then ±20% jitter.
     */
    public long delayMs(int attempt) {
        if (attempt <= 0) {
            return jitter(baseDelayMs);
        }
        long exponential = baseDelayMs * (1L << Math.min(attempt, 20));
        return jitter(exponential);
    }

    private long jitter(long value) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double jitter = 1.0 + (r.nextDouble() * 2.0 - 1.0) * jitterFactor;
        return Math.max(0, (long) (value * jitter));
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    /**
     * Default: 1s base, ±20% jitter, 5 max attempts.
     */
    public static RetryPolicy defaultPolicy() {
        return new RetryPolicy(1000L, 0.2, 5);
    }
}
