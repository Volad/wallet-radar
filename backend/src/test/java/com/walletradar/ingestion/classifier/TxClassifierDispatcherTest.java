package com.walletradar.ingestion.classifier;

import com.walletradar.domain.transaction.normalized.EconomicEventType;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.Order;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TxClassifierDispatcherTest {

    @Test
    void classify_overlapConflict_keepsHigherPrioritySemantic() {
        TxClassifierDispatcher dispatcher = new TxClassifierDispatcher(List.of(
                new GenericTransferClassifier(),
                new LpSemanticClassifier()
        ));

        List<RawClassifiedEvent> events = dispatcher.classify(RawTransactionNormalizationView.wrap(null), "0xwallet");

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().getEventType()).isEqualTo(EconomicEventType.LP_ENTRY);
    }

    @Test
    void classify_identicalEvents_deduplicates() {
        TxClassifierDispatcher dispatcher = new TxClassifierDispatcher(List.of(
                new DuplicateInboundClassifier(),
                new DuplicateInboundClassifier()
        ));

        List<RawClassifiedEvent> events = dispatcher.classify(RawTransactionNormalizationView.wrap(null), "0xwallet");

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().getEventType()).isEqualTo(EconomicEventType.EXTERNAL_INBOUND);
    }

    @Order(100)
    private static class GenericTransferClassifier implements TxClassifier {
        @Override
        public List<RawClassifiedEvent> classify(RawTransactionNormalizationView txView, String walletAddress) {
            RawClassifiedEvent e = new RawClassifiedEvent();
            e.setEventType(EconomicEventType.EXTERNAL_TRANSFER_OUT);
            e.setWalletAddress(walletAddress);
            e.setAssetContract("0xtoken");
            e.setQuantityDelta(new BigDecimal("-1"));
            e.setLogIndex(12);
            return List.of(e);
        }
    }

    @Order(50)
    private static class LpSemanticClassifier implements TxClassifier {
        @Override
        public List<RawClassifiedEvent> classify(RawTransactionNormalizationView txView, String walletAddress) {
            RawClassifiedEvent e = new RawClassifiedEvent();
            e.setEventType(EconomicEventType.LP_ENTRY);
            e.setWalletAddress(walletAddress);
            e.setAssetContract("0xtoken");
            e.setQuantityDelta(new BigDecimal("-1"));
            e.setLogIndex(12);
            return List.of(e);
        }
    }

    @Order(10)
    private static class DuplicateInboundClassifier implements TxClassifier {
        @Override
        public List<RawClassifiedEvent> classify(RawTransactionNormalizationView txView, String walletAddress) {
            RawClassifiedEvent e = new RawClassifiedEvent();
            e.setEventType(EconomicEventType.EXTERNAL_INBOUND);
            e.setWalletAddress(walletAddress);
            e.setAssetContract("0xusdc");
            e.setQuantityDelta(new BigDecimal("100"));
            e.setLogIndex(5);
            return List.of(e);
        }
    }
}

