package com.walletradar.costbasis.application.replay.support;

import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.pricing.domain.PriceQuote;
import com.walletradar.pricing.domain.PriceRequest;
import com.walletradar.pricing.persistence.HistoricalPriceCacheService;
import com.walletradar.pricing.resolver.external.PriceExternalSourceOrchestrator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReplayMarketAuthorityTest {

    @Mock
    private HistoricalPriceCacheService historicalPriceCacheService;

    @Mock
    private PriceExternalSourceOrchestrator priceExternalSourceOrchestrator;

    @Test
    @DisplayName("Unpriced ETH inbound uses historical cache at block timestamp")
    void resolveEthFromHistoricalCache() {
        Instant occurredAt = Instant.parse("2025-01-15T12:00:00Z");
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId("tx-1");
        tx.setSource(NormalizedTransactionSource.BYBIT);
        tx.setBlockTimestamp(occurredAt);
        tx.setType(NormalizedTransactionType.LENDING_WITHDRAW);

        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol("ETH");
        flow.setQuantityDelta(new BigDecimal("0.151"));
        tx.setFlows(List.of(flow));

        when(priceExternalSourceOrchestrator.prioritizedSources(any(PriceRequest.class)))
                .thenReturn(List.of(PriceSource.COINGECKO));
        when(historicalPriceCacheService.findQuote(any(PriceRequest.class), eq(PriceSource.COINGECKO)))
                .thenReturn(Optional.of(new PriceQuote(
                        new BigDecimal("3200.50"),
                        PriceSource.COINGECKO,
                        occurredAt,
                        "ETH",
                        "test"
                )));

        ReplayMarketAuthority authority = new ReplayMarketAuthority(
                historicalPriceCacheService,
                priceExternalSourceOrchestrator
        );

        Optional<ReplayMarketAuthority.ResolvedMarketPrice> resolved = authority.resolve(tx, flow);

        assertThat(resolved).isPresent();
        assertThat(resolved.get().unitPriceUsd()).isEqualByComparingTo("3200.50");
        assertThat(resolved.get().authority())
                .isEqualTo(ReplayMarketAuthority.ResolvedMarketPrice.Authority.HISTORICAL_CACHE);
    }

    @Test
    @DisplayName("Flow with confirmed unit price wins over historical cache")
    void resolvePrefersFlowPrice() {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId("tx-2");
        tx.setSource(NormalizedTransactionSource.BYBIT);
        tx.setBlockTimestamp(Instant.parse("2025-01-15T12:00:00Z"));

        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol("ETH");
        flow.setQuantityDelta(new BigDecimal("1"));
        flow.setUnitPriceUsd(new BigDecimal("3000"));
        flow.setPriceSource(PriceSource.COINGECKO);
        tx.setFlows(List.of(flow));

        ReplayMarketAuthority authority = new ReplayMarketAuthority(
                historicalPriceCacheService,
                priceExternalSourceOrchestrator
        );

        Optional<ReplayMarketAuthority.ResolvedMarketPrice> resolved = authority.resolve(tx, flow);

        assertThat(resolved).isPresent();
        assertThat(resolved.get().unitPriceUsd()).isEqualByComparingTo("3000");
        assertThat(resolved.get().authority())
                .isEqualTo(ReplayMarketAuthority.ResolvedMarketPrice.Authority.FLOW);
    }

    @Test
    @DisplayName("F-5(a): cross-chain MNT borrow resolves canonical price when network/contract cache misses")
    void resolveCanonicalCrossNetworkWhenNetworkContractCacheMisses() {
        Instant occurredAt = Instant.parse("2025-06-01T08:30:00Z");
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId("tx-mnt");
        tx.setSource(NormalizedTransactionSource.ON_CHAIN);
        tx.setBlockTimestamp(occurredAt);
        tx.setType(NormalizedTransactionType.BORROW);

        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.BUY);
        flow.setAssetSymbol("MNT");
        flow.setAssetContract("0xnative-mnt");
        flow.setQuantityDelta(new BigDecimal("3532"));
        tx.setFlows(List.of(flow));

        when(priceExternalSourceOrchestrator.prioritizedSources(any(PriceRequest.class)))
                .thenReturn(List.of(PriceSource.COINGECKO));
        // Network/contract-scoped cache miss (the borrow contract was never priced at this minute).
        when(historicalPriceCacheService.findQuote(any(PriceRequest.class), eq(PriceSource.COINGECKO)))
                .thenReturn(Optional.empty());
        // A same-minute MNT quote priced on another representation resolves the canonical price.
        when(historicalPriceCacheService.findCanonicalQuote(anyCollection(), eq(occurredAt), eq(PriceSource.COINGECKO)))
                .thenReturn(Optional.of(new PriceQuote(
                        new BigDecimal("1.58"),
                        PriceSource.COINGECKO,
                        occurredAt,
                        "MNT",
                        "test-mnt"
                )));

        ReplayMarketAuthority authority = new ReplayMarketAuthority(
                historicalPriceCacheService,
                priceExternalSourceOrchestrator
        );

        Optional<ReplayMarketAuthority.ResolvedMarketPrice> resolved = authority.resolve(tx, flow);

        assertThat(resolved).isPresent();
        assertThat(resolved.get().unitPriceUsd()).isEqualByComparingTo("1.58");
        assertThat(resolved.get().authority())
                .isEqualTo(ReplayMarketAuthority.ResolvedMarketPrice.Authority.HISTORICAL_CACHE);
    }

    @Test
    @DisplayName("F-5(a): confusable lookalike never inherits a canonical cross-network price")
    void resolveDoesNotCrossNetworkResolveConfusableSymbol() {
        Instant occurredAt = Instant.parse("2025-06-01T08:30:00Z");
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId("tx-spoof");
        tx.setSource(NormalizedTransactionSource.ON_CHAIN);
        tx.setBlockTimestamp(occurredAt);
        tx.setType(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);

        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        // Cyrillic homoglyph "ЕTH" must not borrow real ETH's cross-network price.
        flow.setAssetSymbol("\u0415TH");
        flow.setQuantityDelta(new BigDecimal("1"));
        tx.setFlows(List.of(flow));

        when(priceExternalSourceOrchestrator.prioritizedSources(any(PriceRequest.class)))
                .thenReturn(List.of(PriceSource.COINGECKO));
        when(historicalPriceCacheService.findQuote(any(PriceRequest.class), eq(PriceSource.COINGECKO)))
                .thenReturn(Optional.empty());

        ReplayMarketAuthority authority = new ReplayMarketAuthority(
                historicalPriceCacheService,
                priceExternalSourceOrchestrator
        );

        Optional<ReplayMarketAuthority.ResolvedMarketPrice> resolved = authority.resolve(tx, flow);

        assertThat(resolved).isEmpty();
        verify(historicalPriceCacheService, never())
                .findCanonicalQuote(anyCollection(), any(Instant.class), any(PriceSource.class));
    }
}
