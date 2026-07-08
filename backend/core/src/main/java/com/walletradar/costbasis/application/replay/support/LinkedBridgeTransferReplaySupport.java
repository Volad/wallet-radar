package com.walletradar.costbasis.application.replay.support;

import com.walletradar.costbasis.application.replay.model.BridgePendingKey;
import com.walletradar.costbasis.application.replay.model.BridgeSettlementPendingKey;
import com.walletradar.costbasis.application.replay.model.CarryTransfer;
import com.walletradar.costbasis.application.replay.model.FlowRef;
import com.walletradar.costbasis.application.replay.model.PassThroughCorridorPlan;
import com.walletradar.costbasis.application.replay.model.PendingTransferKey;
import com.walletradar.costbasis.application.replay.model.PositionSnapshot;
import com.walletradar.costbasis.application.replay.model.PositionState;
import com.walletradar.costbasis.application.replay.persistence.LedgerPointCollector;
import com.walletradar.costbasis.application.replay.state.PositionStore;
import com.walletradar.costbasis.application.replay.state.ReplayExecutionState;
import com.walletradar.costbasis.domain.AssetLedgerPoint;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Deque;
import java.util.Map;

/**
 * Linked bridge continuity and settlement replay paths extracted from {@code TransferReplayHandler}
 * (Track A / A5 god-class burn-down).
 */
@Component
public class LinkedBridgeTransferReplaySupport {

    private static final MathContext MC = MathContext.DECIMAL128;

    private final ReplayFlowSupport flowSupport;
    private final ContinuityCarryService continuityCarryService;
    private final ReplayPendingTransferKeyFactory keyFactory;
    private final ReplayPendingTransferMatcher matcher;

    public LinkedBridgeTransferReplaySupport(
            ReplayFlowSupport flowSupport,
            ContinuityCarryService continuityCarryService,
            ReplayPendingTransferKeyFactory keyFactory,
            ReplayPendingTransferMatcher matcher
    ) {
        this.flowSupport = flowSupport;
        this.continuityCarryService = continuityCarryService;
        this.keyFactory = keyFactory;
        this.matcher = matcher;
    }

    @FunctionalInterface
    public interface PendingInboundEnqueuer {
        void enqueue(
                NormalizedTransaction transaction,
                NormalizedTransaction.Flow flow,
                int flowIndex,
                PositionState position,
                ReplayExecutionState replayState,
                PendingTransferKey key
        );
    }

    public AssetLedgerPoint.BasisEffect applyLinkedBridgeTransfer(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            int flowIndex,
            PositionState position,
            ReplayExecutionState replayState,
            PendingInboundEnqueuer pendingInboundEnqueuer
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
                        replayState.ledgerPointCollector(),
                        replayState.passThroughCorridorPlan(),
                        replayState.reservedPassThroughCarries()
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
            CarryTransfer effectiveCarry = continuityCarryService.bridgeInboundCarry(
                    carry,
                    flow.getQuantityDelta().abs(),
                    position.assetKey()
            );
            flowSupport.restoreToPosition(effectiveCarry, position);
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

        pendingInboundEnqueuer.enqueue(transaction, flow, flowIndex, position, replayState, bridgeTransferKey);
        return flowSupport.continuityBasisEffect(transaction, flow);
    }

    public AssetLedgerPoint.BasisEffect applyLinkedBridgeSettlementTransfer(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            int flowIndex,
            PositionState position,
            ReplayExecutionState replayState,
            PendingInboundEnqueuer pendingInboundEnqueuer
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
            flowSupport.restoreToPosition(effectiveCarry, position);
            if (queue.isEmpty()) {
                replayState.pendingTransfers().remove(settlementKey);
            }
            return flowSupport.routeSettlementBasisEffect(flow);
        }

        pendingInboundEnqueuer.enqueue(transaction, flow, flowIndex, position, replayState, settlementKey);
        return flowSupport.routeSettlementBasisEffect(flow);
    }

    private void attachLateBridgeCarryToPendingInbound(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            int flowIndex,
            PositionStore positions,
            CarryTransfer pendingInbound,
            CarryTransfer carry,
            LedgerPointCollector ledgerPointCollector,
            PassThroughCorridorPlan passThroughCorridorPlan,
            Map<FlowRef, CarryTransfer> reservedPassThroughCarries
    ) {
        PositionState destination = positions.position(pendingInbound.assetKey());
        PositionSnapshot before = flowSupport.snapshot(destination);
        CarryTransfer effectiveCarry = continuityCarryService.bridgeInboundCarry(
                carry,
                pendingInbound.quantity(),
                pendingInbound.assetKey()
        );
        BigDecimal coveredResolvedQuantity = effectiveCarry.coveredQuantity();
        destination.setUncoveredQuantity(nonNegative(
                destination.uncoveredQuantity().subtract(coveredResolvedQuantity, MC)
        ));
        flowSupport.applyAuthoritativeLateInboundCarryBasis(
                destination,
                pendingInbound.provisionalBasisUsd(),
                flowSupport.pegFlooredStablecoinCarryBasis(
                        destination.assetKey(),
                        effectiveCarry.coveredQuantity(),
                        effectiveCarry.costBasisUsd()
                ),
                flowSupport.pegFlooredStablecoinCarryBasis(
                        destination.assetKey(),
                        effectiveCarry.coveredQuantity(),
                        effectiveCarry.netCostBasisUsd()
                )
        );
        flowSupport.recomputePerWalletAvco(destination);
        if (effectiveCarry.avco() != null && destination.uncoveredQuantity().signum() == 0) {
            flowSupport.resolveTemporaryUnresolved(destination);
        }
        if (pendingInbound.sourceFlowRef() != null) {
            continuityCarryService.reservePassThroughCarry(
                    passThroughCorridorPlan,
                    pendingInbound.sourceFlowRef(),
                    effectiveCarry,
                    reservedPassThroughCarries
            );
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
        destination.setUncoveredQuantity(nonNegative(
                destination.uncoveredQuantity().subtract(coveredResolvedQuantity, MC)
        ));
        flowSupport.applyAuthoritativeLateInboundCarryBasis(
                destination,
                pendingInbound.provisionalBasisUsd(),
                flowSupport.pegFlooredStablecoinCarryBasis(
                        destination.assetKey(),
                        effectiveCarry.coveredQuantity(),
                        effectiveCarry.costBasisUsd()
                ),
                flowSupport.pegFlooredStablecoinCarryBasis(
                        destination.assetKey(),
                        effectiveCarry.coveredQuantity(),
                        effectiveCarry.netCostBasisUsd()
                )
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
        if (value == null || value.signum() < 0) {
            return BigDecimal.ZERO;
        }
        return value;
    }
}
