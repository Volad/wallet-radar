package com.walletradar.application.cex.acquisition.venue.dzengi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.domain.transaction.dzengi.DzengiExtractedEvent;
import com.walletradar.domain.transaction.integration.IntegrationRawEvent;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DzengiExtractionServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DzengiSymbolMetadataCache symbolMetadataCache = mock(DzengiSymbolMetadataCache.class);
    private final DzengiExtractionService extractionService = new DzengiExtractionService(
            objectMapper,
            symbolMetadataCache
    );

    @Test
    void extractMyTradeV2UsesDottedMetadataAndStripsBaseAssetDot() {
        when(symbolMetadataCache.resolve("TSLA.")).thenReturn(new DzengiSymbolMetadataCache.SymbolMetadata(
                "TSLA.",
                "TSLA.",
                "USD",
                "LEVERAGE",
                "EQUITY",
                true
        ));

        IntegrationRawEvent rawEvent = rawEvent(
                "MY_TRADES_V2:TSLA",
                new Document()
                        .append("symbol", "TSLA")
                        .append("price", "247.28")
                        .append("qty", "2.5")
                        .append("commission", "0.13")
                        .append("commissionAsset", "USD")
                        .append("time", 1745359773238L)
                        .append("isBuyer", false)
        );

        List<DzengiExtractedEvent> extracted = extractionService.extract(rawEvent);

        assertThat(extracted).hasSize(1);
        DzengiExtractedEvent event = extracted.getFirst();
        assertThat(event.getCanonicalType()).isEqualTo("SELL");
        assertThat(event.getTradingSymbol()).isEqualTo("TSLA");
        assertThat(event.getAssetSymbol()).isEqualTo("TSLA");
        assertThat(event.getQuoteAsset()).isEqualTo("USD");
        assertThat(event.getBasisRelevant()).isTrue();
        assertThat(event.getQuantityRaw()).isEqualByComparingTo(new BigDecimal("-2.5"));
    }

    @Test
    void extractTradingPositionUsesExecTimestampInsteadOfEpochZero() {
        IntegrationRawEvent rawEvent = rawEvent(
                "TRADING_POSITIONS_HISTORY",
                new Document()
                        .append("symbol", "ETH/USD_LEVERAGE")
                        .append("positionId", "pos-1")
                        .append("accountCurrency", "USD")
                        .append("rplConverted", "-7.438732236")
                        .append("fee", "-0.322792236")
                        .append("execTimestamp", 1750541099409L)
        );

        List<DzengiExtractedEvent> extracted = extractionService.extract(rawEvent);

        assertThat(extracted).hasSize(1);
        DzengiExtractedEvent event = extracted.getFirst();
        assertThat(event.getCanonicalType()).isEqualTo("CEX_DERIVATIVE_SETTLEMENT");
        assertThat(event.getTimeUtc()).isEqualTo(Instant.ofEpochMilli(1750541099409L));
    }

    @Test
    void extractTradingPositionFallsBackToCreatedTimestamp() {
        IntegrationRawEvent rawEvent = rawEvent(
                "TRADING_POSITIONS_HISTORY",
                new Document()
                        .append("symbol", "TSLA.")
                        .append("positionId", "pos-2")
                        .append("accountCurrency", "USD")
                        .append("rplConverted", "0.825")
                        .append("createdTimestamp", 1745360129794L)
        );

        List<DzengiExtractedEvent> extracted = extractionService.extract(rawEvent);

        assertThat(extracted).hasSize(1);
        assertThat(extracted.getFirst().getTimeUtc()).isEqualTo(Instant.ofEpochMilli(1745360129794L));
    }

    @Test
    void extractMyTradeV2FallsBackToUsdQuoteWhenMetadataUnknown() {
        when(symbolMetadataCache.resolve("NVDA.")).thenReturn(DzengiSymbolMetadataCache.SymbolMetadata.unknown("NVDA."));

        IntegrationRawEvent rawEvent = rawEvent(
                "MY_TRADES_V2:NVDA",
                new Document()
                        .append("symbol", "NVDA")
                        .append("price", "139.44")
                        .append("qty", "3")
                        .append("commission", "0.20916")
                        .append("commissionAsset", "USD")
                        .append("time", 1738573200782L)
                        .append("isBuyer", true)
        );

        DzengiExtractedEvent event = extractionService.extract(rawEvent).getFirst();

        assertThat(event.getAssetSymbol()).isEqualTo("NVDA");
        assertThat(event.getQuoteAsset()).isEqualTo("USD");
    }

    private static IntegrationRawEvent rawEvent(String stream, Document payload) {
        IntegrationRawEvent rawEvent = new IntegrationRawEvent();
        rawEvent.setStream(stream);
        rawEvent.setIntegrationId("DZENGI-1023141508");
        rawEvent.setAccountRef("DZENGI:1023141508");
        rawEvent.setSessionId("session-1");
        rawEvent.setProviderEventKey("event-1");
        rawEvent.setPayload(payload);
        return rawEvent;
    }
}
