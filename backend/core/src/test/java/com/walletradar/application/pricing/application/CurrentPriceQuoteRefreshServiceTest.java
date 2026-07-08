package com.walletradar.application.pricing.application;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.common.PriceSource;
import com.walletradar.application.pricing.domain.PriceQuote;
import com.walletradar.application.pricing.persistence.CurrentPriceQuoteDocument;
import com.walletradar.application.pricing.resolver.external.PriceExternalSourceOrchestrator;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CurrentPriceQuoteRefreshServiceTest {

    @Mock
    private MongoOperations mongoOperations;
    @Mock
    private PriceExternalSourceOrchestrator priceExternalSourceOrchestrator;
    @Mock
    private GmxProtocolSnapshotValuationService gmxProtocolSnapshotValuationService;
    @Mock
    private com.walletradar.domain.session.UserSessionRepository userSessionRepository;

    @Test
    void refreshesProtocolPositionSymbolsWithGmxSnapshotNotMarketProviders() {
        Instant requestedAt = Instant.parse("2026-04-26T00:00:00Z");
        when(mongoOperations.find(any(Query.class), eq(Document.class), eq("on_chain_balances")))
                .thenReturn(List.of(new Document()
                        .append("assetSymbol", "GM: ETH/USD [WETH-USDC]")
                        .append("assetContract", "0x70d95587d40a2caf56bd97485ab3eec10bee6336")
                        .append("networkId", NetworkId.ARBITRUM.name())
                        .append("quantity", Decimal128.parse("1"))));
        when(mongoOperations.exists(any(Query.class), eq(CurrentPriceQuoteDocument.class))).thenReturn(false);
        when(gmxProtocolSnapshotValuationService.resolveMarketTokenQuote(
                eq(NetworkId.ARBITRUM),
                eq("0x70d95587d40a2caf56bd97485ab3eec10bee6336"),
                eq("GM: ETH/USD [WETH-USDC]"),
                eq(requestedAt)
        )).thenReturn(Optional.of(new PriceQuote(
                new BigDecimal("1.82"),
                PriceSource.PROTOCOL_SNAPSHOT,
                requestedAt,
                "USD",
                "gmx-v2:ARBITRUM:0x70d95587d40a2caf56bd97485ab3eec10bee6336"
        )));
        CurrentPriceQuoteRefreshService service = new CurrentPriceQuoteRefreshService(
                mongoOperations,
                priceExternalSourceOrchestrator,
                gmxProtocolSnapshotValuationService,
                userSessionRepository
        );

        int refreshed = service.refreshForSessionBalances("session-1", requestedAt);

        assertThat(refreshed).isEqualTo(1);
        verify(mongoOperations).upsert(any(Query.class), any(Update.class), eq(CurrentPriceQuoteDocument.class));
        verify(priceExternalSourceOrchestrator, never()).resolveExternalOnly(any());
    }

    @Test
    void skipsProtocolPositionSymbolsWhenSnapshotIsUnavailable() {
        when(mongoOperations.find(any(Query.class), eq(Document.class), eq("on_chain_balances")))
                .thenReturn(List.of(new Document()
                        .append("assetSymbol", "GM: ETH/USD [WETH-USDC]")
                        .append("assetContract", "0x70d95587d40a2caf56bd97485ab3eec10bee6336")
                        .append("networkId", NetworkId.ARBITRUM.name())
                        .append("quantity", Decimal128.parse("1"))));
        when(mongoOperations.exists(any(Query.class), eq(CurrentPriceQuoteDocument.class))).thenReturn(false);
        when(gmxProtocolSnapshotValuationService.resolveMarketTokenQuote(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        CurrentPriceQuoteRefreshService service = new CurrentPriceQuoteRefreshService(
                mongoOperations,
                priceExternalSourceOrchestrator,
                gmxProtocolSnapshotValuationService,
                userSessionRepository
        );

        int refreshed = service.refreshForSessionBalances("session-1", Instant.parse("2026-04-26T00:00:00Z"));

        assertThat(refreshed).isZero();
        verify(priceExternalSourceOrchestrator, never()).resolveExternalOnly(any());
    }
}
