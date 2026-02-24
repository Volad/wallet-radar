package com.walletradar.ingestion.classifier;

import com.walletradar.domain.EconomicEventType;
import com.walletradar.domain.RawTransaction;
import com.walletradar.ingestion.config.ProtocolRegistryProperties;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TransferClassifierTest {

    private TransferClassifier classifier;
    private ProtocolRegistry registry;

    @BeforeEach
    void setUp() {
        ProtocolRegistryProperties props = new ProtocolRegistryProperties();
        props.setNames(Map.of());
        registry = new DefaultProtocolRegistry(props);
        classifier = new TransferClassifier(registry);
    }

    @Test
    void classify_emptyRawData_returnsEmpty() {
        RawTransaction tx = new RawTransaction();
        tx.setTxHash("0xabc");
        tx.setNetworkId("ETHEREUM");
        tx.setRawData(new Document());

        List<RawClassifiedEvent> result = classifier.classify(tx, "0x1234567890123456789012345678901234567890");

        assertThat(result).isEmpty();
    }

    @Test
    void classify_singleTransferOut_emitsExteriorTransferOut() {
        String wallet = "0x1234567890123456789012345678901234567890";
        String walletTopic = "0x" + "0".repeat(24) + "1234567890123456789012345678901234567890";
        String toTopic = "0x" + "0".repeat(24) + "5678567856785678567856785678567856785678";
        RawTransaction tx = new RawTransaction();
        tx.setRawData(new Document("logs", List.of(
                new Document("address", "0xToken")
                        .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, walletTopic, toTopic))
                        .append("data", "0xde0b6b3a7640000")))); // 1e18

        List<RawClassifiedEvent> result = classifier.classify(tx, wallet);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getEventType()).isEqualTo(EconomicEventType.EXTERNAL_TRANSFER_OUT);
        assertThat(result.get(0).getQuantityDelta()).isEqualByComparingTo("-1");
        assertThat(result.get(0).getWalletAddress()).isEqualTo(wallet);
    }

    @Test
    void classify_singleTransferIn_emitsExternalInbound() {
        String wallet = "0x1234567890123456789012345678901234567890";
        String walletTopic = "0x" + "0".repeat(24) + "1234567890123456789012345678901234567890";
        String fromTopic = "0x" + "0".repeat(24) + "abcdabcdabcdabcdabcdabcdabcdabcdabcdabcd";
        RawTransaction tx = new RawTransaction();
        tx.setRawData(new Document("logs", List.of(
                new Document("address", "0xC02aaA39b223FE8D0A0e5C4F27eAD9083C756Cc2")
                        .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, fromTopic, walletTopic))
                        .append("data", "0xde0b6b3a7640000"))));

        List<RawClassifiedEvent> result = classifier.classify(tx, wallet);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getEventType()).isEqualTo(EconomicEventType.EXTERNAL_INBOUND);
        assertThat(result.get(0).getQuantityDelta()).isEqualByComparingTo("1");
    }

    @Test
    void classify_swapLogPresent_returnsEmpty() {
        String walletTopic = "0x" + "0".repeat(24) + "1234567890123456789012345678901234567890";
        String swapTopic = "0xd78ad95fa46c994b6551d0da85fc275fe613ce37657fb8d5e3d130840159d822";
        RawTransaction tx = new RawTransaction();
        tx.setRawData(new Document("logs", List.of(
                new Document("topics", List.of(swapTopic)),
                new Document("address", "0xToken")
                        .append("topics", List.of(TransferClassifier.TRANSFER_TOPIC, "0xfrom", walletTopic))
                        .append("data", "0xde0b6b3a7640000"))));

        List<RawClassifiedEvent> result = classifier.classify(tx, "0x1234567890123456789012345678901234567890");

        assertThat(result).isEmpty();
    }
}
