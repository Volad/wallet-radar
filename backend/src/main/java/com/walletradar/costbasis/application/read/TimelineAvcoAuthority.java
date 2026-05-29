package com.walletradar.costbasis.application.read;

import com.walletradar.accounting.support.AccountingAssetFamilySupport;
import com.walletradar.costbasis.domain.AssetLedgerPoint;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Read-model authority for move-basis timeline AVCO.
 *
 * <p>Selects one economically meaningful ledger point per grouped event instead of
 * max-|quantityDelta| or family rollup. {@code avcoBeforeUsd} for chart markers should
 * follow the previous timeline row with the same {@code accountingAssetIdentity} spot
 * series (see {@link #updateSeries}).</p>
 */
public final class TimelineAvcoAuthority {

    public static final String KIND_PRIMARY_FLOW = "PRIMARY_FLOW";
    public static final String KIND_UNAVAILABLE = "UNAVAILABLE";

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal OUTLIER_MULTIPLIER = new BigDecimal("10");
    private static final BigDecimal LOW_COVERAGE_RATIO = new BigDecimal("0.01");
    private static final BigDecimal HIGH_UNCOVERED_RATIO = new BigDecimal("0.50");

    private TimelineAvcoAuthority() {
    }

    public record Resolution(
            BigDecimal avcoAfterUsd,
            String avcoKind,
            String accountingAssetIdentity
    ) {
    }

    public static BigDecimal medianSpotAvco(String familyIdentity, List<AssetLedgerPoint> timelinePoints) {
        if (timelinePoints == null || timelinePoints.isEmpty()) {
            return null;
        }
        List<BigDecimal> samples = timelinePoints.stream()
                .filter(point -> AccountingAssetFamilySupport.includeInSpotFamilyTimelineAggregation(
                        familyIdentity,
                        point.getAssetSymbol()
                ))
                .map(AssetLedgerPoint::getAvcoAfterUsd)
                .filter(Objects::nonNull)
                .filter(value -> value.signum() > 0)
                .sorted()
                .toList();
        if (samples.isEmpty()) {
            return null;
        }
        int middle = samples.size() / 2;
        if (samples.size() % 2 == 1) {
            return samples.get(middle);
        }
        return samples.get(middle - 1).add(samples.get(middle), MC).divide(BigDecimal.valueOf(2), MC);
    }

    public static Resolution resolve(
            String familyIdentity,
            List<AssetLedgerPoint> memberPoints,
            BigDecimal medianSpotAvcoUsd
    ) {
        AssetLedgerPoint authoritative = selectAuthoritativePoint(familyIdentity, memberPoints, medianSpotAvcoUsd);
        if (authoritative == null || authoritative.getAvcoAfterUsd() == null) {
            return new Resolution(null, KIND_UNAVAILABLE, authoritative == null ? null : authoritative.getAccountingAssetIdentity());
        }
        if (isAvcoOutlier(authoritative, medianSpotAvcoUsd)) {
            return new Resolution(null, KIND_UNAVAILABLE, authoritative.getAccountingAssetIdentity());
        }
        return new Resolution(
                authoritative.getAvcoAfterUsd(),
                KIND_PRIMARY_FLOW,
                authoritative.getAccountingAssetIdentity()
        );
    }

    public static BigDecimal avcoBeforeForSeries(
            Map<String, BigDecimal> lastAvcoByAssetIdentity,
            String accountingAssetIdentity
    ) {
        if (lastAvcoByAssetIdentity == null || accountingAssetIdentity == null || accountingAssetIdentity.isBlank()) {
            return null;
        }
        return lastAvcoByAssetIdentity.get(accountingAssetIdentity);
    }

    public static Map<String, BigDecimal> newSeriesTracker() {
        return new LinkedHashMap<>();
    }

    public static void updateSeries(Map<String, BigDecimal> lastAvcoByAssetIdentity, Resolution resolution) {
        if (lastAvcoByAssetIdentity == null
                || resolution == null
                || resolution.avcoAfterUsd() == null
                || resolution.accountingAssetIdentity() == null
                || resolution.accountingAssetIdentity().isBlank()) {
            return;
        }
        lastAvcoByAssetIdentity.put(resolution.accountingAssetIdentity(), resolution.avcoAfterUsd());
    }

    private static AssetLedgerPoint selectAuthoritativePoint(
            String familyIdentity,
            List<AssetLedgerPoint> memberPoints,
            BigDecimal medianSpotAvcoUsd
    ) {
        if (memberPoints == null || memberPoints.isEmpty()) {
            return null;
        }
        return memberPoints.stream()
                .filter(point -> scorePoint(familyIdentity, point) > Integer.MIN_VALUE)
                .filter(point -> !isAvcoOutlier(point, medianSpotAvcoUsd))
                .max(Comparator
                        .comparingInt((AssetLedgerPoint point) -> scorePoint(familyIdentity, point))
                        .thenComparing(
                                point -> zeroIfNull(point.getQuantityDelta()).abs(),
                                Comparator.nullsLast(Comparator.naturalOrder())
                        )
                        .thenComparing(
                                AssetLedgerPoint::getReplaySequence,
                                Comparator.nullsLast(Comparator.naturalOrder())
                        ))
                .orElse(null);
    }

    private static int scorePoint(String familyIdentity, AssetLedgerPoint point) {
        if (point == null
                || !AccountingAssetFamilySupport.includeInSpotFamilyTimelineAggregation(
                familyIdentity,
                point.getAssetSymbol()
        )) {
            return Integer.MIN_VALUE;
        }
        if (point.getAvcoAfterUsd() == null || point.getAvcoAfterUsd().signum() <= 0) {
            return Integer.MIN_VALUE + 1;
        }
        int score = 1_000;
        if (isSpotNativeSymbol(familyIdentity, point.getAssetSymbol())) {
            score += 500;
        }
        AssetLedgerPoint.BasisEffect basisEffect = point.getBasisEffect();
        if (basisEffect == AssetLedgerPoint.BasisEffect.ACQUIRE
                || basisEffect == AssetLedgerPoint.BasisEffect.DISPOSE
                || basisEffect == AssetLedgerPoint.BasisEffect.CARRY_IN
                || basisEffect == AssetLedgerPoint.BasisEffect.CARRY_OUT) {
            score += 200;
        }
        BigDecimal basisDelta = point.getCostBasisDeltaUsd();
        if (basisDelta != null && basisDelta.signum() != 0) {
            score += 100;
        }
        if (zeroIfNull(point.getQuantityDelta()).signum() > 0) {
            score += 50;
        }
        return score;
    }

    private static boolean isSpotNativeSymbol(String familyIdentity, String assetSymbol) {
        if (!"FAMILY:ETH".equals(familyIdentity)) {
            return true;
        }
        String symbol = normalizeSymbol(assetSymbol);
        return switch (symbol) {
            case "ETH", "WETH" -> true;
            default -> symbol.startsWith("A") && symbol.endsWith("ETH") && !AccountingAssetFamilySupport.isExcludedFromSpotEthTimelineRollup(symbol);
        };
    }

    private static boolean isAvcoOutlier(AssetLedgerPoint point, BigDecimal medianSpotAvcoUsd) {
        if (point == null) {
            return true;
        }
        BigDecimal avco = point.getAvcoAfterUsd();
        if (avco == null || avco.signum() <= 0) {
            return true;
        }
        if (medianSpotAvcoUsd != null
                && medianSpotAvcoUsd.signum() > 0
                && avco.compareTo(medianSpotAvcoUsd.multiply(OUTLIER_MULTIPLIER, MC)) > 0) {
            return true;
        }
        BigDecimal quantityAfter = point.getQuantityAfter();
        BigDecimal basisBacked = point.getBasisBackedQuantityAfter();
        BigDecimal uncovered = point.getUncoveredQuantityAfter();
        if (quantityAfter == null || quantityAfter.signum() <= 0 || basisBacked == null || uncovered == null) {
            return false;
        }
        BigDecimal coveredRatio = basisBacked.divide(quantityAfter, MC);
        BigDecimal uncoveredRatio = uncovered.divide(quantityAfter, MC);
        return coveredRatio.compareTo(LOW_COVERAGE_RATIO) < 0
                && uncoveredRatio.compareTo(HIGH_UNCOVERED_RATIO) > 0;
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String normalizeSymbol(String symbol) {
        return symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
    }
}
