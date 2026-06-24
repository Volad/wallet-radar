package com.walletradar.costbasis.application;

import com.walletradar.costbasis.domain.AssetLedgerPoint;
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
}
