package com.walletradar.platform.networks.solana.jupiter;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Client-side request-rate gate for all Jupiter traffic (token metadata + price v3), mirroring
 * {@code HeliusRequestThrottle}. Enforces a minimum interval between consecutive outbound requests
 * so bursts (balance refresh mint lookups, price refresh batches) stay under the free-tier limit.
 *
 * <p>Thread-safe: slots are reserved under a lock and the wait happens outside the lock. A
 * non-positive configured interval disables throttling.</p>
 */
public class JupiterRequestThrottle {

    private final long minIntervalNanos;
    private final ReentrantLock lock = new ReentrantLock();
    private long nextAllowedNanos = System.nanoTime();

    public JupiterRequestThrottle(long minRequestIntervalMillis) {
        this.minIntervalNanos = Math.max(0L, minRequestIntervalMillis) * 1_000_000L;
    }

    public void acquire() {
        if (minIntervalNanos <= 0L) {
            return;
        }
        long waitNanos;
        lock.lock();
        try {
            long now = System.nanoTime();
            long scheduled = Math.max(now, nextAllowedNanos);
            waitNanos = scheduled - now;
            nextAllowedNanos = scheduled + minIntervalNanos;
        } finally {
            lock.unlock();
        }
        if (waitNanos > 0L) {
            try {
                TimeUnit.NANOSECONDS.sleep(waitNanos);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
