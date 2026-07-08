package com.walletradar.application.costbasis.application;

import com.walletradar.application.costbasis.application.replay.handler.BorrowReplayHandler;
import com.walletradar.application.costbasis.application.replay.handler.RepayReplayHandler;
import com.walletradar.application.costbasis.application.replay.persistence.LedgerPointCollector;
import com.walletradar.application.costbasis.application.replay.planning.PassThroughCorridorPlanner;
import com.walletradar.application.costbasis.application.replay.state.BorrowLiabilityReplayContext;
import com.walletradar.application.costbasis.application.replay.state.ReplayExecutionState;
import com.walletradar.application.costbasis.application.replay.support.GenericFlowReplayEngine;
import com.walletradar.application.costbasis.application.replay.support.ReplayAssetSupport;
import com.walletradar.application.costbasis.application.replay.support.ReplayFlowSupport;
import com.walletradar.application.costbasis.domain.BorrowLiability;
import com.walletradar.application.costbasis.domain.BorrowLiabilityRepository;
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
    private ReplayAssetSupport assetSupport;
    private Map<String, BorrowLiability> book;
    private BorrowLiabilityReplayContext liabilityContext;
    private ReplayExecutionState replayState;

    @BeforeEach
    void setUp() {
        tracker = new BorrowLiabilityTracker(repository);
        lenient().when(repository.findByUniverseId(anyString())).thenReturn(List.of());
        GenericFlowReplayEngine engine = new GenericFlowReplayEngine(null);
        flowSupport = new ReplayFlowSupport(engine);
        assetSupport = new ReplayAssetSupport();
        borrowHandler = new BorrowReplayHandler(tracker, assetSupport, flowSupport, null);
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

    @Test
    void bybitPledgeBorrowAndRepayWithUnrelatedCorrelationIdsStillNetToZero() {
        // R-4: real Bybit shape — borrow carries an order UUID, repay carries the server-issued
        // uta_pledge-loan-server id. Without the shared revolving key these never match and the
        // repay fabricates realised PnL. With source=BYBIT they collapse to bybit-pledge:<uid>:<asset>.
        NormalizedTransaction borrow = bybitPledgeTx(
                "BYBIT-33625378:TRANSACTION_LOG:33625378-109167781-819ff960-bce9-4721-a175-7638789eeed3",
                NormalizedTransactionType.BORROW, "MNT", "150", "0.8439");
        NormalizedTransaction repay = bybitPledgeTx(
                "BYBIT-33625378:TRANSACTION_LOG:33625378-109167781-uta_pledge-loan-server_1910732310958247939",
                NormalizedTransactionType.REPAY, "MNT", "150", "0.72");

        apply(borrow);
        apply(repay);

        assertThat(repay.getFlows().getFirst().getRealisedPnlUsd()).isEqualByComparingTo("0");
        BorrowLiability liability = book.get(
                BorrowLiability.compositeId(UNIVERSE_ID, "bybit-pledge:33625378:MNT"));
        assertThat(liability).isNotNull();
        assertThat(liability.getQtyOpen()).isEqualByComparingTo("0");
        assertThat(liability.getStatus()).isEqualTo("CLOSED");
    }

    @Test
    void bybitPledgeKeepsMntAndDogsLiabilitiesSeparate() {
        apply(bybitPledgeTx("o1", NormalizedTransactionType.BORROW, "MNT", "150", "0.84"));
        apply(bybitPledgeTx("o2", NormalizedTransactionType.BORROW, "DOGS", "1300000", "0.000145"));
        apply(bybitPledgeTx("o3", NormalizedTransactionType.REPAY, "DOGS", "1300000", "0.000150"));

        BorrowLiability mnt = book.get(BorrowLiability.compositeId(UNIVERSE_ID, "bybit-pledge:33625378:MNT"));
        BorrowLiability dogs = book.get(BorrowLiability.compositeId(UNIVERSE_ID, "bybit-pledge:33625378:DOGS"));
        assertThat(mnt.getQtyOpen()).isEqualByComparingTo("150");
        assertThat(mnt.getStatus()).isEqualTo("OPEN");
        assertThat(dogs.getQtyOpen()).isEqualByComparingTo("0");
        assertThat(dogs.getStatus()).isEqualTo("CLOSED");
    }

    @Test
    void unpricedMntBorrowCarriesMarketAtBorrowBasisInsteadOfZero() {
        // F-5(b): a 3,532 MNT borrow with NO embedded flow price must NOT enter the spot pool at $0
        // (which would blend the pool down to a sub-market ~$0.72). The block-time market price is
        // resolved and applied to both the asset basis and the liability avco.
        com.walletradar.application.costbasis.application.replay.support.ReplayMarketAuthority marketAuthority =
                org.mockito.Mockito.mock(
                        com.walletradar.application.costbasis.application.replay.support.ReplayMarketAuthority.class);
        org.mockito.Mockito.when(marketAuthority.resolve(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.Optional.of(
                        new com.walletradar.application.costbasis.application.replay.support.ReplayMarketAuthority.ResolvedMarketPrice(
                                new BigDecimal("1.58"),
                                PriceSource.COINGECKO,
                                com.walletradar.application.costbasis.application.replay.support.ReplayMarketAuthority
                                        .ResolvedMarketPrice.Authority.HISTORICAL_CACHE)));
        BorrowReplayHandler marketBorrowHandler =
                new BorrowReplayHandler(tracker, assetSupport, flowSupport, marketAuthority);

        NormalizedTransaction borrow = unpricedLoanTx("order-mnt-market", "3532");
        NormalizedTransaction.Flow flow = borrow.getFlows().getFirst();
        marketBorrowHandler.apply(borrow, flow, 0, replayState);

        BorrowLiability liability = book.get(BorrowLiability.compositeId(UNIVERSE_ID, "order-mnt-market"));
        assertThat(liability).isNotNull();
        assertThat(liability.getPortfolioAvcoAtOpen()).isEqualByComparingTo("1.58");

        var position = replayState.position(assetSupport.assetKey(borrow, flow));
        assertThat(position.quantity()).isEqualByComparingTo("3532");
        assertThat(position.uncoveredQuantity()).isEqualByComparingTo("0");
        // 3532 * 1.58 = 5580.56 — market-at-borrow basis, not $0.
        assertThat(position.totalCostBasisUsd()).isEqualByComparingTo("5580.56");
    }

    private static NormalizedTransaction unpricedLoanTx(String orderId, String qty) {
        NormalizedTransaction transaction = new NormalizedTransaction();
        transaction.setId("borrow:" + orderId + ":" + qty);
        transaction.setCorrelationId(orderId);
        transaction.setNetworkId(com.walletradar.domain.common.NetworkId.MANTLE);
        transaction.setWalletAddress("0xwallet");
        transaction.setType(NormalizedTransactionType.BORROW);
        transaction.setBlockTimestamp(EVENT_TIME);
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setAssetSymbol("MNT");
        flow.setAssetContract("0xnative-mnt");
        flow.setAccountRef("0xwallet");
        flow.setRole(NormalizedLegRole.BUY);
        flow.setQuantityDelta(new BigDecimal(qty));
        transaction.setFlows(List.of(flow));
        return transaction;
    }

    private static NormalizedTransaction bybitPledgeTx(
            String correlationId,
            NormalizedTransactionType type,
            String asset,
            String qty,
            String unitPriceUsd
    ) {
        NormalizedTransaction transaction = new NormalizedTransaction();
        transaction.setId(type.name().toLowerCase() + ":" + correlationId);
        transaction.setCorrelationId(correlationId);
        transaction.setSource(com.walletradar.domain.transaction.normalized.NormalizedTransactionSource.BYBIT);
        transaction.setWalletAddress("BYBIT:33625378:UTA");
        transaction.setType(type);
        transaction.setBlockTimestamp(EVENT_TIME);
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setAssetSymbol(asset);
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
