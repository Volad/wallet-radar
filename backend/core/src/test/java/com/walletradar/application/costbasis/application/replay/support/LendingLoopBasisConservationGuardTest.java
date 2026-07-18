package com.walletradar.application.costbasis.application.replay.support;

import com.walletradar.application.costbasis.application.replay.model.CarryTransfer;
import com.walletradar.application.costbasis.application.replay.model.ContinuityBucket;
import com.walletradar.application.costbasis.application.replay.model.ContinuityKey;
import com.walletradar.application.costbasis.application.replay.state.ContinuityStore;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class LendingLoopBasisConservationGuardTest {

    private final LendingLoopBasisConservationGuard guard = new LendingLoopBasisConservationGuard();

    @Test
    void continuityBucketTracksLifetimeParkedAndTakenBasis() {
        ContinuityBucket bucket = new ContinuityBucket();
        bucket.add(covered(new BigDecimal("1.0"), new BigDecimal("100")));

        bucket.take(new BigDecimal("0.4"), null);

        // Invariant: parked == taken + residual, to the cent.
        assertThat(bucket.cumulativeAddedCostBasisUsd()).isEqualByComparingTo("100");
        assertThat(bucket.cumulativeTakenCostBasisUsd()).isEqualByComparingTo("40");
        assertThat(bucket.totalCostBasisUsd()).isEqualByComparingTo("60");
        assertThat(bucket.cumulativeAddedCostBasisUsd())
                .isEqualByComparingTo(bucket.cumulativeTakenCostBasisUsd().add(bucket.totalCostBasisUsd()));
    }

    @Test
    void guardReportsResidualWithoutBreachWhenBucketConserves() {
        ContinuityStore store = new ContinuityStore();
        ContinuityBucket bucket = store.bucket(new ContinuityKey(null, null, "lending-loop:0xopen:FAMILY:ETH"));
        bucket.add(covered(new BigDecimal("1.0"), new BigDecimal("100")));
        bucket.take(new BigDecimal("0.4"), null);

        // A non-lending bucket must be ignored entirely.
        ContinuityBucket other = store.bucket(new ContinuityKey("0xwallet", null, "FAMILY:ETH"));
        other.add(covered(new BigDecimal("2.0"), new BigDecimal("500")));

        LendingLoopBasisConservationGuard.Result result = guard.evaluate(store);

        assertThat(result.buckets()).hasSize(1);
        assertThat(result.breaches()).isEmpty();
        assertThat(result.totalParkedBasisUsd()).isEqualByComparingTo("100");
        assertThat(result.totalRestoredBasisUsd()).isEqualByComparingTo("40");
        assertThat(result.totalResidualBasisUsd()).isEqualByComparingTo("60");
    }

    @Test
    void emptyStoreYieldsEmptyResult() {
        assertThat(guard.evaluate(new ContinuityStore()).buckets()).isEmpty();
    }

    private static CarryTransfer covered(BigDecimal quantity, BigDecimal costBasisUsd) {
        BigDecimal avco = costBasisUsd.divide(quantity, java.math.MathContext.DECIMAL128);
        return new CarryTransfer(
                quantity,
                quantity,
                BigDecimal.ZERO,
                costBasisUsd,
                avco,
                costBasisUsd,
                avco,
                false,
                null
        );
    }
}
