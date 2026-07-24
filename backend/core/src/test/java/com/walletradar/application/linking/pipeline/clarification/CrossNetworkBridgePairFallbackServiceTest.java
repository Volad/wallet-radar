package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.counterparty.CounterpartyType;
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

    @Test
    @DisplayName("NEW-08: cross-asset USDC BRIDGE_OUT pairs with ETH BRIDGE_IN via settlement carry (cc=false, both TRANSFER)")
    void crossAssetOrphanBridgePairsWithSettlementCarry() {
        NormalizedTransaction outbound = bridgeLegWithUsd(
                "out",
                OUT_HASH,
                NetworkId.UNICHAIN,
                NormalizedTransactionType.BRIDGE_OUT,
                "USDC",
                "-2050.040045",
                "2050.040045",
                Instant.parse("2026-02-01T10:00:00Z")
        );
        NormalizedTransaction inbound = bridgeLegWithUsd(
                "in",
                IN_HASH,
                NetworkId.KATANA,
                NormalizedTransactionType.BRIDGE_IN,
                "ETH",
                "0.452894",
                "2135.70",
                Instant.parse("2026-02-01T10:00:02Z")
        );
        inbound.setMissingDataReasons(new ArrayList<>(List.of("BRIDGE_ON_CHAIN_LEG_NOT_FOUND")));

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(inbound), List.of(outbound));

        int paired = service.reconcileOrphanInbounds(25);

        assertThat(paired).isEqualTo(1);
        String expectedCorrelation = "bridge:crossnet:" + OUT_HASH.toLowerCase();
        assertThat(outbound.getCorrelationId()).isEqualTo(expectedCorrelation);
        assertThat(inbound.getCorrelationId()).isEqualTo(expectedCorrelation);
        assertThat(outbound.getContinuityCandidate()).isFalse();
        assertThat(inbound.getContinuityCandidate()).isFalse();
        assertThat(outbound.getMatchedCounterparty()).isEqualTo(IN_HASH);
        assertThat(inbound.getMatchedCounterparty()).isEqualTo(OUT_HASH);
        assertThat(outbound.getFlows().getFirst().getRole()).isEqualTo(NormalizedLegRole.TRANSFER);
        assertThat(inbound.getFlows().getFirst().getRole()).isEqualTo(NormalizedLegRole.TRANSFER);
    }

    @Test
    @DisplayName("NEW-08 guardrail: cross-asset pair outside the 180s window is not paired")
    void crossAssetOutsideTimeWindowIsNotPaired() {
        NormalizedTransaction outbound = bridgeLegWithUsd(
                "out",
                OUT_HASH,
                NetworkId.UNICHAIN,
                NormalizedTransactionType.BRIDGE_OUT,
                "USDC",
                "-2050.040045",
                "2050.040045",
                Instant.parse("2026-02-01T10:00:00Z")
        );
        NormalizedTransaction inbound = bridgeLegWithUsd(
                "in",
                IN_HASH,
                NetworkId.KATANA,
                NormalizedTransactionType.BRIDGE_IN,
                "ETH",
                "0.452894",
                "2135.70",
                Instant.parse("2026-02-01T10:10:00Z")
        );
        inbound.setMissingDataReasons(new ArrayList<>(List.of("BRIDGE_ON_CHAIN_LEG_NOT_FOUND")));

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(inbound), List.of(outbound));

        int paired = service.reconcileOrphanInbounds(25);

        assertThat(paired).isZero();
        verify(normalizedTransactionRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("NEW-08 guardrail: dust inbound against a large source fails USD-value proximity")
    void crossAssetDustInboundFailsValueProximity() {
        NormalizedTransaction outbound = bridgeLegWithUsd(
                "out",
                OUT_HASH,
                NetworkId.UNICHAIN,
                NormalizedTransactionType.BRIDGE_OUT,
                "USDC",
                "-2050.040045",
                "2050.040045",
                Instant.parse("2026-02-01T10:00:00Z")
        );
        NormalizedTransaction inbound = bridgeLegWithUsd(
                "in",
                IN_HASH,
                NetworkId.KATANA,
                NormalizedTransactionType.BRIDGE_IN,
                "ETH",
                "0.0000002",
                "0.001",
                Instant.parse("2026-02-01T10:00:02Z")
        );
        inbound.setMissingDataReasons(new ArrayList<>(List.of("BRIDGE_ON_CHAIN_LEG_NOT_FOUND")));

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(inbound), List.of(outbound));

        int paired = service.reconcileOrphanInbounds(25);

        assertThat(paired).isZero();
        verify(normalizedTransactionRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("NEW-08 guardrail: cross-asset pair with unresolvable destination USD abstains")
    void crossAssetUnresolvableUsdAbstains() {
        NormalizedTransaction outbound = bridgeLegWithUsd(
                "out",
                OUT_HASH,
                NetworkId.UNICHAIN,
                NormalizedTransactionType.BRIDGE_OUT,
                "USDC",
                "-2050.040045",
                "2050.040045",
                Instant.parse("2026-02-01T10:00:00Z")
        );
        NormalizedTransaction inbound = bridgeLeg(
                "in",
                IN_HASH,
                NetworkId.KATANA,
                NormalizedTransactionType.BRIDGE_IN,
                "ETH",
                "0.452894",
                Instant.parse("2026-02-01T10:00:02Z")
        );
        inbound.setMissingDataReasons(new ArrayList<>(List.of("BRIDGE_ON_CHAIN_LEG_NOT_FOUND")));

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(inbound), List.of(outbound));

        int paired = service.reconcileOrphanInbounds(25);

        assertThat(paired).isZero();
        verify(normalizedTransactionRepository, never()).saveAll(any());
    }

    // FB-04: peg-neutral stablecoin corridor. Evidence anchors (audit §A.2 "Cross-network pairs the
    // linker missed") are carried as test-only hash prefixes — never as runtime decision keys.
    @Test
    @DisplayName("FB-04: USDT0 OPTIMISM -> USDC UNICHAIN links as value-equivalent stablecoins (cross-symbol, unpriced inbound endpoint, cc=false settlement carry, $0 realized)")
    void pegStablecoinCrossSymbolUsdt0ToUsdcAcrossNetworks() {
        // anchor: OUT 0xc9052c41… OPTIMISM USDT0 1.0 -> IN 0x6c233a93… UNICHAIN USDC 0.9968, 2s
        String outHash = anchoredHash("c9052c41");
        String inHash = anchoredHash("6c233a93");
        NormalizedTransaction outbound = bridgeLeg(
                "out", outHash, NetworkId.OPTIMISM, NormalizedTransactionType.BRIDGE_OUT,
                "USDT0", "-1.0", Instant.parse("2026-02-01T10:00:00Z"));
        // UNICHAIN inbound has no USD quote (bridge endpoint without a live price): quantity is the
        // $1 proxy, so value-equivalence still resolves.
        NormalizedTransaction inbound = bridgeLeg(
                "in", inHash, NetworkId.UNICHAIN, NormalizedTransactionType.BRIDGE_IN,
                "USDC", "0.9968", Instant.parse("2026-02-01T10:00:02Z"));
        inbound.setMissingDataReasons(new ArrayList<>(List.of("BRIDGE_ON_CHAIN_LEG_NOT_FOUND")));

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(inbound), List.of(outbound));

        int paired = service.reconcileOrphanInbounds(25);

        assertThat(paired).isEqualTo(1);
        String expectedCorrelation = "bridge:crossnet:" + outHash;
        assertThat(outbound.getCorrelationId()).isEqualTo(expectedCorrelation);
        assertThat(inbound.getCorrelationId()).isEqualTo(expectedCorrelation);
        // Distinct families (USDT vs USDC) → asset-converting settlement carry, not plain continuity.
        assertThat(outbound.getContinuityCandidate()).isFalse();
        assertThat(inbound.getContinuityCandidate()).isFalse();
        assertThat(outbound.getMatchedCounterparty()).isEqualTo(inHash);
        assertThat(inbound.getMatchedCounterparty()).isEqualTo(outHash);
        // Both principals retagged to price-less TRANSFER → basis carry, no market SELL/BUY.
        assertThat(outbound.getFlows().getFirst().getRole()).isEqualTo(NormalizedLegRole.TRANSFER);
        assertThat(inbound.getFlows().getFirst().getRole()).isEqualTo(NormalizedLegRole.TRANSFER);
        assertThat(outbound.getFlows().getFirst().getUnitPriceUsd()).isNull();
        assertThat(inbound.getFlows().getFirst().getUnitPriceUsd()).isNull();
    }

    @Test
    @DisplayName("FB-04: USDT0 -> USDC pair 24m apart links (inside the 2h stablecoin window, beyond the 180s cross-asset window)")
    void pegStablecoinCrossSymbolLinksWithinTwoHourWindow() {
        // anchor: OUT 0xd83708e3… OPTIMISM USDT0 895.04 -> IN 0xbeac83e9… UNICHAIN USDC 895.05, 24m
        String outHash = anchoredHash("d83708e3");
        String inHash = anchoredHash("beac83e9");
        NormalizedTransaction outbound = bridgeLeg(
                "out", outHash, NetworkId.OPTIMISM, NormalizedTransactionType.BRIDGE_OUT,
                "USDT0", "-895.04", Instant.parse("2026-02-01T10:00:00Z"));
        NormalizedTransaction inbound = bridgeLeg(
                "in", inHash, NetworkId.UNICHAIN, NormalizedTransactionType.BRIDGE_IN,
                "USDC", "895.05", Instant.parse("2026-02-01T10:24:00Z"));
        inbound.setMissingDataReasons(new ArrayList<>(List.of("BRIDGE_ON_CHAIN_LEG_NOT_FOUND")));

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(inbound), List.of(outbound));

        int paired = service.reconcileOrphanInbounds(25);

        assertThat(paired).isEqualTo(1);
        assertThat(inbound.getCorrelationId()).isEqualTo("bridge:crossnet:" + outHash);
    }

    @Test
    @DisplayName("FB-04: USDC OPTIMISM -> USDC BASE links as same-symbol continuity (cc=true), small value")
    void pegStablecoinSameSymbolOptimismToBaseSmall() {
        // anchor: OUT 0xce9a7182… OPTIMISM USDC 2.0 -> IN 0x436255b4… BASE USDC 1.998, 2s
        String outHash = anchoredHash("ce9a7182");
        String inHash = anchoredHash("436255b4");
        NormalizedTransaction outbound = bridgeLeg(
                "out", outHash, NetworkId.OPTIMISM, NormalizedTransactionType.BRIDGE_OUT,
                "USDC", "-2.0", Instant.parse("2026-02-01T10:00:00Z"));
        NormalizedTransaction inbound = bridgeLeg(
                "in", inHash, NetworkId.BASE, NormalizedTransactionType.BRIDGE_IN,
                "USDC", "1.998", Instant.parse("2026-02-01T10:00:02Z"));
        inbound.setMissingDataReasons(new ArrayList<>(List.of("BRIDGE_ON_CHAIN_LEG_NOT_FOUND")));

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(inbound), List.of(outbound));

        int paired = service.reconcileOrphanInbounds(25);

        assertThat(paired).isEqualTo(1);
        assertThat(outbound.getCorrelationId()).isEqualTo("bridge:crossnet:" + outHash);
        assertThat(inbound.getCorrelationId()).isEqualTo("bridge:crossnet:" + outHash);
        assertThat(outbound.getContinuityCandidate()).isTrue();
        assertThat(inbound.getContinuityCandidate()).isTrue();
        assertThat(outbound.getFlows().getFirst().getRole()).isEqualTo(NormalizedLegRole.TRANSFER);
        assertThat(inbound.getFlows().getFirst().getRole()).isEqualTo(NormalizedLegRole.TRANSFER);
    }

    @Test
    @DisplayName("FB-04: USDC OPTIMISM -> USDC BASE links for a larger 6m-apart pair (cc=true)")
    void pegStablecoinSameSymbolOptimismToBaseLarge() {
        // anchor: OUT 0x3acfa497… OPTIMISM USDC 896.03 -> IN 0xc17de389… BASE USDC 895.98, 6m
        String outHash = anchoredHash("3acfa497");
        String inHash = anchoredHash("c17de389");
        NormalizedTransaction outbound = bridgeLeg(
                "out", outHash, NetworkId.OPTIMISM, NormalizedTransactionType.BRIDGE_OUT,
                "USDC", "-896.03", Instant.parse("2026-02-01T10:00:00Z"));
        NormalizedTransaction inbound = bridgeLeg(
                "in", inHash, NetworkId.BASE, NormalizedTransactionType.BRIDGE_IN,
                "USDC", "895.98", Instant.parse("2026-02-01T10:06:00Z"));
        inbound.setMissingDataReasons(new ArrayList<>(List.of("BRIDGE_ON_CHAIN_LEG_NOT_FOUND")));

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(inbound), List.of(outbound));

        int paired = service.reconcileOrphanInbounds(25);

        assertThat(paired).isEqualTo(1);
        assertThat(inbound.getCorrelationId()).isEqualTo("bridge:crossnet:" + outHash);
        assertThat(inbound.getContinuityCandidate()).isTrue();
    }

    @Test
    @DisplayName("FB-04 guardrail: value-mismatched stablecoin pair (37x) abstains — no mislink")
    void pegStablecoinValueMismatchAbstains() {
        NormalizedTransaction outbound = bridgeLeg(
                "out", anchoredHash("aaaa1111"), NetworkId.OPTIMISM, NormalizedTransactionType.BRIDGE_OUT,
                "USDC", "-1.0", Instant.parse("2026-02-01T10:00:00Z"));
        NormalizedTransaction inbound = bridgeLeg(
                "in", anchoredHash("bbbb2222"), NetworkId.BASE, NormalizedTransactionType.BRIDGE_IN,
                "USDC", "37.0", Instant.parse("2026-02-01T10:00:02Z"));
        inbound.setMissingDataReasons(new ArrayList<>(List.of("BRIDGE_ON_CHAIN_LEG_NOT_FOUND")));

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(inbound), List.of(outbound));

        int paired = service.reconcileOrphanInbounds(25);

        assertThat(paired).isZero();
        verify(normalizedTransactionRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("FB-04 guardrail: stablecoin pair outside the 2h window abstains")
    void pegStablecoinOutsideTwoHourWindowAbstains() {
        NormalizedTransaction outbound = bridgeLeg(
                "out", anchoredHash("cccc3333"), NetworkId.OPTIMISM, NormalizedTransactionType.BRIDGE_OUT,
                "USDT0", "-895.04", Instant.parse("2026-02-01T07:00:00Z"));
        NormalizedTransaction inbound = bridgeLeg(
                "in", anchoredHash("dddd4444"), NetworkId.UNICHAIN, NormalizedTransactionType.BRIDGE_IN,
                "USDC", "895.05", Instant.parse("2026-02-01T10:00:00Z"));
        inbound.setMissingDataReasons(new ArrayList<>(List.of("BRIDGE_ON_CHAIN_LEG_NOT_FOUND")));

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(inbound), List.of(outbound));

        int paired = service.reconcileOrphanInbounds(25);

        assertThat(paired).isZero();
        verify(normalizedTransactionRepository, never()).saveAll(any());
    }

    private static String anchoredHash(String prefix) {
        StringBuilder builder = new StringBuilder("0x").append(prefix.toLowerCase());
        while (builder.length() < 66) {
            builder.append('0');
        }
        return builder.toString();
    }

    private static NormalizedTransaction bridgeLegWithUsd(
            String id,
            String txHash,
            NetworkId networkId,
            NormalizedTransactionType type,
            String assetSymbol,
            String quantity,
            String valueUsd,
            Instant timestamp
    ) {
        NormalizedTransaction transaction = bridgeLeg(id, txHash, networkId, type, assetSymbol, quantity, timestamp);
        transaction.getFlows().getFirst().setValueUsd(new BigDecimal(valueUsd));
        return transaction;
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
