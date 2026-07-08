package com.walletradar.application.costbasis.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RC-8 (ADR-014): {@link OutOfScopeFamilySupport} is the single source of truth that drives BOTH the
 * conservation {@code adjustedMTM} exclusion and the {@code reportedPnL} realized exclusion, so OOS
 * realized PnL (TON, SOL, HYPEREVM) cannot leak asymmetrically into {@code conservationDelta}. The
 * symbols below are requirement-defined unsupported families, never hash/wallet-keyed.
 */
class OutOfScopeFamilySupportTest {

    @Test
    @DisplayName("out-of-scope accounting family identities are recognized (mirrors MtM exclusion)")
    void recognizesOutOfScopeFamilyIdentities() {
        assertThat(OutOfScopeFamilySupport.isOutOfScopeFamily("FAMILY:SOL", "SOL")).isTrue();
        assertThat(OutOfScopeFamilySupport.isOutOfScopeFamily("FAMILY:TON", "TON")).isTrue();
        assertThat(OutOfScopeFamilySupport.isOutOfScopeFamily("FAMILY:HYPEREVM", "HYPE")).isTrue();
    }

    @Test
    @DisplayName("CEX-only tickers with no canonical family mapping are recognized via symbol fallback")
    void recognizesOutOfScopeSymbolsWithoutFamilyMapping() {
        assertThat(OutOfScopeFamilySupport.isOutOfScopeFamily(null, "TON")).isTrue();
        assertThat(OutOfScopeFamilySupport.isOutOfScopeFamily(null, "TONCOIN")).isTrue();
        assertThat(OutOfScopeFamilySupport.isOutOfScopeFamily("SYMBOL:SOL", "SOL")).isTrue();
        assertThat(OutOfScopeFamilySupport.isOutOfScopeFamily(null, "BBSOL")).isTrue();
        assertThat(OutOfScopeFamilySupport.isOutOfScopeFamily(null, "WHYPE")).isTrue();
    }

    @Test
    @DisplayName("symbol matching is case-insensitive and tolerates the SYMBOL: prefix")
    void symbolMatchingIsCaseInsensitive() {
        assertThat(OutOfScopeFamilySupport.isOutOfScopeFamily(null, "ton")).isTrue();
        assertThat(OutOfScopeFamilySupport.isOutOfScopeFamily("family:sol", "sol")).isTrue();
        assertThat(OutOfScopeFamilySupport.isOutOfScopeFamily(null, "symbol:hype")).isTrue();
    }

    @Test
    @DisplayName("in-scope families (ETH/USDC/BTC) are NOT excluded — their realized stays in reportedPnL")
    void inScopeFamiliesAreNotExcluded() {
        assertThat(OutOfScopeFamilySupport.isOutOfScopeFamily("FAMILY:ETH", "ETH")).isFalse();
        assertThat(OutOfScopeFamilySupport.isOutOfScopeFamily("FAMILY:USDC", "USDC")).isFalse();
        assertThat(OutOfScopeFamilySupport.isOutOfScopeFamily("FAMILY:BTC", "BTC")).isFalse();
        assertThat(OutOfScopeFamilySupport.isOutOfScopeFamily("FAMILY:USDT", "USDT")).isFalse();
    }

    @Test
    @DisplayName("null / blank inputs are safe and not out-of-scope")
    void nullInputsAreSafe() {
        assertThat(OutOfScopeFamilySupport.isOutOfScopeFamily(null, null)).isFalse();
        assertThat(OutOfScopeFamilySupport.isOutOfScopeFamily("", "")).isFalse();
    }
}
