package com.walletradar.application.costbasis.application;

import com.walletradar.application.costbasis.breakeven.OffsetLane;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RM-3 (2026-07-24) — fail-closed UNAVAILABLE for sliver-denominated effective-cost points in BOTH
 * directions. The prior guard suppressed only the over-blended-AVCO <em>spike</em> side; the
 * mirror-image floor-to-$0 side (when ETH is parked out via a CARRY corridor and the cumulative
 * attributed offset floors {@code max(marketBasis − offset, 0)} to $0 on a sliver denominator)
 * slipped through. RM-3 drops the AND-condition so any sliver-denominated point renders "—", while a
 * healthy (non-sliver) denominator keeps its value — including a genuine $0 banked-locked-surplus
 * floor. Series/read-model only.
 */
class AssetLedgerChartServiceTest {

    // Family GLOBAL/terminal peak blended ETH-equivalent exposure (max across the whole timeline).
    private static final BigDecimal GLOBAL_PEAK = new BigDecimal("4.03");
    // 5% sliver floor = 0.2015 ETH.
    private static final BigDecimal SLIVER_COVERED = new BigDecimal("0.05");
    private static final BigDecimal HEALTHY_COVERED = new BigDecimal("3.85");

    @Test
    @DisplayName("sliver-denominated offset SPIKE is suppressed (unchanged from the prior guard)")
    void sliverSpikeIsSuppressed() {
        assertThat(AssetLedgerChartService.isOverSliverArtifact(
                new BigDecimal("40000"), SLIVER_COVERED, GLOBAL_PEAK)).isTrue();
    }

    @Test
    @DisplayName("RM-3: sliver-denominated FLOOR-to-$0 is now suppressed too (both directions)")
    void sliverFloorToZeroIsSuppressed() {
        assertThat(AssetLedgerChartService.isOverSliverArtifact(
                BigDecimal.ZERO, SLIVER_COVERED, GLOBAL_PEAK)).isTrue();
    }

    @Test
    @DisplayName("RM-3: a $0 floor on a HEALTHY (non-sliver) denominator stays visible (banked locked surplus)")
    void healthyDenominatorZeroFloorStaysVisible() {
        assertThat(AssetLedgerChartService.isOverSliverArtifact(
                BigDecimal.ZERO, HEALTHY_COVERED, GLOBAL_PEAK)).isFalse();
    }

    @Test
    @DisplayName("a healthy-denominator loss elevation above AVCO stays visible")
    void healthyDenominatorElevationStaysVisible() {
        assertThat(AssetLedgerChartService.isOverSliverArtifact(
                new BigDecimal("2493.51"), HEALTHY_COVERED, GLOBAL_PEAK)).isFalse();
    }

    @Test
    @DisplayName("a null effective cost (already UNAVAILABLE) is never re-flagged")
    void nullEffectiveCostIsNotArtifact() {
        assertThat(AssetLedgerChartService.isOverSliverArtifact(
                null, SLIVER_COVERED, GLOBAL_PEAK)).isFalse();
    }

    @Test
    @DisplayName("a non-positive global peak disables the guard (no exposure ever observed)")
    void zeroGlobalPeakDisablesGuard() {
        assertThat(AssetLedgerChartService.isOverSliverArtifact(
                new BigDecimal("40000"), SLIVER_COVERED, BigDecimal.ZERO)).isFalse();
    }

    @Test
    @DisplayName("boundary: covered quantity exactly at the 5% sliver floor is NOT sliver-denominated")
    void atSliverFloorIsNotSuppressed() {
        BigDecimal exactlyAtFloor = new BigDecimal("0.2015"); // 5% of 4.03
        assertThat(AssetLedgerChartService.isOverSliverArtifact(
                BigDecimal.ZERO, exactlyAtFloor, GLOBAL_PEAK)).isFalse();
    }

    // ---- ADR-062 (2026-07-24): SERIES numerator follows the offset lane ----------------------------

    @Test
    @DisplayName("NET lane: series numerator uses the blended NET AVCO (held reward income credited free)")
    void netLaneSeriesUsesNetAvco() {
        // Blended market AVCO $12/unit, net AVCO $0.50/unit (held reward income), 2 covered units, no
        // offset. Under NET the per-point effective cost uses the net AVCO ⇒ $0.50, not $12.
        BlendedExposureAvcoSeriesBuilder.BlendedPoint blended =
                new BlendedExposureAvcoSeriesBuilder.BlendedPoint(
                        new BigDecimal("12"), new BigDecimal("0.50"), new BigDecimal("2"), "PRIMARY_FLOW");

        BigDecimal net = AssetLedgerChartService.effectiveCostAfterUsd(blended, BigDecimal.ZERO, OffsetLane.NET);
        BigDecimal market = AssetLedgerChartService.effectiveCostAfterUsd(blended, BigDecimal.ZERO, OffsetLane.MARKET);

        assertThat(net).isEqualByComparingTo("0.50");
        assertThat(market).isEqualByComparingTo("12");
    }

    @Test
    @DisplayName("NET lane: series offset banks against the NET held basis (no double-count)")
    void netLaneSeriesOffsetBanksAgainstNetBasis() {
        // Net AVCO $2/unit over 5 covered units ⇒ net held basis $10; cumulative net realized offset
        // $4 ⇒ effective basis 10 − 4 = 6 ⇒ $1.20/unit under NET.
        BlendedExposureAvcoSeriesBuilder.BlendedPoint blended =
                new BlendedExposureAvcoSeriesBuilder.BlendedPoint(
                        new BigDecimal("20"), new BigDecimal("2"), new BigDecimal("5"), "PRIMARY_FLOW");

        BigDecimal net = AssetLedgerChartService.effectiveCostAfterUsd(blended, new BigDecimal("4"), OffsetLane.NET);

        assertThat(net).isEqualByComparingTo("1.20");
    }
}
