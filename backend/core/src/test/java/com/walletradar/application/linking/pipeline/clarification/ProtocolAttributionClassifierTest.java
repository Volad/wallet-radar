package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoOperations;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class ProtocolAttributionClassifierTest {

    @Mock
    private MongoOperations mongoOperations;
    @Mock
    private NormalizedTransactionRepository normalizedTransactionRepository;

    private ProtocolAttributionClassifier classifier;

    @BeforeEach
    void setUp() {
        classifier = new ProtocolAttributionClassifier(mongoOperations, normalizedTransactionRepository);
        lenient().when(normalizedTransactionRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("LI.FI on BASE stamps protocolName and counterpartyType, keeps type")
    void liFiBaseStamped() {
        NormalizedTransaction tx = externalTransferIn(
                "0x8a4c1c0e", NetworkId.BASE, "ETH", "0.001",
                "0x8c826f795466e39acbff1bb4eeeb759609377ba1");

        boolean classified = classifier.classifyIfKnownProtocol(tx, Instant.now());

        assertThat(classified).isTrue();
        assertThat(tx.getProtocolName()).isEqualTo("LI.FI");
        assertThat(tx.getCounterpartyType()).isEqualTo("BRIDGE");
        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
    }

    @Test
    @DisplayName("Relay on BASE stamps protocolName, keeps type")
    void relayBaseStamped() {
        NormalizedTransaction tx = externalTransferIn(
                "0x851437b5", NetworkId.BASE, "ETH", "0.005",
                "0xf70da97812cb96acdf810712aa562db8dfa3dbef");

        boolean classified = classifier.classifyIfKnownProtocol(tx, Instant.now());

        assertThat(classified).isTrue();
        assertThat(tx.getProtocolName()).isEqualTo("Relay");
        assertThat(tx.getCounterpartyType()).isEqualTo("BRIDGE");
        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
    }

    @Test
    @DisplayName("rhino.fi on ZKSYNC reclassifies to BRIDGE_IN, strips prices, adds bridge reason")
    void rhinoFiZkSyncBridgeIn() {
        NormalizedTransaction tx = externalTransferIn(
                "0xaddb9f05", NetworkId.ZKSYNC, "ETH", "0.0073",
                "0x1fa66e2b38d0cc496ec51f81c3e05e6a6708986f");
        tx.getFlows().getFirst().setUnitPriceUsd(new BigDecimal("3000"));
        tx.getFlows().getFirst().setValueUsd(new BigDecimal("21.9"));
        tx.getFlows().getFirst().setPriceSource(PriceSource.COINGECKO);

        boolean classified = classifier.classifyIfKnownProtocol(tx, Instant.now());

        assertThat(classified).isTrue();
        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.BRIDGE_IN);
        assertThat(tx.getProtocolName()).isEqualTo("rhino.fi");
        assertThat(tx.getCounterpartyType()).isEqualTo("BRIDGE");
        assertThat(tx.getFlows().getFirst().getRole()).isEqualTo(NormalizedLegRole.TRANSFER);
        assertThat(tx.getFlows().getFirst().getUnitPriceUsd()).isNull();
        assertThat(tx.getFlows().getFirst().getValueUsd()).isNull();
        assertThat(tx.getFlows().getFirst().getPriceSource()).isNull();
        assertThat(tx.getMissingDataReasons()).contains("BRIDGE_ON_CHAIN_LEG_NOT_FOUND");
    }

    @Test
    @DisplayName("ZkSync Paymaster stamps protocolName=ZkSync Paymaster, type stays")
    void zkSyncPaymasterStamped() {
        NormalizedTransaction tx = externalTransferIn(
                "0xd4e5bc65", NetworkId.ZKSYNC, "ETH", "0.00001",
                "0x91604f590d66ace8975eed6bd16cf55647d1c499");

        boolean classified = classifier.classifyIfKnownProtocol(tx, Instant.now());

        assertThat(classified).isTrue();
        assertThat(tx.getProtocolName()).isEqualTo("ZkSync Paymaster");
        assertThat(tx.getCounterpartyType()).isEqualTo("PROTOCOL");
        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
    }

    @Test
    @DisplayName("random address on BASE is NOT stamped")
    void unknownAddressNotStamped() {
        NormalizedTransaction tx = externalTransferIn(
                "0xabc123", NetworkId.BASE, "ETH", "0.01",
                "0xaaaa000000000000000000000000000000000001");

        boolean classified = classifier.classifyIfKnownProtocol(tx, Instant.now());

        assertThat(classified).isFalse();
        assertThat(tx.getProtocolName()).isNull();
    }

    @Test
    @DisplayName("already-stamped tx is idempotent (no change)")
    void alreadyStampedIsIdempotent() {
        NormalizedTransaction tx = externalTransferIn(
                "0xd4e5bc65", NetworkId.ZKSYNC, "ETH", "0.00001",
                "0x91604f590d66ace8975eed6bd16cf55647d1c499");
        tx.setProtocolName("ZkSync Paymaster");
        tx.setCounterpartyType("PROTOCOL");

        boolean classified = classifier.classifyIfKnownProtocol(tx, Instant.now());

        assertThat(classified).isFalse();
    }

    @Test
    @DisplayName("counterparty resolved from flow when top-level cp is null")
    void counterpartyFromFlow() {
        NormalizedTransaction tx = externalTransferIn(
                "0x7a4d3b0f", NetworkId.BASE, "ETH", "0.001",
                null);
        tx.setCounterpartyAddress(null);
        tx.getFlows().getFirst().setCounterpartyAddress("0x8c826f795466e39acbff1bb4eeeb759609377ba1");

        boolean classified = classifier.classifyIfKnownProtocol(tx, Instant.now());

        assertThat(classified).isTrue();
        assertThat(tx.getProtocolName()).isEqualTo("LI.FI");
    }

    private static NormalizedTransaction externalTransferIn(
            String id, NetworkId networkId, String assetSymbol,
            String quantity, String counterparty
    ) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId(id);
        tx.setTxHash(id);
        tx.setWalletAddress("0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f");
        tx.setSource(NormalizedTransactionSource.ON_CHAIN);
        tx.setNetworkId(networkId);
        tx.setType(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        tx.setBlockTimestamp(Instant.parse("2026-01-15T10:00:00Z"));
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
