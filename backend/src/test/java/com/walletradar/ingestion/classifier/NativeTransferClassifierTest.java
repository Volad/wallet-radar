package com.walletradar.ingestion.classifier;

import com.walletradar.domain.transaction.normalized.EconomicEventType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NativeTransferClassifierTest {

    private final NativeTransferClassifier classifier = new NativeTransferClassifier();

    @Test
    void classify_inboundSimpleNativeTransfer_emitsExternalInbound() {
        String wallet = "0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f";

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("ARBITRUM");
        tx.setRawData(new Document("from", "0xf89d7b9c864f589bbf53a82105107622b35eaa40")
                .append("to", wallet)
                .append("value", "5470060000000000")
                .append("input", "0x")
                .append("explorer", new Document("tokenTransfers", List.of()).append("internalTransfers", List.of())));

        List<RawClassifiedEvent> events = classifier.classify(tx, wallet);

        assertThat(events).hasSize(1);
        RawClassifiedEvent event = events.getFirst();
        assertThat(event.getEventType()).isEqualTo(EconomicEventType.EXTERNAL_INBOUND);
        assertThat(event.getAssetContract()).isEqualTo("0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee");
        assertThat(event.getAssetSymbol()).isEqualTo("ETH");
        assertThat(event.getCounterpartyAddress()).isEqualTo("0xf89d7b9c864f589bbf53a82105107622b35eaa40");
        assertThat(event.getQuantityDelta()).isEqualByComparingTo("0.00547006");
    }

    @Test
    void classify_outboundSimpleNativeTransfer_emitsExternalTransferOut() {
        String wallet = "0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f";

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("ARBITRUM");
        tx.setRawData(new Document("from", wallet)
                .append("to", "0xf89d7b9c864f589bbf53a82105107622b35eaa40")
                .append("value", "100000000000000000")
                .append("input", "0x"));

        List<RawClassifiedEvent> events = classifier.classify(tx, wallet);

        assertThat(events).hasSize(1);
        RawClassifiedEvent event = events.getFirst();
        assertThat(event.getEventType()).isEqualTo(EconomicEventType.EXTERNAL_TRANSFER_OUT);
        assertThat(event.getQuantityDelta()).isEqualByComparingTo("-0.1");
        assertThat(event.getCounterpartyAddress()).isEqualTo("0xf89d7b9c864f589bbf53a82105107622b35eaa40");
    }

    @Test
    void classify_withContractCallInputAndNoOtherSignals_emitsExternalTransferOut() {
        String wallet = "0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f";

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("ARBITRUM");
        tx.setRawData(new Document("from", wallet)
                .append("to", "0x6a000f20005980200259b80c5102003040001068")
                .append("value", "100000000000000000")
                .append("input", "0xa9059cbb"));

        List<RawClassifiedEvent> events = classifier.classify(tx, wallet);

        assertThat(events).hasSize(1);
        RawClassifiedEvent event = events.getFirst();
        assertThat(event.getEventType()).isEqualTo(EconomicEventType.EXTERNAL_TRANSFER_OUT);
        assertThat(event.getQuantityDelta()).isEqualByComparingTo("-0.1");
    }

    @Test
    void classify_withNonEmptyInputInboundAndNoOtherSignals_emitsExternalInbound() {
        String wallet = "0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f";

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("BASE");
        tx.setRawData(new Document("from", "0xf70da97812cb96acdf810712aa562db8dfa3dbef")
                .append("to", wallet)
                .append("value", "74125988889806")
                .append("input", "0x6dea4157ed169db7454472b0ed6104696a62fcedb04eb5e8b2d097d2eefc8583"));

        List<RawClassifiedEvent> events = classifier.classify(tx, wallet);

        assertThat(events).hasSize(1);
        RawClassifiedEvent event = events.getFirst();
        assertThat(event.getEventType()).isEqualTo(EconomicEventType.EXTERNAL_INBOUND);
        assertThat(event.getQuantityDelta()).isEqualByComparingTo("0.000074125988889806");
    }

    @Test
    void classify_withLogsPresent_returnsEmpty() {
        String wallet = "0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f";

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("ARBITRUM");
        tx.setRawData(new Document("from", "0xf89d7b9c864f589bbf53a82105107622b35eaa40")
                .append("to", wallet)
                .append("value", "5470060000000000")
                .append("input", "0x")
                .append("logs", List.of(new Document("address", "0xToken"))));

        List<RawClassifiedEvent> events = classifier.classify(tx, wallet);

        assertThat(events).isEmpty();
    }

    @Test
    void classify_internalTransfersOnly_netInbound_emitsExternalInbound() {
        String wallet = "0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f";

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("ARBITRUM");
        tx.setRawData(new Document("explorer", new Document("tokenTransfers", List.of())
                .append("internalTransfers", List.of(
                        new Document("from", "0x70d95587d40a2caf56bd97485ab3eec10bee6336")
                                .append("to", wallet)
                                .append("value", "9864288325390225")
                                .append("isError", "0"),
                        new Document("from", "0x63dc80ee90f26363b3fcd609007cc9e14c8991be")
                                .append("to", wallet)
                                .append("value", "9996327410602506")
                                .append("isError", "0"),
                        new Document("from", "0x1eea01a3592b8943737977b93ed24be7842d2427")
                                .append("to", wallet)
                                .append("value", "1006008387727200")
                                .append("isError", "0")
                ))));

        List<RawClassifiedEvent> events = classifier.classify(tx, wallet);

        assertThat(events).hasSize(1);
        RawClassifiedEvent event = events.getFirst();
        assertThat(event.getEventType()).isEqualTo(EconomicEventType.EXTERNAL_INBOUND);
        assertThat(event.getAssetContract()).isEqualTo("0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee");
        assertThat(event.getAssetSymbol()).isEqualTo("ETH");
        assertThat(event.getQuantityDelta()).isEqualByComparingTo("0.020866624123719931");
        assertThat(event.getCounterpartyAddress()).isNull();
    }

    @Test
    void classify_wrappedNativeDeposit_emitsSwapSellAndSwapBuy() {
        String wallet = "0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f";
        String walletTopic = "0x0000000000000000000000001a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String wrappedNative = "0x4200000000000000000000000000000000000006";

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("BASE");
        tx.setRawData(new Document("from", wallet)
                .append("to", wrappedNative)
                .append("value", "1000000000000000000")
                .append("input", "0xd0e30db0")
                .append("logs", List.of(
                        new Document("address", wrappedNative)
                                .append("logIndex", "0x1f")
                                .append("topics", List.of(
                                        "0xe1fffcc4923d04b559f4d29a8bfc6cda04eb5b0d3c460751c2402c5c5cc9109c",
                                        walletTopic
                                ))
                                .append("data", "0x0000000000000000000000000000000000000000000000000de0b6b3a7640000")
                )));

        List<RawClassifiedEvent> events = classifier.classify(tx, wallet);

        assertThat(events).hasSize(2);
        RawClassifiedEvent sell = events.stream().filter(e -> e.getEventType() == EconomicEventType.SWAP_SELL).findFirst().orElseThrow();
        RawClassifiedEvent buy = events.stream().filter(e -> e.getEventType() == EconomicEventType.SWAP_BUY).findFirst().orElseThrow();
        assertThat(sell.getAssetContract()).isEqualTo("0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee");
        assertThat(sell.getAssetSymbol()).isEqualTo("ETH");
        assertThat(sell.getQuantityDelta()).isEqualByComparingTo("-1");
        assertThat(buy.getAssetContract()).isEqualTo(wrappedNative);
        assertThat(buy.getAssetSymbol()).isEqualTo("WETH");
        assertThat(buy.getQuantityDelta()).isEqualByComparingTo("1");
    }

    @Test
    void classify_wrappedNativeWithdraw_emitsSwapSellAndSwapBuy() {
        String wallet = "0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f";
        String walletTopic = "0x0000000000000000000000001a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String wrappedNative = "0x4200000000000000000000000000000000000006";

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("BASE");
        tx.setRawData(new Document("from", wallet)
                .append("to", wrappedNative)
                .append("value", "0")
                .append("input", "0x2e1a7d4d0000000000000000000000000000000000000000000000000de0b6b3a7640000")
                .append("logs", List.of(
                        new Document("address", wrappedNative)
                                .append("logIndex", "0x20")
                                .append("topics", List.of(
                                        "0x7fcf532c15f0a6db0bd6d0e038bea71d30d808c7d98cb3bf7268a95f7f1727f2",
                                        walletTopic
                                ))
                                .append("data", "0x0000000000000000000000000000000000000000000000000de0b6b3a7640000")
                )));

        List<RawClassifiedEvent> events = classifier.classify(tx, wallet);

        assertThat(events).hasSize(2);
        RawClassifiedEvent sell = events.stream().filter(e -> e.getEventType() == EconomicEventType.SWAP_SELL).findFirst().orElseThrow();
        RawClassifiedEvent buy = events.stream().filter(e -> e.getEventType() == EconomicEventType.SWAP_BUY).findFirst().orElseThrow();
        assertThat(sell.getAssetContract()).isEqualTo(wrappedNative);
        assertThat(sell.getAssetSymbol()).isEqualTo("WETH");
        assertThat(sell.getQuantityDelta()).isEqualByComparingTo("-1");
        assertThat(buy.getAssetContract()).isEqualTo("0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee");
        assertThat(buy.getAssetSymbol()).isEqualTo("ETH");
        assertThat(buy.getQuantityDelta()).isEqualByComparingTo("1");
    }

    @Test
    void classify_wrappedNativeDeposit_withoutLogs_usesSelectorAndValueFallback() {
        String wallet = "0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f";
        String wrappedNative = "0x4200000000000000000000000000000000000006";

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("BASE");
        tx.setRawData(new Document("from", wallet)
                .append("to", wrappedNative)
                .append("value", "10000000000000")
                .append("input", "0xd0e30db0"));

        List<RawClassifiedEvent> events = classifier.classify(tx, wallet);

        assertThat(events).hasSize(2);
        RawClassifiedEvent sell = events.stream().filter(e -> e.getEventType() == EconomicEventType.SWAP_SELL).findFirst().orElseThrow();
        RawClassifiedEvent buy = events.stream().filter(e -> e.getEventType() == EconomicEventType.SWAP_BUY).findFirst().orElseThrow();
        assertThat(sell.getAssetContract()).isEqualTo("0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee");
        assertThat(sell.getQuantityDelta()).isEqualByComparingTo("-0.00001");
        assertThat(buy.getAssetContract()).isEqualTo(wrappedNative);
        assertThat(buy.getQuantityDelta()).isEqualByComparingTo("0.00001");
    }

    @Test
    void classify_wrappedNativeDeposit_withEmptyMethodId_usesInputSelectorFallback() {
        String wallet = "0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f";
        String wrappedNative = "0x4200000000000000000000000000000000000006";

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("BASE");
        tx.setRawData(new Document("from", wallet)
                .append("to", wrappedNative)
                .append("methodId", "0x")
                .append("value", "10000000000000")
                .append("input", "0xd0e30db0"));

        List<RawClassifiedEvent> events = classifier.classify(tx, wallet);

        assertThat(events).hasSize(2);
        RawClassifiedEvent sell = events.stream().filter(e -> e.getEventType() == EconomicEventType.SWAP_SELL).findFirst().orElseThrow();
        RawClassifiedEvent buy = events.stream().filter(e -> e.getEventType() == EconomicEventType.SWAP_BUY).findFirst().orElseThrow();
        assertThat(sell.getAssetContract()).isEqualTo("0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee");
        assertThat(sell.getQuantityDelta()).isEqualByComparingTo("-0.00001");
        assertThat(buy.getAssetContract()).isEqualTo(wrappedNative);
        assertThat(buy.getQuantityDelta()).isEqualByComparingTo("0.00001");
    }

    @Test
    void classify_wrappedNativeWithdraw_withoutLogs_usesInputAmountFallback() {
        String wallet = "0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f";
        String wrappedNative = "0x4200000000000000000000000000000000000006";

        RawTransaction tx = new RawTransaction();
        tx.setNetworkId("BASE");
        tx.setRawData(new Document("from", wallet)
                .append("to", wrappedNative)
                .append("value", "0")
                .append("input", "0x2e1a7d4d000000000000000000000000000000000000000000000000000009184e72a000"));

        List<RawClassifiedEvent> events = classifier.classify(tx, wallet);

        assertThat(events).hasSize(2);
        RawClassifiedEvent sell = events.stream().filter(e -> e.getEventType() == EconomicEventType.SWAP_SELL).findFirst().orElseThrow();
        RawClassifiedEvent buy = events.stream().filter(e -> e.getEventType() == EconomicEventType.SWAP_BUY).findFirst().orElseThrow();
        assertThat(sell.getAssetContract()).isEqualTo(wrappedNative);
        assertThat(sell.getQuantityDelta()).isEqualByComparingTo("-0.00001");
        assertThat(buy.getAssetContract()).isEqualTo("0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee");
        assertThat(buy.getQuantityDelta()).isEqualByComparingTo("0.00001");
    }
}
