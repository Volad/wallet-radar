package com.walletradar.ingestion.classifier;

import com.walletradar.domain.transaction.normalized.EconomicEventType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalClassifierTest {

    private final ApprovalClassifier classifier = new ApprovalClassifier();

    @Test
    void classify_permitTransferSelectorWithoutTransfers_emitsApproval() {
        String wallet = "0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f";
        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("ARBITRUM");
        tx.setRawData(new Document("from", wallet)
                .append("to", "0x000000000022d473030f116ddee9f6b43ac78ba3")
                .append("value", "0")
                .append("methodId", "0x30f28b7a")
                .append("input", "0x30f28b7a"));

        List<RawClassifiedEvent> events = classifier.classify(tx, wallet);

        assertThat(events).hasSize(1);
        RawClassifiedEvent event = events.getFirst();
        assertThat(event.getEventType()).isEqualTo(EconomicEventType.APPROVAL);
        assertThat(event.getAssetContract()).isEqualTo("0x000000000022d473030f116ddee9f6b43ac78ba3");
        assertThat(event.getQuantityDelta()).isZero();
    }

    @Test
    void classify_selfPermitIfNecessarySelectorWithoutTransfers_emitsApproval() {
        String wallet = "0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f";
        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("ARBITRUM");
        tx.setRawData(new Document("from", wallet)
                .append("to", "0x1111111111111111111111111111111111111111")
                .append("value", "0")
                .append("methodId", "0xc2e3140a")
                .append("input", "0xc2e3140a"));

        List<RawClassifiedEvent> events = classifier.classify(tx, wallet);

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().getEventType()).isEqualTo(EconomicEventType.APPROVAL);
    }

    @Test
    void classify_approvalSelectorWithTransferLog_returnsEmpty() {
        String wallet = "0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f";
        String walletTopic = "0x0000000000000000000000001a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String spenderTopic = "0x0000000000000000000000001111111111111111111111111111111111111111";
        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("ARBITRUM");
        tx.setRawData(new Document("from", wallet)
                .append("to", "0xaf88d065e77c8cc2239327c5edb3a432268e5831")
                .append("value", "0")
                .append("methodId", "0x095ea7b3")
                .append("logs", List.of(
                        new Document("address", "0xaf88d065e77c8cc2239327c5edb3a432268e5831")
                                .append("topics", List.of(
                                        TransferClassifier.TRANSFER_TOPIC,
                                        walletTopic,
                                        spenderTopic
                                ))
                                .append("data", "0x1")
                )));

        List<RawClassifiedEvent> events = classifier.classify(tx, wallet);

        assertThat(events).isEmpty();
    }

    @Test
    void classify_approvalSelectorWithExplorerTokenTransfers_returnsEmpty() {
        String wallet = "0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f";
        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("ARBITRUM");
        tx.setRawData(new Document("from", wallet)
                .append("to", "0xaf88d065e77c8cc2239327c5edb3a432268e5831")
                .append("value", "0")
                .append("methodId", "0x137c29fe")
                .append("explorer", new Document("tokenTransfers", List.of(
                        new Document("from", wallet)
                                .append("to", "0x1111111111111111111111111111111111111111")
                                .append("value", "1000")
                ))));

        List<RawClassifiedEvent> events = classifier.classify(tx, wallet);

        assertThat(events).isEmpty();
    }

    @Test
    void classify_lockdownWithoutEconomicEffects_emitsApproval() {
        String wallet = "0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f";
        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("AVALANCHE");
        tx.setRawData(new Document("from", wallet)
                .append("to", "0x000000000022d473030f116ddee9f6b43ac78ba3")
                .append("value", "0")
                .append("methodId", "0xcc53287f")
                .append("functionName", "lockdown((address token, address spender)[] approvals)"));

        List<RawClassifiedEvent> events = classifier.classify(tx, wallet);

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().getEventType()).isEqualTo(EconomicEventType.APPROVAL);
    }

    @Test
    void classify_setRelayerApprovalWithoutEconomicEffects_emitsApproval() {
        String wallet = "0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f";
        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("AVALANCHE");
        tx.setRawData(new Document("from", wallet)
                .append("to", "0xba12222222228d8ba445958a75a0704d566bf2c8")
                .append("value", "0")
                .append("methodId", "0xfa6e671d")
                .append("functionName", "setRelayerApproval(address sender, address relayer, bool approved)"));

        List<RawClassifiedEvent> events = classifier.classify(tx, wallet);

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().getEventType()).isEqualTo(EconomicEventType.APPROVAL);
    }

    @Test
    void classify_zeroValueExplorerTransfer_stillApproval() {
        String wallet = "0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f";
        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("ARBITRUM");
        tx.setRawData(new Document("from", wallet)
                .append("to", "0xaf88d065e77c8cc2239327c5edb3a432268e5831")
                .append("value", "0")
                .append("methodId", "0x095ea7b3")
                .append("explorer", new Document("tokenTransfers", List.of(
                        new Document("from", wallet)
                                .append("to", "0x1111111111111111111111111111111111111111")
                                .append("value", "0")
                ))));

        List<RawClassifiedEvent> events = classifier.classify(tx, wallet);

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().getEventType()).isEqualTo(EconomicEventType.APPROVAL);
    }
}
