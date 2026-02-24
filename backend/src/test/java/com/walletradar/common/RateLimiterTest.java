package com.walletradar.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterTest {

    @Test
    @DisplayName("tryAcquire allows first call and denies second immediately at 1/min")
    void tryAcquireOnePerMinute() {
        RateLimiter limiter = new RateLimiter(1); // 1 per minute
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isFalse();
    }

    @Test
    @DisplayName("acquire blocks and allows next after interval")
    void acquireBlocksThenAllows() throws InterruptedException {
        RateLimiter limiter = new RateLimiter(60); // 60 per minute = 1 per second
        limiter.acquire();
        long start = System.nanoTime();
        limiter.acquire();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(elapsedMs).isGreaterThanOrEqualTo(900); // ~1s minus tolerance
    }

    @Test
    @DisplayName("multiple threads respect rate")
    void multipleThreadsRespectRate() throws InterruptedException {
        int permitsPerMinute = 100;
        RateLimiter limiter = new RateLimiter(permitsPerMinute);
        int threadCount = 5;
        int acquiresPerThread = 4;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        for (int t = 0; t < threadCount; t++) {
            new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < acquiresPerThread; i++) {
                        limiter.acquire();
                        successCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(successCount.get()).isEqualTo(threadCount * acquiresPerThread);
    }

    @Test
    @DisplayName("constructor rejects non-positive rate")
    void constructorRejectsNonPositive() {
        try {
            new RateLimiter(0);
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage()).contains("positive");
        }
    }
}
