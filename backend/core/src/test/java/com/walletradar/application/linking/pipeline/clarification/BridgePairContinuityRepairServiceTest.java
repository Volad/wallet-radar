package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.counterparty.CounterpartyType;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BridgePairContinuityRepairServiceTest {

    @Mock
    private MongoOperations mongoOperations;
    @Mock
    private NormalizedTransactionRepository normalizedTransactionRepository;

    private BridgePairContinuityRepairService service;

    @BeforeEach
    void setUp() {
        service = new BridgePairContinuityRepairService(mongoOperations, normalizedTransactionRepository);
        lenient().when(normalizedTransactionRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void repairsLegacySealedPairWithSameAssetFamily() {
        NormalizedTransaction outbound = bridgeOut("0xout", "USDC", "-100");
        outbound.setContinuityCandidate(false);
        outbound.setCorrelationId("bridge:lifi:0xout");
        outbound.getFlows().getFirst().setRole(NormalizedLegRole.SELL);
        outbound.getFlows().getFirst().setUnitPriceUsd(BigDecimal.ONE);

        NormalizedTransaction inbound = bridgeIn("0xin", NetworkId.ARBITRUM, "USDC", "100");
        inbound.setCorrelationId("bridge:lifi:0xout");
        inbound.setContinuityCandidate(true);

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(outbound))
                .thenReturn(List.of(inbound));

        int repaired = service.reconcileLegacySealedPairs(10);

        assertThat(repaired).isEqualTo(1);
        ArgumentCaptor<List<NormalizedTransaction>> captor = ArgumentCaptor.forClass(List.class);
        verify(normalizedTransactionRepository).saveAll(captor.capture());
        assertThat(outbound.getContinuityCandidate()).isTrue();
        assertThat(outbound.getFlows().getFirst().getRole()).isEqualTo(NormalizedLegRole.TRANSFER);
        assertThat(outbound.getFlows().getFirst().getUnitPriceUsd()).isNull();
        assertThat(outbound.getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_STAT);
        // F-1: a USD-stablecoin continuity inbound is routed to PENDING_PRICE so the replay
        // inbound-shortfall fallback has a $1 quote for any qty continuity carry leaves uncovered
        // (a fully-carried leg ignores it). The outbound carry leg stays PENDING_STAT.
        assertThat(inbound.getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
    }

    @Test
    void repairsLegacySealedPairWhenOutboundContinuityCandidateIsNull() {
        // After WS-1, newly classified MANTLE LI.FI BRIDGE_OUTs may have continuityCandidate=null
        // (seeded by seedSourceAnchorFromStatus which doesn't set continuityCandidate). Meanwhile
        // the paired BRIDGE_IN was repaired by reconcileLegacySealedInbounds to continuityCandidate=true.
        // loadLegacySealedOutbounds must use ne(true) so null is also picked up.
        NormalizedTransaction outbound = bridgeOut("0xout", "USDC", "-100");
        // continuityCandidate is deliberately left null (not set)
        outbound.setCorrelationId("bridge:lifi:0xout");
        outbound.getFlows().getFirst().setRole(NormalizedLegRole.SELL);

        NormalizedTransaction inbound = bridgeIn("0xin", NetworkId.ARBITRUM, "USDC", "100");
        inbound.setCorrelationId("bridge:lifi:0xout");
        inbound.setContinuityCandidate(true);

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(outbound))
                .thenReturn(List.of(inbound));

        int repaired = service.reconcileLegacySealedPairs(10);

        assertThat(repaired).isEqualTo(1);
        verify(normalizedTransactionRepository).saveAll(any());
        assertThat(outbound.getContinuityCandidate()).isTrue();
        assertThat(outbound.getFlows().getFirst().getRole()).isEqualTo(NormalizedLegRole.TRANSFER);
        assertThat(inbound.getContinuityCandidate()).isTrue();
    }

    @Test
    void repairsPairedInboundMissingCounterparty() {
        NormalizedTransaction outbound = bridgeOut("0xout", "ETH", "-0.000221");
        outbound.setCorrelationId("bridge:lifi:0xout");
        outbound.setMatchedCounterparty("0xin");
        outbound.getFlows().getFirst().setCounterpartyAddress("0x23981fc34e69eedfe2bd9a0a9fcb0719fe09dbfc");
        outbound.getFlows().getFirst().setCounterpartyType(CounterpartyType.BRIDGE);

        NormalizedTransaction inbound = bridgeIn("0xin", NetworkId.OPTIMISM, "ETH", "0.000197");
        inbound.setCorrelationId("bridge:lifi:0xout");
        inbound.setMatchedCounterparty("0xout");
        inbound.setStatus(NormalizedTransactionStatus.NEEDS_REVIEW);

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(inbound))
                .thenReturn(List.of(outbound));

        int repaired = service.reconcilePairedInboundCounterparty(10);

        assertThat(repaired).isEqualTo(1);
        assertThat(inbound.getFlows().getFirst().getCounterpartyAddress())
                .isEqualTo("0x23981fc34e69eedfe2bd9a0a9fcb0719fe09dbfc");
        assertThat(inbound.getFlows().getFirst().getCounterpartyType()).isEqualTo(CounterpartyType.BRIDGE);
        assertThat(inbound.getCounterpartyType()).isEqualTo(CounterpartyType.BRIDGE);
        assertThat(inbound.getStatus()).isNotEqualTo(NormalizedTransactionStatus.NEEDS_REVIEW);
    }

    @Test
    void skipsCrossAssetPair() {
        NormalizedTransaction outbound = bridgeOut("0xout", "USDC", "-100");
        outbound.setContinuityCandidate(false);
        outbound.setCorrelationId("bridge:lifi:0xout");

        NormalizedTransaction inbound = bridgeIn("0xin", NetworkId.OPTIMISM, "USDT0", "99");
        inbound.setCorrelationId("bridge:lifi:0xout");

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(outbound))
                .thenReturn(List.of(inbound));

        int repaired = service.reconcileLegacySealedPairs(10);

        assertThat(repaired).isZero();
        verify(normalizedTransactionRepository, never()).saveAll(any());
    }

    // ── reconcileLegacySealedInbounds ──────────────────────────────────────────

    @Test
    void reconcileLegacySealedInbounds_happyPath_repairsInboundOnly() {
        NormalizedTransaction outbound = bridgeOut("0xout", "ETH", "-1");
        outbound.setContinuityCandidate(true);
        outbound.setCorrelationId("bridge:lifi:0xout");
        outbound.setMatchedCounterparty("0xin");
        outbound.setStatus(NormalizedTransactionStatus.CONFIRMED);
        outbound.getFlows().getFirst().setCounterpartyAddress("0xbridge");
        outbound.getFlows().getFirst().setCounterpartyType(CounterpartyType.BRIDGE);

        NormalizedTransaction inbound = bridgeIn("0xin", NetworkId.ARBITRUM, "ETH", "1");
        inbound.setContinuityCandidate(false);
        inbound.setCorrelationId("bridge:lifi:0xout");
        inbound.setMatchedCounterparty("0xout");
        inbound.getFlows().getFirst().setRole(NormalizedLegRole.BUY);
        inbound.getFlows().getFirst().setUnitPriceUsd(BigDecimal.valueOf(3000));

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(inbound))
                .thenReturn(List.of(outbound));

        int repaired = service.reconcileLegacySealedInbounds(10);

        assertThat(repaired).isEqualTo(1);
        verify(normalizedTransactionRepository).saveAll(any());

        assertThat(inbound.getContinuityCandidate()).isTrue();
        assertThat(inbound.getFlows().getFirst().getRole()).isEqualTo(NormalizedLegRole.TRANSFER);
        assertThat(inbound.getFlows().getFirst().getUnitPriceUsd()).isNull();
        assertThat(inbound.getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_STAT);

        // BRIDGE_OUT status must NOT be changed
        assertThat(outbound.getStatus()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
    }

    @Test
    void reconcileLegacySealedInbounds_skipsAlreadyRepaired() {
        NormalizedTransaction outbound = bridgeOut("0xout", "ETH", "-1");
        outbound.setContinuityCandidate(true);
        outbound.setCorrelationId("bridge:lifi:0xout");

        NormalizedTransaction inbound = bridgeIn("0xin", NetworkId.ARBITRUM, "ETH", "1");
        inbound.setContinuityCandidate(true);  // already repaired
        inbound.setCorrelationId("bridge:lifi:0xout");
        inbound.setMatchedCounterparty("0xout");

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(inbound));

        int repaired = service.reconcileLegacySealedInbounds(10);

        assertThat(repaired).isZero();
        verify(normalizedTransactionRepository, never()).saveAll(any());
    }

    @Test
    void reconcileLegacySealedInbounds_skipsWhenNoPairedOutbound() {
        NormalizedTransaction inbound = bridgeIn("0xin", NetworkId.ARBITRUM, "ETH", "1");
        inbound.setContinuityCandidate(false);
        inbound.setCorrelationId("bridge:lifi:0xout");
        inbound.setMatchedCounterparty("0xout");

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(inbound))
                .thenReturn(List.of());  // no BRIDGE_OUT found

        int repaired = service.reconcileLegacySealedInbounds(10);

        assertThat(repaired).isZero();
        verify(normalizedTransactionRepository, never()).saveAll(any());
    }

    @Test
    void reconcileLegacySealedInbounds_skipsMismatchedAssets() {
        NormalizedTransaction outbound = bridgeOut("0xout", "ETH", "-1");
        outbound.setContinuityCandidate(true);
        outbound.setCorrelationId("bridge:lifi:0xout");

        NormalizedTransaction inbound = bridgeIn("0xin", NetworkId.ARBITRUM, "USDC", "2500");
        inbound.setContinuityCandidate(false);
        inbound.setCorrelationId("bridge:lifi:0xout");
        inbound.setMatchedCounterparty("0xout");

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(inbound))
                .thenReturn(List.of(outbound));

        int repaired = service.reconcileLegacySealedInbounds(10);

        assertThat(repaired).isZero();
        verify(normalizedTransactionRepository, never()).saveAll(any());
    }

    @Test
    void reconcileLegacySealedInbounds_idempotent() {
        NormalizedTransaction outbound = bridgeOut("0xout", "ETH", "-1");
        outbound.setContinuityCandidate(true);
        outbound.setCorrelationId("bridge:lifi:0xout");
        outbound.setMatchedCounterparty("0xin");
        outbound.getFlows().getFirst().setCounterpartyAddress("0xbridge");
        outbound.getFlows().getFirst().setCounterpartyType(CounterpartyType.BRIDGE);

        NormalizedTransaction inbound = bridgeIn("0xin", NetworkId.ARBITRUM, "ETH", "1");
        inbound.setContinuityCandidate(false);
        inbound.setCorrelationId("bridge:lifi:0xout");
        inbound.setMatchedCounterparty("0xout");
        inbound.getFlows().getFirst().setRole(NormalizedLegRole.BUY);

        // First call repairs the inbound
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(inbound))
                .thenReturn(List.of(outbound))
                // Second call: query returns empty because continuityCandidate is now true
                .thenReturn(List.of());

        int firstCall = service.reconcileLegacySealedInbounds(10);
        int secondCall = service.reconcileLegacySealedInbounds(10);

        assertThat(firstCall).isEqualTo(1);
        assertThat(secondCall).isZero();
    }

    @Test
    void applyContinuityRepairIsIdempotentWhenAlreadyContinuity() {
        NormalizedTransaction outbound = bridgeOut("0xout", "ETH", "-1");
        outbound.setContinuityCandidate(true);
        outbound.setStatus(NormalizedTransactionStatus.PENDING_STAT);
        outbound.getFlows().getFirst().setRole(NormalizedLegRole.TRANSFER);
        NormalizedTransaction inbound = bridgeIn("0xin", NetworkId.ARBITRUM, "ETH", "1");
        inbound.setContinuityCandidate(true);
        inbound.setStatus(NormalizedTransactionStatus.PENDING_STAT);
        inbound.getFlows().getFirst().setCounterpartyAddress("0xbridge");
        inbound.getFlows().getFirst().setCounterpartyType(CounterpartyType.BRIDGE);
        inbound.setCounterpartyAddress("0xbridge");
        inbound.setCounterpartyType(CounterpartyType.BRIDGE);

        boolean changed = BridgePairContinuityRepairService.applyContinuityRepair(
                outbound,
                inbound,
                Instant.parse("2026-01-01T00:00:00Z")
        );

        assertThat(changed).isFalse();
    }

    private static NormalizedTransaction bridgeOut(String hash, String symbol, String qty) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId("out-id");
        tx.setType(NormalizedTransactionType.BRIDGE_OUT);
        tx.setTxHash(hash);
        tx.setNetworkId(NetworkId.ETHEREUM);
        tx.setWalletAddress("0xwallet");
        tx.setBlockTimestamp(Instant.parse("2025-01-01T00:00:00Z"));
        tx.setStatus(NormalizedTransactionStatus.CONFIRMED);
        tx.setFlows(List.of(flow(symbol, qty, NormalizedLegRole.SELL)));
        return tx;
    }

    private static NormalizedTransaction bridgeIn(String hash, NetworkId networkId, String symbol, String qty) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId("in-id");
        tx.setType(NormalizedTransactionType.BRIDGE_IN);
        tx.setTxHash(hash);
        tx.setNetworkId(networkId);
        tx.setWalletAddress("0xwallet");
        tx.setBlockTimestamp(Instant.parse("2025-01-01T00:05:00Z"));
        tx.setStatus(NormalizedTransactionStatus.CONFIRMED);
        tx.setFlows(List.of(flow(symbol, qty, NormalizedLegRole.TRANSFER)));
        return tx;
    }

    private static NormalizedTransaction.Flow flow(String symbol, String qty, NormalizedLegRole role) {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setAssetSymbol(symbol);
        flow.setQuantityDelta(new BigDecimal(qty));
        flow.setRole(role);
        return flow;
    }
}
