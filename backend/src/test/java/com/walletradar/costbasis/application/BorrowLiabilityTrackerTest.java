package com.walletradar.costbasis.application;

import com.walletradar.costbasis.application.replay.handler.BorrowReplayHandler;
import com.walletradar.costbasis.application.replay.handler.RepayReplayHandler;
import com.walletradar.costbasis.application.replay.persistence.LedgerPointCollector;
import com.walletradar.costbasis.application.replay.planning.PassThroughCorridorPlanner;
import com.walletradar.costbasis.application.replay.state.BorrowLiabilityReplayContext;
import com.walletradar.costbasis.application.replay.state.ReplayExecutionState;
import com.walletradar.costbasis.application.replay.support.GenericFlowReplayEngine;
import com.walletradar.costbasis.application.replay.support.ReplayAssetSupport;
import com.walletradar.costbasis.application.replay.support.ReplayFlowSupport;
import com.walletradar.costbasis.domain.BorrowLiability;
import com.walletradar.costbasis.domain.BorrowLiabilityRepository;
import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BorrowLiabilityTrackerTest {

    private static final String UNIVERSE_ID = "n19-universe";
    private static final Instant EVENT_TIME = Instant.parse("2026-03-25T12:00:00Z");

    @Mock
    private BorrowLiabilityRepository repository;

    private BorrowLiabilityTracker tracker;
    private BorrowReplayHandler borrowHandler;
    private RepayReplayHandler repayHandler;
    private ReplayFlowSupport flowSupport;
    private Map<String, BorrowLiability> book;
    private BorrowLiabilityReplayContext liabilityContext;
    private ReplayExecutionState replayState;

    @BeforeEach
    void setUp() {
        tracker = new BorrowLiabilityTracker(repository);
        lenient().when(repository.findByUniverseId(anyString())).thenReturn(List.of());
        GenericFlowReplayEngine engine = new GenericFlowReplayEngine();
        flowSupport = new ReplayFlowSupport(engine);
        ReplayAssetSupport assetSupport = new ReplayAssetSupport();
        borrowHandler = new BorrowReplayHandler(tracker, assetSupport, flowSupport);
        repayHandler = new RepayReplayHandler(tracker, assetSupport, flowSupport);
        book = new HashMap<>();
        liabilityContext = new BorrowLiabilityReplayContext(UNIVERSE_ID, book, new HashSet<>());
        replayState = new ReplayExecutionState(
                new PassThroughCorridorPlanner().buildPlan(List.of(), assetSupport::assetKey),
                new LedgerPointCollector(UNIVERSE_ID, new java.util.ArrayList<>(), Instant.now()),
                null,
                liabilityContext
        );
    }

    @Test
    void mntStyleRoundtripZeroesRealisedPnlAndClosesLiabilities() {
        NormalizedTransaction borrow1050 = loanTx("order-a", NormalizedTransactionType.BORROW, "1050", "0.65");
        NormalizedTransaction borrow199 = loanTx("order-b", NormalizedTransactionType.BORROW, "199", "0.70");
        NormalizedTransaction repay199 = loanTx("order-b", NormalizedTransactionType.REPAY, "199", "0.72");
        NormalizedTransaction repay1050 = loanTx("order-a", NormalizedTransactionType.REPAY, "1050", "0.65");

        apply(borrow1050);
        apply(borrow199);
        apply(repay199);
        apply(repay1050);

        assertThat(repay199.getFlows().getFirst().getRealisedPnlUsd()).isEqualByComparingTo("0");
        assertThat(repay1050.getFlows().getFirst().getRealisedPnlUsd()).isEqualByComparingTo("0");

        BorrowLiability liabilityA = book.get(BorrowLiability.compositeId(UNIVERSE_ID, "order-a"));
        BorrowLiability liabilityB = book.get(BorrowLiability.compositeId(UNIVERSE_ID, "order-b"));
        assertThat(liabilityA.getQtyOpen()).isEqualByComparingTo("0");
        assertThat(liabilityB.getQtyOpen()).isEqualByComparingTo("0");
        assertThat(liabilityA.getStatus()).isEqualTo("CLOSED");
        assertThat(liabilityB.getStatus()).isEqualTo("CLOSED");
    }

    @Test
    void excessRepayRealisesOnlyResidualAtMarket() {
        NormalizedTransaction borrow = loanTx("order-excess", NormalizedTransactionType.BORROW, "199", "0.70");
        NormalizedTransaction repay = loanTx("order-excess", NormalizedTransactionType.REPAY, "200", "0.72");
        apply(borrow);
        apply(repay);

        assertThat(repay.getFlows().getFirst().getRealisedPnlUsd()).isEqualByComparingTo("0.02");
        BorrowLiability liability = book.get(BorrowLiability.compositeId(UNIVERSE_ID, "order-excess"));
        assertThat(liability.getQtyOpen()).isEqualByComparingTo("0");
    }

    @Test
    void partialRepayLeavesOpenQtyAndZeroRealisedOnMatchedPortion() {
        NormalizedTransaction borrow = loanTx("order-partial", NormalizedTransactionType.BORROW, "500", "1.00");
        NormalizedTransaction repay = loanTx("order-partial", NormalizedTransactionType.REPAY, "200", "1.05");
        apply(borrow);
        apply(repay);

        assertThat(repay.getFlows().getFirst().getRealisedPnlUsd()).isEqualByComparingTo("0");
        BorrowLiability liability = book.get(BorrowLiability.compositeId(UNIVERSE_ID, "order-partial"));
        assertThat(liability.getQtyOpen()).isEqualByComparingTo("300");
        assertThat(liability.getStatus()).isEqualTo("PARTIAL");
    }

    private void apply(NormalizedTransaction transaction) {
        NormalizedTransaction.Flow flow = transaction.getFlows().getFirst();
        if (transaction.getType() == NormalizedTransactionType.BORROW) {
            borrowHandler.apply(transaction, flow, 0, replayState);
        } else {
            repayHandler.apply(transaction, flow, 0, replayState);
        }
    }

    private static NormalizedTransaction loanTx(
            String orderId,
            NormalizedTransactionType type,
            String qty,
            String unitPriceUsd
    ) {
        NormalizedTransaction transaction = new NormalizedTransaction();
        transaction.setId(type.name().toLowerCase() + ":" + orderId + ":" + qty);
        transaction.setCorrelationId(orderId);
        transaction.setNetworkId(null);
        transaction.setWalletAddress("BYBIT:33625378:UTA");
        transaction.setType(type);
        transaction.setBlockTimestamp(EVENT_TIME);
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setAssetSymbol("MNT");
        flow.setAccountRef("BYBIT:33625378:UTA");
        flow.setUnitPriceUsd(new BigDecimal(unitPriceUsd));
        flow.setPriceSource(PriceSource.COINGECKO);
        if (type == NormalizedTransactionType.BORROW) {
            flow.setRole(NormalizedLegRole.BUY);
            flow.setQuantityDelta(new BigDecimal(qty));
        } else {
            flow.setRole(NormalizedLegRole.SELL);
            flow.setQuantityDelta(new BigDecimal(qty).negate());
        }
        transaction.setFlows(List.of(flow));
        return transaction;
    }
}
