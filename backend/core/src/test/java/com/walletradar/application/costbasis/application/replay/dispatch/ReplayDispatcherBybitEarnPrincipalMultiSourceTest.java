package com.walletradar.application.costbasis.application.replay.dispatch;

import com.walletradar.application.costbasis.application.replay.handler.AsyncSpotOrderReplayHandler;
import com.walletradar.testsupport.TransferReplayHandlerFixtures;
import com.walletradar.application.costbasis.application.replay.handler.BorrowReplayHandler;
import com.walletradar.application.costbasis.application.replay.handler.BybitVenueInternalReplayHandler;
import com.walletradar.application.costbasis.application.replay.handler.EulerLoopReplayHandler;
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
import com.walletradar.application.costbasis.application.replay.support.ReplayTransferClassifier;
import com.walletradar.application.costbasis.domain.AssetLedgerPoint;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
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

class ReplayDispatcherBybitEarnPrincipalMultiSourceTest {

    @Test
    void inboundFirstUnpricedBundleStillPersistsEarnLedgerRowAndConsumesOutbounds() {
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
        BybitVenueInternalReplayHandler bybitVenueInternalReplayHandler = new BybitVenueInternalReplayHandler(
                transferClassifier,
                transferReplayHandler
        );

        ReplayTransactionRouter replayTransactionRouter = mock(ReplayTransactionRouter.class);
        when(replayTransactionRouter.route(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(ReplayRoutingDecision.generic());

        ReplayRouteHandlerRegistry replayRouteHandlerRegistry = ReplayRouteHandlerRegistryFactory.create(
                mock(EulerLoopReplayHandler.class),
                mock(GmxLpEntryReplayHandler.class),
                mock(LpReceiptEntryReplayHandler.class),
                mock(GenericAsyncLifecycleReplayHandler.class),
                mock(PositionScopedLpExitReplayHandler.class),
                mock(LiquidStakingReplayHandler.class),
                mock(FamilyEquivalentCustodyReplayHandler.class)
        );

        ReplayDispatcher dispatcher = new ReplayDispatcher(
                replayTransactionRouter,
                assetSupport,
                flowSupport,
                transferClassifier,
                keyFactory,
                replayRouteHandlerRegistry,
                mock(com.walletradar.application.costbasis.support.AcquisitionFeeCapitalizationPolicy.class),
                transferReplayHandler,
                bybitVenueInternalReplayHandler,
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

        NormalizedTransaction fundOut = fundOut();
        replayState.position(assetSupport.assetKey(fundOut, fundOut.getFlows().getFirst()))
                .setQuantity(new BigDecimal("0.510963384"));
        replayState.position(assetSupport.assetKey(fundOut, fundOut.getFlows().getFirst()))
                .setTotalCostBasisUsd(new BigDecimal("20.60905027909113041062505735717546"));
        replayState.position(assetSupport.assetKey(fundOut, fundOut.getFlows().getFirst()))
                .setUncoveredQuantity(BigDecimal.ZERO);
        replayState.position(assetSupport.assetKey(fundOut, fundOut.getFlows().getFirst()))
                .setPerWalletAvco(new BigDecimal("40.33371259943575606706303118811241"));

        dispatcher.dispatch(earnIn(), replayState);
        dispatcher.dispatch(fundOut, replayState);
        dispatcher.dispatch(utaOut(), replayState);

        assertThat(ledgerPoints)
                .anySatisfy(point -> {
                    assertThat(point.getNormalizedTransactionId()).isEqualTo("earn-in");
                    assertThat(point.getWalletAddress()).isEqualTo("BYBIT:33625378:EARN");
                    assertThat(point.getQuantityDelta()).isEqualByComparingTo("0.51096338");
                });
        assertThat(ledgerPoints)
                .filteredOn(point -> point.getNormalizedTransactionId().equals("earn-in"))
                .hasSize(1);
        assertThat(ledgerPoints)
                .filteredOn(point -> point.getNormalizedTransactionId().equals("fund-out") || point.getNormalizedTransactionId().equals("uta-out"))
                .extracting(AssetLedgerPoint::getBasisEffect)
                .contains(AssetLedgerPoint.BasisEffect.REALLOCATE_OUT, AssetLedgerPoint.BasisEffect.CARRY_OUT);
    }

    private static NormalizedTransaction earnIn() {
        return tx("earn-in", NormalizedTransactionType.EARN_FLEXIBLE_SAVING, "BYBIT:33625378:EARN", new BigDecimal("0.51096338"));
    }

    private static NormalizedTransaction fundOut() {
        return tx("fund-out", NormalizedTransactionType.EARN_FLEXIBLE_SAVING, "BYBIT:33625378:FUND", new BigDecimal("-0.01146338"));
    }

    private static NormalizedTransaction utaOut() {
        return tx("uta-out", NormalizedTransactionType.INTERNAL_TRANSFER, "BYBIT:33625378:UTA", new BigDecimal("-0.4995"));
    }

    private static NormalizedTransaction tx(String id, NormalizedTransactionType type, String wallet, BigDecimal quantity) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId(id);
        tx.setSource(NormalizedTransactionSource.BYBIT);
        tx.setType(type);
        tx.setWalletAddress(wallet);
        tx.setContinuityCandidate(true);
        tx.setCorrelationId("bybit-earn-principal-v1:ltc-anchor");
        tx.setBlockTimestamp(Instant.parse("2026-06-27T06:46:57Z"));
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol("LTC");
        flow.setAccountRef(wallet);
        flow.setQuantityDelta(quantity);
        tx.setFlows(List.of(flow));
        return tx;
    }
}
