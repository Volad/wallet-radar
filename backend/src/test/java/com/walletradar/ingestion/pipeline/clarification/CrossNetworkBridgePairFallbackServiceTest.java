package com.walletradar.ingestion.pipeline.clarification;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CrossNetworkBridgePairFallbackServiceTest {

    private static final String WALLET = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
    private static final String OUT_HASH = "0x9f6983d00441ed13bf45e1b7ac34e94540fb61f58e4a9a2189826b1e761a2f7f";
    private static final String IN_HASH = "0x7d8c79a327637fda080bcfa9204181359de791ccff95dd4b2f1b020b8af0b678";

    @Mock
    private MongoOperations mongoOperations;
    @Mock
    private NormalizedTransactionRepository normalizedTransactionRepository;

    private CrossNetworkBridgePairFallbackService service;

    @BeforeEach
    void setUp() {
        service = new CrossNetworkBridgePairFallbackService(mongoOperations, normalizedTransactionRepository);
        lenient().when(normalizedTransactionRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("orphan BRIDGE_IN pairs with cross-network BRIDGE_OUT")
    void orphanBridgeInPairsWithCrossNetworkBridgeOut() {
        NormalizedTransaction outbound = bridgeLeg(
                "out",
                OUT_HASH,
                NetworkId.KATANA,
                NormalizedTransactionType.BRIDGE_OUT,
                "vbUSDC",
                "-28.997378",
                Instant.parse("2026-02-01T10:00:00Z")
        );
        NormalizedTransaction inbound = bridgeLeg(
                "in",
                IN_HASH,
                NetworkId.ARBITRUM,
                NormalizedTransactionType.BRIDGE_IN,
                "USDC",
                "28.920966",
                Instant.parse("2026-02-01T10:30:00Z")
        );
        inbound.setMissingDataReasons(new ArrayList<>(List.of("BRIDGE_ON_CHAIN_LEG_NOT_FOUND")));

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(inbound), List.of(outbound));

        int paired = service.reconcileOrphanInbounds(25);

        assertThat(paired).isEqualTo(1);
        ArgumentCaptor<List<NormalizedTransaction>> savedCaptor = ArgumentCaptor.forClass(List.class);
        verify(normalizedTransactionRepository).saveAll(savedCaptor.capture());
        String expectedCorrelation = "bridge:crossnet:" + OUT_HASH.toLowerCase();
        assertThat(inbound.getCorrelationId()).isEqualTo(expectedCorrelation);
        assertThat(outbound.getCorrelationId()).isEqualTo(expectedCorrelation);
        assertThat(inbound.getContinuityCandidate()).isTrue();
        assertThat(outbound.getContinuityCandidate()).isTrue();
        assertThat(inbound.getMatchedCounterparty()).isEqualTo(OUT_HASH);
        assertThat(outbound.getMatchedCounterparty()).isEqualTo(IN_HASH);
        assertThat(inbound.getMissingDataReasons()).doesNotContain("BRIDGE_ON_CHAIN_LEG_NOT_FOUND");
        assertThat(inbound.getFlows().getFirst().getRole()).isEqualTo(NormalizedLegRole.TRANSFER);
    }

    @Test
    @DisplayName("BR-1: discovered BASE ETH -> ZKSYNC ETH pair shares corridor key and stamps counterpartyType=BRIDGE on both legs")
    void crossNetworkEthPairStampsBridgeCounterpartyTypeOnBothLegs() {
        NormalizedTransaction outbound = bridgeLeg(
                "out",
                OUT_HASH,
                NetworkId.BASE,
                NormalizedTransactionType.BRIDGE_OUT,
                "ETH",
                "-0.0111",
                Instant.parse("2026-02-01T10:00:00Z")
        );
        NormalizedTransaction inbound = bridgeLeg(
                "in",
                IN_HASH,
                NetworkId.ZKSYNC,
                NormalizedTransactionType.BRIDGE_IN,
                "ETH",
                "0.0111",
                Instant.parse("2026-02-01T10:20:00Z")
        );
        inbound.setMissingDataReasons(new ArrayList<>(List.of("BRIDGE_ON_CHAIN_LEG_NOT_FOUND")));

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(inbound), List.of(outbound));

        int paired = service.reconcileOrphanInbounds(25);

        assertThat(paired).isEqualTo(1);
        String expectedCorrelation = "bridge:crossnet:" + OUT_HASH.toLowerCase();
        assertThat(outbound.getCorrelationId()).isEqualTo(expectedCorrelation);
        assertThat(inbound.getCorrelationId()).isEqualTo(expectedCorrelation);
        assertThat(outbound.getFlows().getFirst().getCounterpartyType()).isEqualTo(CounterpartyType.BRIDGE);
        assertThat(inbound.getFlows().getFirst().getCounterpartyType()).isEqualTo(CounterpartyType.BRIDGE);
    }

    @Test
    @DisplayName("ambiguous cross-network matches are not paired")
    void ambiguousCrossNetworkMatchesAreNotPaired() {
        NormalizedTransaction inbound = bridgeLeg(
                "in",
                IN_HASH,
                NetworkId.ARBITRUM,
                NormalizedTransactionType.BRIDGE_IN,
                "USDC",
                "28.920966",
                Instant.parse("2026-02-01T10:30:00Z")
        );
        inbound.setMissingDataReasons(new ArrayList<>(List.of("BRIDGE_ON_CHAIN_LEG_NOT_FOUND")));

        NormalizedTransaction outboundA = bridgeLeg(
                "out-a",
                OUT_HASH,
                NetworkId.KATANA,
                NormalizedTransactionType.BRIDGE_OUT,
                "vbUSDC",
                "-28.997378",
                Instant.parse("2026-02-01T10:00:00Z")
        );
        NormalizedTransaction outboundB = bridgeLeg(
                "out-b",
                "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                NetworkId.BASE,
                NormalizedTransactionType.BRIDGE_OUT,
                "USDC",
                "-28.90",
                Instant.parse("2026-02-01T10:05:00Z")
        );

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(inbound), List.of(outboundA, outboundB));

        int paired = service.reconcileOrphanInbounds(25);

        assertThat(paired).isZero();
        verify(normalizedTransactionRepository, never()).saveAll(any());
    }

    private static NormalizedTransaction bridgeLeg(
            String id,
            String txHash,
            NetworkId networkId,
            NormalizedTransactionType type,
            String assetSymbol,
            String quantity,
            Instant timestamp
    ) {
        NormalizedTransaction transaction = new NormalizedTransaction();
        transaction.setId(id);
        transaction.setTxHash(txHash);
        transaction.setWalletAddress(WALLET);
        transaction.setSource(NormalizedTransactionSource.ON_CHAIN);
        transaction.setNetworkId(networkId);
        transaction.setType(type);
        transaction.setStatus(NormalizedTransactionStatus.CONFIRMED);
        transaction.setBlockTimestamp(timestamp);
        transaction.setTransactionIndex(1);
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setAssetSymbol(assetSymbol);
        flow.setQuantityDelta(new BigDecimal(quantity));
        flow.setRole(quantity.startsWith("-") ? NormalizedLegRole.SELL : NormalizedLegRole.BUY);
        transaction.setFlows(List.of(flow));
        return transaction;
    }
}
