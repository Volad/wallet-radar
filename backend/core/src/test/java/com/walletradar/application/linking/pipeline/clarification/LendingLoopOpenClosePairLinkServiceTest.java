package com.walletradar.application.linking.pipeline.clarification;

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
class LendingLoopOpenClosePairLinkServiceTest {

    private static final String WALLET = "0xloopwallet";
    private static final String OPEN_HASH = "0xOPENhash1111111111111111111111111111111111111111111111111111aaaa";
    private static final String OPEN2_HASH = "0xOPENhash2222222222222222222222222222222222222222222222222222bbbb";

    @Mock
    private MongoOperations mongoOperations;
    @Mock
    private NormalizedTransactionRepository normalizedTransactionRepository;

    private LendingLoopOpenClosePairLinkService service;

    @BeforeEach
    void setUp() {
        service = new LendingLoopOpenClosePairLinkService(mongoOperations, normalizedTransactionRepository);
        lenient().when(normalizedTransactionRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("links a CLOSE to its still-open OPEN with a per-open-instance correlation id")
    void linksCloseToOpen() {
        NormalizedTransaction open = loopLeg("open", OPEN_HASH, NetworkId.BASE,
                NormalizedTransactionType.LENDING_LOOP_OPEN, "Euler", "-0.9", "2026-01-01T00:00:00Z");
        NormalizedTransaction close = loopLeg("close", "0xclose", NetworkId.UNICHAIN,
                NormalizedTransactionType.LENDING_LOOP_CLOSE, "Euler", "0.9", "2026-03-01T00:00:00Z");

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(open), List.of());

        boolean paired = service.pair(close);

        assertThat(paired).isTrue();
        String expected = "lending-loop:" + OPEN_HASH.toLowerCase();
        assertThat(open.getCorrelationId()).isEqualTo(expected);
        assertThat(close.getCorrelationId()).isEqualTo(expected);
        // continuityCandidate must be left untouched (the borrow leg must not be routed to corr-family:).
        assertThat(open.getContinuityCandidate()).isNull();
        assertThat(close.getContinuityCandidate()).isNull();
    }

    @Test
    @DisplayName("does not gate on network equality (open BASE, close UNICHAIN still pair)")
    void pairsAcrossNetworks() {
        NormalizedTransaction open = loopLeg("open", OPEN_HASH, NetworkId.BASE,
                NormalizedTransactionType.LENDING_LOOP_OPEN, "Euler", "-0.9", "2026-01-01T00:00:00Z");
        NormalizedTransaction decrease = loopLeg("dec", "0xdec", NetworkId.UNICHAIN,
                NormalizedTransactionType.LENDING_LOOP_DECREASE, "Euler", "0.4", "2026-02-01T00:00:00Z");

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(open), List.of());

        assertThat(service.pair(decrease)).isTrue();
        assertThat(decrease.getCorrelationId()).isEqualTo("lending-loop:" + OPEN_HASH.toLowerCase());
    }

    @Test
    @DisplayName("1→N: one OPEN links to multiple DECREASE legs and a final CLOSE with a shared corr id")
    void oneOpenLinksManyDecreasesAndClose() {
        NormalizedTransaction open = loopLeg("open", OPEN_HASH, NetworkId.BASE,
                NormalizedTransactionType.LENDING_LOOP_OPEN, "Euler", "-0.9", "2026-01-01T00:00:00Z");
        NormalizedTransaction dec1 = loopLeg("dec1", "0xdec1", NetworkId.BASE,
                NormalizedTransactionType.LENDING_LOOP_DECREASE, "Euler", "0.2", "2026-01-10T00:00:00Z");
        NormalizedTransaction dec2 = loopLeg("dec2", "0xdec2", NetworkId.BASE,
                NormalizedTransactionType.LENDING_LOOP_DECREASE, "Euler", "0.2", "2026-01-20T00:00:00Z");
        NormalizedTransaction close = loopLeg("close", "0xclose", NetworkId.BASE,
                NormalizedTransactionType.LENDING_LOOP_CLOSE, "Euler", "0.5", "2026-02-01T00:00:00Z");

        // find sequence: candidates, then (opens, closes) per candidate.
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(
                        List.of(dec1, dec2, close),
                        List.of(open), List.of(),
                        List.of(open), List.of(),
                        List.of(open), List.of());

        int linked = service.reconcileOutstandingLoops(50);

        String expected = "lending-loop:" + OPEN_HASH.toLowerCase();
        assertThat(linked).isEqualTo(3);
        assertThat(dec1.getCorrelationId()).isEqualTo(expected);
        assertThat(dec2.getCorrelationId()).isEqualTo(expected);
        assertThat(close.getCorrelationId()).isEqualTo(expected);
    }

    @Test
    @DisplayName("reopened position gets a NEW correlation anchored on the second OPEN")
    void reopenedPositionGetsNewCorrelation() {
        NormalizedTransaction open1 = loopLeg("open1", OPEN_HASH, NetworkId.BASE,
                NormalizedTransactionType.LENDING_LOOP_OPEN, "Euler", "-0.9", "2026-01-01T00:00:00Z");
        NormalizedTransaction close1 = loopLeg("close1", "0xclose1", NetworkId.BASE,
                NormalizedTransactionType.LENDING_LOOP_CLOSE, "Euler", "0.9", "2026-02-01T00:00:00Z");
        NormalizedTransaction open2 = loopLeg("open2", OPEN2_HASH, NetworkId.BASE,
                NormalizedTransactionType.LENDING_LOOP_OPEN, "Euler", "-0.7", "2026-03-01T00:00:00Z");
        NormalizedTransaction close2 = loopLeg("close2", "0xclose2", NetworkId.BASE,
                NormalizedTransactionType.LENDING_LOOP_CLOSE, "Euler", "0.7", "2026-04-01T00:00:00Z");

        // pair(close2): OPEN query returns both opens (desc), CLOSE query returns close1 (before close2).
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(open2, open1), List.of(close1));

        assertThat(service.pair(close2)).isTrue();
        assertThat(close2.getCorrelationId()).isEqualTo("lending-loop:" + OPEN2_HASH.toLowerCase());
        assertThat(open2.getCorrelationId()).isEqualTo("lending-loop:" + OPEN2_HASH.toLowerCase());
        // open1 (already closed by close1) must NOT be re-stamped by this pass.
        assertThat(open1.getCorrelationId()).isNull();
    }

    @Test
    @DisplayName("two overlapping loops (no close between two OPENs) must NOT mis-pair")
    void overlappingLoopsAbstain() {
        NormalizedTransaction open1 = loopLeg("open1", OPEN_HASH, NetworkId.BASE,
                NormalizedTransactionType.LENDING_LOOP_OPEN, "Euler", "-0.9", "2026-01-01T00:00:00Z");
        NormalizedTransaction open2 = loopLeg("open2", OPEN2_HASH, NetworkId.BASE,
                NormalizedTransactionType.LENDING_LOOP_OPEN, "Euler", "-0.7", "2026-01-15T00:00:00Z");
        NormalizedTransaction decrease = loopLeg("dec", "0xdec", NetworkId.BASE,
                NormalizedTransactionType.LENDING_LOOP_DECREASE, "Euler", "0.3", "2026-02-01T00:00:00Z");

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(open2, open1), List.of());

        assertThat(service.pair(decrease)).isFalse();
        assertThat(decrease.getCorrelationId()).isNull();
        verify(normalizedTransactionRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("different protocol does not pair")
    void differentProtocolDoesNotPair() {
        NormalizedTransaction open = loopLeg("open", OPEN_HASH, NetworkId.BASE,
                NormalizedTransactionType.LENDING_LOOP_OPEN, "Aave", "-0.9", "2026-01-01T00:00:00Z");
        NormalizedTransaction close = loopLeg("close", "0xclose", NetworkId.BASE,
                NormalizedTransactionType.LENDING_LOOP_CLOSE, "Euler", "0.9", "2026-02-01T00:00:00Z");

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(open), List.of());

        assertThat(service.pair(close)).isFalse();
        assertThat(close.getCorrelationId()).isNull();
        verify(normalizedTransactionRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("no matching OPEN → abstain")
    void noOpenAbstains() {
        NormalizedTransaction close = loopLeg("close", "0xclose", NetworkId.BASE,
                NormalizedTransactionType.LENDING_LOOP_CLOSE, "Euler", "0.9", "2026-02-01T00:00:00Z");

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of());

        assertThat(service.pair(close)).isFalse();
        verify(normalizedTransactionRepository, never()).saveAll(any());
    }

    private static NormalizedTransaction loopLeg(
            String id,
            String txHash,
            NetworkId networkId,
            NormalizedTransactionType type,
            String protocol,
            String collateralQty,
            String timestamp
    ) {
        NormalizedTransaction transaction = new NormalizedTransaction();
        transaction.setId(id);
        transaction.setTxHash(txHash);
        transaction.setWalletAddress(WALLET);
        transaction.setSource(NormalizedTransactionSource.ON_CHAIN);
        transaction.setNetworkId(networkId);
        transaction.setType(type);
        transaction.setProtocolName(protocol);
        transaction.setStatus(NormalizedTransactionStatus.CONFIRMED);
        transaction.setBlockTimestamp(Instant.parse(timestamp));
        transaction.setTransactionIndex(1);
        NormalizedTransaction.Flow collateral = new NormalizedTransaction.Flow();
        collateral.setRole(NormalizedLegRole.TRANSFER);
        collateral.setAssetSymbol("ETH");
        collateral.setQuantityDelta(new BigDecimal(collateralQty));
        // A borrowed USDC leg accompanies the collateral; it must never become the position identity.
        NormalizedTransaction.Flow debt = new NormalizedTransaction.Flow();
        debt.setRole(NormalizedLegRole.TRANSFER);
        debt.setAssetSymbol("USDC");
        debt.setQuantityDelta(new BigDecimal(collateralQty).signum() < 0 ? new BigDecimal("2050") : new BigDecimal("-789"));
        transaction.setFlows(List.of(collateral, debt));
        return transaction;
    }
}
