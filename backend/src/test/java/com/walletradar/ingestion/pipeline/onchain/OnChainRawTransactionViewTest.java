package com.walletradar.ingestion.pipeline.onchain;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.raw.RawTransaction;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OnChainRawTransactionViewTest {

    @Test
    @DisplayName("parses hex transactionIndex from top-level rawData")
    void parsesHexTransactionIndexFromTopLevelRawData() {
        OnChainRawTransactionView view = OnChainRawTransactionView.wrap(rawWith(new Document()
                .append("timeStamp", "1700000000")
                .append("transactionIndex", "0x5")));

        assertThat(view.transactionIndex()).isEqualTo(5);
        assertThat(view.blockTimestamp()).isEqualTo(Instant.ofEpochSecond(1_700_000_000L));
    }

    @Test
    @DisplayName("falls back to explorer tx ordering metadata when top-level fields are missing")
    void fallsBackToExplorerTxOrderingMetadata() {
        OnChainRawTransactionView view = OnChainRawTransactionView.wrap(rawWith(new Document()
                .append("explorer", new Document("tx", new Document()
                        .append("timeStamp", "1700000123")
                        .append("transactionIndex", "0x9")))));

        assertThat(view.blockTimestamp()).isEqualTo(Instant.ofEpochSecond(1_700_000_123L));
        assertThat(view.transactionIndex()).isEqualTo(9);
    }

    @Test
    @DisplayName("falls back to unanimous nested explorer evidence when explorer tx is absent")
    void fallsBackToUnanimousNestedExplorerEvidence() {
        OnChainRawTransactionView view = OnChainRawTransactionView.wrap(rawWith(new Document()
                .append("explorer", new Document()
                        .append("tokenTransfers", List.of(
                                new Document("timeStamp", "1700000200").append("transactionIndex", "3")
                        ))
                        .append("internalTransfers", List.of(
                                new Document("timeStamp", "1700000200").append("transactionIndex", "0x3")
                        )))));

        assertThat(view.blockTimestamp()).isEqualTo(Instant.ofEpochSecond(1_700_000_200L));
        assertThat(view.transactionIndex()).isEqualTo(3);
    }

    @Test
    @DisplayName("does not guess ordering metadata when nested explorer evidence conflicts")
    void doesNotGuessOrderingMetadataWhenNestedExplorerEvidenceConflicts() {
        OnChainRawTransactionView view = OnChainRawTransactionView.wrap(rawWith(new Document()
                .append("explorer", new Document()
                        .append("tokenTransfers", List.of(
                                new Document("timeStamp", "1700000200").append("transactionIndex", "3"),
                                new Document("timeStamp", "1700000201").append("transactionIndex", "4")
                        ))
                        .append("internalTransfers", List.of()))));

        assertThat(view.blockTimestamp()).isNull();
        assertThat(view.transactionIndex()).isNull();
    }

    @Test
    @DisplayName("recovers methodId from input and normalizes selector to lower-case")
    void recoversMethodIdFromInputAndNormalizesSelector() {
        OnChainRawTransactionView view = OnChainRawTransactionView.wrap(rawWith(new Document()
                .append("methodId", "0x")
                .append("input", "0x095EA7B3000000000000000000000000")
                .append("timeStamp", "1700000000")
                .append("transactionIndex", "1")));

        assertThat(view.methodId()).isEqualTo("0x095ea7b3");
    }

    @Test
    @DisplayName("returns canonical empty selector when no methodId can be resolved")
    void returnsCanonicalEmptySelectorWhenNoMethodIdCanBeResolved() {
        OnChainRawTransactionView view = OnChainRawTransactionView.wrap(rawWith(new Document()
                .append("input", "0x")
                .append("timeStamp", "1700000000")
                .append("transactionIndex", "1")));

        assertThat(view.methodId()).isEqualTo("0x");
    }

    @Test
    @DisplayName("exposes persisted raw logs through the view")
    void exposesPersistedRawLogsThroughTheView() {
        Document firstLog = new Document("address", "0xtoken")
                .append("topics", List.of("0xtopic"))
                .append("data", "0x01");
        OnChainRawTransactionView view = OnChainRawTransactionView.wrap(rawWith(new Document()
                .append("timeStamp", "1700000000")
                .append("transactionIndex", "1")
                .append("logs", List.of(firstLog))));

        assertThat(view.persistedLogs()).hasSize(1);
        assertThat(view.persistedLogs().getFirst().getString("address")).isEqualTo("0xtoken");
    }

    @Test
    @DisplayName("suppresses direct native value when top-level raw is transfer-shaped")
    void suppressesDirectNativeValueWhenTopLevelRawIsTransferShaped() {
        OnChainRawTransactionView view = OnChainRawTransactionView.wrap(rawWith(new Document()
                .append("timeStamp", "1700000000")
                .append("transactionIndex", "1")
                .append("from", "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                .append("to", "0x1111111111111111111111111111111111111111")
                .append("value", "897975990")
                .append("contractAddress", "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
                .append("tokenSymbol", "USDC")
                .append("tokenName", "USD Coin")
                .append("tokenDecimal", "6")
                .append("methodId", "0xe2de2a03")
                .append("explorer", new Document("tokenTransfers", List.of(
                        new Document("contractAddress", "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
                                .append("from", "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                                .append("to", "0x1111111111111111111111111111111111111111")
                                .append("value", "897975990")
                                .append("tokenSymbol", "USDC")
                                .append("tokenName", "USD Coin")
                                .append("tokenDecimal", "6")
                )))));

        assertThat(view.methodId()).isEqualTo("0xe2de2a03");
        assertThat(view.rawValue()).isNull();
        assertThat(view.contractAddress()).isNull();
    }

    @Test
    @DisplayName("prefers explorer tx fields over contaminated top-level values")
    void prefersExplorerTxFieldsOverContaminatedTopLevelValues() {
        OnChainRawTransactionView view = OnChainRawTransactionView.wrap(rawWith(new Document()
                .append("timeStamp", "1700000000")
                .append("transactionIndex", "1")
                .append("from", "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                .append("to", "0x1111111111111111111111111111111111111111")
                .append("value", "897975990")
                .append("explorer", new Document()
                        .append("tx", new Document()
                                .append("from", "0x9999999999999999999999999999999999999999")
                                .append("to", "0x8888888888888888888888888888888888888888")
                                .append("value", "0x0"))
                        .append("tokenTransfers", List.of(
                                new Document("contractAddress", "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
                                        .append("from", "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                                        .append("to", "0x1111111111111111111111111111111111111111")
                                        .append("value", "897975990")
                        )))));

        assertThat(view.fromAddress()).isEqualTo("0x9999999999999999999999999999999999999999");
        assertThat(view.toAddress()).isEqualTo("0x8888888888888888888888888888888888888888");
        assertThat(view.rawValue()).isEqualTo(BigInteger.ZERO);
    }

    @Test
    @DisplayName("does not infer contract creation from a missing to key alone")
    void doesNotInferContractCreationFromMissingToKeyAlone() {
        OnChainRawTransactionView view = OnChainRawTransactionView.wrap(rawWith(new Document()
                .append("timeStamp", "1700000000")
                .append("transactionIndex", "1")
                .append("from", "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                .append("input", "0x6080604052")));

        assertThat(view.isContractCreation()).isFalse();
    }

    @Test
    @DisplayName("treats explicit creates flag as contract creation")
    void treatsExplicitCreatesFlagAsContractCreation() {
        OnChainRawTransactionView view = OnChainRawTransactionView.wrap(rawWith(new Document()
                .append("timeStamp", "1700000000")
                .append("transactionIndex", "1")
                .append("from", "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                .append("input", "0x6080604052")
                .append("creates", true)));

        assertThat(view.isContractCreation()).isTrue();
    }

    private static RawTransaction rawWith(Document rawData) {
        RawTransaction rawTransaction = new RawTransaction();
        rawTransaction.setId("0xabc:" + NetworkId.ETHEREUM + ":0xwallet");
        rawTransaction.setTxHash("0xabc");
        rawTransaction.setNetworkId(NetworkId.ETHEREUM.name());
        rawTransaction.setWalletAddress("0x1111111111111111111111111111111111111111");
        rawTransaction.setRawData(rawData);
        return rawTransaction;
    }
}
