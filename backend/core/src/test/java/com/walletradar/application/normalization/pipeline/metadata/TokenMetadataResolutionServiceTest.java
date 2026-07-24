package com.walletradar.application.normalization.pipeline.metadata;

import com.walletradar.domain.common.NetworkId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WS-7 resolution-order guarantees for {@link TokenMetadataResolutionService}:
 * descriptor override → persistent cache → live resolver (write-through) → explicit unresolved,
 * with the safety rules from the plan's edge cases:
 * <ul>
 *   <li>(b) a live timeout falls back to cache/override, never a wrong default;</li>
 *   <li>(c) an unknown token resolves to explicit {@code unresolved}, not a wrong default.</li>
 * </ul>
 */
class TokenMetadataResolutionServiceTest {

    private static final String SPL_USDC = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v";
    private static final String UNKNOWN_MINT = "MemeMint1111111111111111111111111111111111";
    private static final String CACHED_MINT = "CacheMint111111111111111111111111111111111";

    private final TokenMetadataCacheRepository cacheRepository = mock(TokenMetadataCacheRepository.class);
    private final LiveTokenMetadataResolver liveResolver = mock(LiveTokenMetadataResolver.class);

    private TokenMetadataResolutionService service() {
        return new TokenMetadataResolutionService(cacheRepository, List.of(liveResolver));
    }

    @Test
    @DisplayName("descriptor override wins outright — live resolver is never consulted (no corruption)")
    void descriptorOverrideWinsAndSkipsLive() {
        when(liveResolver.supports(any())).thenReturn(true);

        ResolvedTokenMetadata resolved = service().resolve(NetworkId.SOLANA, SPL_USDC);

        assertThat(resolved.decimals()).isEqualTo(6);
        assertThat(resolved.symbol()).isEqualTo("USDC");
        assertThat(resolved.source()).isEqualTo(ResolvedTokenMetadata.Source.DESCRIPTOR_OVERRIDE);
        verify(liveResolver, never()).resolve(any(), anyString());
        verify(cacheRepository, never()).save(any());
    }

    @Test
    @DisplayName("(b) persistent cache is honoured and a live timeout never corrupts a cached decimals")
    void cacheHitServedWithoutLive() {
        when(cacheRepository.findById(anyString())).thenReturn(Optional.of(cacheEntry("MSOL", 9)));
        // Live resolver would throw (timeout), but it must not be reached when cache fully satisfies.
        when(liveResolver.supports(any())).thenReturn(true);
        when(liveResolver.resolve(any(), anyString())).thenThrow(new RuntimeException("timeout"));

        ResolvedTokenMetadata resolved = service().resolve(NetworkId.SOLANA, CACHED_MINT);

        assertThat(resolved.decimals()).isEqualTo(9);
        assertThat(resolved.symbol()).isEqualTo("MSOL");
        assertThat(resolved.source()).isEqualTo(ResolvedTokenMetadata.Source.PERSISTENT_CACHE);
        verify(liveResolver, never()).resolve(any(), anyString());
    }

    @Test
    @DisplayName("live resolution is written through to the durable cache (RPC-free replay)")
    void liveResolutionIsWrittenThrough() {
        when(cacheRepository.findById(anyString())).thenReturn(Optional.empty());
        when(liveResolver.supports(NetworkId.SOLANA)).thenReturn(true);
        when(liveResolver.resolve(NetworkId.SOLANA, UNKNOWN_MINT)).thenReturn(Optional.of(
                new ResolvedTokenMetadata("MEME", 4, ResolvedTokenMetadata.Source.LIVE_RESOLVER)));

        ResolvedTokenMetadata resolved = service().resolve(NetworkId.SOLANA, UNKNOWN_MINT);

        assertThat(resolved.symbol()).isEqualTo("MEME");
        assertThat(resolved.decimals()).isEqualTo(4);
        assertThat(resolved.source()).isEqualTo(ResolvedTokenMetadata.Source.LIVE_RESOLVER);
        verify(cacheRepository, times(1)).save(any(TokenMetadataCacheEntry.class));
    }

    @Test
    @DisplayName("a decimals-only cache entry still triggers live symbol resolution and write-through")
    void decimalsOnlyCacheStillResolvesSymbolLive() {
        // Cache holds decimals (load-bearing) but no symbol — a prior LIVE_RESOLVER decimals-only hit.
        when(cacheRepository.findById(anyString())).thenReturn(Optional.of(cacheEntry(null, 6)));
        when(liveResolver.supports(NetworkId.SOLANA)).thenReturn(true);
        // The live resolver now upgrades: it returns a symbol (Metaplex fallback) with the same decimals.
        when(liveResolver.resolve(NetworkId.SOLANA, UNKNOWN_MINT)).thenReturn(Optional.of(
                new ResolvedTokenMetadata("GRAM", 6, ResolvedTokenMetadata.Source.LIVE_RESOLVER)));

        ResolvedTokenMetadata resolved = service().resolve(NetworkId.SOLANA, UNKNOWN_MINT);

        assertThat(resolved.symbol()).isEqualTo("GRAM");
        assertThat(resolved.decimals()).isEqualTo(6);
        assertThat(resolved.source()).isEqualTo(ResolvedTokenMetadata.Source.LIVE_RESOLVER);
        // The upgraded symbol is written through so subsequent replays are RPC-free.
        verify(liveResolver, times(1)).resolve(NetworkId.SOLANA, UNKNOWN_MINT);
        verify(cacheRepository, times(1)).save(any(TokenMetadataCacheEntry.class));
    }

    @Test
    @DisplayName("a decimals-only cache entry with a symbol-only live upgrade keeps the cached decimals")
    void decimalsOnlyCacheKeepsDecimalsOnSymbolOnlyLive() {
        when(cacheRepository.findById(anyString())).thenReturn(Optional.of(cacheEntry(null, 6)));
        when(liveResolver.supports(NetworkId.SOLANA)).thenReturn(true);
        when(liveResolver.resolve(NetworkId.SOLANA, UNKNOWN_MINT)).thenReturn(Optional.of(
                new ResolvedTokenMetadata("GRAM", null, ResolvedTokenMetadata.Source.LIVE_RESOLVER)));

        ResolvedTokenMetadata resolved = service().resolve(NetworkId.SOLANA, UNKNOWN_MINT);

        assertThat(resolved.symbol()).isEqualTo("GRAM");
        assertThat(resolved.decimals()).isEqualTo(6);
    }

    @Test
    @DisplayName("(c) unknown token → explicit unresolved, never a fabricated default")
    void unknownTokenResolvesToExplicitUnresolved() {
        when(cacheRepository.findById(anyString())).thenReturn(Optional.empty());
        when(liveResolver.supports(NetworkId.SOLANA)).thenReturn(true);
        when(liveResolver.resolve(NetworkId.SOLANA, UNKNOWN_MINT)).thenReturn(Optional.empty());

        ResolvedTokenMetadata resolved = service().resolve(NetworkId.SOLANA, UNKNOWN_MINT);

        assertThat(resolved.isResolved()).isFalse();
        assertThat(resolved.decimals()).isNull();
        assertThat(resolved.symbol()).isNull();
        assertThat(resolved.source()).isEqualTo(ResolvedTokenMetadata.Source.UNRESOLVED);
        verify(cacheRepository, never()).save(any());
    }

    @Test
    @DisplayName("blank / null inputs resolve to explicit unresolved")
    void blankInputsUnresolved() {
        assertThat(service().resolve(NetworkId.SOLANA, "  ").isResolved()).isFalse();
        assertThat(service().resolve(null, SPL_USDC).isResolved()).isFalse();
    }

    private static TokenMetadataCacheEntry cacheEntry(String symbol, Integer decimals) {
        return new TokenMetadataCacheEntry()
                .setSymbol(symbol)
                .setDecimals(decimals)
                .setSource(ResolvedTokenMetadata.Source.LIVE_RESOLVER.name());
    }
}
