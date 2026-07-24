package com.walletradar.application.costbasis.application;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-062 Wave 3 (AC-10 / D6): the blended total-exposure AVCO series must render "—" (null) when its
 * covered basis is dust, matching the Market/Balance AVCO $1 dust guard, instead of a phantom high
 * per-unit figure (e.g. the observed $1.75k on a dust residual).
 */
class AssetLedgerChartServiceDustGuardTest {

    @Test
    void suppressesPhantomBlendedAvcoOnDustCoveredBasis() {
        // avco $1,750 × 0.0001 covered = $0.175 basis (< $1 dust cutoff) → suppressed.
        BigDecimal guarded = AssetLedgerChartService.dustGuardedBlendedAvco(
                new BigDecimal("1750"), new BigDecimal("0.0001"));
        assertThat(guarded).isNull();
    }

    @Test
    void keepsBlendedAvcoWhenCoveredBasisIsMaterial() {
        BigDecimal guarded = AssetLedgerChartService.dustGuardedBlendedAvco(
                new BigDecimal("3000"), BigDecimal.ONE);
        assertThat(guarded).isEqualByComparingTo("3000");
    }

    @Test
    void nullAvcoStaysNull() {
        assertThat(AssetLedgerChartService.dustGuardedBlendedAvco(null, BigDecimal.ONE)).isNull();
    }

    @Test
    void zeroOrMissingCoveredQuantityReturnsAvcoUnchanged() {
        // Guard is a no-op when covered quantity is unavailable (the caller already handles no-coverage).
        assertThat(AssetLedgerChartService.dustGuardedBlendedAvco(new BigDecimal("42"), BigDecimal.ZERO))
                .isEqualByComparingTo("42");
        assertThat(AssetLedgerChartService.dustGuardedBlendedAvco(new BigDecimal("42"), null))
                .isEqualByComparingTo("42");
    }
}
