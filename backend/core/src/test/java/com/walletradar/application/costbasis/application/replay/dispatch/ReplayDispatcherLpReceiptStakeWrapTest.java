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
import com.walletradar.application.costbasis.application.replay.model.AssetKey;
import com.walletradar.application.costbasis.application.replay.model.PassThroughCorridorPlan;
import com.walletradar.application.costbasis.application.replay.model.PositionState;
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
import com.walletradar.domain.common.NetworkId;
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

/**
 * RC-6 (ADR-047 addendum): a STAKING_DEPOSIT of a Pendle LP receipt into an Equilibria/Penpie booster
 * is a non-realizing wrap. The receipt out-leg must drain the synthetic holding with a {@code CARRY_OUT}
 * ledger point and <b>no realized P&amp;L</b> — never a {@code DISPOSE} that books a phantom loss.
 */
class ReplayDispatcherLpReceiptStakeWrapTest {

    @Test
    void equilibriaStakingDepositOfPendleLpReceiptCarriesWithoutRealising() {
        ReplayAssetSupport assetSupport = new ReplayAssetSupport();
        ReplayMarketAuthority marketAuthority = mock(ReplayMarketAuthority.class);
        // Any market resolution would only matter for a priced (SELL) leg; a CARRY leg must not price.
        when(marketAuthority.resolve(any(), any())).thenReturn(Optional.empty());
        ReplayDispatcher dispatcher = buildDispatcher(assetSupport, marketAuthority);

        List<AssetLedgerPoint> ledgerPoints = new ArrayList<>();
        ReplayExecutionState replayState = new ReplayExecutionState(
                new PassThroughCorridorPlan(Map.of(), Map.of()),
                new LedgerPointCollector("u1", ledgerPoints, Instant.now()),
                null, null, null
        );

        NormalizedTransaction stakingDeposit = equilibriaPendleLptStakingDeposit();
        NormalizedTransaction.Flow receiptOut = stakingDeposit.getFlows().getFirst();
        AssetKey receiptKey = assetSupport.assetKey(stakingDeposit, receiptOut);
        // LP entry left 0.445 PENDLE-LPT carrying $3,380.66 basis (avco ≈ $7,596/unit).
        PositionState receiptPosition = replayState.position(receiptKey);
        receiptPosition.setQuantity(new BigDecimal("0.445041029858104302"));
        receiptPosition.setTotalCostBasisUsd(new BigDecimal("3380.66"));
        receiptPosition.setNetTotalCostBasisUsd(new BigDecimal("3380.66"));

        dispatcher.dispatch(stakingDeposit, replayState);

        // The receipt out-leg is CARRY_OUT (a non-realizing wrap), never DISPOSE.
        assertThat(ledgerPoints)
                .filteredOn(p -> "PENDLE-LPT".equals(p.getAssetSymbol()))
                .extracting(AssetLedgerPoint::getBasisEffect)
                .containsExactly(AssetLedgerPoint.BasisEffect.CARRY_OUT);
        // No phantom realized P&L booked on the leg.
        assertThat(receiptOut.getRealisedPnlUsd()).isNull();
        assertThat(receiptOut.getAvcoAtTimeOfSale()).isNull();
        // Synthetic receipt holding drained; no realized P&L accrued on the position.
        assertThat(receiptPosition.quantity()).isEqualByComparingTo("0");
        assertThat(receiptPosition.totalCostBasisUsd()).isEqualByComparingTo("0");
        assertThat(ledgerPoints)
                .noneMatch(p -> p.getBasisEffect() == AssetLedgerPoint.BasisEffect.DISPOSE);
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

    private static NormalizedTransaction equilibriaPendleLptStakingDeposit() {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId("equilibria-pendle-lpt-stake");
        tx.setTxHash("0x8dd9dbad58e886cd04c095c5303671535a153f352f52d9538e49b94ae58eb70d");
        tx.setWalletAddress("0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f");
        tx.setNetworkId(NetworkId.MANTLE);
        tx.setSource(NormalizedTransactionSource.ON_CHAIN);
        tx.setType(NormalizedTransactionType.STAKING_DEPOSIT);
        tx.setProtocolName("Equilibria");
        tx.setCorrelationId("pendle-lp:mantle:pendle-lpt:0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f");
        tx.setBlockTimestamp(Instant.parse("2026-03-25T11:00:00Z"));

        // Only the LP-receipt out-leg is visible on-chain; eqbPENDLE-LPT is minted internally.
        NormalizedTransaction.Flow receiptOut = new NormalizedTransaction.Flow();
        receiptOut.setRole(NormalizedLegRole.TRANSFER);
        receiptOut.setAssetSymbol("PENDLE-LPT");
        receiptOut.setAssetContract("0x6304ccbda63a7fb94919c705de54f9889f3ce047");
        receiptOut.setQuantityDelta(new BigDecimal("-0.445041029858104302"));
        receiptOut.setLpReceipt(Boolean.TRUE);

        tx.setFlows(List.of(receiptOut));
        return tx;
    }
}
