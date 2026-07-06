package com.walletradar.ingestion.pipeline.clarification;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GmxV2RefundClassifierTest {

    @Mock
    private MongoOperations mongoOperations;
    @Mock
    private NormalizedTransactionRepository normalizedTransactionRepository;

    private GmxV2RefundClassifier classifier;

    @BeforeEach
    void setUp() {
        classifier = new GmxV2RefundClassifier(mongoOperations, normalizedTransactionRepository);
        lenient().when(normalizedTransactionRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("transfer from current GMX OrderHandler is stamped as GMX V2 refund")
    void currentOrderHandlerStamped() {
        NormalizedTransaction tx = externalTransferIn(
                "0x17273e5c",
                "ETH",
                "0.000019472334",
                "0x63492B775e30a9E6b4b4761c12605EB9d071d5e9"
        );

        boolean classified = classifier.classifyIfGmxRefund(tx, Instant.now());

        assertThat(classified).isTrue();
        assertThat(tx.getProtocolName()).isEqualTo("GMX V2");
        assertThat(tx.getCounterpartyType()).isEqualTo("PROTOCOL");
        assertThat(tx.getMissingDataReasons()).contains("GMX_EXECUTION_FEE_REFUND");
        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
    }

    @Test
    @DisplayName("transfer from deprecated legacy OrderHandler is stamped")
    void deprecatedLegacyHandlerStamped() {
        NormalizedTransaction tx = externalTransferIn(
                "0xabcdef",
                "ETH",
                "0.000019",
                "0xe68caaacdf6439628dfd2fe624847602991a31eb"
        );

        boolean classified = classifier.classifyIfGmxRefund(tx, Instant.now());

        assertThat(classified).isTrue();
        assertThat(tx.getProtocolName()).isEqualTo("GMX V2");
    }

    @Test
    @DisplayName("transfer from unknown address is NOT stamped")
    void unknownAddressNotStamped() {
        NormalizedTransaction tx = externalTransferIn(
                "0x123456",
                "ETH",
                "0.001",
                "0xaaaa000000000000000000000000000000000001"
        );

        boolean classified = classifier.classifyIfGmxRefund(tx, Instant.now());

        assertThat(classified).isFalse();
        assertThat(tx.getProtocolName()).isNull();
    }

    @Test
    @DisplayName("re-running on already-stamped tx is idempotent")
    void idempotentReRun() {
        NormalizedTransaction tx = externalTransferIn(
                "0x17273e5c",
                "ETH",
                "0.000019472334",
                "0x63492B775e30a9E6b4b4761c12605EB9d071d5e9"
        );
        tx.setProtocolName("GMX V2");
        tx.setCounterpartyType("PROTOCOL");
        tx.getMissingDataReasons().add("GMX_EXECUTION_FEE_REFUND");

        boolean classified = classifier.classifyIfGmxRefund(tx, Instant.now());

        assertThat(classified).isFalse();
    }

    @Test
    @DisplayName("F4: composite UNKNOWN: cp falls back to raw internal transactions and matches GMX V2")
    void compositeUnknownCpFallsBackToRawInternal() {
        String compositeCp = "UNKNOWN:0xf3581fb9ARBITRUM:someBUY:ETH:0";
        NormalizedTransaction tx = externalTransferIn(
                "0xf3581fb9",
                "ETH",
                "0.000019",
                compositeCp
        );
        tx.getFlows().getFirst().setCounterpartyAddress(compositeCp);

        RawTransaction raw = new RawTransaction();
        raw.setTxHash("0xf3581fb9");
        raw.setNetworkId("ARBITRUM");
        Document explorer = new Document("internalTransactions", List.of(
                new Document("from", "0x70d95587d40A2caf56bd97485aB3Eec10Bee6336")
                        .append("to", "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f")
                        .append("value", "19472334000000"),
                new Document("from", "0x63dc80EE90F26a3dBa1b1681bfdd29dCf48b9C6e")
                        .append("to", "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f")
                        .append("value", "1000000000")
        ));
        raw.setRawData(new Document("explorer", explorer));

        when(mongoOperations.find(any(Query.class), eq(RawTransaction.class)))
                .thenReturn(List.of(raw));

        boolean classified = classifier.classifyIfGmxRefund(tx, Instant.now());

        assertThat(classified).isTrue();
        assertThat(tx.getProtocolName()).isEqualTo("GMX V2");
        assertThat(tx.getCounterpartyType()).isEqualTo("PROTOCOL");
        assertThat(tx.getMissingDataReasons()).contains("GMX_EXECUTION_FEE_REFUND");
    }

    @Test
    @DisplayName("F4: composite UNKNOWN: cp with no GMX V2 internal senders is NOT stamped")
    void compositeUnknownCpWithNoGmxInternalNotStamped() {
        String compositeCp = "UNKNOWN:0xabc123ARBITRUM:someBUY:ETH:0";
        NormalizedTransaction tx = externalTransferIn(
                "0xabc123",
                "ETH",
                "0.000019",
                compositeCp
        );
        tx.getFlows().getFirst().setCounterpartyAddress(compositeCp);

        RawTransaction raw = new RawTransaction();
        raw.setTxHash("0xabc123");
        raw.setNetworkId("ARBITRUM");
        Document explorer = new Document("internalTransactions", List.of(
                new Document("from", "0xaaaa000000000000000000000000000000000001")
                        .append("to", "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f")
                        .append("value", "1000000000")
        ));
        raw.setRawData(new Document("explorer", explorer));

        when(mongoOperations.find(any(Query.class), eq(RawTransaction.class)))
                .thenReturn(List.of(raw));

        boolean classified = classifier.classifyIfGmxRefund(tx, Instant.now());

        assertThat(classified).isFalse();
        assertThat(tx.getProtocolName()).isNull();
    }

    @Test
    @DisplayName("matchesGmxV2InternalSender detects GMX handler in raw internal transactions")
    void matchesGmxV2InternalSenderFromRaw() {
        RawTransaction raw = new RawTransaction();
        Document explorer = new Document("internalTransactions", List.of(
                new Document("from", "0x70d95587d40A2caf56bd97485aB3Eec10Bee6336")
                        .append("to", "0xuser")
                        .append("value", "100")
        ));
        raw.setRawData(new Document("explorer", explorer));

        assertThat(classifier.matchesGmxV2InternalSender(raw)).isTrue();
    }

    @Test
    @DisplayName("matchesGmxV2InternalSender returns false for empty internal txns")
    void noInternalTransactionsReturnsFalse() {
        RawTransaction raw = new RawTransaction();
        raw.setRawData(new Document("explorer", new Document()));

        assertThat(classifier.matchesGmxV2InternalSender(raw)).isFalse();
    }

    private static NormalizedTransaction externalTransferIn(
            String id, String assetSymbol, String quantity, String counterparty
    ) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId(id);
        tx.setTxHash(id);
        tx.setWalletAddress("0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f");
        tx.setSource(NormalizedTransactionSource.ON_CHAIN);
        tx.setNetworkId(NetworkId.ARBITRUM);
        tx.setType(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        tx.setBlockTimestamp(Instant.parse("2025-12-15T10:00:00Z"));
        tx.setCounterpartyAddress(counterparty);
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setAssetSymbol(assetSymbol);
        flow.setQuantityDelta(new BigDecimal(quantity));
        flow.setRole(NormalizedLegRole.BUY);
        flow.setCounterpartyAddress(counterparty);
        tx.setFlows(new ArrayList<>(List.of(flow)));
        tx.setMissingDataReasons(new ArrayList<>());
        return tx;
    }
}
