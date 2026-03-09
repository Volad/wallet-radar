package com.walletradar.ingestion.classifier;

import com.walletradar.domain.transaction.normalized.EconomicEventType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.Order;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

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

    @Test
    void classify_swapCallWithRefundLegs_swapWinsOverTransfer() {
        TxClassifierDispatcher dispatcher = new TxClassifierDispatcher(List.of(
                new GenericTransferClassifier(),
                new SwapSemanticClassifier()
        ));

        List<RawClassifiedEvent> events = dispatcher.classify(RawTransactionNormalizationView.wrap(null), "0xwallet");

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().getEventType()).isEqualTo(EconomicEventType.SWAP_SELL);
    }

    @Test
    void classify_withdrawEthSelector_prefersLendOverTransfer() {
        TxClassifierDispatcher dispatcher = new TxClassifierDispatcher(List.of(
                new GenericTransferClassifier(),
                new LendWithdrawalClassifier()
        ));
        RawTransaction tx = new RawTransaction();
        tx.setRawData(new Document("methodId", "0x80500d20"));

        List<RawClassifiedEvent> events = dispatcher.classify(RawTransactionNormalizationView.wrap(tx), "0xwallet");

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().getEventType()).isEqualTo(EconomicEventType.LEND_WITHDRAWAL);
    }

    @Test
    void conflict_unichainV4PositionManagerMint_lpWinsOverSwap() {
        TxClassifierDispatcher dispatcher = new TxClassifierDispatcher(List.of(
                new GenericSwapClassifier(),
                new LpSemanticClassifier()
        ));

        List<RawClassifiedEvent> events = dispatcher.classify(RawTransactionNormalizationView.wrap(null), "0xwallet");

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().getEventType()).isEqualTo(EconomicEventType.LP_ENTRY);
    }

    @Test
    void conflict_knownLpManager_enrichedReceipt_preventsTransferFallback() {
        TxClassifierDispatcher dispatcher = new TxClassifierDispatcher(List.of(
                new GenericTransferClassifier(),
                new LpSemanticClassifier()
        ));

        List<RawClassifiedEvent> events = dispatcher.classify(RawTransactionNormalizationView.wrap(null), "0xwallet");

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().getEventType()).isEqualTo(EconomicEventType.LP_ENTRY);
    }

    @Test
    void classify_sameInputManyTimes_isDeterministic() {
        TxClassifierDispatcher dispatcher = new TxClassifierDispatcher(List.of(
                new GenericTransferClassifier(),
                new SwapSemanticClassifier(),
                new LendWithdrawalClassifier()
        ));
        RawTransaction tx = new RawTransaction();
        tx.setRawData(new Document("methodId", "0xa9059cbb"));
        RawTransactionNormalizationView view = RawTransactionNormalizationView.wrap(tx);

        String baseline = fingerprint(dispatcher.classify(view, "0xwallet"));
        for (int i = 0; i < 30; i++) {
            assertThat(fingerprint(dispatcher.classify(view, "0xwallet"))).isEqualTo(baseline);
        }
    }

    private static String fingerprint(List<RawClassifiedEvent> events) {
        return events.stream()
                .map(e -> e.getEventType() + "|" + e.getAssetContract() + "|" + e.getQuantityDelta() + "|" + e.getLogIndex())
                .collect(Collectors.joining("||"));
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

    @Order(20)
    private static class SwapSemanticClassifier implements TxClassifier {
        @Override
        public List<RawClassifiedEvent> classify(RawTransactionNormalizationView txView, String walletAddress) {
            RawClassifiedEvent e = new RawClassifiedEvent();
            e.setEventType(EconomicEventType.SWAP_SELL);
            e.setWalletAddress(walletAddress);
            e.setAssetContract("0xtoken");
            e.setQuantityDelta(new BigDecimal("-1"));
            e.setLogIndex(12);
            return List.of(e);
        }
    }

    @Order(20)
    private static class GenericSwapClassifier implements TxClassifier {
        @Override
        public List<RawClassifiedEvent> classify(RawTransactionNormalizationView txView, String walletAddress) {
            RawClassifiedEvent e = new RawClassifiedEvent();
            e.setEventType(EconomicEventType.SWAP_SELL);
            e.setWalletAddress(walletAddress);
            e.setAssetContract("0xtoken");
            e.setQuantityDelta(new BigDecimal("-1"));
            e.setLogIndex(12);
            return List.of(e);
        }
    }

    @Order(40)
    private static class LendWithdrawalClassifier implements TxClassifier {
        @Override
        public List<RawClassifiedEvent> classify(RawTransactionNormalizationView txView, String walletAddress) {
            RawClassifiedEvent e = new RawClassifiedEvent();
            e.setEventType(EconomicEventType.LEND_WITHDRAWAL);
            e.setWalletAddress(walletAddress);
            e.setAssetContract("0xtoken");
            e.setQuantityDelta(new BigDecimal("-1"));
            e.setLogIndex(12);
            return List.of(e);
        }
    }
}
