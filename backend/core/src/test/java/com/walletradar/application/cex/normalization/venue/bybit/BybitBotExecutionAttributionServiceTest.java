package com.walletradar.application.cex.normalization.venue.bybit;

import com.walletradar.domain.transaction.bybit.BybitExtractedEvent;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ADR-058 A7/A8 / C.2 — deterministic, observability-only {@code EXECUTION_SPOT} attribution to the
 * per-uid {@code :BOT} compartment. Evidence anchors: bot-only member {@code 516601508} and the
 * mixed-account risk case ({@code 33625378}) whose out-of-window manual trades must stay {@code :UTA}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BybitBotExecutionAttributionServiceTest {

    private static final String UID = "516601508";
    private static final Instant FIRST = Instant.parse("2025-10-10T00:00:00Z");
    private static final Instant LAST = Instant.parse("2025-12-12T00:00:00Z");

    @Mock
    private MongoOperations mongoOperations;

    private BybitBotExecutionAttributionService service() {
        return new BybitBotExecutionAttributionService(mongoOperations);
    }

    @Test
    void inWindowUtaFill_isRetaggedToBot_outsideAndNonUta_untouched() {
        stubWindow();
        BybitExtractedEvent inWindow = fill("f1", "BYBIT:" + UID + ":UTA", "2025-11-03T15:29:32Z");
        BybitExtractedEvent outWindow = fill("f2", "BYBIT:" + UID + ":UTA", "2026-01-05T10:00:00Z");
        BybitExtractedEvent alreadyBot = fill("f3", "BYBIT:" + UID + ":BOT", "2025-11-04T18:04:09Z");
        BybitExtractedEvent fundFill = fill("f4", "BYBIT:" + UID + ":FUND", "2025-11-05T18:04:09Z");
        stubFills(List.of(inWindow, outWindow, alreadyBot, fundFill));

        int tagged = service().tagBotWindowExecutions();

        assertThat(tagged).isEqualTo(1);
        assertThat(inWindow.getWalletRef()).isEqualTo("BYBIT:" + UID + ":BOT");
        assertThat(outWindow.getWalletRef()).isEqualTo("BYBIT:" + UID + ":UTA");
        assertThat(alreadyBot.getWalletRef()).isEqualTo("BYBIT:" + UID + ":BOT");
        assertThat(fundFill.getWalletRef()).isEqualTo("BYBIT:" + UID + ":FUND");
        verify(mongoOperations, times(1)).save(any());
    }

    @Test
    void idempotent_secondRun_tagsNothing() {
        stubWindow();
        BybitExtractedEvent inWindow = fill("f1", "BYBIT:" + UID + ":UTA", "2025-11-03T15:29:32Z");
        stubFills(List.of(inWindow));

        assertThat(service().tagBotWindowExecutions()).isEqualTo(1);
        // inWindow is now :BOT; the same query returns it re-tagged -> nothing left to do.
        assertThat(service().tagBotWindowExecutions()).isEqualTo(0);
        verify(mongoOperations, times(1)).save(any());
    }

    @Test
    void noBotWindows_returnsZero_noReads() {
        when(mongoOperations.aggregate(any(Aggregation.class), eq(BybitExtractedEvent.class), eq(BybitBotExecutionAttributionService.BotWindow.class)))
                .thenReturn(new AggregationResults<>(new ArrayList<>(), new Document()));

        assertThat(service().tagBotWindowExecutions()).isEqualTo(0);
        verify(mongoOperations, never()).find(any(Query.class), eq(BybitExtractedEvent.class));
        verify(mongoOperations, never()).save(any());
    }

    // ---- helpers ----

    private void stubWindow() {
        BybitBotExecutionAttributionService.BotWindow window = new BybitBotExecutionAttributionService.BotWindow();
        window.setId(UID);
        window.setFirstTs(FIRST);
        window.setLastTs(LAST);
        when(mongoOperations.aggregate(any(Aggregation.class), eq(BybitExtractedEvent.class), eq(BybitBotExecutionAttributionService.BotWindow.class)))
                .thenReturn(new AggregationResults<>(new ArrayList<>(List.of(window)), new Document()));
    }

    private void stubFills(List<BybitExtractedEvent> fills) {
        when(mongoOperations.find(any(Query.class), eq(BybitExtractedEvent.class)))
                .thenReturn(new ArrayList<>(fills));
    }

    private BybitExtractedEvent fill(String id, String walletRef, String ts) {
        BybitExtractedEvent event = new BybitExtractedEvent();
        event.setId(id);
        event.setUid(UID);
        event.setSourceStream("EXECUTION_SPOT");
        event.setWalletRef(walletRef);
        event.setTimeUtc(Instant.parse(ts));
        return event;
    }
}
