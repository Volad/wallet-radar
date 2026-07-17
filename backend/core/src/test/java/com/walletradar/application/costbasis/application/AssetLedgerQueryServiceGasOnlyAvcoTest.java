package com.walletradar.application.costbasis.application;

import com.walletradar.application.costbasis.domain.AssetLedgerPoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WS-4: sponsored-gas / reward-claim AVCO representation — null when basisBackedQty ≈ 0.
 */
class AssetLedgerQueryServiceGasOnlyAvcoTest {

    @Test
    @DisplayName("SPONSORED_GAS_IN + basisBackedQty≈0 → avcoAfter is null (not 0)")
    void sponsoredGasIn_basisBackedNearZero_avcoNull() {
        AssetLedgerPoint point = buildPoint(
                "SPONSORED_GAS_IN",
                AssetLedgerPoint.BasisEffect.GAS_ONLY,
                BigDecimal.ZERO,
                BigDecimal.ZERO
        );

        BigDecimal result = AssetLedgerQueryService.gasOnlyAvcoAfter(point);

        assertThat(result).as("SPONSORED_GAS_IN with basisBackedQty≈0 must return null, not 0").isNull();
    }

    @Test
    @DisplayName("REWARD_CLAIM + basisBackedQty≈0 → avcoAfter is null")
    void rewardClaim_basisBackedNearZero_avcoNull() {
        AssetLedgerPoint point = buildPoint(
                "REWARD_CLAIM",
                AssetLedgerPoint.BasisEffect.GAS_ONLY,
                BigDecimal.ZERO,
                BigDecimal.ZERO
        );

        BigDecimal result = AssetLedgerQueryService.gasOnlyAvcoAfter(point);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("SPONSORED_GAS_IN + non-zero basisBackedQty → avcoAfter NOT nulled (has real basis)")
    void sponsoredGasIn_nonZeroBasisBacked_avcoNotNulled() {
        AssetLedgerPoint point = buildPoint(
                "SPONSORED_GAS_IN",
                AssetLedgerPoint.BasisEffect.GAS_ONLY,
                new BigDecimal("0.5"),  // non-trivial basis-backed qty
                new BigDecimal("3000")
        );

        BigDecimal result = AssetLedgerQueryService.gasOnlyAvcoAfter(point);

        assertThat(result).isEqualByComparingTo(new BigDecimal("3000"));
    }

    @Test
    @DisplayName("WRAP with basisBackedQty > 0 → avcoAfter NOT nulled (must not fire for WRAP)")
    void wrap_basisBackedPositive_avcoNotNulled() {
        AssetLedgerPoint point = buildPoint(
                "WRAP",
                AssetLedgerPoint.BasisEffect.REALLOCATE_OUT,
                new BigDecimal("1.0"),
                new BigDecimal("2950")
        );

        BigDecimal result = AssetLedgerQueryService.gasOnlyAvcoAfter(point);

        assertThat(result).isEqualByComparingTo(new BigDecimal("2950"));
    }

    @Test
    @DisplayName("GAS_ONLY basisEffect + basisBackedQty≈0 + non-gas type → avcoAfter null")
    void gasOnlyBasisEffect_basisBackedNearZero_avcoNull() {
        AssetLedgerPoint point = buildPoint(
                "SOME_OTHER_TYPE",
                AssetLedgerPoint.BasisEffect.GAS_ONLY,
                new BigDecimal("0.000000005"),  // < 1e-8 threshold
                BigDecimal.ZERO
        );

        BigDecimal result = AssetLedgerQueryService.gasOnlyAvcoAfter(point);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("BRIDGE_OUT with basisBackedQty≈0 → avcoAfter NOT nulled (not gas-only type)")
    void bridgeOut_basisBackedNearZero_avcoNotNulled() {
        AssetLedgerPoint point = buildPoint(
                "BRIDGE_OUT",
                AssetLedgerPoint.BasisEffect.CARRY_OUT,
                BigDecimal.ZERO,
                BigDecimal.ZERO
        );
        point.setAvcoAfterUsd(new BigDecimal("3100"));

        BigDecimal result = AssetLedgerQueryService.gasOnlyAvcoAfter(point);

        assertThat(result).isEqualByComparingTo(new BigDecimal("3100"));
    }

    // ──────────────────────────────────────────────────────────────
    // B-ETH-04: LP_EXIT zero-cost-basis dust guard
    // ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("B-ETH-04: LP_EXIT_FINAL zero-cbd dust restoration → avcoAfter null (seq 4875)")
    void lpExitFinal_zeroCostBasisDust_avcoNull() {
        // Mirrors audited seq 4875: an unpriced LP_FEE_INCOME ETH leg booked as a zero-cost
        // ACQUIRE dilutes the covered AVCO of a $0.13 dust residual to a spurious ≈$249.47.
        AssetLedgerPoint point = buildLpExitPoint(
                "LP_EXIT_FINAL",
                AssetLedgerPoint.BasisEffect.ACQUIRE,
                new BigDecimal("0.000196308863730581"),  // quantityDelta > 0
                BigDecimal.ZERO,                          // costBasisDeltaUsd == 0
                new BigDecimal("0.130252979895573508"),  // sub-$1 dust basis after
                new BigDecimal("249.467056619348637799") // spurious diluted AVCO
        );

        BigDecimal result = AssetLedgerQueryService.gasOnlyAvcoAfter(point);

        assertThat(result)
                .as("zero-cbd LP_EXIT_FINAL dust restoration must not emit a spurious AVCO")
                .isNull();
    }

    @Test
    @DisplayName("B-ETH-04: LP_EXIT_PARTIAL zero-cbd dust restoration (REALLOCATE_IN) → avcoAfter null")
    void lpExitPartial_zeroCostBasisDust_reallocateIn_avcoNull() {
        AssetLedgerPoint point = buildLpExitPoint(
                "LP_EXIT_PARTIAL",
                AssetLedgerPoint.BasisEffect.REALLOCATE_IN,
                new BigDecimal("0.0005"),
                BigDecimal.ZERO,
                new BigDecimal("0.46"),
                new BigDecimal("120")
        );

        BigDecimal result = AssetLedgerQueryService.gasOnlyAvcoAfter(point);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("B-ETH-04: LP_EXIT that DOES restore basis → avcoAfter unchanged (byte-identical)")
    void lpExit_withRealBasisRestored_avcoNotNulled() {
        // Real basis restored (costBasisDelta > 0) must be left untouched.
        AssetLedgerPoint point = buildLpExitPoint(
                "LP_EXIT",
                AssetLedgerPoint.BasisEffect.REALLOCATE_IN,
                new BigDecimal("0.05"),
                new BigDecimal("150"),   // costBasisDeltaUsd > 0
                new BigDecimal("650"),
                new BigDecimal("3000")
        );

        BigDecimal result = AssetLedgerQueryService.gasOnlyAvcoAfter(point);

        assertThat(result)
                .as("LP exits that restore real basis must keep their stored AVCO")
                .isEqualByComparingTo(new BigDecimal("3000"));
    }

    @Test
    @DisplayName("B-ETH-04: non-dust zero-cbd LP fee-income leg → avcoAfter unchanged (large position)")
    void lpExit_zeroCostBasisNonDust_avcoNotNulled() {
        // A large LP position earning fee income (zero-cost) keeps its genuine diluted AVCO;
        // only sub-$1 dust residuals are treated as undefined.
        AssetLedgerPoint point = buildLpExitPoint(
                "LP_EXIT",
                AssetLedgerPoint.BasisEffect.ACQUIRE,
                new BigDecimal("0.546279273895213849"),
                BigDecimal.ZERO,
                new BigDecimal("2088.5723550315424"),  // non-dust basis after
                new BigDecimal("3846.502181354212")
        );

        BigDecimal result = AssetLedgerQueryService.gasOnlyAvcoAfter(point);

        assertThat(result)
                .as("non-dust zero-cbd LP fee-income legs must keep their diluted AVCO")
                .isEqualByComparingTo(new BigDecimal("3846.502181354212"));
    }

    private static AssetLedgerPoint buildPoint(
            String normalizedType,
            AssetLedgerPoint.BasisEffect basisEffect,
            BigDecimal basisBackedQuantityAfter,
            BigDecimal avcoAfterUsd
    ) {
        AssetLedgerPoint point = new AssetLedgerPoint();
        point.setNormalizedType(normalizedType);
        point.setBasisEffect(basisEffect);
        point.setBasisBackedQuantityAfter(basisBackedQuantityAfter);
        point.setAvcoAfterUsd(avcoAfterUsd.signum() == 0 ? null : avcoAfterUsd);
        return point;
    }

    private static AssetLedgerPoint buildLpExitPoint(
            String normalizedType,
            AssetLedgerPoint.BasisEffect basisEffect,
            BigDecimal quantityDelta,
            BigDecimal costBasisDeltaUsd,
            BigDecimal totalCostBasisAfterUsd,
            BigDecimal avcoAfterUsd
    ) {
        AssetLedgerPoint point = new AssetLedgerPoint();
        point.setNormalizedType(normalizedType);
        point.setBasisEffect(basisEffect);
        point.setQuantityDelta(quantityDelta);
        point.setCostBasisDeltaUsd(costBasisDeltaUsd);
        point.setTotalCostBasisAfterUsd(totalCostBasisAfterUsd);
        point.setBasisBackedQuantityAfter(quantityDelta);
        point.setAvcoAfterUsd(avcoAfterUsd);
        return point;
    }
}
