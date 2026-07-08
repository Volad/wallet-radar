package com.walletradar.lending.application;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LendingFactualApyNetStrategySupportTest {

    @Test
    void supplyOnlyBlendsPositiveAssetYieldDespiteNegativeUsdPnlScenario() {
        LendingFactualApyNetStrategySupport.NetStrategyRates result =
                LendingFactualApyNetStrategySupport.blend(
                        Map.of("ETH", new BigDecimal("3.5")),
                        Map.of("ETH", new BigDecimal("3.56")),
                        Map.of(),
                        Map.of(),
                        List.of(new LendingFactualApyNetStrategySupport.PositionExposure(
                                "WETH",
                                "SUPPLY",
                                new BigDecimal("1000")
                        )),
                        asset -> "ETH".equals(asset) || "WETH".equals(asset) ? "ETH" : asset
                );

        assertThat(result.netStrategyApyPct()).isEqualByComparingTo("3.56");
        assertThat(result.netStrategyAprPct()).isEqualByComparingTo("3.5");
    }

    @Test
    void netStrategySubtractsBorrowLegFromSupplyLeg() {
        LendingFactualApyNetStrategySupport.NetStrategyRates result =
                LendingFactualApyNetStrategySupport.blend(
                        Map.of("ETH", new BigDecimal("4.0"), "USDC", new BigDecimal("8.0")),
                        Map.of("ETH", new BigDecimal("4.08"), "USDC", new BigDecimal("8.16")),
                        Map.of("USDC", new BigDecimal("5.0")),
                        Map.of("USDC", new BigDecimal("5.13")),
                        List.of(
                                new LendingFactualApyNetStrategySupport.PositionExposure("ETH", "SUPPLY", new BigDecimal("3000")),
                                new LendingFactualApyNetStrategySupport.PositionExposure("USDC", "BORROW", new BigDecimal("1000"))
                        ),
                        asset -> asset
                );

        // (3000*4.08 - 1000*5.13) / (3000 - 1000) = 3.55
        assertThat(result.netStrategyApyPct().doubleValue()).isCloseTo(3.555, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void returnsNullWhenBorrowWeightExceedsSupplyWeight() {
        LendingFactualApyNetStrategySupport.NetStrategyRates result =
                LendingFactualApyNetStrategySupport.blend(
                        Map.of("ETH", new BigDecimal("3.0")),
                        Map.of("ETH", new BigDecimal("3.04")),
                        Map.of("USDC", new BigDecimal("5.0")),
                        Map.of("USDC", new BigDecimal("5.13")),
                        List.of(
                                new LendingFactualApyNetStrategySupport.PositionExposure("ETH", "SUPPLY", new BigDecimal("100")),
                                new LendingFactualApyNetStrategySupport.PositionExposure("USDC", "BORROW", new BigDecimal("200"))
                        ),
                        asset -> asset
                );

        assertThat(result.netStrategyApyPct()).isNull();
        assertThat(result.netStrategyAprPct()).isNull();
    }
}
