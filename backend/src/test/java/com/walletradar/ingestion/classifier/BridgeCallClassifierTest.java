package com.walletradar.ingestion.classifier;

import com.walletradar.domain.transaction.normalized.EconomicEventType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BridgeCallClassifierTest {

    private final BridgeCallClassifier classifier = new BridgeCallClassifier();

    @Test
    void classify_bridgeWithoutLogs_emitsExternalTransferOut() {
        String wallet = "0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f";

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("ARBITRUM");
        tx.setRawData(new Document("from", wallet)
                .append("to", "0x23981fc34e69eedfe2bd9a0a9fcb0719fe09dbfc")
                .append("value", "3000000000000000")
                .append("methodId", "0x3ce33bff")
                .append("functionName", "bridge(string adapterId,address srcToken,uint256 amount,bytes data)")
                .append("input", "0x3ce33bff")
                .append("explorer", new Document("tokenTransfers", List.of()).append("internalTransfers", List.of())));

        List<RawClassifiedEvent> events = classifier.classify(tx, wallet);

        assertThat(events).hasSize(1);
        RawClassifiedEvent event = events.getFirst();
        assertThat(event.getEventType()).isEqualTo(EconomicEventType.EXTERNAL_TRANSFER_OUT);
        assertThat(event.getAssetContract()).isEqualTo("0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee");
        assertThat(event.getAssetSymbol()).isEqualTo("ETH");
        assertThat(event.getQuantityDelta()).isEqualByComparingTo("-0.003");
        assertThat(event.getCounterpartyAddress()).isEqualTo("0x23981fc34e69eedfe2bd9a0a9fcb0719fe09dbfc");
    }

    @Test
    void classify_nonBridgeContractCall_returnsEmpty() {
        String wallet = "0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f";

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("ARBITRUM");
        tx.setRawData(new Document("from", wallet)
                .append("to", "0xaf88d065e77c8cc2239327c5edb3a432268e5831")
                .append("value", "3000000000000000")
                .append("methodId", "0x095ea7b3")
                .append("functionName", "approve(address spender, uint256 amount)")
                .append("input", "0x095ea7b3"));

        List<RawClassifiedEvent> events = classifier.classify(tx, wallet);

        assertThat(events).isEmpty();
    }

    @Test
    void classify_bridgeSelectorFromInputFallback_emitsExternalTransferOut() {
        String wallet = "0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f";

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("ARBITRUM");
        tx.setRawData(new Document("from", wallet)
                .append("to", "0x1231deb6f5749ef6ce6943a275a1d3e7486f4eae")
                .append("methodId", "0x")
                .append("value", "3000000000000000")
                .append("input", "0x3ce33bff"));

        List<RawClassifiedEvent> events = classifier.classify(tx, wallet);

        assertThat(events).hasSize(1);
        RawClassifiedEvent event = events.getFirst();
        assertThat(event.getEventType()).isEqualTo(EconomicEventType.EXTERNAL_TRANSFER_OUT);
        assertThat(event.getQuantityDelta()).isEqualByComparingTo("-0.003");
        assertThat(event.getCounterpartyAddress()).isEqualTo("0x1231deb6f5749ef6ce6943a275a1d3e7486f4eae");
    }

    @Test
    void classify_knownBridgeRouterWithoutMethodId_emitsExternalTransferOut() {
        String wallet = "0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f";

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("ARBITRUM");
        tx.setRawData(new Document("from", wallet)
                .append("to", "0x23981fc34e69eedfe2bd9a0a9fcb0719fe09dbfc")
                .append("value", "221062554127893")
                .append("input", "0x12345678"));

        List<RawClassifiedEvent> events = classifier.classify(tx, wallet);

        assertThat(events).hasSize(1);
        RawClassifiedEvent event = events.getFirst();
        assertThat(event.getEventType()).isEqualTo(EconomicEventType.EXTERNAL_TRANSFER_OUT);
        assertThat(event.getAssetContract()).isEqualTo("0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee");
        assertThat(event.getAssetSymbol()).isEqualTo("ETH");
        assertThat(event.getQuantityDelta()).isEqualByComparingTo("-0.000221062554127893");
        assertThat(event.getCounterpartyAddress()).isEqualTo("0x23981fc34e69eedfe2bd9a0a9fcb0719fe09dbfc");
    }

    @Test
    void classify_bridgeWithNonTransferLogs_emitsExternalTransferOut() {
        String wallet = "0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f";

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("ARBITRUM");
        tx.setRawData(new Document("from", wallet)
                .append("to", "0x23981fc34e69eedfe2bd9a0a9fcb0719fe09dbfc")
                .append("value", "3000000000000000")
                .append("methodId", "0x3ce33bff")
                .append("functionName", "bridge(string adapterId,address srcToken,uint256 amount,bytes data)")
                .append("logs", List.of(new Document("address", "0xsome").append("topics", List.of("0x1234")))));

        List<RawClassifiedEvent> events = classifier.classify(tx, wallet);

        assertThat(events).hasSize(1);
        RawClassifiedEvent event = events.getFirst();
        assertThat(event.getEventType()).isEqualTo(EconomicEventType.EXTERNAL_TRANSFER_OUT);
        assertThat(event.getQuantityDelta()).isEqualByComparingTo("-0.003");
    }

    @Test
    void classify_nativeBridgeDepositWithLogs_emitsExternalTransferOut() {
        String wallet = "0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f";

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("ARBITRUM");
        tx.setRawData(new Document("from", wallet)
                .append("to", "0x10417734001162ea139e8b044dfe28dbb8b28ad0")
                .append("value", "7315920000000000")
                .append("methodId", "0xf9068677")
                .append("functionName", "depositNativeWithId(uint256 commitmentId)")
                .append("logs", List.of(
                        new Document("address", "0x10417734001162ea139e8b044dfe28dbb8b28ad0")
                                .append("topics", List.of("0x1655dc426ee0145d9436d28cfb463fb0e0717ae145566e5e534da64b735e49f3"))
                )));

        List<RawClassifiedEvent> events = classifier.classify(tx, wallet);

        assertThat(events).hasSize(1);
        RawClassifiedEvent event = events.getFirst();
        assertThat(event.getEventType()).isEqualTo(EconomicEventType.EXTERNAL_TRANSFER_OUT);
        assertThat(event.getAssetContract()).isEqualTo("0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee");
        assertThat(event.getAssetSymbol()).isEqualTo("ETH");
        assertThat(event.getQuantityDelta()).isEqualByComparingTo("-0.00731592");
        assertThat(event.getCounterpartyAddress()).isEqualTo("0x10417734001162ea139e8b044dfe28dbb8b28ad0");
    }

    @Test
    void classify_bridgeWithErc20TransferLogs_returnsEmpty() {
        String wallet = "0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f";
        String walletTopic = "0x0000000000000000000000001a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String bridgeTopic = "0x00000000000000000000000010417734001162ea139e8b044dfe28dbb8b28ad0";

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("ARBITRUM");
        tx.setRawData(new Document("from", wallet)
                .append("to", "0x10417734001162ea139e8b044dfe28dbb8b28ad0")
                .append("value", "7315920000000000")
                .append("methodId", "0xf9068677")
                .append("functionName", "depositNativeWithId(uint256 commitmentId)")
                .append("logs", List.of(
                        new Document("address", "0xaf88d065e77c8cc2239327c5edb3a432268e5831")
                                .append("topics", List.of(
                                        TransferClassifier.TRANSFER_TOPIC,
                                        walletTopic,
                                        bridgeTopic
                                ))
                                .append("data", "0x1")
                )));

        List<RawClassifiedEvent> events = classifier.classify(tx, wallet);

        assertThat(events).isEmpty();
    }

    @Test
    void classify_unknownSelectorWithZeroValue_returnsEmpty() {
        String wallet = "0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f";

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("BASE");
        tx.setRawData(new Document("from", wallet)
                .append("to", "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                .append("methodId", "0x")
                .append("value", "0")
                .append("input", "0x12345678"));

        List<RawClassifiedEvent> events = classifier.classify(tx, wallet);

        assertThat(events).isEmpty();
    }
}
