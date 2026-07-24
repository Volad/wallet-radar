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
 * ADR-083 cluster-carry semantics for intra-cluster cross-canonical conversions. These conversions
 * (ETH↔mETH/cmETH, AVAX↔sAVAX, …) previously realized P&amp;L at market (ADR-054 §2 / ADR-082); under
 * ADR-083 they now carry basis on both lanes with realized PnL = 0. The dispatcher is wired with the
 * REAL {@link ReplayTransactionRouter} so these route through the {@code CLUSTER_CARRY} route exactly
 * as in production.
 */
class ReplayDispatcherCrossCanonicalStakingTest {

    /**
     * CC-AC-1 (2025-03-12 tx): Bybit ETH → CMETH STAKING_DEPOSIT now CARRIES basis. ETH books
     * REALLOCATE_OUT, CMETH books REALLOCATE_IN inheriting the disposed ETH basis; realized PnL = 0.
     */
    @Test
    void bybitEthToCmethStakingDepositCarriesBasisWithZeroRealizedPnl() {
        ReplayAssetSupport assetSupport = new ReplayAssetSupport();
        ReplayMarketAuthority marketAuthority = mock(ReplayMarketAuthority.class);
        when(marketAuthority.resolve(any(), any())).thenReturn(Optional.empty());
        ReplayDispatcher dispatcher = buildDispatcher(assetSupport, marketAuthority);

        List<AssetLedgerPoint> ledgerPoints = new ArrayList<>();
        ReplayExecutionState replayState = newReplayState(ledgerPoints);

        NormalizedTransaction stakingDeposit = bybitEthToCmethStakingDeposit();
        var ethKey = assetSupport.assetKey(stakingDeposit, stakingDeposit.getFlows().getFirst());
        replayState.position(ethKey).setQuantity(new BigDecimal("0.20"));
        replayState.position(ethKey).setTotalCostBasisUsd(new BigDecimal("600"));
        replayState.position(ethKey).setNetTotalCostBasisUsd(new BigDecimal("600"));
        replayState.position(ethKey).setPerWalletAvco(new BigDecimal("3000"));
        replayState.position(ethKey).setPerWalletNetAvco(new BigDecimal("3000"));

        dispatcher.dispatch(stakingDeposit, replayState);

        assertThat(ledgerPoints)
                .filteredOn(point -> "ETH".equals(point.getAssetSymbol()))
                .extracting(AssetLedgerPoint::getBasisEffect)
                .containsExactly(AssetLedgerPoint.BasisEffect.REALLOCATE_OUT);
        assertThat(ledgerPoints)
                .filteredOn(point -> "CMETH".equals(point.getAssetSymbol()))
                .extracting(AssetLedgerPoint::getBasisEffect)
                .containsExactly(AssetLedgerPoint.BasisEffect.REALLOCATE_IN);

        // ETH disposed 0.15116813 @ avco 3000 → 453.50439 basis carried onto CMETH (both lanes).
        var cmethKey = assetSupport.assetKey(stakingDeposit, stakingDeposit.getFlows().get(1));
        assertThat(replayState.position(cmethKey).totalCostBasisUsd())
                .isCloseTo(new BigDecimal("453.50439"), within(new BigDecimal("0.01")));
        assertThat(replayState.position(cmethKey).netTotalCostBasisUsd())
                .isCloseTo(new BigDecimal("453.50439"), within(new BigDecimal("0.01")));
        assertThat(replayState.position(cmethKey).uncoveredQuantity())
                .isEqualByComparingTo("0");
        // PnL = 0 → carried legs carry no realized PnL.
        assertThat(stakingDeposit.getFlows().getFirst().getRealisedPnlUsd()).isNull();
    }

    /**
     * ADR-083: carry is price-independent for the covered portion. An intra-cluster conversion whose
     * inbound leg is unpriced (and the market authority returns empty) still carries the disposed
     * basis rather than fabricating a $0 lot or failing closed to UNKNOWN.
     */
    @Test
    void bybitEthToCmethStakingDepositCarriesEvenWhenMarketPriceMissing() {
        ReplayAssetSupport assetSupport = new ReplayAssetSupport();
        ReplayMarketAuthority marketAuthority = mock(ReplayMarketAuthority.class);
        when(marketAuthority.resolve(any(), any())).thenReturn(Optional.empty());
        ReplayDispatcher dispatcher = buildDispatcher(assetSupport, marketAuthority);

        List<AssetLedgerPoint> ledgerPoints = new ArrayList<>();
        ReplayExecutionState replayState = newReplayState(ledgerPoints);

        NormalizedTransaction stakingDeposit = bybitEthToCmethStakingDeposit();
        var ethKey = assetSupport.assetKey(stakingDeposit, stakingDeposit.getFlows().getFirst());
        replayState.position(ethKey).setQuantity(new BigDecimal("0.20"));
        replayState.position(ethKey).setTotalCostBasisUsd(new BigDecimal("600"));
        replayState.position(ethKey).setNetTotalCostBasisUsd(new BigDecimal("600"));
        replayState.position(ethKey).setPerWalletAvco(new BigDecimal("3000"));
        replayState.position(ethKey).setPerWalletNetAvco(new BigDecimal("3000"));

        dispatcher.dispatch(stakingDeposit, replayState);

        var cmethKey = assetSupport.assetKey(stakingDeposit, stakingDeposit.getFlows().get(1));
        assertThat(replayState.position(cmethKey).totalCostBasisUsd())
                .isCloseTo(new BigDecimal("453.50439"), within(new BigDecimal("0.01")));
        assertThat(ledgerPoints)
                .filteredOn(point -> "CMETH".equals(point.getAssetSymbol()))
                .extracting(AssetLedgerPoint::getBasisEffect)
                .containsExactly(AssetLedgerPoint.BasisEffect.REALLOCATE_IN);
    }

    /**
     * CC-AC-1: Bybit ETH → mETH STAKING_DEPOSIT (2025-03-12, universe df5e69cc). The mETH leg
     * inherits the disposed ETH basis ($1889.54 Market / $1833.89 Net) via REALLOCATE_IN with
     * realized PnL = 0 — superseding the former realize-at-market behavior.
     */
    @Test
    void bybitEthToMethStakingDepositCarriesInheritedBasisBothLanes() {
        ReplayAssetSupport assetSupport = new ReplayAssetSupport();
        ReplayMarketAuthority marketAuthority = mock(ReplayMarketAuthority.class);
        when(marketAuthority.resolve(any(), any())).thenReturn(Optional.empty());
        ReplayDispatcher dispatcher = buildDispatcher(assetSupport, marketAuthority);

        List<AssetLedgerPoint> ledgerPoints = new ArrayList<>();
        ReplayExecutionState replayState = newReplayState(ledgerPoints);

        NormalizedTransaction tx = bybitEthToMethStakingDeposit(true);
        var ethKey = assetSupport.assetKey(tx, tx.getFlows().get(0));
        replayState.position(ethKey).setQuantity(new BigDecimal("0.709"));
        replayState.position(ethKey).setTotalCostBasisUsd(new BigDecimal("1889.54"));
        replayState.position(ethKey).setNetTotalCostBasisUsd(new BigDecimal("1833.89"));
        replayState.position(ethKey).setPerWalletAvco(new BigDecimal("2665.08"));
        replayState.position(ethKey).setPerWalletNetAvco(new BigDecimal("2586.59"));

        dispatcher.dispatch(tx, replayState);

        assertThat(ledgerPoints)
                .filteredOn(point -> "ETH".equals(point.getAssetSymbol()))
                .extracting(AssetLedgerPoint::getBasisEffect)
                .containsExactly(AssetLedgerPoint.BasisEffect.REALLOCATE_OUT);
        assertThat(ledgerPoints)
                .filteredOn(point -> "METH".equals(point.getAssetSymbol()))
                .extracting(AssetLedgerPoint::getBasisEffect)
                .containsExactly(AssetLedgerPoint.BasisEffect.REALLOCATE_IN);

        var methKey = assetSupport.assetKey(tx, tx.getFlows().get(1));
        assertThat(replayState.position(methKey).totalCostBasisUsd())
                .isCloseTo(new BigDecimal("1889.54"), within(new BigDecimal("0.01")));
        assertThat(replayState.position(methKey).netTotalCostBasisUsd())
                .isCloseTo(new BigDecimal("1833.89"), within(new BigDecimal("0.01")));
        assertThat(replayState.position(methKey).uncoveredQuantity())
                .isEqualByComparingTo("0");
        assertThat(tx.getFlows().get(0).getRealisedPnlUsd()).isNull();
    }

    /**
     * ADR-083: the mETH leg carries even when the inbound leg is unpriced — carry does not consult
     * market price for the covered portion, so an unpriceable receipt no longer fails closed.
     */
    @Test
    void bybitEthToMethStakingDepositUnpricedInboundStillCarries() {
        ReplayAssetSupport assetSupport = new ReplayAssetSupport();
        ReplayMarketAuthority marketAuthority = mock(ReplayMarketAuthority.class);
        when(marketAuthority.resolve(any(), any())).thenReturn(Optional.empty());
        ReplayDispatcher dispatcher = buildDispatcher(assetSupport, marketAuthority);

        List<AssetLedgerPoint> ledgerPoints = new ArrayList<>();
        ReplayExecutionState replayState = newReplayState(ledgerPoints);

        NormalizedTransaction tx = bybitEthToMethStakingDeposit(false);
        var ethKey = assetSupport.assetKey(tx, tx.getFlows().get(0));
        replayState.position(ethKey).setQuantity(new BigDecimal("0.709"));
        replayState.position(ethKey).setTotalCostBasisUsd(new BigDecimal("1889.54"));
        replayState.position(ethKey).setNetTotalCostBasisUsd(new BigDecimal("1833.89"));
        replayState.position(ethKey).setPerWalletAvco(new BigDecimal("2665.08"));
        replayState.position(ethKey).setPerWalletNetAvco(new BigDecimal("2586.59"));

        dispatcher.dispatch(tx, replayState);

        assertThat(ledgerPoints)
                .filteredOn(point -> "METH".equals(point.getAssetSymbol()))
                .extracting(AssetLedgerPoint::getBasisEffect)
                .containsExactly(AssetLedgerPoint.BasisEffect.REALLOCATE_IN);
        var methKey = assetSupport.assetKey(tx, tx.getFlows().get(1));
        assertThat(replayState.position(methKey).totalCostBasisUsd())
                .isCloseTo(new BigDecimal("1889.54"), within(new BigDecimal("0.01")));
        assertThat(replayState.position(methKey).uncoveredQuantity())
                .isEqualByComparingTo("0");
    }

    /**
     * ADR-083: AVAX → sAVAX STAKING_DEPOSIT carries basis both lanes. sAVAX inherits the AVAX
     * Market basis ($13.09) AND the AVAX Net basis ($10.59) via REALLOCATE_IN — no realize.
     * Flows are inbound-first (sAVAX BUY at index 0) to exercise outbound-first draining.
     */
    @Test
    void avaxToSavaxStakingDepositCarriesBothLanes() {
        ReplayAssetSupport assetSupport = new ReplayAssetSupport();
        ReplayMarketAuthority marketAuthority = mock(ReplayMarketAuthority.class);
        when(marketAuthority.resolve(any(), any())).thenReturn(Optional.empty());
        ReplayDispatcher dispatcher = buildDispatcher(assetSupport, marketAuthority);

        List<AssetLedgerPoint> ledgerPoints = new ArrayList<>();
        ReplayExecutionState replayState = newReplayState(ledgerPoints);

        NormalizedTransaction tx = avaxToSavaxStakingDeposit("1.0", "1.0", "13.09");
        var avaxKey = assetSupport.assetKey(tx, tx.getFlows().get(1)); // AVAX SELL at index 1
        replayState.position(avaxKey).setQuantity(new BigDecimal("1.0"));
        replayState.position(avaxKey).setTotalCostBasisUsd(new BigDecimal("13.09"));
        replayState.position(avaxKey).setNetTotalCostBasisUsd(new BigDecimal("10.59"));
        replayState.position(avaxKey).setPerWalletAvco(new BigDecimal("13.09"));
        replayState.position(avaxKey).setPerWalletNetAvco(new BigDecimal("10.59"));

        dispatcher.dispatch(tx, replayState);

        var savaxKey = assetSupport.assetKey(tx, tx.getFlows().get(0)); // sAVAX at index 0
        assertThat(replayState.position(savaxKey).totalCostBasisUsd())
                .isCloseTo(new BigDecimal("13.09"), within(new BigDecimal("0.01")));
        assertThat(replayState.position(savaxKey).netTotalCostBasisUsd())
                .isCloseTo(new BigDecimal("10.59"), within(new BigDecimal("0.01")));
        assertThat(tx.getFlows().get(1).getRealisedPnlUsd()).isNull();
    }

    /**
     * ADR-040 amendment (ADR-083): the carry path writes basis via {@code restoreToPosition} and does
     * NOT clamp the Net lane down to Market. When the disposed Net basis exceeds the Market basis, the
     * carried lot preserves Net &gt; Market (the {@code Net ≤ Market} cap is intentionally bypassed on
     * carried lots) rather than realizing the difference.
     */
    @Test
    void avaxToSavaxStakingDepositCarriedLotDoesNotClampNetToMarket() {
        ReplayAssetSupport assetSupport = new ReplayAssetSupport();
        ReplayMarketAuthority marketAuthority = mock(ReplayMarketAuthority.class);
        when(marketAuthority.resolve(any(), any())).thenReturn(Optional.empty());
        ReplayDispatcher dispatcher = buildDispatcher(assetSupport, marketAuthority);

        List<AssetLedgerPoint> ledgerPoints = new ArrayList<>();
        ReplayExecutionState replayState = newReplayState(ledgerPoints);

        NormalizedTransaction tx = avaxToSavaxStakingDeposit("1.0", "1.0", "13.09");
        var avaxKey = assetSupport.assetKey(tx, tx.getFlows().get(1));
        replayState.position(avaxKey).setQuantity(new BigDecimal("1.0"));
        replayState.position(avaxKey).setTotalCostBasisUsd(new BigDecimal("13.09"));
        replayState.position(avaxKey).setNetTotalCostBasisUsd(new BigDecimal("20.00")); // net > market
        replayState.position(avaxKey).setPerWalletAvco(new BigDecimal("13.09"));
        replayState.position(avaxKey).setPerWalletNetAvco(new BigDecimal("20.00"));

        dispatcher.dispatch(tx, replayState);

        var savaxKey = assetSupport.assetKey(tx, tx.getFlows().get(0));
        // Carried, NOT clamped: Net basis is preserved at $20.00, Market at $13.09.
        assertThat(replayState.position(savaxKey).netTotalCostBasisUsd())
                .isCloseTo(new BigDecimal("20.00"), within(new BigDecimal("0.01")));
        assertThat(replayState.position(savaxKey).totalCostBasisUsd())
                .isCloseTo(new BigDecimal("13.09"), within(new BigDecimal("0.01")));
    }

    /**
     * ADR-083: ETH → WETH is an intra-cluster (C1 same-family) conversion — it now CARRIES both
     * lanes. WETH inherits the ETH Net basis ($2000, not fresh market $3000), which correctly
     * preserves accumulated net discount across the wrap.
     */
    @Test
    void ethToWethStakingDepositCarriesInheritedNetBasis() {
        ReplayAssetSupport assetSupport = new ReplayAssetSupport();
        ReplayMarketAuthority marketAuthority = mock(ReplayMarketAuthority.class);
        when(marketAuthority.resolve(any(), any())).thenReturn(Optional.empty());
        ReplayDispatcher dispatcher = buildDispatcher(assetSupport, marketAuthority);

        List<AssetLedgerPoint> ledgerPoints = new ArrayList<>();
        ReplayExecutionState replayState = newReplayState(ledgerPoints);

        NormalizedTransaction tx = ethToWethStakingDeposit();
        var ethKey = assetSupport.assetKey(tx, tx.getFlows().get(0)); // ETH SELL at index 0
        replayState.position(ethKey).setQuantity(new BigDecimal("1.0"));
        replayState.position(ethKey).setTotalCostBasisUsd(new BigDecimal("3000.00"));
        replayState.position(ethKey).setNetTotalCostBasisUsd(new BigDecimal("2000.00"));
        replayState.position(ethKey).setPerWalletAvco(new BigDecimal("3000.00"));
        replayState.position(ethKey).setPerWalletNetAvco(new BigDecimal("2000.00"));

        dispatcher.dispatch(tx, replayState);

        var wethKey = assetSupport.assetKey(tx, tx.getFlows().get(1)); // WETH BUY at index 1
        assertThat(replayState.position(wethKey).totalCostBasisUsd())
                .isCloseTo(new BigDecimal("3000.00"), within(new BigDecimal("0.01")));
        assertThat(replayState.position(wethKey).netTotalCostBasisUsd())
                .isCloseTo(new BigDecimal("2000.00"), within(new BigDecimal("0.01")));
        assertThat(tx.getFlows().get(0).getRealisedPnlUsd()).isNull();
    }

    /**
     * ADR-083 §4.5 mirror dedup: cluster-carry runs at the route level and bypasses the generic
     * continuity-transfer dedup. A Bybit mirror document (same correlationId re-emitting the same
     * conversion) must NOT double-carry. The second (mirror) dispatch is skipped, so the carried mETH
     * basis is unchanged and no second REALLOCATE_IN point is recorded.
     */
    @Test
    void bybitMirrorDocumentDoesNotDoubleCarry() {
        ReplayAssetSupport assetSupport = new ReplayAssetSupport();
        ReplayMarketAuthority marketAuthority = mock(ReplayMarketAuthority.class);
        when(marketAuthority.resolve(any(), any())).thenReturn(Optional.empty());
        ReplayDispatcher dispatcher = buildDispatcher(assetSupport, marketAuthority);

        List<AssetLedgerPoint> ledgerPoints = new ArrayList<>();
        ReplayExecutionState replayState = newReplayState(ledgerPoints);

        NormalizedTransaction authoritative = bybitEthToMethStakingDeposit(true);
        authoritative.setCorrelationId("bybit:earn:eth-meth:2025-03-12");
        var ethKey = assetSupport.assetKey(authoritative, authoritative.getFlows().get(0));
        replayState.position(ethKey).setQuantity(new BigDecimal("0.709"));
        replayState.position(ethKey).setTotalCostBasisUsd(new BigDecimal("1889.54"));
        replayState.position(ethKey).setNetTotalCostBasisUsd(new BigDecimal("1833.89"));
        replayState.position(ethKey).setPerWalletAvco(new BigDecimal("2665.08"));
        replayState.position(ethKey).setPerWalletNetAvco(new BigDecimal("2586.59"));

        dispatcher.dispatch(authoritative, replayState);

        var methKey = assetSupport.assetKey(authoritative, authoritative.getFlows().get(1));
        BigDecimal methBasisAfterFirst = replayState.position(methKey).totalCostBasisUsd();
        BigDecimal methQtyAfterFirst = replayState.position(methKey).quantity();

        // Mirror document: same correlationId / wallet / network / quantities.
        NormalizedTransaction mirror = bybitEthToMethStakingDeposit(true);
        mirror.setCorrelationId("bybit:earn:eth-meth:2025-03-12");
        dispatcher.dispatch(mirror, replayState);

        assertThat(replayState.position(methKey).totalCostBasisUsd())
                .isEqualByComparingTo(methBasisAfterFirst);
        assertThat(replayState.position(methKey).quantity())
                .isEqualByComparingTo(methQtyAfterFirst);
        assertThat(ledgerPoints)
                .filteredOn(point -> "METH".equals(point.getAssetSymbol()))
                .extracting(AssetLedgerPoint::getBasisEffect)
                .containsExactly(AssetLedgerPoint.BasisEffect.REALLOCATE_IN);
    }

    private ReplayExecutionState newReplayState(List<AssetLedgerPoint> ledgerPoints) {
        return new ReplayExecutionState(
                new PassThroughCorridorPlan(Map.of(), Map.of()),
                new LedgerPointCollector("u1", ledgerPoints, Instant.now()),
                null, null, null
        );
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
        // ADR-083: exercise the REAL router so intra-cluster conversions route through CLUSTER_CARRY
        // exactly as in production (the GMX/LP matchers come from mocked handlers → false).
        ReplayTransactionRouter replayTransactionRouter = new ReplayTransactionRouter();
        GmxLpEntryReplayHandler gmxLpEntryReplayHandler = mock(GmxLpEntryReplayHandler.class);
        LpReceiptEntryReplayHandler lpReceiptEntryReplayHandler = mock(LpReceiptEntryReplayHandler.class);
        PositionScopedLpExitReplayHandler positionScopedLpExitReplayHandler = mock(PositionScopedLpExitReplayHandler.class);
        ReplayRouteHandlerRegistry replayRouteHandlerRegistry = ReplayRouteHandlerRegistryFactory.create(
                mock(com.walletradar.application.costbasis.application.replay.handler.EulerLoopReplayHandler.class),
                gmxLpEntryReplayHandler,
                lpReceiptEntryReplayHandler,
                mock(GenericAsyncLifecycleReplayHandler.class),
                positionScopedLpExitReplayHandler,
                liquidStakingReplayHandler,
                familyEquivalentCustodyReplayHandler
        );
        return new ReplayDispatcher(
                replayTransactionRouter, assetSupport, flowSupport, transferClassifier, keyFactory,
                replayRouteHandlerRegistry, mock(AcquisitionFeeCapitalizationPolicy.class),
                transferReplayHandler, bybitVenueInternalReplayHandler, liquidStakingReplayHandler,
                familyEquivalentCustodyReplayHandler, mock(GenericAsyncLifecycleReplayHandler.class),
                gmxLpEntryReplayHandler, lpReceiptEntryReplayHandler,
                positionScopedLpExitReplayHandler, mock(AsyncSpotOrderReplayHandler.class),
                mock(CounterpartyBasisPoolReplayHook.class),
                mock(com.walletradar.application.costbasis.application.replay.support.LeverageBorrowReplayHook.class),
                mock(BorrowReplayHandler.class), mock(RepayReplayHandler.class), marketAuthority
        );
    }

    /**
     * AVAX → sAVAX STAKING_DEPOSIT with BUY/SELL roles.
     * sAVAX BUY is intentionally placed at index 0 (inbound-first) to exercise outbound-first draining.
     */
    private static NormalizedTransaction avaxToSavaxStakingDeposit(
            String avaxQty, String savaxQty, String savaxUnitPrice
    ) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId("bybit-avax-savax-stake");
        tx.setSource(NormalizedTransactionSource.BYBIT);
        tx.setType(NormalizedTransactionType.STAKING_DEPOSIT);
        tx.setWalletAddress("BYBIT:test");
        tx.setBlockTimestamp(Instant.parse("2025-01-15T10:00:00Z"));

        NormalizedTransaction.Flow savaxIn = new NormalizedTransaction.Flow();
        savaxIn.setRole(NormalizedLegRole.BUY);
        savaxIn.setAssetSymbol("SAVAX");
        savaxIn.setAccountRef("BYBIT:test:FUND");
        savaxIn.setQuantityDelta(new BigDecimal(savaxQty));
        savaxIn.setUnitPriceUsd(new BigDecimal(savaxUnitPrice));

        NormalizedTransaction.Flow avaxOut = new NormalizedTransaction.Flow();
        avaxOut.setRole(NormalizedLegRole.SELL);
        avaxOut.setAssetSymbol("AVAX");
        avaxOut.setAccountRef("BYBIT:test:FUND");
        avaxOut.setQuantityDelta(new BigDecimal(avaxQty).negate());

        tx.setFlows(List.of(savaxIn, avaxOut));
        return tx;
    }

    /**
     * ETH → WETH STAKING_DEPOSIT — C1 same-family intra-cluster move.
     */
    private static NormalizedTransaction ethToWethStakingDeposit() {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId("eth-weth-stake");
        tx.setSource(NormalizedTransactionSource.BYBIT);
        tx.setType(NormalizedTransactionType.STAKING_DEPOSIT);
        tx.setWalletAddress("BYBIT:test");
        tx.setBlockTimestamp(Instant.parse("2025-01-15T10:00:00Z"));

        NormalizedTransaction.Flow ethOut = new NormalizedTransaction.Flow();
        ethOut.setRole(NormalizedLegRole.SELL);
        ethOut.setAssetSymbol("ETH");
        ethOut.setAccountRef("BYBIT:test:FUND");
        ethOut.setQuantityDelta(new BigDecimal("-1.0"));

        NormalizedTransaction.Flow wethIn = new NormalizedTransaction.Flow();
        wethIn.setRole(NormalizedLegRole.BUY);
        wethIn.setAssetSymbol("WETH");
        wethIn.setAccountRef("BYBIT:test:FUND");
        wethIn.setQuantityDelta(new BigDecimal("1.0"));
        wethIn.setUnitPriceUsd(new BigDecimal("3000.00"));
        wethIn.setPriceSource(PriceSource.BYBIT);

        tx.setFlows(List.of(ethOut, wethIn));
        return tx;
    }

    /**
     * Bybit ETH → mETH STAKING_DEPOSIT carried as TRANSFER-role principal legs (as in production
     * Bybit rows). ETH is C1 (FAMILY:ETH); mETH is C2 (FAMILY:METH) — both in the ETH staking cluster.
     *
     * @param methPriced when {@code true}, the mETH inbound leg carries its own flow price; the carry
     *                   path ignores it for the covered portion (basis is inherited, not re-priced).
     */
    private static NormalizedTransaction bybitEthToMethStakingDeposit(boolean methPriced) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId("bybit-eth-meth-stake");
        tx.setSource(NormalizedTransactionSource.BYBIT);
        tx.setType(NormalizedTransactionType.STAKING_DEPOSIT);
        tx.setWalletAddress("BYBIT:33625378");
        tx.setBlockTimestamp(Instant.parse("2025-03-12T00:00:00Z"));

        NormalizedTransaction.Flow ethOut = new NormalizedTransaction.Flow();
        ethOut.setRole(NormalizedLegRole.TRANSFER);
        ethOut.setAssetSymbol("ETH");
        ethOut.setAccountRef("BYBIT:33625378:FUND");
        ethOut.setQuantityDelta(new BigDecimal("-0.709"));

        NormalizedTransaction.Flow methIn = new NormalizedTransaction.Flow();
        methIn.setRole(NormalizedLegRole.TRANSFER);
        methIn.setAssetSymbol("METH");
        methIn.setAccountRef("BYBIT:33625378:FUND");
        methIn.setQuantityDelta(new BigDecimal("0.66865026"));
        if (methPriced) {
            methIn.setUnitPriceUsd(new BigDecimal("2825.9"));
            methIn.setPriceSource(PriceSource.BYBIT);
        }

        tx.setFlows(List.of(ethOut, methIn));
        return tx;
    }

    private static NormalizedTransaction bybitEthToCmethStakingDeposit() {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId("bybit-eth-cmeth-stake");
        tx.setSource(NormalizedTransactionSource.BYBIT);
        tx.setType(NormalizedTransactionType.STAKING_DEPOSIT);
        tx.setWalletAddress("BYBIT:33625378");
        tx.setBlockTimestamp(Instant.parse("2025-01-12T16:21:03Z"));

        NormalizedTransaction.Flow ethOut = new NormalizedTransaction.Flow();
        ethOut.setRole(NormalizedLegRole.TRANSFER);
        ethOut.setAssetSymbol("ETH");
        ethOut.setAccountRef("BYBIT:33625378:FUND");
        ethOut.setQuantityDelta(new BigDecimal("-0.15116813"));

        NormalizedTransaction.Flow cmethIn = new NormalizedTransaction.Flow();
        cmethIn.setRole(NormalizedLegRole.TRANSFER);
        cmethIn.setAssetSymbol("CMETH");
        cmethIn.setAccountRef("BYBIT:33625378:FUND");
        cmethIn.setQuantityDelta(new BigDecimal("0.1432901"));

        tx.setFlows(List.of(ethOut, cmethIn));
        return tx;
    }
}
