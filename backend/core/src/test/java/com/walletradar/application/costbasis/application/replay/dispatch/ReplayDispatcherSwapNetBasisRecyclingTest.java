package com.walletradar.application.costbasis.application.replay.dispatch;

import com.walletradar.application.costbasis.application.replay.handler.AsyncSpotOrderReplayHandler;
import com.walletradar.application.costbasis.application.replay.handler.BorrowReplayHandler;
import com.walletradar.application.costbasis.application.replay.handler.BybitVenueInternalReplayHandler;
import com.walletradar.application.costbasis.application.replay.handler.FamilyEquivalentCustodyReplayHandler;
import com.walletradar.application.costbasis.application.replay.handler.GenericAsyncLifecycleReplayHandler;
import com.walletradar.application.costbasis.application.replay.handler.GmxLpEntryReplayHandler;
import com.walletradar.application.costbasis.application.replay.handler.LiquidStakingReplayHandler;
import com.walletradar.application.costbasis.application.replay.handler.LpReceiptEntryReplayHandler;
import com.walletradar.application.costbasis.application.replay.handler.PositionScopedLpExitReplayHandler;
import com.walletradar.application.costbasis.application.replay.handler.RepayReplayHandler;
import com.walletradar.application.costbasis.application.replay.handler.TransferReplayHandler;
import com.walletradar.application.costbasis.application.replay.model.PassThroughCorridorPlan;
import com.walletradar.application.costbasis.application.replay.persistence.LedgerPointCollector;
import com.walletradar.application.costbasis.application.replay.planning.ReplayRoutingDecision;
import com.walletradar.application.costbasis.application.replay.planning.ReplayTransactionRouter;
import com.walletradar.application.costbasis.application.replay.state.ReplayExecutionState;
import com.walletradar.application.costbasis.application.replay.support.ContinuityCarryService;
import com.walletradar.application.costbasis.application.replay.support.CounterpartyBasisPoolReplayHook;
import com.walletradar.application.costbasis.application.replay.support.GenericFlowReplayEngine;
import com.walletradar.application.costbasis.application.replay.support.ReplayAssetSupport;
import com.walletradar.application.costbasis.application.replay.support.ReplayFlowSupport;
import com.walletradar.application.costbasis.application.replay.support.ReplayMarketAuthority;
import com.walletradar.application.costbasis.application.replay.support.ReplayPendingTransferKeyFactory;
import com.walletradar.application.costbasis.application.replay.support.ReplayPendingTransferMatcher;
import com.walletradar.application.costbasis.application.replay.support.ReplaySettlementAllocator;
import com.walletradar.application.costbasis.application.replay.support.ReplayTransferClassifier;
import com.walletradar.application.costbasis.domain.AssetLedgerPoint;
import com.walletradar.application.costbasis.support.AcquisitionFeeCapitalizationPolicy;
import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.testsupport.TransferReplayHandlerFixtures;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * FB-01 / ADR-082 — NET-lane "re-base on realize" (no basis recycling) for realizing swaps between
 * distinct canonical instruments.
 *
 * <p>Reproduces the cmETH → PT-cmETH → cmETH recycling defect and asserts:</p>
 * <ul>
 *   <li>each realizing leg re-bases the acquired NET basis to the market acquisition cost
 *       (net cbΔ == market cbΔ), so the pre-loop discount is never recycled;</li>
 *   <li>NET realized == Market realized on every priced disposal (no triple-realization / income
 *       fabrication);</li>
 *   <li>the Market lane is byte-identical to the deterministic market arithmetic (never touched);</li>
 *   <li>genuine reward carries (net ≪ market) STILL inherit the low net basis (preserved).</li>
 * </ul>
 */
class ReplayDispatcherSwapNetBasisRecyclingTest {

    private static final MathContext MC = MathContext.DECIMAL128;

    /**
     * Round-trip cmETH → PT-cmETH → cmETH → (final sell). The disposed cmETH lot carries NO reward
     * discount (net == market), so every realizing leg must re-base the acquired NET basis to market.
     */
    @Test
    void realizingRoundTripReBasesNetToMarketAndDoesNotRecycleBasis() {
        ReplayAssetSupport assetSupport = new ReplayAssetSupport();
        ReplayMarketAuthority marketAuthority = mock(ReplayMarketAuthority.class);
        when(marketAuthority.resolve(any(), any())).thenReturn(Optional.empty());
        ReplayDispatcher dispatcher = buildDispatcher(assetSupport, marketAuthority);

        List<AssetLedgerPoint> points = new ArrayList<>();
        ReplayExecutionState replayState = new ReplayExecutionState(
                new PassThroughCorridorPlan(Map.of(), Map.of()),
                new LedgerPointCollector("u1", points, Instant.now()),
                null, null, null
        );

        // Seed cmETH: 1.0 @ market avco $1500, net avco $1500 (net == market: no reward discount).
        NormalizedTransaction swap1 = swap("swap1", "CMETH", "-1.0", "3000", "PT", "1.0", "3000");
        var cmethKey = assetSupport.assetKey(swap1, swap1.getFlows().get(0));
        replayState.position(cmethKey).setQuantity(new BigDecimal("1.0"));
        replayState.position(cmethKey).setTotalCostBasisUsd(new BigDecimal("1500"));
        replayState.position(cmethKey).setNetTotalCostBasisUsd(new BigDecimal("1500"));
        replayState.position(cmethKey).setPerWalletAvco(new BigDecimal("1500"));
        replayState.position(cmethKey).setPerWalletNetAvco(new BigDecimal("1500"));

        dispatcher.dispatch(swap1, replayState);
        // PT-cmETH SELL @ 2900 → cmETH BUY @ 2900 (PT depreciates: realized −100).
        dispatcher.dispatch(swap("swap2", "PT", "-1.0", "2900", "CMETH", "1.0", "2900"), replayState);
        // Final cmETH disposal @ 3100 (single SELL leg).
        dispatcher.dispatch(swapSingleSell("swap3", "CMETH", "-1.0", "3100"), replayState);

        // --- Leg 1: PT-cmETH ACQUIRE re-bases NET to market ($3000), NOT the recycled $1500. ---
        AssetLedgerPoint ptAcquire = point(points, "PT", AssetLedgerPoint.BasisEffect.ACQUIRE);
        assertThat(ptAcquire.getCostBasisDeltaUsd()).isEqualByComparingTo("3000");
        assertThat(ptAcquire.getNetCostBasisDeltaUsd()).isEqualByComparingTo("3000");

        // --- Leg 1: cmETH DISPOSE realizes NET == Market (+1500). ---
        AssetLedgerPoint cmethDispose1 = pointForTx(points, "swap1", "CMETH", AssetLedgerPoint.BasisEffect.DISPOSE);
        assertThat(cmethDispose1.getRealisedPnlDeltaUsd()).isEqualByComparingTo("1500");
        assertThat(cmethDispose1.getNetRealisedPnlDeltaUsd()).isEqualByComparingTo("1500");

        // --- Leg 2: PT DISPOSE realizes NET == Market (−100), NOT the fabricated +1400 artifact. ---
        AssetLedgerPoint ptDispose = point(points, "PT", AssetLedgerPoint.BasisEffect.DISPOSE);
        assertThat(ptDispose.getRealisedPnlDeltaUsd()).isEqualByComparingTo("-100");
        assertThat(ptDispose.getNetRealisedPnlDeltaUsd()).isEqualByComparingTo("-100");

        // --- Leg 2: cmETH ACQUIRE re-bases NET to market ($2900). ---
        AssetLedgerPoint cmethAcquire = pointForTx(points, "swap2", "CMETH", AssetLedgerPoint.BasisEffect.ACQUIRE);
        assertThat(cmethAcquire.getCostBasisDeltaUsd()).isEqualByComparingTo("2900");
        assertThat(cmethAcquire.getNetCostBasisDeltaUsd()).isEqualByComparingTo("2900");

        // --- Leg 3: final cmETH DISPOSE realizes NET == Market (+200), NOT +1600 artifact. ---
        AssetLedgerPoint cmethDispose3 = pointForTx(points, "swap3", "CMETH", AssetLedgerPoint.BasisEffect.DISPOSE);
        assertThat(cmethDispose3.getRealisedPnlDeltaUsd()).isEqualByComparingTo("200");
        assertThat(cmethDispose3.getNetRealisedPnlDeltaUsd()).isEqualByComparingTo("200");

        // --- FAMILY-level: Σ NET realized == Σ Market realized (net − market income ≈ $0). ---
        BigDecimal marketRealized = sum(points, AssetLedgerPoint::getRealisedPnlDeltaUsd);
        BigDecimal netRealized = sum(points, AssetLedgerPoint::getNetRealisedPnlDeltaUsd);
        assertThat(netRealized).isEqualByComparingTo(marketRealized);
        assertThat(netRealized).isEqualByComparingTo("1600"); // 1500 − 100 + 200

        // --- Invariant 0 ≤ net cbΔ ≤ market cbΔ on every priced ACQUIRE. ---
        for (AssetLedgerPoint acquire : pointsOf(points, AssetLedgerPoint.BasisEffect.ACQUIRE)) {
            assertThat(acquire.getNetCostBasisDeltaUsd()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
            assertThat(acquire.getNetCostBasisDeltaUsd()).isLessThanOrEqualTo(acquire.getCostBasisDeltaUsd());
        }
    }

    /**
     * Market-lane byte-invariance: the NET re-base must never change the Market lane. On a single
     * realizing swap the Market cbΔ / realized are exactly the deterministic market arithmetic,
     * independent of how the NET basis is seeded.
     */
    @Test
    void marketLaneIsUnchangedByNetReBaseOnRealizingSwap() {
        ReplayAssetSupport assetSupport = new ReplayAssetSupport();
        ReplayMarketAuthority marketAuthority = mock(ReplayMarketAuthority.class);
        when(marketAuthority.resolve(any(), any())).thenReturn(Optional.empty());
        ReplayDispatcher dispatcher = buildDispatcher(assetSupport, marketAuthority);

        List<AssetLedgerPoint> points = new ArrayList<>();
        ReplayExecutionState replayState = new ReplayExecutionState(
                new PassThroughCorridorPlan(Map.of(), Map.of()),
                new LedgerPointCollector("u1", points, Instant.now()),
                null, null, null
        );

        NormalizedTransaction swap = swap("swap1", "CMETH", "-1.0", "3000", "PT", "1.0", "3000");
        var cmethKey = assetSupport.assetKey(swap, swap.getFlows().get(0));
        replayState.position(cmethKey).setQuantity(new BigDecimal("1.0"));
        replayState.position(cmethKey).setTotalCostBasisUsd(new BigDecimal("1500"));
        replayState.position(cmethKey).setNetTotalCostBasisUsd(new BigDecimal("1500"));
        replayState.position(cmethKey).setPerWalletAvco(new BigDecimal("1500"));
        replayState.position(cmethKey).setPerWalletNetAvco(new BigDecimal("1500"));

        dispatcher.dispatch(swap, replayState);

        // Market lane is the ground truth: dispose relieves $1500 basis, realizes +$1500; acquire
        // books $3000 market basis. These hold regardless of the NET-lane seeding rule.
        AssetLedgerPoint dispose = point(points, "CMETH", AssetLedgerPoint.BasisEffect.DISPOSE);
        assertThat(dispose.getCostBasisDeltaUsd()).isEqualByComparingTo("-1500");
        assertThat(dispose.getRealisedPnlDeltaUsd()).isEqualByComparingTo("1500");
        AssetLedgerPoint acquire = point(points, "PT", AssetLedgerPoint.BasisEffect.ACQUIRE);
        assertThat(acquire.getCostBasisDeltaUsd()).isEqualByComparingTo("3000");
    }

    /**
     * Genuine reward carry preserved: the disposed lot has net ≪ market (a real zero-cost reward
     * discount). The discriminator must PRESERVE the min(released, market) carry so the acquired lot
     * inherits the low net basis — "rewards reduce cost for free".
     */
    @Test
    void genuineRewardDiscountedSwapStillInheritsLowNetBasis() {
        ReplayAssetSupport assetSupport = new ReplayAssetSupport();
        ReplayMarketAuthority marketAuthority = mock(ReplayMarketAuthority.class);
        when(marketAuthority.resolve(any(), any())).thenReturn(Optional.empty());
        ReplayDispatcher dispatcher = buildDispatcher(assetSupport, marketAuthority);

        List<AssetLedgerPoint> points = new ArrayList<>();
        ReplayExecutionState replayState = new ReplayExecutionState(
                new PassThroughCorridorPlan(Map.of(), Map.of()),
                new LedgerPointCollector("u1", points, Instant.now()),
                null, null, null
        );

        // Reward-acquired ZRO: 10 units @ market avco $10 (basis $100), net avco $0 (basis $0).
        NormalizedTransaction swap = swap("swap1", "ZRO", "-10", "10", "TKN", "1", "100");
        var zroKey = assetSupport.assetKey(swap, swap.getFlows().get(0));
        replayState.position(zroKey).setQuantity(new BigDecimal("10"));
        replayState.position(zroKey).setTotalCostBasisUsd(new BigDecimal("100"));
        replayState.position(zroKey).setNetTotalCostBasisUsd(BigDecimal.ZERO);
        replayState.position(zroKey).setPerWalletAvco(new BigDecimal("10"));
        replayState.position(zroKey).setPerWalletNetAvco(BigDecimal.ZERO);

        dispatcher.dispatch(swap, replayState);

        // TKN acquired: market basis $100, NET basis inherits the reward-discounted $0 (net ≪ market).
        AssetLedgerPoint tknAcquire = point(points, "TKN", AssetLedgerPoint.BasisEffect.ACQUIRE);
        assertThat(tknAcquire.getCostBasisDeltaUsd()).isEqualByComparingTo("100");
        assertThat(tknAcquire.getNetCostBasisDeltaUsd()).isEqualByComparingTo("0");

        // The reward income is realized once at the disposal (net realized > market realized).
        AssetLedgerPoint zroDispose = point(points, "ZRO", AssetLedgerPoint.BasisEffect.DISPOSE);
        assertThat(zroDispose.getRealisedPnlDeltaUsd()).isEqualByComparingTo("0");
        assertThat(zroDispose.getNetRealisedPnlDeltaUsd()).isEqualByComparingTo("100");
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private static BigDecimal sum(List<AssetLedgerPoint> points,
                                  java.util.function.Function<AssetLedgerPoint, BigDecimal> extractor) {
        return points.stream()
                .map(extractor)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, (left, right) -> left.add(right, MC));
    }

    private static AssetLedgerPoint point(List<AssetLedgerPoint> points, String symbol,
                                          AssetLedgerPoint.BasisEffect effect) {
        return points.stream()
                .filter(p -> symbol.equals(p.getAssetSymbol()) && p.getBasisEffect() == effect)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + effect + " point for " + symbol));
    }

    private static AssetLedgerPoint pointForTx(List<AssetLedgerPoint> points, String txId, String symbol,
                                               AssetLedgerPoint.BasisEffect effect) {
        return points.stream()
                .filter(p -> txId.equals(p.getNormalizedTransactionId())
                        && symbol.equals(p.getAssetSymbol()) && p.getBasisEffect() == effect)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + effect + " point for " + symbol + " in " + txId));
    }

    private static List<AssetLedgerPoint> pointsOf(List<AssetLedgerPoint> points, AssetLedgerPoint.BasisEffect effect) {
        return points.stream().filter(p -> p.getBasisEffect() == effect).toList();
    }

    /** SWAP with a SELL (outbound, index 0) then a BUY (inbound, index 1) leg. */
    private static NormalizedTransaction swap(
            String id, String sellSymbol, String sellQty, String sellUnitPrice,
            String buySymbol, String buyQty, String buyUnitPrice
    ) {
        NormalizedTransaction tx = swapSingleSell(id, sellSymbol, sellQty, sellUnitPrice);
        NormalizedTransaction.Flow buy = new NormalizedTransaction.Flow();
        buy.setRole(NormalizedLegRole.BUY);
        buy.setAssetSymbol(buySymbol);
        buy.setAccountRef("wallet-a:SPOT");
        buy.setQuantityDelta(new BigDecimal(buyQty));
        buy.setUnitPriceUsd(new BigDecimal(buyUnitPrice));
        buy.setPriceSource(PriceSource.BINANCE);
        List<NormalizedTransaction.Flow> flows = new ArrayList<>(tx.getFlows());
        flows.add(buy);
        tx.setFlows(flows);
        return tx;
    }

    /** SWAP with a single SELL (outbound) leg. */
    private static NormalizedTransaction swapSingleSell(
            String id, String sellSymbol, String sellQty, String sellUnitPrice
    ) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId(id);
        tx.setSource(NormalizedTransactionSource.ON_CHAIN);
        tx.setType(NormalizedTransactionType.SWAP);
        tx.setWalletAddress("wallet-a");
        tx.setBlockTimestamp(Instant.parse("2025-07-31T00:00:00Z"));

        NormalizedTransaction.Flow sell = new NormalizedTransaction.Flow();
        sell.setRole(NormalizedLegRole.SELL);
        sell.setAssetSymbol(sellSymbol);
        sell.setAccountRef("wallet-a:SPOT");
        sell.setQuantityDelta(new BigDecimal(sellQty));
        sell.setUnitPriceUsd(new BigDecimal(sellUnitPrice));
        sell.setPriceSource(PriceSource.BINANCE);

        tx.setFlows(new ArrayList<>(List.of(sell)));
        return tx;
    }

    private ReplayDispatcher buildDispatcher(ReplayAssetSupport assetSupport, ReplayMarketAuthority marketAuthority) {
        GenericFlowReplayEngine engine = new GenericFlowReplayEngine(marketAuthority);
        ReplayFlowSupport flowSupport = new ReplayFlowSupport(engine);
        ContinuityCarryService carryService = new ContinuityCarryService(engine, flowSupport);
        ReplayPendingTransferKeyFactory keyFactory = new ReplayPendingTransferKeyFactory(assetSupport);
        ReplayTransferClassifier transferClassifier = new ReplayTransferClassifier(keyFactory);
        TransferReplayHandler transferReplayHandler = TransferReplayHandlerFixtures.handler(
                flowSupport, carryService, keyFactory, transferClassifier,
                new ReplayPendingTransferMatcher(), marketAuthority
        );
        BybitVenueInternalReplayHandler bybitVenueInternalReplayHandler =
                new BybitVenueInternalReplayHandler(transferClassifier, transferReplayHandler);
        LiquidStakingReplayHandler liquidStakingReplayHandler = new LiquidStakingReplayHandler(
                assetSupport, flowSupport, new ReplaySettlementAllocator(assetSupport, flowSupport)
        );
        FamilyEquivalentCustodyReplayHandler familyEquivalentCustodyReplayHandler =
                new FamilyEquivalentCustodyReplayHandler(assetSupport, flowSupport, carryService, keyFactory);
        ReplayTransactionRouter replayTransactionRouter = mock(ReplayTransactionRouter.class);
        when(replayTransactionRouter.route(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(ReplayRoutingDecision.generic());
        ReplayRouteHandlerRegistry replayRouteHandlerRegistry = ReplayRouteHandlerRegistryFactory.create(
                mock(com.walletradar.application.costbasis.application.replay.handler.EulerLoopReplayHandler.class),
                mock(GmxLpEntryReplayHandler.class),
                mock(LpReceiptEntryReplayHandler.class),
                mock(GenericAsyncLifecycleReplayHandler.class),
                mock(PositionScopedLpExitReplayHandler.class),
                liquidStakingReplayHandler,
                familyEquivalentCustodyReplayHandler
        );
        return new ReplayDispatcher(
                replayTransactionRouter, assetSupport, flowSupport, transferClassifier, keyFactory,
                replayRouteHandlerRegistry, mock(AcquisitionFeeCapitalizationPolicy.class),
                transferReplayHandler, bybitVenueInternalReplayHandler, liquidStakingReplayHandler,
                familyEquivalentCustodyReplayHandler, mock(GenericAsyncLifecycleReplayHandler.class),
                mock(GmxLpEntryReplayHandler.class), mock(LpReceiptEntryReplayHandler.class),
                mock(PositionScopedLpExitReplayHandler.class), mock(AsyncSpotOrderReplayHandler.class),
                mock(CounterpartyBasisPoolReplayHook.class),
                mock(com.walletradar.application.costbasis.application.replay.support.LeverageBorrowReplayHook.class),
                mock(BorrowReplayHandler.class), mock(RepayReplayHandler.class), marketAuthority
        );
    }
}
