package com.walletradar.application.normalization.pipeline.solana;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-063 (FIX C): USDC/USDT/wSOL SPL mints are config-seeded so Solana normalization can resolve
 * symbol + decimals when the Helius parsed payload omits them, letting USDC/USDT reach the
 * symbol-driven {@code STABLE_USD} asset family.
 */
class SolanaSplTokenMetadataRegistryTest {

    private static final String USDC_MINT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v";
    private static final String USDT_MINT = "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB";
    private static final String WSOL_MINT = "So11111111111111111111111111111111111111112";

    @Test
    void usdcResolvesSymbolAndSixDecimals() {
        assertThat(SolanaSplTokenMetadataRegistry.symbol(USDC_MINT)).isEqualTo("USDC");
        assertThat(SolanaSplTokenMetadataRegistry.decimals(USDC_MINT)).isEqualTo(6);
    }

    @Test
    void usdtResolvesSymbolAndSixDecimals() {
        assertThat(SolanaSplTokenMetadataRegistry.symbol(USDT_MINT)).isEqualTo("USDT");
        assertThat(SolanaSplTokenMetadataRegistry.decimals(USDT_MINT)).isEqualTo(6);
    }

    @Test
    void wrappedSolResolvesNineDecimals() {
        assertThat(SolanaSplTokenMetadataRegistry.symbol(WSOL_MINT)).isEqualTo("SOL");
        assertThat(SolanaSplTokenMetadataRegistry.decimals(WSOL_MINT)).isEqualTo(9);
    }

    @Test
    void unknownMintReturnsNull() {
        assertThat(SolanaSplTokenMetadataRegistry.symbol("7oBYdEhV4GkXC19ZfgAvXpJWp2Rn9pm1Bx2cVNxFpump")).isNull();
        assertThat(SolanaSplTokenMetadataRegistry.decimals(null)).isNull();
    }

    @Test
    void mintKeysAreCaseSensitiveAndNotLowercased() {
        assertThat(SolanaSplTokenMetadataRegistry.symbol(USDC_MINT.toLowerCase())).isNull();
    }
}
