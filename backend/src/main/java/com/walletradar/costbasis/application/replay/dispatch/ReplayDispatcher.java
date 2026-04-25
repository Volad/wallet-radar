package com.walletradar.costbasis.application.replay.dispatch;

import com.walletradar.costbasis.application.replay.handler.AsyncSpotOrderReplayHandler;
import com.walletradar.costbasis.application.replay.handler.EulerLoopReplayHandler;
import com.walletradar.costbasis.application.replay.handler.FamilyEquivalentCustodyReplayHandler;
import com.walletradar.costbasis.application.replay.handler.GenericAsyncLifecycleReplayHandler;
import com.walletradar.costbasis.application.replay.handler.GmxLpEntryReplayHandler;
import com.walletradar.costbasis.application.replay.handler.LiquidStakingReplayHandler;
import com.walletradar.costbasis.application.replay.handler.PositionScopedLpExitReplayHandler;
import com.walletradar.costbasis.application.replay.handler.TransferReplayHandler;
import com.walletradar.costbasis.application.replay.model.AssetKey;
import com.walletradar.costbasis.application.replay.model.PositionSnapshot;
import com.walletradar.costbasis.application.replay.model.PositionState;
import com.walletradar.costbasis.application.replay.planning.ReplayRoutingDecision;
import com.walletradar.costbasis.application.replay.planning.ReplayTransactionRouter;
import com.walletradar.costbasis.application.replay.state.ReplayExecutionState;
import com.walletradar.costbasis.application.replay.support.ReplayAssetSupport;
import com.walletradar.costbasis.application.replay.support.ReplayFlowSupport;
import com.walletradar.costbasis.application.replay.support.ReplayTransferClassifier;
import com.walletradar.costbasis.domain.AssetLedgerPoint;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.springframework.stereotype.Component;

@Component
public class ReplayDispatcher {

    private final ReplayTransactionRouter replayTransactionRouter;
    private final ReplayAssetSupport assetSupport;
    private final ReplayFlowSupport flowSupport;
    private final ReplayTransferClassifier transferClassifier;
    private final TransferReplayHandler transferReplayHandler;
    private final LiquidStakingReplayHandler liquidStakingReplayHandler;
    private final FamilyEquivalentCustodyReplayHandler familyEquivalentCustodyReplayHandler;
    private final GenericAsyncLifecycleReplayHandler genericAsyncLifecycleReplayHandler;
    private final GmxLpEntryReplayHandler gmxLpEntryReplayHandler;
    private final PositionScopedLpExitReplayHandler positionScopedLpExitReplayHandler;
    private final AsyncSpotOrderReplayHandler asyncSpotOrderReplayHandler;
    private final EulerLoopReplayHandler eulerLoopReplayHandler;

    public ReplayDispatcher(
            ReplayTransactionRouter replayTransactionRouter,
            ReplayAssetSupport assetSupport,
            ReplayFlowSupport flowSupport,
            ReplayTransferClassifier transferClassifier,
            TransferReplayHandler transferReplayHandler,
            LiquidStakingReplayHandler liquidStakingReplayHandler,
            FamilyEquivalentCustodyReplayHandler familyEquivalentCustodyReplayHandler,
            GenericAsyncLifecycleReplayHandler genericAsyncLifecycleReplayHandler,
            GmxLpEntryReplayHandler gmxLpEntryReplayHandler,
            PositionScopedLpExitReplayHandler positionScopedLpExitReplayHandler,
            AsyncSpotOrderReplayHandler asyncSpotOrderReplayHandler,
            EulerLoopReplayHandler eulerLoopReplayHandler
    ) {
        this.replayTransactionRouter = replayTransactionRouter;
        this.assetSupport = assetSupport;
        this.flowSupport = flowSupport;
        this.transferClassifier = transferClassifier;
        this.transferReplayHandler = transferReplayHandler;
        this.liquidStakingReplayHandler = liquidStakingReplayHandler;
        this.familyEquivalentCustodyReplayHandler = familyEquivalentCustodyReplayHandler;
        this.genericAsyncLifecycleReplayHandler = genericAsyncLifecycleReplayHandler;
        this.gmxLpEntryReplayHandler = gmxLpEntryReplayHandler;
        this.positionScopedLpExitReplayHandler = positionScopedLpExitReplayHandler;
        this.asyncSpotOrderReplayHandler = asyncSpotOrderReplayHandler;
        this.eulerLoopReplayHandler = eulerLoopReplayHandler;
    }

    public void dispatch(
            NormalizedTransaction transaction,
            ReplayExecutionState replayState
    ) {
        if (transaction == null || Boolean.TRUE.equals(transaction.getExcludedFromAccounting())) {
            return;
        }
        ReplayRoutingDecision routingDecision = replayTransactionRouter.route(
                transaction,
                gmxLpEntryReplayHandler::isGmxLpEntryRequest,
                gmxLpEntryReplayHandler::isGmxLpEntrySettlement,
                positionScopedLpExitReplayHandler::isPositionScopedLpExit,
                liquidStakingReplayHandler::selectPrincipalFlows,
                familyEquivalentCustodyReplayHandler::selectFlows
        );
        switch (routingDecision.route()) {
            case EULER_LOOP -> eulerLoopReplayHandler.apply(transaction, replayState);
            case GMX_LP_ENTRY_REQUEST -> gmxLpEntryReplayHandler.applyRequest(transaction, replayState);
            case GMX_LP_ENTRY_SETTLEMENT -> gmxLpEntryReplayHandler.applySettlement(transaction, replayState);
            case ASYNC_LP_EXIT_SETTLEMENT -> genericAsyncLifecycleReplayHandler.applyAsyncLpExitSettlement(transaction, replayState);
            case POSITION_SCOPED_LP_EXIT -> positionScopedLpExitReplayHandler.apply(transaction, replayState);
            case LIQUID_STAKING -> {
                liquidStakingReplayHandler.applySelected(transaction, routingDecision.liquidStakingSelection(), replayState);
                java.util.Set<Integer> selectedIndexes = new java.util.LinkedHashSet<>();
                selectedIndexes.addAll(routingDecision.liquidStakingSelection().outbound().stream()
                        .map(flow -> flow.index())
                        .toList());
                selectedIndexes.addAll(routingDecision.liquidStakingSelection().inbound().stream()
                        .mapToInt(flow -> flow.index())
                        .boxed()
                        .toList());
                replayGenericFlowsSkipping(transaction, replayState, selectedIndexes);
            }
            case FAMILY_EQUIVALENT_CUSTODY -> {
                familyEquivalentCustodyReplayHandler.applySelected(transaction, routingDecision.familyCustodySelection(), replayState);
                replayGenericFlowsSkipping(
                        transaction,
                        replayState,
                        routingDecision.familyCustodySelection().selectedByIndex().keySet()
                );
            }
            case GENERIC -> replayGenericFlows(transaction, replayState);
        }
    }

    private void replayGenericFlows(
            NormalizedTransaction transaction,
            ReplayExecutionState replayState
    ) {
        replayGenericFlowsSkipping(transaction, replayState, java.util.Set.of());
    }

    private void replayGenericFlowsSkipping(
            NormalizedTransaction transaction,
            ReplayExecutionState replayState,
            java.util.Set<Integer> skippedIndexes
    ) {
        for (int flowIndex = 0; flowIndex < transaction.getFlows().size(); flowIndex++) {
            if (skippedIndexes.contains(flowIndex)) {
                continue;
            }
            NormalizedTransaction.Flow flow = transaction.getFlows().get(flowIndex);
            applyFlow(transaction, flow, flowIndex, replayState);
        }
    }

    private void applyFlow(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            int flowIndex,
            ReplayExecutionState replayState
    ) {
        if (flow == null || flow.getQuantityDelta() == null || flow.getQuantityDelta().signum() == 0) {
            return;
        }
        if (asyncSpotOrderReplayHandler.isAsyncSpotOrderRequestSell(transaction, flow)) {
            applyFlowWithEffect(
                    transaction,
                    flow,
                    flowIndex,
                    replayState,
                    AssetLedgerPoint.BasisEffect.REALLOCATE_OUT,
                    position -> asyncSpotOrderReplayHandler.applyRequest(transaction, flow, position, replayState)
            );
            return;
        }
        if (asyncSpotOrderReplayHandler.isAsyncSpotOrderSettlementBuy(transaction, flow)) {
            applyFlowWithEffect(
                    transaction,
                    flow,
                    flowIndex,
                    replayState,
                    AssetLedgerPoint.BasisEffect.ACQUIRE,
                    position -> asyncSpotOrderReplayHandler.applySettlement(transaction, flow, position, replayState)
            );
            return;
        }
        if (genericAsyncLifecycleReplayHandler.isAsyncLifecycleRequestOutbound(transaction, flow)) {
            applyFlowWithEffect(
                    transaction,
                    flow,
                    flowIndex,
                    replayState,
                    AssetLedgerPoint.BasisEffect.REALLOCATE_OUT,
                    position -> genericAsyncLifecycleReplayHandler.applyAsyncLifecycleRequest(transaction, flow, position, replayState)
            );
            return;
        }
        if (genericAsyncLifecycleReplayHandler.isAsyncLifecycleSettlementInbound(transaction, flow)) {
            applyFlowWithEffect(
                    transaction,
                    flow,
                    flowIndex,
                    replayState,
                    AssetLedgerPoint.BasisEffect.REALLOCATE_IN,
                    position -> genericAsyncLifecycleReplayHandler.applyAsyncLifecycleSettlement(transaction, flow, position, replayState)
            );
            return;
        }
        if (genericAsyncLifecycleReplayHandler.isPositionScopedLpEntryOutbound(transaction, flow)) {
            applyFlowWithEffect(
                    transaction,
                    flow,
                    flowIndex,
                    replayState,
                    AssetLedgerPoint.BasisEffect.REALLOCATE_OUT,
                    position -> genericAsyncLifecycleReplayHandler.applyAsyncLifecycleRequest(
                            transaction,
                            flow,
                            position,
                            replayState,
                            assetSupport.continuityIdentity(transaction, flow)
                    )
            );
            return;
        }
        if (positionScopedLpExitReplayHandler.shouldIgnoreLpReceiptMarker(transaction, flow)) {
            return;
        }

        AssetKey assetKey = assetSupport.assetKey(transaction, flow);
        PositionState position = replayState.position(assetKey);
        position.setLastEventTimestamp(flowSupport.laterOf(position.lastEventTimestamp(), transaction.getBlockTimestamp()));
        PositionSnapshot before = flowSupport.snapshot(position);

        if (isSponsoredGasIn(transaction, flow)) {
            flowSupport.applySponsoredGasIn(flow, position);
            replayState.ledgerPointCollector().record(
                    transaction,
                    flow,
                    flowIndex,
                    position.assetKey(),
                    before,
                    position,
                    AssetLedgerPoint.BasisEffect.ACQUIRE
            );
            return;
        }

        if (transferClassifier.shouldTreatAsContinuityTransfer(transaction, flow)) {
            AssetLedgerPoint.BasisEffect basisEffect = transferReplayHandler.applyTransfer(
                    transaction,
                    flowSupport.asTransferFlow(flow),
                    flowIndex,
                    position,
                    replayState
            );
            replayState.ledgerPointCollector().record(
                    transaction,
                    flow,
                    flowIndex,
                    position.assetKey(),
                    before,
                    position,
                    basisEffect
            );
            return;
        }

        switch (flow.getRole()) {
            case BUY -> flowSupport.applyBuy(flow, position);
            case SELL -> flowSupport.applySell(flow, position);
            case FEE -> flowSupport.applyFee(flow, position);
            case TRANSFER -> {
                AssetLedgerPoint.BasisEffect basisEffect = transferReplayHandler.applyTransfer(
                        transaction,
                        flow,
                        flowIndex,
                        position,
                        replayState
                );
                replayState.ledgerPointCollector().record(
                        transaction,
                        flow,
                        flowIndex,
                        position.assetKey(),
                        before,
                        position,
                        basisEffect
                );
                return;
            }
        }
        replayState.ledgerPointCollector().record(
                transaction,
                flow,
                flowIndex,
                position.assetKey(),
                before,
                position,
                flowSupport.defaultBasisEffect(flow)
        );
    }

    private void applyFlowWithEffect(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            int flowIndex,
            ReplayExecutionState replayState,
            AssetLedgerPoint.BasisEffect basisEffect,
            java.util.function.Consumer<PositionState> action
    ) {
        AssetKey assetKey = assetSupport.assetKey(transaction, flow);
        PositionState position = replayState.position(assetKey);
        position.setLastEventTimestamp(flowSupport.laterOf(position.lastEventTimestamp(), transaction.getBlockTimestamp()));
        PositionSnapshot before = flowSupport.snapshot(position);
        action.accept(position);
        replayState.ledgerPointCollector().record(
                transaction,
                flow,
                flowIndex,
                position.assetKey(),
                before,
                position,
                basisEffect
        );
    }

    private boolean isSponsoredGasIn(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow
    ) {
        return transaction != null
                && flow != null
                && transaction.getType() == NormalizedTransactionType.SPONSORED_GAS_IN
                && flow.getRole() == NormalizedLegRole.TRANSFER
                && flow.getQuantityDelta() != null
                && flow.getQuantityDelta().signum() > 0;
    }
}
