package com.walletradar.application.costbasis.application.replay.support;

import com.walletradar.application.costbasis.application.BorrowLiabilityTracker;
import com.walletradar.application.costbasis.application.replay.model.AssetKey;
import com.walletradar.application.costbasis.application.replay.state.BorrowLiabilityReplayContext;
import com.walletradar.application.costbasis.application.replay.state.PositionStore;
import com.walletradar.application.costbasis.application.replay.state.ReplayExecutionState;
import com.walletradar.application.costbasis.domain.BorrowLiability;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.transaction.normalized.LeverageBorrowAnnotation;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.application.costbasis.support.leverage.LeverageAcquisitionDetector;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the ADR-028 replay hook: a leveraged buy registers exactly one synthetic USD liability of
 * size {@code marketValue(collateral) − consideration} and adds no asset lot, while ordinary swaps
 * and non-divergent acquisitions register nothing.
 */
class LeverageBorrowReplayHookTest {

    private static final String UNIVERSE = "universe-1";
    private static final String WALLET = "0x1111111111111111111111111111111111111111";
    private static final String CMETH = "0xe6829d9a7ee3040e1276fa75293bde931859e8fa";
    private static final String USDC = "0xb97ef9ef8734c71904d8002f8b6bc66dd9c48a6e";
    private static final String CORRELATION_ID = "evm-lev:MANTLE:" + CMETH + ":" + WALLET;

    private final BorrowLiabilityTracker tracker = new BorrowLiabilityTracker(null);
    private final LeverageBorrowReplayHook hook =
            new LeverageBorrowReplayHook(new LeverageAcquisitionDetector(), tracker);

    @Test
    void recordsSingleSyntheticBorrowForLeveragedBuy() {
        Map<String, BorrowLiability> book = new LinkedHashMap<>();
        NormalizedTransaction tx = leveragedBuy(
                pricedBuy(CMETH, "cmETH", "0.86155", "2845.00"),
                pricedSell(USDC, "USDC", "1005.30", "1005.30")
        );

        hook.applyIfLeverage(tx, replayState(book));

        assertThat(book).hasSize(1);
        BorrowLiability liability = book.values().iterator().next();
        assertThat(liability.getAsset()).isEqualTo("USD");
        assertThat(liability.getPortfolioAvcoAtOpen()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(liability.getQtyOpen()).isEqualByComparingTo(new BigDecimal("1839.70"));
        assertThat(liability.getStatus()).isEqualTo("OPEN");
    }

    @Test
    void recordsNoBorrowWhenGapBelowDivergenceThreshold() {
        Map<String, BorrowLiability> book = new LinkedHashMap<>();
        NormalizedTransaction tx = leveragedBuy(
                pricedBuy(CMETH, "cmETH", "0.30", "1010.00"),
                pricedSell(USDC, "USDC", "1000.00", "1000.00")
        );

        hook.applyIfLeverage(tx, replayState(book));

        assertThat(book).isEmpty();
    }

    @Test
    void skipsTransactionWithoutLeverageAnnotation() {
        Map<String, BorrowLiability> book = new LinkedHashMap<>();
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setType(NormalizedTransactionType.SWAP);
        tx.setWalletAddress(WALLET);
        tx.setBlockTimestamp(Instant.now());
        tx.setFlows(new ArrayList<>(List.of(
                pricedBuy(CMETH, "cmETH", "0.86155", "2845.00"),
                pricedSell(USDC, "USDC", "1005.30", "1005.30")
        )));

        hook.applyIfLeverage(tx, replayState(book));

        assertThat(book).isEmpty();
    }

    @Test
    void doesNotFailWhenLiabilityContextAbsent() {
        NormalizedTransaction tx = leveragedBuy(
                pricedBuy(CMETH, "cmETH", "0.86155", "2845.00"),
                pricedSell(USDC, "USDC", "1005.30", "1005.30")
        );
        ReplayExecutionState state = mock(ReplayExecutionState.class);
        when(state.borrowLiabilityContext()).thenReturn(null);

        hook.applyIfLeverage(tx, state);
        // No exception, nothing to assert beyond the no-op completing.
    }

    @Test
    void closesSyntheticLeverageLiabilityWhenCollateralFullyDrained() {
        Map<String, BorrowLiability> book = new LinkedHashMap<>();
        seedLeverageLiability(book, new BigDecimal("2323.23"));
        PositionStore positions = new PositionStore();
        // Collateral round-tripped out: cmETH position at the leverage wallet ends at zero.
        positions.position(collateralKey(CMETH)).setQuantity(BigDecimal.ZERO);
        // Unrelated FAMILY:ETH holding still present — must not block the close.
        positions.position(collateralKey("0xeac30ed8609f564ae65c809c4bf42db2ff426d2c"))
                .setQuantity(new BigDecimal("3.06"));

        hook.closeDrainedLeverageLiabilities(stateWithPositions(book, positions));

        BorrowLiability liability = book.values().iterator().next();
        assertThat(liability.getQtyOpen()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(liability.getStatus()).isEqualTo("CLOSED");
        assertThat(liability.getClosedAt()).isNotNull();
    }

    @Test
    void closesSyntheticLeverageLiabilityWhenCollateralPositionAbsent() {
        Map<String, BorrowLiability> book = new LinkedHashMap<>();
        seedLeverageLiability(book, new BigDecimal("2323.23"));

        hook.closeDrainedLeverageLiabilities(stateWithPositions(book, new PositionStore()));

        BorrowLiability liability = book.values().iterator().next();
        assertThat(liability.getQtyOpen()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(liability.getStatus()).isEqualTo("CLOSED");
    }

    @Test
    void leavesSyntheticLeverageLiabilityOpenWhenCollateralStillHeld() {
        Map<String, BorrowLiability> book = new LinkedHashMap<>();
        seedLeverageLiability(book, new BigDecimal("2323.23"));
        PositionStore positions = new PositionStore();
        positions.position(collateralKey(CMETH)).setQuantity(new BigDecimal("0.5"));

        hook.closeDrainedLeverageLiabilities(stateWithPositions(book, positions));

        BorrowLiability liability = book.values().iterator().next();
        assertThat(liability.getQtyOpen()).isEqualByComparingTo(new BigDecimal("2323.23"));
        assertThat(liability.getStatus()).isEqualTo("OPEN");
    }

    @Test
    void doesNotCloseNonLeverageLiabilities() {
        Map<String, BorrowLiability> book = new LinkedHashMap<>();
        BorrowLiability aaveDebt = new BorrowLiability();
        String orderId = "evm:AVALANCHE:0xfb00ac187a8eb5afae4eace434f493eb62672df7:" + WALLET;
        aaveDebt.setCompositeId(BorrowLiability.compositeId(UNIVERSE, orderId));
        aaveDebt.setUniverseId(UNIVERSE);
        aaveDebt.setOrderId(orderId);
        aaveDebt.setAsset("AVAX");
        aaveDebt.setQtyOpen(new BigDecimal("390.00"));
        aaveDebt.setQtyBorrowed(new BigDecimal("390.00"));
        aaveDebt.setStatus("OPEN");
        book.put(aaveDebt.getCompositeId(), aaveDebt);

        hook.closeDrainedLeverageLiabilities(stateWithPositions(book, new PositionStore()));

        assertThat(aaveDebt.getQtyOpen()).isEqualByComparingTo(new BigDecimal("390.00"));
        assertThat(aaveDebt.getStatus()).isEqualTo("OPEN");
    }

    private void seedLeverageLiability(Map<String, BorrowLiability> book, BigDecimal principal) {
        BorrowLiability liability = new BorrowLiability();
        liability.setCompositeId(BorrowLiability.compositeId(UNIVERSE, CORRELATION_ID));
        liability.setUniverseId(UNIVERSE);
        liability.setOrderId(CORRELATION_ID);
        liability.setAccountRef(WALLET);
        liability.setAsset("USD");
        liability.setQtyBorrowed(principal);
        liability.setQtyOpen(principal);
        liability.setPortfolioAvcoAtOpen(BigDecimal.ONE);
        liability.setPortfolioAvcoSource(PriceSource.STABLECOIN);
        liability.setOpenedAt(Instant.now());
        liability.setLastTouchedAt(Instant.now());
        liability.setStatus("OPEN");
        book.put(liability.getCompositeId(), liability);
    }

    private static AssetKey collateralKey(String contract) {
        return new AssetKey(WALLET, NetworkId.MANTLE, contract, "cmETH", "FAMILY:ETH");
    }

    private ReplayExecutionState stateWithPositions(Map<String, BorrowLiability> book, PositionStore positions) {
        Set<String> dirty = new LinkedHashSet<>();
        BorrowLiabilityReplayContext context = new BorrowLiabilityReplayContext(UNIVERSE, book, dirty);
        ReplayExecutionState state = mock(ReplayExecutionState.class);
        when(state.borrowLiabilityContext()).thenReturn(context);
        when(state.positions()).thenReturn(positions);
        return state;
    }

    private NormalizedTransaction leveragedBuy(NormalizedTransaction.Flow... flows) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setType(NormalizedTransactionType.SWAP);
        tx.setWalletAddress(WALLET);
        tx.setBlockTimestamp(Instant.now());
        tx.setFlows(new ArrayList<>(List.of(flows)));
        LeverageBorrowAnnotation.write(tx, true, "LEVERAGE_ROUTER_SELECTOR", CORRELATION_ID, CMETH, "cmETH");
        return tx;
    }

    private ReplayExecutionState replayState(Map<String, BorrowLiability> book) {
        Set<String> dirty = new LinkedHashSet<>();
        BorrowLiabilityReplayContext context = new BorrowLiabilityReplayContext(UNIVERSE, book, dirty);
        ReplayExecutionState state = mock(ReplayExecutionState.class);
        when(state.borrowLiabilityContext()).thenReturn(context);
        return state;
    }

    private static NormalizedTransaction.Flow pricedBuy(String contract, String symbol, String qty, String valueUsd) {
        return pricedFlow(NormalizedLegRole.BUY, contract, symbol, new BigDecimal(qty), valueUsd);
    }

    private static NormalizedTransaction.Flow pricedSell(String contract, String symbol, String qty, String valueUsd) {
        return pricedFlow(NormalizedLegRole.SELL, contract, symbol, new BigDecimal(qty).negate(), valueUsd);
    }

    private static NormalizedTransaction.Flow pricedFlow(
            NormalizedLegRole role,
            String contract,
            String symbol,
            BigDecimal qty,
            String valueUsd
    ) {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(role);
        flow.setAssetContract(contract);
        flow.setAssetSymbol(symbol);
        flow.setQuantityDelta(qty);
        flow.setValueUsd(new BigDecimal(valueUsd));
        flow.setPriceSource(PriceSource.COINGECKO);
        return flow;
    }
}
