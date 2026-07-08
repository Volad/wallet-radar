package com.walletradar.application.costbasis.application.replay.model;

import com.walletradar.application.costbasis.application.replay.model.CarryTransfer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class ContinuityBucketTest {

    @Test
    void takeReturnsAppliedQuantityNotRequestedWhenBucketIsPartial() {
        ContinuityBucket bucket = new ContinuityBucket();
        bucket.add(new CarryTransfer(
                new BigDecimal("0.5"),
                new BigDecimal("0.5"),
                BigDecimal.ZERO,
                new BigDecimal("1000"),
                new BigDecimal("2000"),
                new BigDecimal("1000"),
                new BigDecimal("2000"),
                false,
                null
        ));

        CarryTransfer taken = bucket.take(new BigDecimal("0.3"), null);

        assertThat(taken.quantity()).isEqualByComparingTo("0.3");
        assertThat(taken.costBasisUsd()).isEqualByComparingTo("600");
        assertThat(bucket.quantity()).isEqualByComparingTo("0.2");
    }
}
