package com.walletradar.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RetryPolicyTest {

    @Test
    void delayMs_attemptZero_returnsJitteredBaseDelay() {
        RetryPolicy policy = new RetryPolicy(1000L, 0.2, 5);
        for (int i = 0; i < 20; i++) {
            long d = policy.delayMs(0);
            assertThat(d).isBetween(800L, 1200L); // ±20% of 1000
        }
    }

    @Test
    void delayMs_exponentialIncreases() {
        RetryPolicy policy = new RetryPolicy(100L, 0, 5); // no jitter for deterministic test
        long d0 = policy.delayMs(0);
        long d1 = policy.delayMs(1);
        long d2 = policy.delayMs(2);
        assertThat(d0).isEqualTo(100L);
        assertThat(d1).isEqualTo(200L);
        assertThat(d2).isEqualTo(400L);
    }

    @Test
    void defaultPolicy_hasExpectedMaxAttempts() {
        assertThat(RetryPolicy.defaultPolicy().getMaxAttempts()).isEqualTo(5);
    }
}
