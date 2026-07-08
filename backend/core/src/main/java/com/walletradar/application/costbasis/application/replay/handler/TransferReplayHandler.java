package com.walletradar.application.costbasis.application.replay.handler;

import com.walletradar.application.costbasis.application.replay.model.ContinuityBucket;
import com.walletradar.application.costbasis.application.replay.model.PositionState;
import com.walletradar.application.costbasis.application.replay.model.TransferPendingKey;
import com.walletradar.application.costbasis.application.replay.state.ReplayExecutionState;
import com.walletradar.application.costbasis.application.replay.support.ContinuityCarryService;
import com.walletradar.application.costbasis.application.replay.support.LinkedBridgeTransferReplaySupport;
import com.walletradar.application.costbasis.application.replay.support.ReplayFlowSupport;
import com.walletradar.application.costbasis.application.replay.support.ReplayMarketAuthority;
import com.walletradar.application.costbasis.application.replay.support.ReplayPendingTransferKeyFactory;
import com.walletradar.application.costbasis.application.replay.support.ReplayPendingTransferMatcher;
import com.walletradar.application.costbasis.application.replay.support.ReplayTransferClassifier;
import com.walletradar.application.costbasis.domain.AssetLedgerPoint;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class TransferReplayHandler {

    private final ReplayFlowSupport flowSupport;
    private final ContinuityCarryService continuityCarryService;
    private final ReplayPendingTransferKeyFactory keyFactory;
    private final ReplayTransferClassifier classifier;
    private final LinkedBridgeTransferReplaySupport linkedBridgeTransferReplaySupport;
    private final BridgeTransferReplaySupport bridgeTransferReplaySupport;
    private final EarnBundleTransferReplaySupport earnBundleTransferReplaySupport;
    private final CarryTransferReplaySupport carryTransferReplaySupport;

    public TransferReplayHandler(
            ReplayFlowSupport flowSupport,
            ContinuityCarryService continuityCarryService,
            ReplayPendingTransferKeyFactory keyFactory,
            ReplayTransferClassifier classifier,
            LinkedBridgeTransferReplaySupport linkedBridgeTransferReplaySupport,
            BridgeTransferReplaySupport bridgeTransferReplaySupport,
            EarnBundleTransferReplaySupport earnBundleTransferReplaySupport,
            CarryTransferReplaySupport carryTransferReplaySupport
    ) {
        this.flowSupport = flowSupport;
        this.continuityCarryService = continuityCarryService;
        this.keyFactory = keyFactory;
        this.classifier = classifier;
        this.linkedBridgeTransferReplaySupport = linkedBridgeTransferReplaySupport;
        this.bridgeTransferReplaySupport = bridgeTransferReplaySupport;
        this.earnBundleTransferReplaySupport = earnBundleTransferReplaySupport;
        this.carryTransferReplaySupport = carryTransferReplaySupport;
    }

    /**
     * Routes a combined BORROW transaction's outbound non-debt flow (collateral deposited into
     * the lending protocol) to the continuity bucket, so the matching LENDING_WITHDRAW transaction
     * can restore the basis via {@link ReplayTransferClassifier#isBucketInbound}.
     *
     * @return {@link AssetLedgerPoint.BasisEffect#REALLOCATE_OUT} to mark the carry as pending.
     */
    public AssetLedgerPoint.BasisEffect applyBorrowCollateralOut(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            int flowIndex,
            PositionState position,
            ReplayExecutionState replayState
    ) {
        continuityCarryService.moveToBucket(
                continuityCarryService.removeTransferCarry(
                        transaction,
                        flow,
                        flowIndex,
                        position,
                        replayState.passThroughCorridorPlan(),
                        replayState.reservedPassThroughCarries(),
                        false
                ),
                replayState.continuity().bucket(keyFactory.continuityKey(transaction, flow))
        );
        flowSupport.purgeOrphanBasisWhenEmpty(position);
        return AssetLedgerPoint.BasisEffect.REALLOCATE_OUT;
    }

    public AssetLedgerPoint.BasisEffect applyTransfer(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            int flowIndex,
            PositionState position,
            ReplayExecutionState replayState
    ) {
        if (classifier.isLinkedBridgeContinuityTransfer(transaction, flow)) {
            return linkedBridgeTransferReplaySupport.applyLinkedBridgeTransfer(
                    transaction,
                    flow,
                    flowIndex,
                    position,
                    replayState,
                    bridgeTransferReplaySupport::enqueuePendingInbound
            );
        }
        if (classifier.isLinkedBridgeSettlementTransfer(transaction, flow)) {
            return linkedBridgeTransferReplaySupport.applyLinkedBridgeSettlementTransfer(
                    transaction,
                    flow,
                    flowIndex,
                    position,
                    replayState,
                    bridgeTransferReplaySupport::enqueuePendingInbound
            );
        }
        if (classifier.isFamilyEquivalentCustodyTransfer(transaction, flow)) {
            return applyFamilyEquivalentCustodyTransfer(transaction, flow, flowIndex, position, replayState);
        }
        if (classifier.isBucketOutbound(transaction, flow)) {
            return applyBucketOutboundTransfer(transaction, flow, flowIndex, position, replayState);
        }
        if (classifier.isBucketInbound(transaction, flow)) {
            return applyBucketInboundTransfer(transaction, flow, position, replayState);
        }
        if (classifier.isBybitMultiLegBundleTransfer(transaction)) {
            return earnBundleTransferReplaySupport.applyBybitMultiLegBundleTransfer(
                    transaction, flow, flowIndex, position, replayState
            );
        }

        TransferPendingKey transferKey = keyFactory.transferKey(transaction, flow);
        if (transferKey == null) {
            flowSupport.applyUnknownTransfer(flow, position);
            return AssetLedgerPoint.BasisEffect.UNKNOWN;
        }
        return bridgeTransferReplaySupport.applyPendingTransfer(
                transaction,
                flow,
                flowIndex,
                position,
                replayState,
                transferKey,
                earnBundleTransferReplaySupport,
                carryTransferReplaySupport
        );
    }

    public void accumulateInboundSpotFallbackProvisional(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            BigDecimal spotFallbackBasisUsd,
            ReplayExecutionState replayState
    ) {
        bridgeTransferReplaySupport.accumulateInboundSpotFallbackProvisional(
                transaction,
                flow,
                spotFallbackBasisUsd,
                replayState
        );
    }

    private AssetLedgerPoint.BasisEffect applyFamilyEquivalentCustodyTransfer(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            int flowIndex,
            PositionState position,
            ReplayExecutionState replayState
    ) {
        ContinuityBucket bucket = replayState.continuity().bucket(keyFactory.continuityKey(transaction, flow));
        if (flow.getQuantityDelta().signum() < 0) {
            PositionState carrySource = carryTransferReplaySupport.resolveCarrySourcePosition(
                    transaction, flow, position, replayState, false, flow.getQuantityDelta().abs());
            boolean redirectedToInventory = carrySource != position;
            continuityCarryService.moveToBucket(
                    continuityCarryService.removeTransferCarry(
                            transaction,
                            flow,
                            flowIndex,
                            carrySource,
                            replayState.passThroughCorridorPlan(),
                            replayState.reservedPassThroughCarries(),
                            redirectedToInventory
                    ),
                    bucket
            );
            flowSupport.purgeOrphanBasisWhenEmpty(carrySource);
            if (redirectedToInventory) {
                flowSupport.purgeOrphanBasisWhenEmpty(position);
            }
            return flowSupport.continuityBasisEffect(transaction, flow);
        }
        if (transaction.getType() == NormalizedTransactionType.PROTOCOL_CUSTODY_WITHDRAW
                && bucket.quantity().signum() == 0) {
            flowSupport.applyBuy(flow, position);
            return flowSupport.continuityBasisEffect(transaction, flow);
        }
        restoreFromBucket(transaction, flow, position, bucket);
        return flowSupport.continuityBasisEffect(transaction, flow);
    }

    private AssetLedgerPoint.BasisEffect applyBucketOutboundTransfer(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            int flowIndex,
            PositionState position,
            ReplayExecutionState replayState
    ) {
        continuityCarryService.moveToBucket(
                continuityCarryService.removeTransferCarry(
                        transaction,
                        flow,
                        flowIndex,
                        position,
                        replayState.passThroughCorridorPlan(),
                        replayState.reservedPassThroughCarries(),
                        CarryTransferReplaySupport.preserveBucketOutboundCoverage(transaction)
                ),
                replayState.continuity().bucket(keyFactory.continuityKey(transaction, flow))
        );
        flowSupport.purgeOrphanBasisWhenEmpty(position);
        return flowSupport.continuityBasisEffect(transaction, flow);
    }

    private AssetLedgerPoint.BasisEffect applyBucketInboundTransfer(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            PositionState position,
            ReplayExecutionState replayState
    ) {
        ContinuityBucket bucket = replayState.continuity().bucket(keyFactory.continuityKey(transaction, flow));
        if (keyFactory.usesWrapperCompositeBucket(transaction)) {
            carryTransferReplaySupport.restoreFullBucket(transaction, flow, position, bucket);
        } else if (transaction.getType() == NormalizedTransactionType.PROTOCOL_CUSTODY_WITHDRAW
                && bucket.quantity().signum() == 0) {
            flowSupport.applyBuy(flow, position);
        } else {
            restoreFromBucket(transaction, flow, position, bucket);
        }
        return flowSupport.continuityBasisEffect(transaction, flow);
    }

    private void restoreFromBucket(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            PositionState position,
            ContinuityBucket bucket
    ) {
        if (transaction.getType() == NormalizedTransactionType.LENDING_DEPOSIT) {
            carryTransferReplaySupport.restoreFromBucketLendingDepositUsdWeighted(transaction, flow, position, bucket);
        } else {
            carryTransferReplaySupport.restoreFromContinuityBucket(flow, position, bucket);
        }
    }
}
