package com.walletradar.application.normalization.pipeline.metadata;

import com.walletradar.application.normalization.pipeline.classification.support.TokenSymbolFallbackSupport;
import com.walletradar.application.normalization.pipeline.solana.SolanaSplTokenMetadataRegistry;
import com.walletradar.application.normalization.pipeline.ton.TonJettonMetadataRegistry;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.common.ton.TonAddressCanonicalizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WS-7 migrate-then-delete gate: every load-bearing decimal that previously lived in the deleted
 * {@code token-metadata.json} must resolve identically via the new descriptor-override path
 * ({@code network-descriptors.yml} → {@link NetworkTokenOverrides}). A wrong decimals silently
 * corrupts an asset's entire recomputed history (qty = raw / 10^decimals feeds AVCO/value), so this
 * before/after equivalence is asserted independently of the classification/pricing tests.
 *
 * <p>These assertions exercise the pure descriptor tier (no Spring context, no cache, no live
 * resolver) — proving the checked-in overrides alone preserve the pre-deletion decimals coverage.</p>
 */
class TokenMetadataMigrationEquivalenceTest {

    private static final String SPL_USDC = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v";
    private static final String SPL_USDT = "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB";
    private static final String WSOL = "So11111111111111111111111111111111111111112";
    private static final String USDT_TON = "EQCxE6mUtQJKFnGfaROTKOt1lZbDiiX1kCixRv7Nw2Id_sDs";
    private static final String AMZNX = "0:ad0f6fbbab11e1428361c6b8b6252cc7f9d9665aa298083b65119a918b7fb9ea";
    private static final String MSTRX = "0:5bb0b607e6c0fbe2060ee033a88f442c1da34bf3534b0f5dc07619260f4529fb";
    private static final String XAUT0 = "0:3547f2ee4022c794c80ea354b81bb63b5b571dd05ac091b035d19abbadd74ac6";
    private static final String SOUSDC = "0x2514a2ce842705ead703d02fabfd8250bfcfb8bd";

    @Test
    @DisplayName("SPL USDC/USDT resolve to 6 decimals; wSOL to 9 (identical to the deleted JSON seeds)")
    void solanaLoadBearingDecimals() {
        assertThat(SolanaSplTokenMetadataRegistry.decimals(SPL_USDC)).isEqualTo(6);
        assertThat(SolanaSplTokenMetadataRegistry.symbol(SPL_USDC)).isEqualTo("USDC");
        assertThat(SolanaSplTokenMetadataRegistry.decimals(SPL_USDT)).isEqualTo(6);
        assertThat(SolanaSplTokenMetadataRegistry.symbol(SPL_USDT)).isEqualTo("USDT");
        assertThat(SolanaSplTokenMetadataRegistry.decimals(WSOL)).isEqualTo(9);
        assertThat(SolanaSplTokenMetadataRegistry.symbol(WSOL)).isEqualTo("SOL");
    }

    @Test
    @DisplayName("USDT-TON=6, AMZNx/MSTRx=8, XAUT0=6 resolve via descriptor across canonical forms")
    void tonLoadBearingDecimals() {
        assertThat(TonJettonMetadataRegistry.decimals(USDT_TON)).isEqualTo(6);
        assertThat(TonJettonMetadataRegistry.symbol(USDT_TON)).isEqualTo("USDT");
        assertThat(TonJettonMetadataRegistry.decimals(AMZNX)).isEqualTo(8);
        assertThat(TonJettonMetadataRegistry.decimals(MSTRX)).isEqualTo(8);
        assertThat(TonJettonMetadataRegistry.decimals(XAUT0)).isEqualTo(6);

        // Cross-form: the raw workchain:hex form TON Center emits resolves the same seeded entry as
        // the friendly EQ… form (USDT-TON).
        String usdtRaw = TonAddressCanonicalizer.lookupKeys(USDT_TON).stream()
                .filter(key -> key.contains(":"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected a decodable raw form for USDT-TON"));
        assertThat(TonJettonMetadataRegistry.decimals(usdtRaw)).isEqualTo(6);
    }

    @Test
    @DisplayName("soUSDC authoritative decimal-override stays 12 (Etherscan misreports 6)")
    void evmDecimalOverridePreserved() {
        assertThat(TokenSymbolFallbackSupport.resolveDecimalOverride(SOUSDC)).isEqualTo(12);
        assertThat(TokenSymbolFallbackSupport.resolveSymbolByContract(SOUSDC)).isEqualTo("soUSDC");
        // No fallback decimals for soUSDC — only the authoritative override (field separation).
        assertThat(TokenSymbolFallbackSupport.resolveDecimalsByContract(SOUSDC)).isNull();
    }

    @Test
    @DisplayName("descriptor overrides expose the same load-bearing decimals through NetworkTokenOverrides")
    void descriptorTierExposesEffectiveDecimals() {
        assertThat(NetworkTokenOverrides.find(NetworkId.SOLANA, SPL_USDC)
                .map(NetworkTokenOverrides.Override::effectiveDecimals)).contains(6);
        assertThat(NetworkTokenOverrides.find(NetworkId.TON, XAUT0)
                .map(NetworkTokenOverrides.Override::effectiveDecimals)).contains(6);
        assertThat(NetworkTokenOverrides.findEvm(SOUSDC)
                .map(NetworkTokenOverrides.Override::effectiveDecimals)).contains(12);
    }
}
