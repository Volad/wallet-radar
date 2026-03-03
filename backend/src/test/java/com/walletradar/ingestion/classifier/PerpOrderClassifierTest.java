package com.walletradar.ingestion.classifier;

import com.walletradar.domain.transaction.normalized.EconomicEventType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PerpOrderClassifierTest {

    private final PerpOrderClassifier classifier = new PerpOrderClassifier();

    @Test
    void classify_createOrderToKnownRouter_emitsExternalTransferOut() {
        String wallet = "0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f";

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("ARBITRUM");
        tx.setRawData(new Document("from", wallet)
                .append("to", "0xba3cb449bd2b4adddbc894d8697f5170800eadec")
                .append("value", "27638811423349461")
                .append("methodId", "0x322bba21")
                .append("functionName", "createOrder(tuple order)")
                .append("input", "0x322bba21"));

        List<RawClassifiedEvent> events = classifier.classify(tx, wallet);

        assertThat(events).hasSize(1);
        RawClassifiedEvent event = events.getFirst();
        assertThat(event.getEventType()).isEqualTo(EconomicEventType.EXTERNAL_TRANSFER_OUT);
        assertThat(event.getAssetContract()).isEqualTo("0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee");
        assertThat(event.getAssetSymbol()).isEqualTo("ETH");
        assertThat(event.getQuantityDelta()).isEqualByComparingTo("-0.027638811423349461");
        assertThat(event.getCounterpartyAddress()).isEqualTo("0xba3cb449bd2b4adddbc894d8697f5170800eadec");
    }

    @Test
    void classify_createOrderWithZeroValue_returnsEmpty() {
        String wallet = "0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f";

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("ARBITRUM");
        tx.setRawData(new Document("from", wallet)
                .append("to", "0xba3cb449bd2b4adddbc894d8697f5170800eadec")
                .append("value", "0")
                .append("methodId", "0x322bba21")
                .append("functionName", "createOrder(tuple order)"));

        List<RawClassifiedEvent> events = classifier.classify(tx, wallet);

        assertThat(events).isEmpty();
    }

    @Test
    void classify_unrelatedCall_returnsEmpty() {
        String wallet = "0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f";

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("ARBITRUM");
        tx.setRawData(new Document("from", wallet)
                .append("to", "0xaf88d065e77c8cc2239327c5edb3a432268e5831")
                .append("value", "27638811423349461")
                .append("methodId", "0x095ea7b3")
                .append("functionName", "approve(address spender, uint256 amount)")
                .append("input", "0x095ea7b3"));

        List<RawClassifiedEvent> events = classifier.classify(tx, wallet);

        assertThat(events).isEmpty();
    }

    @Test
    void classify_createOrderSelectorFallback_whenMethodIdEmpty_emitsExternalTransferOut() {
        String wallet = "0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f";

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("ARBITRUM");
        tx.setRawData(new Document("from", wallet)
                .append("to", "0xba3cb449bd2b4adddbc894d8697f5170800eadec")
                .append("value", "27638811423349461")
                .append("methodId", "0x")
                .append("input", "0x322bba21"));

        List<RawClassifiedEvent> events = classifier.classify(tx, wallet);

        assertThat(events).hasSize(1);
        RawClassifiedEvent event = events.getFirst();
        assertThat(event.getEventType()).isEqualTo(EconomicEventType.EXTERNAL_TRANSFER_OUT);
        assertThat(event.getQuantityDelta()).isEqualByComparingTo("-0.027638811423349461");
    }
}
