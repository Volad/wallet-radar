package com.walletradar.application.linking.pipeline.clarification;

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
class EtherFiOftBridgeInClassifierTest {

    @Mock
    private MongoOperations mongoOperations;
    @Mock
    private NormalizedTransactionRepository normalizedTransactionRepository;

    private EtherFiOftBridgeInClassifier classifier;

    @BeforeEach
    void setUp() {
        classifier = new EtherFiOftBridgeInClassifier(mongoOperations, normalizedTransactionRepository);
        lenient().when(normalizedTransactionRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("weETH from EtherFi minter proxy on Unichain is reclassified to BRIDGE_IN")
    void weethFromMinterReclassifiedToBridgeIn() {
        RawTransaction raw = etherFiRaw(
                "0x7DCC39B4d1C53CB31e1aBc0e358b43987FEF80f7",
                "0xeeeeee9ec4769a09a76a83c7bc42b185872860ee"
        );

        boolean matches = classifier.matchesEtherFiOftSignature(raw);
        assertThat(matches).isTrue();
    }

    @Test
    @DisplayName("weETH from non-EtherFi sender is NOT reclassified")
    void nonEtherFiSenderNotReclassified() {
        RawTransaction raw = etherFiRaw(
                "0x7DCC39B4d1C53CB31e1aBc0e358b43987FEF80f7",
                "0xaaaa000000000000000000000000000000000001"
        );

        boolean matches = classifier.matchesEtherFiOftSignature(raw);
        assertThat(matches).isFalse();
    }

    @Test
    @DisplayName("non-weETH token from EtherFi sender is NOT reclassified")
    void nonWeethTokenNotReclassified() {
        RawTransaction raw = etherFiRaw(
                "0xaaaa000000000000000000000000000000000001",
                "0xeeeeee9ec4769a09a76a83c7bc42b185872860ee"
        );

        boolean matches = classifier.matchesEtherFiOftSignature(raw);
        assertThat(matches).isFalse();
    }

    @Test
    @DisplayName("full reclassification stamps type, protocol, and retags flows")
    void fullReclassificationStamps() {
        NormalizedTransaction tx = externalTransferIn(
                "0xd25bb96c",
                "0x68bc3b81c853338eaaa21552f57437dfd7bf5b7f",
                NetworkId.UNICHAIN,
                "weETH",
                "0.018781349438692058"
        );
        RawTransaction raw = etherFiRaw(
                "0x7dcc39b4d1c53cb31e1abc0e358b43987fef80f7",
                "0xeeeeee9ec4769a09a76a83c7bc42b185872860ee"
        );

        when(mongoOperations.find(any(Query.class), eq(RawTransaction.class)))
                .thenReturn(List.of(raw));

        boolean reclassified = classifier.reclassifyIfEtherFiOft(tx, Instant.now());

        assertThat(reclassified).isTrue();
        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.BRIDGE_IN);
        assertThat(tx.getProtocolName()).isEqualTo("EtherFi");
        assertThat(tx.getCounterpartyType()).isEqualTo("PROTOCOL");
        assertThat(tx.getMissingDataReasons()).contains("BRIDGE_ON_CHAIN_LEG_NOT_FOUND");
        assertThat(tx.getFlows().getFirst().getRole()).isEqualTo(NormalizedLegRole.TRANSFER);
        assertThat(tx.getFlows().getFirst().getUnitPriceUsd()).isNull();
    }

    private static RawTransaction etherFiRaw(String contract, String from) {
        RawTransaction raw = new RawTransaction();
        Document rawData = new Document();
        rawData.put("methodId", "0x6e305f80");
        Document explorer = new Document();
        List<Document> tokenTransfers = List.of(
                new Document()
                        .append("contractAddress", contract)
                        .append("tokenSymbol", "weETH")
                        .append("from", from)
                        .append("to", "0x68bc3b81c853338eaaa21552f57437dfd7bf5b7f")
                        .append("value", "18781349438692058")
        );
        explorer.put("tokenTransfers", tokenTransfers);
        rawData.put("explorer", explorer);
        raw.setRawData(rawData);
        return raw;
    }

    private static NormalizedTransaction externalTransferIn(
            String id, String wallet, NetworkId networkId, String assetSymbol, String quantity
    ) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId(id);
        tx.setTxHash(id);
        tx.setWalletAddress(wallet);
        tx.setSource(NormalizedTransactionSource.ON_CHAIN);
        tx.setNetworkId(networkId);
        tx.setType(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        tx.setBlockTimestamp(Instant.parse("2026-04-10T14:00:00Z"));
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setAssetSymbol(assetSymbol);
        flow.setQuantityDelta(new BigDecimal(quantity));
        flow.setRole(NormalizedLegRole.BUY);
        flow.setUnitPriceUsd(new BigDecimal("2500.0"));
        flow.setValueUsd(new BigDecimal("46.95"));
        flow.setCounterpartyAddress("0xeeeeee9ec4769a09a76a83c7bc42b185872860ee");
        tx.setFlows(new ArrayList<>(List.of(flow)));
        tx.setMissingDataReasons(new ArrayList<>());
        return tx;
    }
}
