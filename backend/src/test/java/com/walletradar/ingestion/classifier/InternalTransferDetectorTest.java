package com.walletradar.ingestion.classifier;

import com.walletradar.domain.EconomicEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class InternalTransferDetectorTest {

    private InternalTransferDetector detector;

    @BeforeEach
    void setUp() {
        detector = new InternalTransferDetector();
    }

    @Test
    void reclassifyInboundToInternal_counterpartyInSession_changesToInternalTransfer() {
        RawClassifiedEvent e = new RawClassifiedEvent();
        e.setEventType(EconomicEventType.EXTERNAL_INBOUND);
        e.setCounterpartyAddress("0xBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB");
        List<RawClassifiedEvent> events = List.of(e);
        Set<String> session = Set.of("0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");

        detector.reclassifyInboundToInternal(events, session);

        assertThat(e.getEventType()).isEqualTo(EconomicEventType.INTERNAL_TRANSFER);
    }

    @Test
    void reclassifyInboundToInternal_counterpartyNotInSession_unchanged() {
        RawClassifiedEvent e = new RawClassifiedEvent();
        e.setEventType(EconomicEventType.EXTERNAL_INBOUND);
        e.setCounterpartyAddress("0xcccccccccccccccccccccccccccccccccccccccc");
        List<RawClassifiedEvent> events = List.of(e);
        Set<String> session = Set.of("0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");

        detector.reclassifyInboundToInternal(events, session);

        assertThat(e.getEventType()).isEqualTo(EconomicEventType.EXTERNAL_INBOUND);
    }

    @Test
    void reclassifyInboundToInternal_nonInbound_unchanged() {
        RawClassifiedEvent e = new RawClassifiedEvent();
        e.setEventType(EconomicEventType.SWAP_BUY);
        List<RawClassifiedEvent> events = List.of(e);
        Set<String> session = Set.of("0xaaa");

        detector.reclassifyInboundToInternal(events, session);

        assertThat(e.getEventType()).isEqualTo(EconomicEventType.SWAP_BUY);
    }
}
