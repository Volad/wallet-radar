package com.walletradar.platform.networks.solana.metaplex;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Known-answer tests for {@link MetaplexMetadataPda}. Expected PDAs were derived independently with
 * libsodium ({@code crypto_core_ed25519_is_valid_point}) via PyNaCl, so they validate both the
 * bump-search and the Ed25519 off-curve check against a reference implementation.
 */
class MetaplexMetadataPdaTest {

    private static final String USDC_MINT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v";
    private static final String WSOL_MINT = "So11111111111111111111111111111111111111112";
    private static final String LONG_TAIL_MINT = "vPtS4ywrbEuufwPkBXsCYkeTBfpzCd6hF52p8kJGt9b";

    @Test
    @DisplayName("derives the canonical Metaplex metadata PDA (matches libsodium reference)")
    void derivesKnownPdas() {
        assertThat(MetaplexMetadataPda.metadataAddress(USDC_MINT))
                .contains("5x38Kp4hvdomTCnCrAny4UtMUt5rQBdB6px2K1Ui45Wq");
        assertThat(MetaplexMetadataPda.metadataAddress(WSOL_MINT))
                .contains("6dM4TqWyWJsbx7obrdLcviBkTafD5E8av61zfU6jq57X");
        assertThat(MetaplexMetadataPda.metadataAddress(LONG_TAIL_MINT))
                .contains("2FJZExGCCNjNnYVJjqtDLJN5SbRo9ghuR68jY6uV4P4x");
    }

    @Test
    @DisplayName("invalid / blank mints resolve to empty, never throw")
    void invalidMintsResolveEmpty() {
        assertThat(MetaplexMetadataPda.metadataAddress(null)).isEmpty();
        assertThat(MetaplexMetadataPda.metadataAddress("   ")).isEmpty();
        assertThat(MetaplexMetadataPda.metadataAddress("not!base58!")).isEmpty();
        assertThat(MetaplexMetadataPda.metadataAddress("abc")).isEmpty();
    }
}
