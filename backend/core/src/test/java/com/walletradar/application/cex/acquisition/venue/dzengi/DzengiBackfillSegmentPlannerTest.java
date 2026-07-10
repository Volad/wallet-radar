package com.walletradar.application.cex.acquisition.venue.dzengi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.walletradar.application.cex.config.DzengiIntegrationProperties;
import com.walletradar.application.cex.config.IntegrationBackfillProperties;
import com.walletradar.application.cex.acquisition.venue.dzengi.DzengiLiveBalanceService;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.sync.BackfillSegment;
import com.walletradar.domain.transaction.integration.IntegrationRawEvent;
import com.walletradar.domain.transaction.integration.IntegrationRawEventRepository;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DzengiBackfillSegmentPlannerTest {

    @Test
    void planInitialBackfillProbesAllNonLeverageSpotSymbolsRegardlessOfQuote() {
        DzengiIntegrationProperties dzengiProperties = new DzengiIntegrationProperties();
        IntegrationBackfillProperties backfillProperties = new IntegrationBackfillProperties();
        backfillProperties.setHistoryYears(2);

        DzengiApiClient apiClient = mock(DzengiApiClient.class);
        DzengiSymbolMetadataCache metadataCache = mock(DzengiSymbolMetadataCache.class);
        IntegrationRawEventRepository rawEventRepository = mock(IntegrationRawEventRepository.class);

        ObjectNode exchangeInfo = JsonNodeFactory.instance.objectNode();
        ArrayNode symbols = JsonNodeFactory.instance.arrayNode();
        symbols.add(symbol("BTC/USDT", "BTC", "USDT", "SPOT", "CRYPTOCURRENCY"));
        symbols.add(symbol("ETH/EUR", "ETH", "EUR", "SPOT", "CRYPTOCURRENCY"));
        symbols.add(symbol("USD/BYN", "USD", "BYN", "SPOT", "CURRENCY"));
        symbols.add(symbol("BTC/USD_LEVERAGE", "BTC", "USD", "LEVERAGE", "CRYPTOCURRENCY"));
        symbols.add(symbol("TSLA.", "TSLA.", "USD", "LEVERAGE", "EQUITY"));
        symbols.add(symbol("AAL.", "AAL.", "USD", "LEVERAGE", "EQUITY"));
        exchangeInfo.set("symbols", symbols);
        when(apiClient.fetchExchangeInfo()).thenReturn(exchangeInfo);
        when(rawEventRepository.findByIntegrationIdAndStream(eq("DZENGI-1"), any()))
                .thenReturn(List.of(positionHistoryRawEvent("ETH/USD")));

        DzengiBackfillSegmentPlanner planner = new DzengiBackfillSegmentPlanner(
                dzengiProperties,
                backfillProperties,
                apiClient,
                metadataCache,
                rawEventRepository,
                mock(DzengiLiveBalanceService.class)
        );

        UserSession.SessionIntegration integration = new UserSession.SessionIntegration();
        integration.setIntegrationId("DZENGI-1");
        integration.setProvider(UserSession.IntegrationProvider.DZENGI);
        integration.setAccountRef("DZENGI:user-1");

        Instant plannedAt = Instant.parse("2026-07-08T12:00:00Z");
        List<BackfillSegment> segments = planner.planInitialBackfill("session-1", integration, plannedAt);

        assertThat(segments.stream().map(BackfillSegment::getStream))
                .contains("MY_TRADES:BTC/USDT", "MY_TRADES:ETH/EUR", "MY_TRADES:USD/BYN", "MY_TRADES:ETH/USD")
                .contains("MY_TRADES_V2:TSLA", "MY_TRADES_V2:AAL")
                .doesNotContain("MY_TRADES:BTC/USD_LEVERAGE", "MY_TRADES:TSLA.");
        segments.stream()
                .filter(segment -> segment.getStream().startsWith("MY_TRADES"))
                .forEach(segment -> assertThat(segment.getFromTime()).isBefore(segment.getToTime()));
    }

    @Test
    void planInitialBackfillAddsV2EquitySymbolsFromPositionHistory() {
        DzengiIntegrationProperties dzengiProperties = new DzengiIntegrationProperties();
        IntegrationBackfillProperties backfillProperties = new IntegrationBackfillProperties();
        backfillProperties.setHistoryYears(2);

        DzengiApiClient apiClient = mock(DzengiApiClient.class);
        DzengiSymbolMetadataCache metadataCache = mock(DzengiSymbolMetadataCache.class);
        IntegrationRawEventRepository rawEventRepository = mock(IntegrationRawEventRepository.class);

        ObjectNode exchangeInfo = JsonNodeFactory.instance.objectNode();
        exchangeInfo.set("symbols", JsonNodeFactory.instance.arrayNode());
        when(apiClient.fetchExchangeInfo()).thenReturn(exchangeInfo);
        when(rawEventRepository.findByIntegrationIdAndStream(eq("DZENGI-1"), any()))
                .thenReturn(List.of(positionHistoryRawEvent("NVDA.")));

        DzengiBackfillSegmentPlanner planner = new DzengiBackfillSegmentPlanner(
                dzengiProperties,
                backfillProperties,
                apiClient,
                metadataCache,
                rawEventRepository,
                mock(DzengiLiveBalanceService.class)
        );

        UserSession.SessionIntegration integration = new UserSession.SessionIntegration();
        integration.setIntegrationId("DZENGI-1");
        integration.setProvider(UserSession.IntegrationProvider.DZENGI);
        integration.setAccountRef("DZENGI:user-1");

        List<BackfillSegment> segments = planner.planInitialBackfill(
                "session-1",
                integration,
                Instant.parse("2026-07-08T12:00:00Z")
        );

        assertThat(segments.stream().map(BackfillSegment::getStream))
                .contains("MY_TRADES_V2:NVDA", "MY_TRADES_V2:BABA", "MY_TRADES_V2:SNAP");
    }

    @Test
    void planInitialBackfillAddsConfiguredV2AdditionalSymbols() {
        DzengiIntegrationProperties dzengiProperties = new DzengiIntegrationProperties();
        dzengiProperties.setMyTradesV2AdditionalSymbols(List.of("NVDA", "BABA"));
        IntegrationBackfillProperties backfillProperties = new IntegrationBackfillProperties();
        backfillProperties.setHistoryYears(2);

        DzengiApiClient apiClient = mock(DzengiApiClient.class);
        DzengiSymbolMetadataCache metadataCache = mock(DzengiSymbolMetadataCache.class);
        IntegrationRawEventRepository rawEventRepository = mock(IntegrationRawEventRepository.class);

        ObjectNode exchangeInfo = JsonNodeFactory.instance.objectNode();
        exchangeInfo.set("symbols", JsonNodeFactory.instance.arrayNode());
        when(apiClient.fetchExchangeInfo()).thenReturn(exchangeInfo);
        when(rawEventRepository.findByIntegrationIdAndStream(eq("DZENGI-1"), any()))
                .thenReturn(List.of());

        DzengiBackfillSegmentPlanner planner = new DzengiBackfillSegmentPlanner(
                dzengiProperties,
                backfillProperties,
                apiClient,
                metadataCache,
                rawEventRepository,
                mock(DzengiLiveBalanceService.class)
        );

        UserSession.SessionIntegration integration = new UserSession.SessionIntegration();
        integration.setIntegrationId("DZENGI-1");
        integration.setProvider(UserSession.IntegrationProvider.DZENGI);
        integration.setAccountRef("DZENGI:user-1");

        List<BackfillSegment> segments = planner.planInitialBackfill(
                "session-1",
                integration,
                Instant.parse("2026-07-08T12:00:00Z")
        );

        assertThat(segments.stream().map(BackfillSegment::getStream))
                .contains("MY_TRADES_V2:NVDA", "MY_TRADES_V2:BABA");
    }

    private static ObjectNode symbol(String symbol, String base, String quote, String marketType, String assetType) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("symbol", symbol);
        node.put("baseAsset", base);
        node.put("quoteAsset", quote);
        node.put("marketType", marketType);
        node.put("assetType", assetType);
        node.put("status", "TRADING");
        return node;
    }

    private static ObjectNode symbol(String symbol, String base, String quote, String marketType) {
        return symbol(symbol, base, quote, marketType, "CRYPTOCURRENCY");
    }

    private static IntegrationRawEvent positionHistoryRawEvent(String symbol) {
        IntegrationRawEvent rawEvent = new IntegrationRawEvent();
        rawEvent.setStream("TRADING_POSITIONS_HISTORY");
        rawEvent.setPayload(new Document("symbol", symbol));
        return rawEvent;
    }
}
