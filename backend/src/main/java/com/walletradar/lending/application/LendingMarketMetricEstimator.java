package com.walletradar.lending.application;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Locale;

@Component
public class LendingMarketMetricEstimator {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal MAX_PROGRESS_HEALTH = BigDecimal.valueOf(3);

    MetricSnapshot estimate(String protocol, String side, String underlyingSymbol, BigDecimal supplyUsd, BigDecimal borrowUsd) {
        BigDecimal apy = estimateApy(protocol, side, underlyingSymbol);
        BigDecimal healthFactor = estimateHealthFactor(protocol, supplyUsd, borrowUsd);
        return new MetricSnapshot(
                healthFactor,
                healthLabel(healthFactor),
                healthProgress(healthFactor),
                apy,
                "ESTIMATED",
                "ACCOUNTING_ESTIMATE"
        );
    }

    private BigDecimal estimateHealthFactor(String protocol, BigDecimal supplyUsd, BigDecimal borrowUsd) {
        if (borrowUsd == null || borrowUsd.signum() <= 0) {
            return BigDecimal.valueOf(99);
        }
        BigDecimal threshold = liquidationThreshold(protocol);
        BigDecimal safeSupply = supplyUsd == null ? BigDecimal.ZERO : supplyUsd;
        return safeSupply.multiply(threshold, MC).divide(borrowUsd, MC).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal liquidationThreshold(String protocol) {
        return switch (normalizeProtocol(protocol)) {
            case "AAVE" -> BigDecimal.valueOf(0.78);
            case "EULER" -> BigDecimal.valueOf(0.75);
            case "MORPHO" -> BigDecimal.valueOf(0.77);
            case "FLUID" -> BigDecimal.valueOf(0.80);
            case "COMPOUND" -> BigDecimal.valueOf(0.74);
            default -> BigDecimal.valueOf(0.70);
        };
    }

    private BigDecimal estimateApy(String protocol, String side, String underlyingSymbol) {
        String normalizedProtocol = normalizeProtocol(protocol);
        boolean stable = LendingAssetSymbolSupport.isStable(underlyingSymbol);
        boolean borrow = "BORROW".equalsIgnoreCase(side);
        double base = switch (normalizedProtocol) {
            case "AAVE" -> stable ? 4.1 : 2.4;
            case "EULER" -> stable ? 5.2 : 2.9;
            case "MORPHO" -> stable ? 4.8 : 2.6;
            case "FLUID" -> stable ? 5.0 : 2.8;
            case "COMPOUND" -> stable ? 3.9 : 2.1;
            default -> stable ? 3.5 : 1.8;
        };
        return BigDecimal.valueOf(borrow ? -base - 1.2 : base).setScale(2, RoundingMode.HALF_UP);
    }

    private String healthLabel(BigDecimal healthFactor) {
        if (healthFactor == null) {
            return "Unavailable";
        }
        if (healthFactor.compareTo(BigDecimal.valueOf(10)) >= 0) {
            return "No debt";
        }
        if (healthFactor.compareTo(BigDecimal.valueOf(2)) >= 0) {
            return "Safe";
        }
        if (healthFactor.compareTo(BigDecimal.valueOf(1.5)) >= 0) {
            return "Moderate";
        }
        if (healthFactor.compareTo(BigDecimal.valueOf(1.1)) >= 0) {
            return "At risk";
        }
        return "Liquidation risk";
    }

    private BigDecimal healthProgress(BigDecimal healthFactor) {
        if (healthFactor == null || healthFactor.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal capped = healthFactor.min(MAX_PROGRESS_HEALTH);
        return capped.divide(MAX_PROGRESS_HEALTH, MC).multiply(BigDecimal.valueOf(100), MC)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private String normalizeProtocol(String protocol) {
        if (protocol == null || protocol.isBlank()) {
            return "UNKNOWN";
        }
        String normalized = protocol.trim().toUpperCase(Locale.ROOT);
        if (normalized.contains("AAVE")) {
            return "AAVE";
        }
        if (normalized.contains("EULER")) {
            return "EULER";
        }
        if (normalized.contains("MORPHO")) {
            return "MORPHO";
        }
        if (normalized.contains("FLUID")) {
            return "FLUID";
        }
        if (normalized.contains("COMPOUND")) {
            return "COMPOUND";
        }
        return normalized;
    }

    record MetricSnapshot(
            BigDecimal healthFactor,
            String healthLabel,
            BigDecimal healthProgress,
            BigDecimal apyPct,
            String status,
            String source
    ) {
    }
}
