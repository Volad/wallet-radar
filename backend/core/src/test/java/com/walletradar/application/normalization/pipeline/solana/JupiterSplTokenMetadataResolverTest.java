package com.walletradar.application.normalization.pipeline.solana;

import com.walletradar.platform.networks.solana.jupiter.JupiterClient;
import com.walletradar.platform.networks.solana.jupiter.JupiterProperties;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the fixed source order (registry-first → Jupiter → empty), negative-result caching, and
 * never-throw fallback of {@link JupiterSplTokenMetadataResolver}.
 */
class JupiterSplTokenMetadataResolverTest {

    // Descriptor-override seed in network-descriptors.yml (SOLANA token-overrides).
    private static final String USDC_MINT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v";
    private static final String MSOL_MINT = "mSoLzYCxHdYgdzU16g5QSh3i5K3z3KZK7ytfqcJm7So";
    private static final String UNKNOWN_MINT = "MemeMint1111111111111111111111111111111111";

    private final JupiterClient jupiterClient = mock(JupiterClient.class);
    private final JupiterSplTokenMetadataResolver resolver =
            new JupiterSplTokenMetadataResolver(jupiterClient, new JupiterProperties());

    @Test
    void seededMintResolvesFromRegistryWithoutCallingJupiter() {
        assertThat(resolver.resolveSymbol(USDC_MINT)).contains("USDC");
        assertThat(resolver.resolveDecimals(USDC_MINT)).contains(6);
        verify(jupiterClient, never()).fetchTokenMetadata(eq(USDC_MINT));
    }

    @Test
    void unseededMintResolvesFromJupiterUpperCasedAndCaches() {
        when(jupiterClient.fetchTokenMetadata(MSOL_MINT))
                .thenReturn(Optional.of(new JupiterClient.JupiterTokenMetadata("mSOL", 9, "Marinade staked SOL")));

        assertThat(resolver.resolveSymbol(MSOL_MINT)).contains("MSOL");
        assertThat(resolver.resolveDecimals(MSOL_MINT)).contains(9);
        // Second lookup is served from cache — Jupiter is queried exactly once.
        assertThat(resolver.resolveSymbol(MSOL_MINT)).contains("MSOL");
        verify(jupiterClient, times(1)).fetchTokenMetadata(MSOL_MINT);
    }

    @Test
    void unresolvableMintFallsBackToEmptyAndCachesNegativeResult() {
        when(jupiterClient.fetchTokenMetadata(UNKNOWN_MINT)).thenReturn(Optional.empty());

        assertThat(resolver.resolveSymbol(UNKNOWN_MINT)).isEmpty();
        assertThat(resolver.resolveSymbol(UNKNOWN_MINT)).isEmpty();
        verify(jupiterClient, times(1)).fetchTokenMetadata(UNKNOWN_MINT);
    }

    @Test
    void jupiterExceptionResolvesToEmpty() {
        when(jupiterClient.fetchTokenMetadata(UNKNOWN_MINT)).thenThrow(new RuntimeException("boom"));
        assertThat(resolver.resolveSymbol(UNKNOWN_MINT)).isEmpty();
    }

    @Test
    void blankMintResolvesToEmpty() {
        assertThat(resolver.resolveSymbol("  ")).isEmpty();
        assertThat(resolver.resolveSymbol(null)).isEmpty();
    }
}
