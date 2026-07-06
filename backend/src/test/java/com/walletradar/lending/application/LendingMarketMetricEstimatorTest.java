package com.walletradar.lending.application;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class LendingMarketMetricEstimatorTest {

    private final LendingMarketMetricEstimator estimator = new LendingMarketMetricEstimator();

    @Test
    void estimatesHealthFactorFromProtocolThresholdAndExposure() {
        LendingMarketMetricEstimator.MetricSnapshot metric = estimator.estimate(
                "Aave",
                "GROUP",
                "USDC",
                BigDecimal.valueOf(10_000),
                BigDecimal.valueOf(2_500)
        );

        assertThat(metric.healthFactor()).isEqualByComparingTo("3.12");
        assertThat(metric.healthLabel()).isEqualTo("Safe");
        assertThat(metric.status()).isEqualTo(LendingMarketRateStatus.FALLBACK_ESTIMATE);
        assertThat(metric.source()).isEqualTo("ACCOUNTING_ESTIMATE");
    }

    @Test
    void noDebtGroupsRemainRenderable() {
        LendingMarketMetricEstimator.MetricSnapshot metric = estimator.estimate(
                "Morpho",
                "GROUP",
                "ETH",
                BigDecimal.valueOf(100),
                BigDecimal.ZERO
        );

        assertThat(metric.healthFactor()).isEqualByComparingTo("99");
        assertThat(metric.healthLabel()).isEqualTo("No debt");
        assertThat(metric.healthProgress()).isEqualByComparingTo("100.00");
    }
}
