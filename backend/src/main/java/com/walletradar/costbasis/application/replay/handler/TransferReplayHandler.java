package com.walletradar.costbasis.application.replay.handler;

import com.walletradar.costbasis.application.replay.model.AssetKey;
import com.walletradar.costbasis.application.replay.model.BridgePendingKey;
import com.walletradar.costbasis.application.replay.model.BridgeSettlementPendingKey;
import com.walletradar.costbasis.application.replay.model.CarryTransfer;
import com.walletradar.costbasis.application.replay.model.ContinuityBucket;
import com.walletradar.costbasis.application.replay.model.PositionSnapshot;
import com.walletradar.costbasis.application.replay.model.PositionState;
import com.walletradar.costbasis.application.replay.model.TransferPendingKey;
import com.walletradar.costbasis.application.replay.persistence.LedgerPointCollector;
import com.walletradar.costbasis.application.replay.state.PositionStore;
import com.walletradar.costbasis.application.replay.state.ReplayExecutionState;
import com.walletradar.costbasis.application.replay.support.ContinuityCarryService;
import com.walletradar.costbasis.application.replay.support.ReplayFlowSupport;
import com.walletradar.costbasis.application.replay.support.ReplayPendingTransferKeyFactory;
import com.walletradar.costbasis.application.replay.support.ReplayPendingTransferMatcher;
import com.walletradar.costbasis.application.replay.support.ReplayTransferClassifier;
import com.walletradar.costbasis.domain.AssetLedgerPoint;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Deque;

@Component
public class TransferReplayHandler {

    private static final MathContext MC = MathContext.DECIMAL128;

    private final ReplayFlowSupport flowSupport;
    private final ContinuityCarryService continuityCarryService;
    private final ReplayPendingTransferKeyFactory keyFactory;
    private final ReplayTransferClassifier classifier;
    private final ReplayPendingTransferMatcher matcher;

    public TransferReplayHandler(
            ReplayFlowSupport flowSupport,
            ContinuityCarryService continuityCarryService,
            ReplayPendingTransferKeyFactory keyFactory,
            ReplayTransferClassifier classifier,
            ReplayPendingTransferMatcher matcher
    ) {
        this.flowSupport = flowSupport;
        this.continuityCarryService = continuityCarryService;
        this.keyFactory = keyFactory;
        this.classifier = classifier;
        this.matcher = matcher;
    }

    public AssetLedgerPoint.BasisEffect applyTransfer(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            int flowIndex,
            PositionState position,
            ReplayExecutionState replayState
    ) {
        if (classifier.isLinkedBridgeContinuityTransfer(transaction, flow)) {
            return applyLinkedBridgeTransfer(transaction, flow, flowIndex, position, replayState);
        }
        if (classifier.isLinkedBridgeSettlementTransfer(transaction, flow)) {
            return applyLinkedBridgeSettlementTransfer(transaction, flow, flowIndex, position, replayState);
        }
        if (classifier.isFamilyEquivalentCustodyTransfer(transaction, flow)) {
            ContinuityBucket bucket = replayState.continuity().bucket(keyFactory.continuityKey(transaction, flow));
            if (flow.getQuantityDelta().signum() < 0) {
                continuityCarryService.moveToBucket(
                        continuityCarryService.removeTransferCarry(
                                transaction,
                                flow,
                                flowIndex,
                                position,
                                replayState.passThroughCorridorPlan(),
                                replayState.reservedPassThroughCarries()
                        ),
                        bucket
                );
                flowSupport.purgeOrphanBasisWhenEmpty(position);
                return flowSupport.continuityBasisEffect(transaction, flow);
            }
            restoreFromContinuityBucket(flow, position, bucket);
            return flowSupport.continuityBasisEffect(transaction, flow);
        }
        if (classifier.isBucketOutbound(transaction, flow)) {
            continuityCarryService.moveToBucket(
                    continuityCarryService.removeTransferCarry(
                            transaction,
                            flow,
                            flowIndex,
                            position,
                            replayState.passThroughCorridorPlan(),
                            replayState.reservedPassThroughCarries()
                    ),
                    replayState.continuity().bucket(keyFactory.continuityKey(transaction, flow))
            );
            flowSupport.purgeOrphanBasisWhenEmpty(position);
            return flowSupport.continuityBasisEffect(transaction, flow);
        }
        if (classifier.isBucketInbound(transaction, flow)) {
            restoreFromContinuityBucket(
                    flow,
                    position,
                    replayState.continuity().bucket(keyFactory.continuityKey(transaction, flow))
            );
            return flowSupport.continuityBasisEffect(transaction, flow);
        }
        if (classifier.isBybitMultiLegBundleTransfer(transaction)) {
            return applyBybitMultiLegBundleTransfer(transaction, flow, flowIndex, position, replayState);
        }

        TransferPendingKey transferKey = keyFactory.transferKey(transaction, flow);
        if (transferKey == null) {
            flowSupport.applyUnknownTransfer(flow, position);
            return AssetLedgerPoint.BasisEffect.UNKNOWN;
        }

        if (flow.getQuantityDelta().signum() < 0) {
            CarryTransfer carry = continuityCarryService.removeTransferCarry(
                    transaction,
                    flow,
                    flowIndex,
                    position,
                    replayState.passThroughCorridorPlan(),
                    replayState.reservedPassThroughCarries()
            );
            Deque<CarryTransfer> queue = replayState.pendingTransfers().queue(transferKey);
            int pendingInboundIndex = matcher.findUniqueCompatibleQueueIndex(queue, true, carry.quantity());
            if (pendingInboundIndex >= 0) {
                CarryTransfer pendingInbound = matcher.removeQueueElement(queue, pendingInboundIndex);
                attachLateCarryToPendingInbound(
                        transaction,
                        flow,
                        flowIndex,
                        replayState.positions(),
                        pendingInbound,
                        carry,
                        replayState.ledgerPointCollector()
                );
                if (queue.isEmpty()) {
                    replayState.pendingTransfers().remove(transferKey);
                }
                return flowSupport.continuityBasisEffect(transaction, flow);
            }
            queue.addLast(carry);
            return flowSupport.continuityBasisEffect(transaction, flow);
        }

        Deque<CarryTransfer> queue = replayState.pendingTransfers().find(transferKey);
        int carryIndex = matcher.findUniqueCompatibleQueueIndex(queue, false, flow.getQuantityDelta().abs());
        if (carryIndex >= 0) {
            CarryTransfer carry = matcher.removeQueueElement(queue, carryIndex);
            CarryTransfer effectiveCarry = continuityCarryService.sliceCarryTransfer(carry, flow.getQuantityDelta().abs(), position.assetKey());
            flowSupport.restoreToPosition(
                    flow.getQuantityDelta().abs(),
                    position,
                    effectiveCarry.costBasisUsd(),
                    effectiveCarry.uncoveredQuantity(),
                    effectiveCarry.avco()
            );
            continuityCarryService.reservePassThroughCarryIfPlanned(
                    transaction,
                    flowIndex,
                    effectiveCarry,
                    replayState.passThroughCorridorPlan(),
                    replayState.reservedPassThroughCarries()
            );
            if (queue.isEmpty()) {
                replayState.pendingTransfers().remove(transferKey);
            }
            return flowSupport.continuityBasisEffect(transaction, flow);
        }

        enqueuePendingInbound(flow, position, replayState, transferKey);
        return flowSupport.continuityBasisEffect(transaction, flow);
    }

    /**
     * Cycle/18 R9: after inbound shortfall spot fallback promotes provisional basis on a queued
     * pending inbound, record the exact USD added so late carry can replace it.
     */
    public void accumulateInboundSpotFallbackProvisional(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            BigDecimal spotFallbackBasisUsd,
            ReplayExecutionState replayState
    ) {
        if (transaction == null
                || flow == null
                || flow.getQuantityDelta() == null
                || flow.getQuantityDelta().signum() <= 0
                || spotFallbackBasisUsd == null
                || spotFallbackBasisUsd.signum() <= 0) {
            return;
        }
        NormalizedTransaction.Flow transferFlow = flowSupport.asTransferFlow(flow);
        BridgePendingKey bridgeKey = keyFactory.bridgeTransferKey(transaction, transferFlow);
        if (bridgeKey != null) {
            replayState.pendingTransfers().addProvisionalBasisToPendingInbounds(bridgeKey, spotFallbackBasisUsd);
        }
        BridgeSettlementPendingKey settlementKey = keyFactory.bridgeSettlementKey(transaction, transferFlow);
        if (settlementKey != null) {
            replayState.pendingTransfers().addProvisionalBasisToPendingInbounds(settlementKey, spotFallbackBasisUsd);
        }
        TransferPendingKey transferKey = keyFactory.transferKey(transaction, transferFlow);
        if (transferKey != null) {
            replayState.pendingTransfers().addProvisionalBasisToPendingInbounds(transferKey, spotFallbackBasisUsd);
        }
    }

    private void enqueuePendingInbound(
            NormalizedTransaction.Flow flow,
            PositionState position,
            ReplayExecutionState replayState,
            com.walletradar.costbasis.application.replay.model.PendingTransferKey key
    ) {
        BigDecimal provisionalBasis = flowSupport.materializePendingInbound(flow, position);
        replayState.pendingTransfers().queue(key)
                .addLast(CarryTransfer.pendingInbound(
                        flow.getQuantityDelta().abs(),
                        position.assetKey(),
                        provisionalBasis
                ));
    }

    private AssetLedgerPoint.BasisEffect applyLinkedBridgeTransfer(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            int flowIndex,
            PositionState position,
            ReplayExecutionState replayState
    ) {
        BridgePendingKey bridgeTransferKey = keyFactory.bridgeTransferKey(transaction, flow);
        if (bridgeTransferKey == null) {
            flowSupport.applyUnknownTransfer(flow, position);
            return AssetLedgerPoint.BasisEffect.UNKNOWN;
        }

        if (flow.getQuantityDelta().signum() < 0) {
            CarryTransfer carry = continuityCarryService.removeTransferCarry(
                    transaction,
                    flow,
                    flowIndex,
                    position,
                    replayState.passThroughCorridorPlan(),
                    replayState.reservedPassThroughCarries()
            );
            Deque<CarryTransfer> queue = replayState.pendingTransfers().queue(bridgeTransferKey);
            int pendingInboundIndex = matcher.findUniqueBridgeQueueIndex(queue, true);
            if (pendingInboundIndex >= 0) {
                CarryTransfer pendingInbound = matcher.removeQueueElement(queue, pendingInboundIndex);
                attachLateBridgeCarryToPendingInbound(
                        transaction,
                        flow,
                        flowIndex,
                        replayState.positions(),
                        pendingInbound,
                        carry,
                        replayState.ledgerPointCollector()
                );
                if (queue.isEmpty()) {
                    replayState.pendingTransfers().remove(bridgeTransferKey);
                }
                return flowSupport.continuityBasisEffect(transaction, flow);
            }
            queue.addLast(carry);
            return flowSupport.continuityBasisEffect(transaction, flow);
        }

        Deque<CarryTransfer> queue = replayState.pendingTransfers().find(bridgeTransferKey);
        int carryIndex = matcher.findUniqueBridgeQueueIndex(queue, false);
        if (carryIndex >= 0) {
            CarryTransfer carry = matcher.removeQueueElement(queue, carryIndex);
            CarryTransfer effectiveCarry = continuityCarryService.bridgeInboundCarry(carry, flow.getQuantityDelta().abs(), position.assetKey());
            flowSupport.restoreToPosition(
                    flow.getQuantityDelta().abs(),
                    position,
                    effectiveCarry.costBasisUsd(),
                    effectiveCarry.uncoveredQuantity(),
                    effectiveCarry.avco()
            );
            continuityCarryService.reservePassThroughCarryIfPlanned(
                    transaction,
                    flowIndex,
                    effectiveCarry,
                    replayState.passThroughCorridorPlan(),
                    replayState.reservedPassThroughCarries()
            );
            if (queue.isEmpty()) {
                replayState.pendingTransfers().remove(bridgeTransferKey);
            }
            return flowSupport.continuityBasisEffect(transaction, flow);
        }

        enqueuePendingInbound(flow, position, replayState, bridgeTransferKey);
        return flowSupport.continuityBasisEffect(transaction, flow);
    }

    private AssetLedgerPoint.BasisEffect applyLinkedBridgeSettlementTransfer(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            int flowIndex,
            PositionState position,
            ReplayExecutionState replayState
    ) {
        BridgeSettlementPendingKey settlementKey = keyFactory.bridgeSettlementKey(transaction, flow);
        if (settlementKey == null) {
            flowSupport.applyUnknownTransfer(flow, position);
            return AssetLedgerPoint.BasisEffect.UNKNOWN;
        }

        if (flow.getQuantityDelta().signum() < 0) {
            CarryTransfer carry = continuityCarryService.removeTransferCarry(
                    transaction,
                    flow,
                    flowIndex,
                    position,
                    replayState.passThroughCorridorPlan(),
                    replayState.reservedPassThroughCarries()
            );
            Deque<CarryTransfer> queue = replayState.pendingTransfers().queue(settlementKey);
            int pendingInboundIndex = matcher.findUniqueBridgeQueueIndex(queue, true);
            if (pendingInboundIndex >= 0) {
                CarryTransfer pendingInbound = matcher.removeQueueElement(queue, pendingInboundIndex);
                attachLateBridgeSettlementCarryToPendingInbound(
                        transaction,
                        flow,
                        flowIndex,
                        replayState.positions(),
                        pendingInbound,
                        carry,
                        replayState.ledgerPointCollector()
                );
                if (queue.isEmpty()) {
                    replayState.pendingTransfers().remove(settlementKey);
                }
                return flowSupport.routeSettlementBasisEffect(flow);
            }
            queue.addLast(carry);
            return flowSupport.routeSettlementBasisEffect(flow);
        }

        Deque<CarryTransfer> queue = replayState.pendingTransfers().find(settlementKey);
        int carryIndex = matcher.findUniqueBridgeQueueIndex(queue, false);
        if (carryIndex >= 0) {
            CarryTransfer carry = matcher.removeQueueElement(queue, carryIndex);
            CarryTransfer effectiveCarry = continuityCarryService.bridgeSettlementInboundCarry(
                    carry,
                    flow.getQuantityDelta().abs(),
                    position.assetKey()
            );
            flowSupport.restoreToPosition(
                    flow.getQuantityDelta().abs(),
                    position,
                    effectiveCarry.costBasisUsd(),
                    effectiveCarry.uncoveredQuantity(),
                    effectiveCarry.avco()
            );
            if (queue.isEmpty()) {
                replayState.pendingTransfers().remove(settlementKey);
            }
            return flowSupport.routeSettlementBasisEffect(flow);
        }

        enqueuePendingInbound(flow, position, replayState, settlementKey);
        return flowSupport.routeSettlementBasisEffect(flow);
    }

    /**
     * Cycle/12: N-way Bybit bundle ({@code bybit-it-bundle-v1:*}) uses the shared {@code corr-family}
     * pending queue. Inbound legs drain every parked outbound carry (qty-agnostic bridge match) so
     * UTA+FUND outflows jointly restore basis on the EARN inbound even when timestamps are skewed.
     */
    private AssetLedgerPoint.BasisEffect applyBybitMultiLegBundleTransfer(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            int flowIndex,
            PositionState position,
            ReplayExecutionState replayState
    ) {
        TransferPendingKey transferKey = keyFactory.transferKey(transaction, flow);
        if (transferKey == null) {
            flowSupport.applyUnknownTransfer(flow, position);
            return AssetLedgerPoint.BasisEffect.UNKNOWN;
        }

        if (flow.getQuantityDelta().signum() < 0) {
            CarryTransfer carry = continuityCarryService.removeTransferCarry(
                    transaction,
                    flow,
                    flowIndex,
                    position,
                    replayState.passThroughCorridorPlan(),
                    replayState.reservedPassThroughCarries()
            );
            Deque<CarryTransfer> queue = replayState.pendingTransfers().queue(transferKey);
            int pendingInboundIndex = matcher.findUniqueBridgeQueueIndex(queue, true);
            if (pendingInboundIndex >= 0) {
                CarryTransfer pendingInbound = matcher.removeQueueElement(queue, pendingInboundIndex);
                attachLateCarryToPendingInbound(
                        transaction,
                        flow,
                        flowIndex,
                        replayState.positions(),
                        pendingInbound,
                        carry,
                        replayState.ledgerPointCollector()
                );
                if (queue.isEmpty()) {
                    replayState.pendingTransfers().remove(transferKey);
                }
                return flowSupport.continuityBasisEffect(transaction, flow);
            }
            queue.addLast(carry);
            return flowSupport.continuityBasisEffect(transaction, flow);
        }

        Deque<CarryTransfer> queue = replayState.pendingTransfers().find(transferKey);
        BigDecimal remaining = flow.getQuantityDelta().abs();
        boolean restoredAny = false;
        while (remaining.signum() > 0) {
            int carryIndex = matcher.findUniqueBridgeQueueIndex(queue, false);
            if (carryIndex < 0) {
                break;
            }
            CarryTransfer carry = matcher.removeQueueElement(queue, carryIndex);
            BigDecimal takeQty = remaining.min(carry.quantity());
            CarryTransfer effectiveCarry = continuityCarryService.sliceCarryTransfer(
                    carry,
                    takeQty,
                    position.assetKey()
            );
            flowSupport.restoreToPosition(
                    takeQty,
                    position,
                    effectiveCarry.costBasisUsd(),
                    effectiveCarry.uncoveredQuantity(),
                    effectiveCarry.avco()
            );
            continuityCarryService.reservePassThroughCarryIfPlanned(
                    transaction,
                    flowIndex,
                    effectiveCarry,
                    replayState.passThroughCorridorPlan(),
                    replayState.reservedPassThroughCarries()
            );
            remaining = remaining.subtract(takeQty, MC);
            restoredAny = true;
        }
        if (queue != null && queue.isEmpty()) {
            replayState.pendingTransfers().remove(transferKey);
        }
        if (remaining.signum() > 0) {
            NormalizedTransaction.Flow remainderFlow = flow;
            if (restoredAny) {
                remainderFlow = flowWithQuantity(flow, remaining);
            }
            BigDecimal provisionalBasis = flowSupport.materializePendingInbound(remainderFlow, position);
            replayState.pendingTransfers().queue(transferKey)
                    .addLast(CarryTransfer.pendingInbound(remaining, position.assetKey(), provisionalBasis));
        }
        return flowSupport.continuityBasisEffect(transaction, flow);
    }

    private static NormalizedTransaction.Flow flowWithQuantity(
            NormalizedTransaction.Flow source,
            BigDecimal quantity
    ) {
        NormalizedTransaction.Flow copy = new NormalizedTransaction.Flow();
        copy.setRole(source.getRole());
        copy.setAssetSymbol(source.getAssetSymbol());
        copy.setAssetContract(source.getAssetContract());
        copy.setQuantityDelta(quantity);
        copy.setCounterpartyAddress(source.getCounterpartyAddress());
        copy.setCounterpartyType(source.getCounterpartyType());
        copy.setAccountRef(source.getAccountRef());
        return copy;
    }

    private void restoreFromContinuityBucket(
            NormalizedTransaction.Flow flow,
            PositionState position,
            ContinuityBucket bucket
    ) {
        CarryTransfer carry = continuityCarryService.takeFromBucket(bucket, flow.getQuantityDelta().abs(), position.assetKey());
        if (carry == null) {
            flowSupport.applyUnknownTransfer(flow, position);
            return;
        }
        flowSupport.restoreToPosition(
                flow.getQuantityDelta().abs(),
                position,
                carry.costBasisUsd(),
                carry.uncoveredQuantity(),
                carry.avco()
        );
    }

    private void attachLateCarryToPendingInbound(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            int flowIndex,
            PositionStore positions,
            CarryTransfer pendingInbound,
            CarryTransfer carry,
            LedgerPointCollector ledgerPointCollector
    ) {
        PositionState destination = positions.position(pendingInbound.assetKey());
        PositionSnapshot before = flowSupport.snapshot(destination);
        CarryTransfer effectiveCarry = continuityCarryService.sliceCarryTransfer(carry, pendingInbound.quantity(), pendingInbound.assetKey());
        BigDecimal coveredResolvedQuantity = effectiveCarry.coveredQuantity();
        destination.setUncoveredQuantity(nonNegative(destination.uncoveredQuantity().subtract(coveredResolvedQuantity, MC)));
        destination.setTotalCostBasisUsd(destination.totalCostBasisUsd().add(effectiveCarry.costBasisUsd()));
        flowSupport.recomputePerWalletAvco(destination);
        if (effectiveCarry.avco() != null && effectiveCarry.uncoveredQuantity().signum() == 0) {
            flowSupport.resolveTemporaryUnresolved(destination);
        }
        ledgerPointCollector.record(
                transaction,
                flow,
                flowIndex,
                destination.assetKey(),
                before,
                destination,
                AssetLedgerPoint.BasisEffect.CARRY_IN
        );
    }

    private void attachLateBridgeCarryToPendingInbound(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            int flowIndex,
            PositionStore positions,
            CarryTransfer pendingInbound,
            CarryTransfer carry,
            LedgerPointCollector ledgerPointCollector
    ) {
        PositionState destination = positions.position(pendingInbound.assetKey());
        PositionSnapshot before = flowSupport.snapshot(destination);
        CarryTransfer effectiveCarry = continuityCarryService.bridgeInboundCarry(carry, pendingInbound.quantity(), pendingInbound.assetKey());
        BigDecimal coveredResolvedQuantity = effectiveCarry.coveredQuantity();
        destination.setUncoveredQuantity(nonNegative(destination.uncoveredQuantity().subtract(coveredResolvedQuantity, MC)));
        flowSupport.applyAuthoritativeLateInboundCarryBasis(
                destination,
                pendingInbound.provisionalBasisUsd(),
                effectiveCarry.costBasisUsd()
        );
        flowSupport.recomputePerWalletAvco(destination);
        if (effectiveCarry.avco() != null && destination.uncoveredQuantity().signum() == 0) {
            flowSupport.resolveTemporaryUnresolved(destination);
        }
        ledgerPointCollector.record(
                transaction,
                flow,
                flowIndex,
                destination.assetKey(),
                before,
                destination,
                AssetLedgerPoint.BasisEffect.CARRY_IN
        );
    }

    private void attachLateBridgeSettlementCarryToPendingInbound(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            int flowIndex,
            PositionStore positions,
            CarryTransfer pendingInbound,
            CarryTransfer carry,
            LedgerPointCollector ledgerPointCollector
    ) {
        PositionState destination = positions.position(pendingInbound.assetKey());
        PositionSnapshot before = flowSupport.snapshot(destination);
        CarryTransfer effectiveCarry = continuityCarryService.bridgeSettlementInboundCarry(
                carry,
                pendingInbound.quantity(),
                pendingInbound.assetKey()
        );
        BigDecimal coveredResolvedQuantity = effectiveCarry.coveredQuantity();
        destination.setUncoveredQuantity(nonNegative(destination.uncoveredQuantity().subtract(coveredResolvedQuantity, MC)));
        flowSupport.applyAuthoritativeLateInboundCarryBasis(
                destination,
                pendingInbound.provisionalBasisUsd(),
                effectiveCarry.costBasisUsd()
        );
        flowSupport.recomputePerWalletAvco(destination);
        if (effectiveCarry.avco() != null && destination.uncoveredQuantity().signum() == 0) {
            flowSupport.resolveTemporaryUnresolved(destination);
        }
        ledgerPointCollector.record(
                transaction,
                flow,
                flowIndex,
                destination.assetKey(),
                before,
                destination,
                AssetLedgerPoint.BasisEffect.REALLOCATE_IN
        );
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        return value.signum() < 0 ? BigDecimal.ZERO : value;
    }
}
