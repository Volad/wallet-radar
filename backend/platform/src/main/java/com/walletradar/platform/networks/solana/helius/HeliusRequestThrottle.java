package com.walletradar.platform.networks.solana.helius;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Client-side request-rate gate for all Helius traffic (Enhanced Transactions REST + Solana
 * JSON-RPC hitting the Helius RPC URL).
 *
 * <p>The 2-year single-segment Solana backfill issues a burst of Helius calls in one pass
 * (owner-history paging + per-ATA {@code getSignaturesForAddress} + {@code parseTransactions}
 * batches). Per-call retry backoff alone does not help because the problem is the aggregate
 * <em>burst rate</em>, not isolated transient blips. This gate enforces a minimum interval between
 * consecutive outbound requests so the whole pass stays under the Helius rate limit.</p>
 *
 * <p>A single shared instance is injected into both the Enhanced-API client and the RPC ATA path so
 * they draw from one rate budget. Thread-safe: slots are reserved under a lock (so concurrent
 * callers serialize onto distinct future slots) and the wait happens outside the lock.</p>
 */
@Slf4j
public class HeliusRequestThrottle {

    private final long minIntervalNanos;
    private final ReentrantLock lock = new ReentrantLock();
    private long nextAllowedNanos = System.nanoTime();

    public HeliusRequestThrottle(long minRequestIntervalMillis) {
        this.minIntervalNanos = Math.max(0L, minRequestIntervalMillis) * 1_000_000L;
    }

    /**
     * Blocks (if necessary) until the next request slot is due, then reserves the following slot.
     * A non-positive configured interval disables throttling.
     */
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
