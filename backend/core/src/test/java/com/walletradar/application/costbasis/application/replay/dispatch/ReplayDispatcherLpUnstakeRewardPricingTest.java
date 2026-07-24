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
import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
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
 * R6a: reward tokens (BAL/AURA/WAVAX) received on a Balancer/Aura {@code LP_POSITION_UNSTAKE} are
 * zero-cost income legs, not carried LP principal. They must be booked like the LP-exit reward
 * sideflow: tax lane = FMV (income realized on later sale), net lane = $0 ({@code REWARD_CLAIM}
 * zero-cost), with an {@code ACQUIRE} ledger point. The LP-RECEIPT principal leg is excluded (it
 * rides the continuity carry). Evidence anchor (AVALANCHE): {@code 0x2447…} withdrawAndUnwrap
 * returning BPT {@code 0xfcec3c8d…} plus BAL/AURA rewards.
 */
class ReplayDispatcherLpUnstakeRewardPricingTest {

    private static final String CORR = "lp-position:avalanche:balancerv3:0xfcec3c8d86329defb548202fe1b86ff2188603a8";
    private static final String WALLET = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";

    @Test
    void balRewardOnBalancerV3UnstakeIsBookedZeroCostNetLaneFmvTaxLane() {
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

        NormalizedTransaction unstake = balancerV3Unstake();
        NormalizedTransaction.Flow balReward = unstake.getFlows().getFirst();
        AssetKey balKey = assetSupport.assetKey(unstake, balReward);

        dispatcher.dispatch(unstake, replayState);

        PositionState balPosition = replayState.position(balKey);
        // Tax (market) lane = FMV: 0.12 BAL × $2 = $0.24.
        assertThat(balPosition.totalCostBasisUsd()).isEqualByComparingTo("0.24");
        // Net lane = $0 (zero-cost REWARD_CLAIM acquisition).
        assertThat(balPosition.netTotalCostBasisUsd()).isEqualByComparingTo("0");
        assertThat(balPosition.quantity()).isEqualByComparingTo("0.12");

        // Booked as an ACQUIRE ledger point (income), never DISPOSE.
        assertThat(ledgerPoints)
                .filteredOn(p -> "BAL".equals(p.getAssetSymbol()))
                .extracting(AssetLedgerPoint::getBasisEffect)
                .containsExactly(AssetLedgerPoint.BasisEffect.ACQUIRE);
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

    private static NormalizedTransaction balancerV3Unstake() {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId("balancerv3-unstake-reward");
        tx.setTxHash("0x2447000000000000000000000000000000000000000000000000000000000000");
        tx.setWalletAddress(WALLET);
        tx.setNetworkId(NetworkId.AVALANCHE);
        tx.setSource(NormalizedTransactionSource.ON_CHAIN);
        tx.setType(NormalizedTransactionType.LP_POSITION_UNSTAKE);
        tx.setStatus(NormalizedTransactionStatus.CONFIRMED);
        tx.setProtocolName("Aura");
        tx.setCorrelationId(CORR);
        tx.setBlockTimestamp(Instant.parse("2026-03-25T11:00:00Z"));

        // BAL reward: inbound, market-priced at $2/unit — must be booked zero-cost (net lane $0).
        NormalizedTransaction.Flow balReward = new NormalizedTransaction.Flow();
        balReward.setRole(NormalizedLegRole.TRANSFER);
        balReward.setAssetSymbol("BAL");
        balReward.setAssetContract("0xbal0000000000000000000000000000000000000");
        balReward.setQuantityDelta(new BigDecimal("0.12"));
        balReward.setUnitPriceUsd(new BigDecimal("2"));
        balReward.setPriceSource(PriceSource.COINGECKO);

        tx.setFlows(List.of(balReward));
        return tx;
    }
}
