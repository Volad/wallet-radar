package com.walletradar.lending.application;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Blends per-asset asset-denominated factual APYs into a single net-strategy headline rate.
 * USD position weights are used only for exposure weighting; rates themselves are not
 * contaminated by principal price revaluation.
 */
final class LendingFactualApyNetStrategySupport {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal MAX_APY_PCT = BigDecimal.valueOf(100_000L);

    private LendingFactualApyNetStrategySupport() {
    }

    record PositionExposure(
            String underlyingSymbol,
            String side,
            BigDecimal valueUsd
    ) {
    }

    record NetStrategyRates(
            BigDecimal netStrategyAprPct,
            BigDecimal netStrategyApyPct
    ) {
    }

    static NetStrategyRates blend(
            Map<String, BigDecimal> factualSupplyAprByAsset,
            Map<String, BigDecimal> factualSupplyApyByAsset,
            Map<String, BigDecimal> factualBorrowAprByAsset,
            Map<String, BigDecimal> factualBorrowApyByAsset,
            List<PositionExposure> exposures,
            Function<String, String> assetNormalizer
    ) {
        BigDecimal supplyYieldApy = BigDecimal.ZERO;
        BigDecimal supplyYieldApr = BigDecimal.ZERO;
        BigDecimal supplyWeight = BigDecimal.ZERO;
        BigDecimal borrowCostApy = BigDecimal.ZERO;
        BigDecimal borrowCostApr = BigDecimal.ZERO;
        BigDecimal borrowWeight = BigDecimal.ZERO;

        for (PositionExposure exposure : exposures) {
            if (exposure.valueUsd() == null || exposure.valueUsd().signum() <= 0) {
                continue;
            }
            String asset = assetNormalizer.apply(exposure.underlyingSymbol());
            if ("SUPPLY".equals(exposure.side())) {
                BigDecimal apy = factualSupplyApyByAsset.get(asset);
                BigDecimal apr = factualSupplyAprByAsset.get(asset);
                if (apy != null) {
                    supplyYieldApy = supplyYieldApy.add(exposure.valueUsd().multiply(apy, MC), MC);
                    supplyWeight = supplyWeight.add(exposure.valueUsd(), MC);
                }
                if (apr != null) {
                    supplyYieldApr = supplyYieldApr.add(exposure.valueUsd().multiply(apr, MC), MC);
                }
            } else if ("BORROW".equals(exposure.side())) {
                BigDecimal apy = factualBorrowApyByAsset.get(asset);
                BigDecimal apr = factualBorrowAprByAsset.get(asset);
                if (apy != null) {
                    borrowCostApy = borrowCostApy.add(exposure.valueUsd().multiply(apy, MC), MC);
                    borrowWeight = borrowWeight.add(exposure.valueUsd(), MC);
                }
                if (apr != null) {
                    borrowCostApr = borrowCostApr.add(exposure.valueUsd().multiply(apr, MC), MC);
                }
            }
        }

        BigDecimal netWeight = supplyWeight.subtract(borrowWeight, MC);
        if (netWeight.signum() <= 0 || (supplyWeight.signum() <= 0 && borrowWeight.signum() <= 0)) {
            return new NetStrategyRates(null, null);
        }

        BigDecimal netApy = supplyYieldApy.subtract(borrowCostApy, MC).divide(netWeight, MC);
        BigDecimal netApr = supplyYieldApr.subtract(borrowCostApr, MC).divide(netWeight, MC);
        if (!isPlausible(netApy) || !isPlausible(netApr)) {
            return new NetStrategyRates(null, null);
        }
        return new NetStrategyRates(
                netApr.setScale(8, RoundingMode.HALF_UP),
                netApy.setScale(8, RoundingMode.HALF_UP)
        );
    }

    private static boolean isPlausible(BigDecimal apyPct) {
        return apyPct != null && apyPct.abs().compareTo(MAX_APY_PCT) <= 0;
    }
}
