package com.walletradar.ingestion.classifier;

import com.walletradar.domain.EconomicEventType;
import com.walletradar.domain.RawTransaction;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SwapClassifierTest {

    private SwapClassifier classifier;
    private ProtocolRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DefaultProtocolRegistry();
        classifier = new SwapClassifier(registry);
    }

    @Test
    void classify_noSwapLog_returnsEmpty() {
        String walletTopic = "0x" + "0".repeat(24) + "1234567890123456789012345678901234567890";
        RawTransaction tx = new RawTransaction();
        tx.setRawData(new Document("logs", List.of(
                new Document("address", "0xToken")
                        .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, "0xfrom", walletTopic))
                        .append("data", "0xde0b6b3a7640000"))));

        List<RawClassifiedEvent> result = classifier.classify(tx, "0x1234567890123456789012345678901234567890");

        assertThat(result).isEmpty();
    }

    @Test
    void classify_hasSwapLogAndTransfer_emitsSwapBuyAndSell() {
        String walletTopic = "0x" + "0".repeat(24) + "1234567890123456789012345678901234567890";
        String swapTopic = "0xd78ad95fa46c994b6551d0da85fc275fe613ce37657fb8d5e3d130840159d822";
        RawTransaction tx = new RawTransaction();
        tx.setRawData(new Document("logs", List.of(
                new Document("topics", List.of(swapTopic)),
                new Document("address", "0xTokenA")
                        .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, "0xfrom", walletTopic))
                        .append("data", "0xde0b6b3a7640000"),
                new Document("address", "0xTokenB")
                        .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, walletTopic, "0xto"))
                        .append("data", "0x1bc16d674ec80000")))); // 2e18

        List<RawClassifiedEvent> result = classifier.classify(tx, "0x1234567890123456789012345678901234567890");

        assertThat(result).hasSize(2);
        assertThat(result.stream().map(RawClassifiedEvent::getEventType))
                .containsExactlyInAnyOrder(EconomicEventType.SWAP_BUY, EconomicEventType.SWAP_SELL);
    }
}
