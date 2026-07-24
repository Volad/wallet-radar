package com.walletradar.application.costbasis.application.replay.handler;

import com.walletradar.application.costbasis.application.BorrowLiabilityTracker;
import com.walletradar.application.costbasis.application.replay.model.AssetKey;
import com.walletradar.application.costbasis.application.replay.model.PositionState;
import com.walletradar.application.costbasis.application.replay.persistence.LedgerPointCollector;
import com.walletradar.application.costbasis.application.replay.state.BorrowLiabilityReplayContext;
import com.walletradar.application.costbasis.application.replay.state.ReplayExecutionState;
import com.walletradar.application.costbasis.application.replay.support.GenericFlowReplayEngine;
import com.walletradar.application.costbasis.application.replay.support.ReplayAssetSupport;
import com.walletradar.application.costbasis.application.replay.support.ReplayFlowSupport;
import com.walletradar.application.costbasis.domain.BorrowLiability;
import com.walletradar.domain.common.NetworkId;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/**
 * ADR-040 §5 (2026-07-18 amendment) + ADR-012 §D3 regression suite: with borrowed principal now
 * entering both lanes at market-at-borrow basis, disposing borrowed units (REPAY / transfer-out /
 * swap sell leg) must book ≈ $0 phantom Net-lane realized income. The historical net-$0 borrow
 * basis booked ≈ marketAvco × qty of phantom Net income on every such disposal.
 */
@ExtendWith(MockitoExtension.class)
class RepayReplayHandlerTest {

    private static final String UNIVERSE = "universe-1";
    private static final String WALLET = "0xaabbccddeeff0011223344556677889900112233";
    private static final BigDecimal DUST = new BigDecimal("0.0001");

    @Mock
    private ReplayAssetSupport assetSupport;

    private BorrowReplayHandler borrowHandler;
    private RepayReplayHandler repayHandler;
    private ReplayFlowSupport flowSupport;
    private ReplayExecutionState replayState;
    private AssetKey mntKey;

    @BeforeEach
    void setUp() {
        GenericFlowReplayEngine engine = new GenericFlowReplayEngine(null);
        flowSupport = new ReplayFlowSupport(engine);
        BorrowLiabilityTracker tracker = new BorrowLiabilityTracker(null);
        borrowHandler = new BorrowReplayHandler(tracker, assetSupport, flowSupport, null);
        repayHandler = new RepayReplayHandler(tracker, assetSupport, flowSupport);

        mntKey = new AssetKey(WALLET, NetworkId.MANTLE, null, "MNT", "SYMBOL:MNT");

        LedgerPointCollector collector = new LedgerPointCollector(UNIVERSE, new ArrayList<>(), Instant.now());
        Map<String, BorrowLiability> book = new LinkedHashMap<>();
        Set<String> dirty = new LinkedHashSet<>();
        BorrowLiabilityReplayContext liabilityContext = new BorrowLiabilityReplayContext(UNIVERSE, book, dirty);
        replayState = new ReplayExecutionState(null, collector, null, liabilityContext);

        lenient().when(assetSupport.assetKey(any(), any())).thenReturn(mntKey);
    }

    @Test
    void borrowThenRepayRoundTripNetsToZeroInBothLanes() {
        // MNT round-trip: borrow 1600 @ $1.15, repay 1600 @ $1.147625 (full liability match).
        NormalizedTransaction borrow = borrowTx("loan-mnt");
        borrowHandler.apply(borrow, buyFlow(new BigDecimal("1600"), new BigDecimal("1.15")), 0, replayState);

        PositionState position = replayState.position(mntKey);
        // Sanity: borrowed principal carries equal Market and Net basis (ADR-040 §5).
        assertThat(position.perWalletNetAvco()).isEqualByComparingTo(position.perWalletAvco());

        NormalizedTransaction repay = repayTx("loan-mnt");
        repayHandler.apply(repay, sellFlow(new BigDecimal("-1600"), new BigDecimal("1.147625")), 0, replayState);

        // Only interest/fees would survive; principal round-trip nets ≈ $0 in BOTH lanes
        // (the historical net-$0 borrow basis booked +$1,840 phantom Net income here).
        assertThat(position.quantity()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(position.totalRealisedPnlUsd().abs()).isLessThan(DUST);
        assertThat(position.totalNetRealisedPnlUsd().abs()).isLessThan(DUST);
    }

    @Test
    void borrowThenRepayOnBlendedPoolZeroesNetLaneResidual() {
        // D2 defense-in-depth: a blended pool where Net AVCO != Market AVCO at repay time. The prior
        // priorRealised-only correction left a (marketAvco − netAvco)·matchedQty leak in the Net
        // lane; D2 removes it so the matched principal is basis-neutral in BOTH lanes.
        PositionState position = replayState.position(mntKey);
        position.setQuantity(new BigDecimal("1000"));
        position.setUncoveredQuantity(BigDecimal.ZERO);
        position.setTotalCostBasisUsd(new BigDecimal("1000"));   // Market AVCO $1.00
        position.setNetTotalCostBasisUsd(new BigDecimal("600")); // Net AVCO $0.60 (reward-diluted)
        position.setPerWalletAvco(new BigDecimal("1.00"));
        position.setPerWalletNetAvco(new BigDecimal("0.60"));

        NormalizedTransaction borrow = borrowTx("loan-blend");
        borrowHandler.apply(borrow, buyFlow(new BigDecimal("1000"), new BigDecimal("1.15")), 0, replayState);

        // Blended: Market AVCO = 2150/2000 = 1.075; Net AVCO = 1750/2000 = 0.875.
        assertThat(position.perWalletAvco()).isEqualByComparingTo(new BigDecimal("1.075"));
        assertThat(position.perWalletNetAvco()).isEqualByComparingTo(new BigDecimal("0.875"));

        BigDecimal marketBefore = position.totalRealisedPnlUsd();
        BigDecimal netBefore = position.totalNetRealisedPnlUsd();

        NormalizedTransaction repay = repayTx("loan-blend");
        repayHandler.apply(repay, sellFlow(new BigDecimal("-1000"), new BigDecimal("1.15")), 0, replayState);

        // Matched principal realizes ≈ $0 in BOTH lanes; no Net-lane residual survives.
        assertThat(position.totalRealisedPnlUsd().subtract(marketBefore).abs()).isLessThan(DUST);
        assertThat(position.totalNetRealisedPnlUsd().subtract(netBefore).abs()).isLessThan(DUST);
    }

    @Test
    void borrowThenExternalTransferOutBooksNoPhantomNetIncome() {
        // Regression for zkSync USDC / TON / MNT: a borrowed-principal EXTERNAL_TRANSFER_OUT SELL leg
        // disposed at market-at-borrow price nets ≈ $0 in the Net lane (was ≈ marketAvco × qty).
        NormalizedTransaction borrow = borrowTx("loan-transfer");
        borrowHandler.apply(borrow, buyFlow(new BigDecimal("1600"), new BigDecimal("1.15")), 0, replayState);

        PositionState position = replayState.position(mntKey);
        flowSupport.applySell(sellFlow(new BigDecimal("-1600"), new BigDecimal("1.15")), position);

        assertThat(position.quantity()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(position.totalRealisedPnlUsd().abs()).isLessThan(DUST);
        assertThat(position.totalNetRealisedPnlUsd().abs()).isLessThan(DUST);
    }

    @Test
    void borrowThenSwapSellLegBooksNoPhantomNetIncomeAndTracksRealPriceMove() {
        // D5 regression: a borrowed-principal swap SELL leg books no phantom Net income. With Net
        // AVCO == Market AVCO (market-at-borrow), the Net realized tracks the genuine price move —
        // NOT ≈ marketAvco × qty of phantom income. (Acquired-asset net-basis propagation lives in
        // the SWAP handler; this asserts the borrowed sell leg carries no phantom.)
        NormalizedTransaction borrow = borrowTx("loan-swap");
        borrowHandler.apply(borrow, buyFlow(new BigDecimal("1600"), new BigDecimal("1.15")), 0, replayState);

        PositionState position = replayState.position(mntKey);
        // Swap the borrowed principal out at $1.20 (price moved +$0.05).
        flowSupport.applySell(sellFlow(new BigDecimal("-1600"), new BigDecimal("1.20")), position);

        // True realized = (1.20 − 1.15) × 1600 = $80 in BOTH lanes (Net tracks Market, no phantom).
        BigDecimal expected = new BigDecimal("80");
        assertThat(position.totalRealisedPnlUsd()).isEqualByComparingTo(expected);
        assertThat(position.totalNetRealisedPnlUsd()).isEqualByComparingTo(expected);
        // Net == Market: the borrowed sell leg carries no phantom Net income.
        assertThat(position.totalNetRealisedPnlUsd()).isEqualByComparingTo(position.totalRealisedPnlUsd());
    }

    private NormalizedTransaction borrowTx(String correlationId) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setType(NormalizedTransactionType.BORROW);
        tx.setWalletAddress(WALLET);
        tx.setNetworkId(NetworkId.MANTLE);
        tx.setCorrelationId(correlationId);
        tx.setBlockTimestamp(Instant.parse("2025-06-17T12:00:00Z"));
        return tx;
    }

    private NormalizedTransaction repayTx(String correlationId) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setType(NormalizedTransactionType.REPAY);
        tx.setWalletAddress(WALLET);
        tx.setNetworkId(NetworkId.MANTLE);
        tx.setCorrelationId(correlationId);
        tx.setBlockTimestamp(Instant.parse("2025-06-18T12:00:00Z"));
        return tx;
    }

    private NormalizedTransaction.Flow buyFlow(BigDecimal qty, BigDecimal price) {
        return flow(NormalizedLegRole.BUY, qty, price);
    }

    private NormalizedTransaction.Flow sellFlow(BigDecimal qty, BigDecimal price) {
        return flow(NormalizedLegRole.SELL, qty, price);
    }

    private NormalizedTransaction.Flow flow(NormalizedLegRole role, BigDecimal qty, BigDecimal price) {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(role);
        flow.setAssetSymbol("MNT");
        flow.setQuantityDelta(qty);
        flow.setUnitPriceUsd(price);
        flow.setPriceSource(PriceSource.BINANCE);
        return flow;
    }
}
