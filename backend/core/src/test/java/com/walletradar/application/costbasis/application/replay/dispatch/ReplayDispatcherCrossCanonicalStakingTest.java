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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReplayDispatcherCrossCanonicalStakingTest {

    @Test
    void bybitEthToCmethStakingDepositResolvesReplayMarketPriceAndRealisesPl() {
        ReplayAssetSupport assetSupport = new ReplayAssetSupport();
        ReplayMarketAuthority marketAuthority = mock(ReplayMarketAuthority.class);
        when(marketAuthority.resolve(any(), any())).thenAnswer(invocation -> {
            NormalizedTransaction.Flow flow = invocation.getArgument(1);
            return switch (flow.getAssetSymbol()) {
                case "ETH" -> Optional.of(new ReplayMarketAuthority.ResolvedMarketPrice(
                        new BigDecimal("3000"),
                        PriceSource.BYBIT,
                        ReplayMarketAuthority.ResolvedMarketPrice.Authority.HISTORICAL_CACHE
                ));
                case "CMETH" -> Optional.of(new ReplayMarketAuthority.ResolvedMarketPrice(
                        new BigDecimal("3100"),
                        PriceSource.BYBIT,
                        ReplayMarketAuthority.ResolvedMarketPrice.Authority.HISTORICAL_CACHE
                ));
                default -> Optional.empty();
            };
        });
        GenericFlowReplayEngine engine = new GenericFlowReplayEngine(marketAuthority);
        ReplayFlowSupport flowSupport = new ReplayFlowSupport(engine);
        ContinuityCarryService carryService = new ContinuityCarryService(engine, flowSupport);
        ReplayPendingTransferKeyFactory keyFactory = new ReplayPendingTransferKeyFactory(assetSupport);
        ReplayTransferClassifier transferClassifier = new ReplayTransferClassifier(keyFactory);
        TransferReplayHandler transferReplayHandler = TransferReplayHandlerFixtures.handler(
                flowSupport,
                carryService,
                keyFactory,
                transferClassifier,
                new ReplayPendingTransferMatcher(),
                marketAuthority
        );
        BybitVenueInternalReplayHandler bybitVenueInternalReplayHandler = new BybitVenueInternalReplayHandler(
                transferClassifier,
                transferReplayHandler
        );
        LiquidStakingReplayHandler liquidStakingReplayHandler = new LiquidStakingReplayHandler(
                assetSupport,
                flowSupport,
                new ReplaySettlementAllocator(assetSupport, flowSupport)
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

        ReplayDispatcher dispatcher = new ReplayDispatcher(
                replayTransactionRouter,
                assetSupport,
                flowSupport,
                transferClassifier,
                keyFactory,
                replayRouteHandlerRegistry,
                mock(AcquisitionFeeCapitalizationPolicy.class),
                transferReplayHandler,
                bybitVenueInternalReplayHandler,
                liquidStakingReplayHandler,
                familyEquivalentCustodyReplayHandler,
                mock(GenericAsyncLifecycleReplayHandler.class),
                mock(GmxLpEntryReplayHandler.class),
                mock(LpReceiptEntryReplayHandler.class),
                mock(PositionScopedLpExitReplayHandler.class),
                mock(AsyncSpotOrderReplayHandler.class),
                mock(CounterpartyBasisPoolReplayHook.class),
                mock(com.walletradar.application.costbasis.application.replay.support.LeverageBorrowReplayHook.class),
                mock(BorrowReplayHandler.class),
                mock(RepayReplayHandler.class),
                marketAuthority
        );

        List<AssetLedgerPoint> ledgerPoints = new ArrayList<>();
        ReplayExecutionState replayState = new ReplayExecutionState(
                new PassThroughCorridorPlan(Map.of(), Map.of()),
                new LedgerPointCollector("u1", ledgerPoints, Instant.now()),
                null,
                null,
                null
        );

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
                .containsExactly(AssetLedgerPoint.BasisEffect.DISPOSE);
        assertThat(ledgerPoints)
                .filteredOn(point -> "CMETH".equals(point.getAssetSymbol()))
                .extracting(AssetLedgerPoint::getBasisEffect)
                .containsExactly(AssetLedgerPoint.BasisEffect.ACQUIRE);

        var cmethKey = assetSupport.assetKey(stakingDeposit, stakingDeposit.getFlows().get(1));
        assertThat(replayState.position(cmethKey).totalCostBasisUsd())
                .isEqualByComparingTo("444.19931");
        assertThat(replayState.position(cmethKey).uncoveredQuantity())
                .isEqualByComparingTo("0");
        assertThat(stakingDeposit.getFlows().getFirst().getRealisedPnlUsd())
                .isNotNull();
    }

    @Test
    void bybitEthToCmethStakingDepositFailsClosedWhenMarketPriceMissing() {
        ReplayAssetSupport assetSupport = new ReplayAssetSupport();
        ReplayMarketAuthority marketAuthority = mock(ReplayMarketAuthority.class);
        when(marketAuthority.resolve(any(), any())).thenReturn(Optional.empty());
        GenericFlowReplayEngine engine = new GenericFlowReplayEngine(marketAuthority);
        ReplayFlowSupport flowSupport = new ReplayFlowSupport(engine);
        ContinuityCarryService carryService = new ContinuityCarryService(engine, flowSupport);
        ReplayPendingTransferKeyFactory keyFactory = new ReplayPendingTransferKeyFactory(assetSupport);
        ReplayTransferClassifier transferClassifier = new ReplayTransferClassifier(keyFactory);
        TransferReplayHandler transferReplayHandler = TransferReplayHandlerFixtures.handler(
                flowSupport,
                carryService,
                keyFactory,
                transferClassifier,
                new ReplayPendingTransferMatcher(),
                marketAuthority
        );
        ReplayTransactionRouter replayTransactionRouter = mock(ReplayTransactionRouter.class);
        when(replayTransactionRouter.route(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(ReplayRoutingDecision.generic());
        ReplayDispatcher dispatcher = new ReplayDispatcher(
                replayTransactionRouter,
                assetSupport,
                flowSupport,
                transferClassifier,
                keyFactory,
                ReplayRouteHandlerRegistryFactory.create(
                        mock(com.walletradar.application.costbasis.application.replay.handler.EulerLoopReplayHandler.class),
                        mock(GmxLpEntryReplayHandler.class),
                        mock(LpReceiptEntryReplayHandler.class),
                        mock(GenericAsyncLifecycleReplayHandler.class),
                        mock(PositionScopedLpExitReplayHandler.class),
                        mock(LiquidStakingReplayHandler.class),
                        mock(FamilyEquivalentCustodyReplayHandler.class)
                ),
                mock(AcquisitionFeeCapitalizationPolicy.class),
                transferReplayHandler,
                mock(BybitVenueInternalReplayHandler.class),
                mock(LiquidStakingReplayHandler.class),
                mock(FamilyEquivalentCustodyReplayHandler.class),
                mock(GenericAsyncLifecycleReplayHandler.class),
                mock(GmxLpEntryReplayHandler.class),
                mock(LpReceiptEntryReplayHandler.class),
                mock(PositionScopedLpExitReplayHandler.class),
                mock(AsyncSpotOrderReplayHandler.class),
                mock(CounterpartyBasisPoolReplayHook.class),
                mock(com.walletradar.application.costbasis.application.replay.support.LeverageBorrowReplayHook.class),
                mock(BorrowReplayHandler.class),
                mock(RepayReplayHandler.class),
                marketAuthority
        );

        List<AssetLedgerPoint> ledgerPoints = new ArrayList<>();
        ReplayExecutionState replayState = new ReplayExecutionState(
                new PassThroughCorridorPlan(Map.of(), Map.of()),
                new LedgerPointCollector("u1", ledgerPoints, Instant.now()),
                null,
                null,
                null
        );

        NormalizedTransaction stakingDeposit = bybitEthToCmethStakingDeposit();

        dispatcher.dispatch(stakingDeposit, replayState);

        assertThat(ledgerPoints)
                .extracting(AssetLedgerPoint::getBasisEffect)
                .containsOnly(AssetLedgerPoint.BasisEffect.UNKNOWN);
    }

    /**
     * ADR-054 Net-lane carry: AVAX → sAVAX cross-canonical staking deposit.
     * The sAVAX net AVCO must inherit the AVAX net basis (not fresh market price).
     * The sAVAX market AVCO must be set at market price (unchanged).
     * Flows are ordered inbound-first (sAVAX BUY at index 0) to exercise the ordering fix.
     */
    @Test
    void avaxToSavaxCrossCanonicalStakingDepositInheritsSellNetBasis() {
        ReplayAssetSupport assetSupport = new ReplayAssetSupport();
        ReplayMarketAuthority marketAuthority = mock(ReplayMarketAuthority.class);
        when(marketAuthority.resolve(any(), any())).thenReturn(Optional.empty());
        ReplayDispatcher dispatcher = buildDispatcher(assetSupport, marketAuthority);

        List<AssetLedgerPoint> ledgerPoints = new ArrayList<>();
        ReplayExecutionState replayState = new ReplayExecutionState(
                new PassThroughCorridorPlan(Map.of(), Map.of()),
                new LedgerPointCollector("u1", ledgerPoints, Instant.now()),
                null, null, null
        );

        // sAVAX BUY at index 0 (inbound-first) — requires reordering to process SELL first
        NormalizedTransaction tx = avaxToSavaxStakingDeposit("1.0", "1.0", "13.09");
        var avaxKey = assetSupport.assetKey(tx, tx.getFlows().get(1)); // AVAX SELL at index 1
        replayState.position(avaxKey).setQuantity(new BigDecimal("1.0"));
        replayState.position(avaxKey).setTotalCostBasisUsd(new BigDecimal("13.09"));    // market AVCO = $13.09
        replayState.position(avaxKey).setNetTotalCostBasisUsd(new BigDecimal("10.59")); // net AVCO = $10.59
        replayState.position(avaxKey).setPerWalletAvco(new BigDecimal("13.09"));
        replayState.position(avaxKey).setPerWalletNetAvco(new BigDecimal("10.59"));

        dispatcher.dispatch(tx, replayState);

        var savaxKey = assetSupport.assetKey(tx, tx.getFlows().get(0)); // sAVAX at index 0
        // Net lane inherits AVAX net basis ($10.59), NOT market price ($13.09)
        assertThat(replayState.position(savaxKey).netTotalCostBasisUsd())
                .isEqualByComparingTo("10.59");
        // Market lane uses fresh sAVAX market price — must be unchanged
        assertThat(replayState.position(savaxKey).totalCostBasisUsd())
                .isEqualByComparingTo("13.09");
    }

    /**
     * ADR-040 invariant: Net AVCO ≤ Market AVCO.
     * When inherited AVAX net basis exceeds the sAVAX market basis, the cap clamps net to market.
     */
    @Test
    void avaxToSavaxCrossCanonicalStakingDepositPegFloorCapClampsNetAtMarket() {
        ReplayAssetSupport assetSupport = new ReplayAssetSupport();
        ReplayMarketAuthority marketAuthority = mock(ReplayMarketAuthority.class);
        when(marketAuthority.resolve(any(), any())).thenReturn(Optional.empty());
        ReplayDispatcher dispatcher = buildDispatcher(assetSupport, marketAuthority);

        List<AssetLedgerPoint> ledgerPoints = new ArrayList<>();
        ReplayExecutionState replayState = new ReplayExecutionState(
                new PassThroughCorridorPlan(Map.of(), Map.of()),
                new LedgerPointCollector("u1", ledgerPoints, Instant.now()),
                null, null, null
        );

        // AVAX net AVCO ($20.00) > sAVAX market price ($13.09) — cap must engage
        NormalizedTransaction tx = avaxToSavaxStakingDeposit("1.0", "1.0", "13.09");
        var avaxKey = assetSupport.assetKey(tx, tx.getFlows().get(1));
        replayState.position(avaxKey).setQuantity(new BigDecimal("1.0"));
        replayState.position(avaxKey).setTotalCostBasisUsd(new BigDecimal("13.09"));
        replayState.position(avaxKey).setNetTotalCostBasisUsd(new BigDecimal("20.00")); // net > market
        replayState.position(avaxKey).setPerWalletAvco(new BigDecimal("13.09"));
        replayState.position(avaxKey).setPerWalletNetAvco(new BigDecimal("20.00"));

        dispatcher.dispatch(tx, replayState);

        var savaxKey = assetSupport.assetKey(tx, tx.getFlows().get(0));
        // Cap: min(20.00, 13.09) = 13.09 — net basis equals market basis
        assertThat(replayState.position(savaxKey).netTotalCostBasisUsd())
                .isEqualByComparingTo("13.09");
        assertThat(replayState.position(savaxKey).totalCostBasisUsd())
                .isEqualByComparingTo("13.09");
    }

    /**
     * Regression: ETH → WETH is a C1→C1 same-family move.
     * {@code hasCrossCanonicalIdentityPrincipalPair} returns false → {@code isCrossCanonicalStaking}
     * is false → {@code swapNetRef} is null → WETH net AVCO uses market price, NOT inherited basis.
     */
    @Test
    void ethToWethSameC1StakingDepositDoesNotInheritNetBasis() {
        ReplayAssetSupport assetSupport = new ReplayAssetSupport();
        ReplayMarketAuthority marketAuthority = mock(ReplayMarketAuthority.class);
        when(marketAuthority.resolve(any(), any())).thenReturn(Optional.empty());
        ReplayDispatcher dispatcher = buildDispatcher(assetSupport, marketAuthority);

        List<AssetLedgerPoint> ledgerPoints = new ArrayList<>();
        ReplayExecutionState replayState = new ReplayExecutionState(
                new PassThroughCorridorPlan(Map.of(), Map.of()),
                new LedgerPointCollector("u1", ledgerPoints, Instant.now()),
                null, null, null
        );

        // STAKING_DEPOSIT ETH→WETH: both are C1 (FAMILY:ETH) — no cross-canonical identity pair
        NormalizedTransaction tx = ethToWethStakingDeposit();
        var ethKey = assetSupport.assetKey(tx, tx.getFlows().get(0)); // ETH SELL at index 0
        replayState.position(ethKey).setQuantity(new BigDecimal("1.0"));
        replayState.position(ethKey).setTotalCostBasisUsd(new BigDecimal("3000.00"));
        replayState.position(ethKey).setNetTotalCostBasisUsd(new BigDecimal("2000.00")); // net below market
        replayState.position(ethKey).setPerWalletAvco(new BigDecimal("3000.00"));
        replayState.position(ethKey).setPerWalletNetAvco(new BigDecimal("2000.00"));

        dispatcher.dispatch(tx, replayState);

        var wethKey = assetSupport.assetKey(tx, tx.getFlows().get(1)); // WETH BUY at index 1
        // swapNetRef is null — no basis inheritance; WETH net cost = market price ($3000)
        assertThat(replayState.position(wethKey).totalCostBasisUsd())
                .isEqualByComparingTo("3000.00");
        assertThat(replayState.position(wethKey).netTotalCostBasisUsd())
                .isEqualByComparingTo("3000.00");
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

    /**
     * AVAX → sAVAX STAKING_DEPOSIT with BUY/SELL roles.
     * sAVAX BUY is intentionally placed at index 0 (inbound-first) to exercise ordering fix.
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

        // sAVAX BUY first (inbound at index 0), AVAX SELL second (outbound at index 1)
        tx.setFlows(List.of(savaxIn, avaxOut));
        return tx;
    }

    /**
     * ETH → WETH STAKING_DEPOSIT — C1→C1 same-family move; no cross-canonical pair.
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
