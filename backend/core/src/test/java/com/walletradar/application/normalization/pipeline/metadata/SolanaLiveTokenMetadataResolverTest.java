package com.walletradar.application.normalization.pipeline.metadata;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.platform.networks.solana.jupiter.JupiterClient;
import com.walletradar.platform.networks.solana.metaplex.MetaplexMetadataClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Two-tier Solana live resolution (WS-7, Cluster C): Jupiter is primary (symbol + decimals) and the
 * on-chain Metaplex metadata PDA is the symbol fallback when Jupiter returns decimals-only or does
 * not know the mint. Decimals from Jupiter are always preserved (financially load-bearing).
 */
class SolanaLiveTokenMetadataResolverTest {

    private static final String LONG_TAIL_MINT = "vPtS4ywrbEuufwPkBXsCYkeTBfpzCd6hF52p8kJGt9b";

    private final JupiterClient jupiterClient = mock(JupiterClient.class);
    private final MetaplexMetadataClient metaplexMetadataClient = mock(MetaplexMetadataClient.class);
    private final SolanaLiveTokenMetadataResolver resolver =
            new SolanaLiveTokenMetadataResolver(jupiterClient, metaplexMetadataClient);

    @Test
    void supportsOnlySolana() {
        assertThat(resolver.supports(NetworkId.SOLANA)).isTrue();
        assertThat(resolver.supports(NetworkId.TON)).isFalse();
        assertThat(resolver.supports(NetworkId.ETHEREUM)).isFalse();
    }

    @Test
    @DisplayName("Jupiter decimals-only → Metaplex PDA supplies the symbol; decimals preserved")
    void metaplexFallbackSuppliesSymbolWhenJupiterHasNone() {
        when(jupiterClient.fetchTokenMetadata(LONG_TAIL_MINT))
                .thenReturn(Optional.of(new JupiterClient.JupiterTokenMetadata(null, 6, null)));
        when(metaplexMetadataClient.fetchMetadata(LONG_TAIL_MINT))
                .thenReturn(Optional.of(new MetaplexMetadataClient.MetaplexTokenMetadata("Gtraphene", "gram")));

        Optional<ResolvedTokenMetadata> resolved = resolver.resolve(NetworkId.SOLANA, LONG_TAIL_MINT);

        assertThat(resolved).isPresent();
        assertThat(resolved.get().symbol()).isEqualTo("GRAM");
        assertThat(resolved.get().decimals()).isEqualTo(6);
        assertThat(resolved.get().source()).isEqualTo(ResolvedTokenMetadata.Source.LIVE_RESOLVER);
    }

    @Test
    @DisplayName("Jupiter symbol present → Metaplex is never consulted")
    void jupiterSymbolShortCircuitsMetaplex() {
        when(jupiterClient.fetchTokenMetadata(anyString()))
                .thenReturn(Optional.of(new JupiterClient.JupiterTokenMetadata("usdc", 6, "USD Coin")));

        Optional<ResolvedTokenMetadata> resolved = resolver.resolve(NetworkId.SOLANA, LONG_TAIL_MINT);

        assertThat(resolved).isPresent();
        assertThat(resolved.get().symbol()).isEqualTo("USDC");
        assertThat(resolved.get().decimals()).isEqualTo(6);
        verify(metaplexMetadataClient, never()).fetchMetadata(anyString());
    }

    @Test
    @DisplayName("mint absent from Jupiter → Metaplex-only symbol, decimals null")
    void metaplexOnlyWhenJupiterAbsent() {
        when(jupiterClient.fetchTokenMetadata(anyString())).thenReturn(Optional.empty());
        when(metaplexMetadataClient.fetchMetadata(LONG_TAIL_MINT))
                .thenReturn(Optional.of(new MetaplexMetadataClient.MetaplexTokenMetadata(null, "MEME")));

        Optional<ResolvedTokenMetadata> resolved = resolver.resolve(NetworkId.SOLANA, LONG_TAIL_MINT);

        assertThat(resolved).isPresent();
        assertThat(resolved.get().symbol()).isEqualTo("MEME");
        assertThat(resolved.get().decimals()).isNull();
    }

    @Test
    @DisplayName("neither tier resolves anything → empty (explicit unresolved upstream)")
    void nothingResolvesToEmpty() {
        when(jupiterClient.fetchTokenMetadata(anyString())).thenReturn(Optional.empty());
        when(metaplexMetadataClient.fetchMetadata(anyString())).thenReturn(Optional.empty());

        assertThat(resolver.resolve(NetworkId.SOLANA, LONG_TAIL_MINT)).isEmpty();
    }

    @Test
    @DisplayName("venue errors in either tier never throw")
    void venueErrorsNeverThrow() {
        when(jupiterClient.fetchTokenMetadata(anyString())).thenThrow(new RuntimeException("jup boom"));
        when(metaplexMetadataClient.fetchMetadata(anyString())).thenThrow(new RuntimeException("rpc boom"));

        assertThat(resolver.resolve(NetworkId.SOLANA, LONG_TAIL_MINT)).isEmpty();
    }
}
