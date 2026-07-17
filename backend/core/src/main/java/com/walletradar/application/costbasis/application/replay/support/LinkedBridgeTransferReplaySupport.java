package com.walletradar.application.costbasis.application.replay.support;

import com.walletradar.application.costbasis.application.replay.model.BridgePendingKey;
import com.walletradar.application.costbasis.application.replay.model.BridgeSettlementPendingKey;
import com.walletradar.application.costbasis.application.replay.model.CarryTransfer;
import com.walletradar.application.costbasis.application.replay.model.FlowRef;
import com.walletradar.application.costbasis.application.replay.model.PassThroughCorridorPlan;
import com.walletradar.application.costbasis.application.replay.model.PendingTransferKey;
import com.walletradar.application.costbasis.application.replay.model.PositionSnapshot;
import com.walletradar.application.costbasis.application.replay.model.PositionState;
import com.walletradar.application.costbasis.application.replay.persistence.LedgerPointCollector;
import com.walletradar.application.costbasis.application.replay.state.PositionStore;
import com.walletradar.application.costbasis.application.replay.state.ReplayExecutionState;
import com.walletradar.application.costbasis.domain.AssetLedgerPoint;
import com.walletradar.application.costbasis.support.BridgeSettlementMetadataSupport;
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

        // B-ETH-01: an asset-converting bridge with a non-peg source (e.g. WBTC→ETH, ETH→USDC)
        // realizes P&L on the source and acquires the destination at its stamped fair market value,
        // instead of blindly copying the source USD basis onto a different-family asset. The decision
        // and destination fair value are stamped at the OUTBOUND link decision
        // (BridgeSettlementMetadataSupport). Peg-neutral + same-asset corridors keep the existing
        // byte-identical settlement/continuity behavior below.
        boolean realizeOnConvert = BridgeSettlementMetadataSupport.isRealizeOnConvert(transaction);

        if (flow.getQuantityDelta().signum() < 0) {
            if (realizeOnConvert) {
                return applyAssetConvertingSettlementOutbound(
                        transaction, flow, flowIndex, position, replayState, settlementKey);
            }
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
                        replayState.ledgerPointCollector(),
                        AssetLedgerPoint.BasisEffect.REALLOCATE_IN
                );
                if (queue.isEmpty()) {
                    replayState.pendingTransfers().remove(settlementKey);
                }
                return flowSupport.routeSettlementBasisEffect(flow);
            }
            queue.addLast(carry);
            return flowSupport.routeSettlementBasisEffect(flow);
        }

        if (realizeOnConvert) {
            return applyAssetConvertingSettlementInbound(
                    transaction, flow, flowIndex, position, replayState, settlementKey, pendingInboundEnqueuer);
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

    /**
     * B-ETH-01 OUTBOUND (asset-converting, non-peg source): realize-on-convert.
     *
     * <p>DISPOSEs the source against its own AVCO on the source family (tax + net lanes) with
     * proceeds equal to the destination fair market value at the settlement timestamp, then enqueues
     * a carry whose basis is exactly those realized proceeds so the matched INBOUND leg can ACQUIRE
     * the destination at that USD. Mirrors the same-tx SWAP {@code swapNetRef} semantics: the net
     * lane is carried to the destination capped at market, so only the un-carryable excess realizes.
     *
     * <p>Guard-orphan safety: the enqueued carry is drained by the matched INBOUND leg (or attached
     * immediately when the INBOUND already parked a pending inbound), so no residual carry remains on
     * the asset-suffix-less {@code bridge-settlement:} queue for {@link CorridorBasisConservationGuard}.
     */
    private AssetLedgerPoint.BasisEffect applyAssetConvertingSettlementOutbound(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            int flowIndex,
            PositionState position,
            ReplayExecutionState replayState,
            BridgeSettlementPendingKey settlementKey
    ) {
        BigDecimal destFairValueUsd = BridgeSettlementMetadataSupport.destFairValueUsd(transaction);
        CarryTransfer sourceCarry = continuityCarryService.removeTransferCarry(
                transaction,
                flow,
                flowIndex,
                position,
                replayState.passThroughCorridorPlan(),
                replayState.reservedPassThroughCarries()
        );
        BigDecimal sourceTaxBasis = nonNegative(sourceCarry.costBasisUsd());
        BigDecimal sourceNetBasis = sourceCarry.netCostBasisUsd() == null
                ? sourceTaxBasis
                : nonNegative(sourceCarry.netCostBasisUsd());
        // Tax lane realizes fully at market: proceeds − source tax basis relieved.
        position.setTotalRealisedPnlUsd(
                position.totalRealisedPnlUsd().add(destFairValueUsd.subtract(sourceTaxBasis, MC), MC));
        // Net lane is carried to the destination capped at market; only the excess above market
        // realizes on the source (usually zero — source net basis ≤ destination market value).
        BigDecimal netCarried = sourceNetBasis.min(destFairValueUsd);
        position.setTotalNetRealisedPnlUsd(
                position.totalNetRealisedPnlUsd().add(sourceNetBasis.subtract(netCarried, MC), MC));
        flowSupport.purgeOrphanBasisWhenEmpty(position);

        BigDecimal quantity = flow.getQuantityDelta().abs();
        CarryTransfer realizeCarry = continuityCarryService.buildExplicitCarryTransfer(
                quantity, destFairValueUsd, netCarried, position.assetKey());

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
                    realizeCarry,
                    replayState.ledgerPointCollector(),
                    AssetLedgerPoint.BasisEffect.ACQUIRE
            );
            if (queue.isEmpty()) {
                replayState.pendingTransfers().remove(settlementKey);
            }
        } else {
            queue.addLast(realizeCarry);
        }
        return AssetLedgerPoint.BasisEffect.DISPOSE;
    }

    /**
     * B-ETH-01 INBOUND (asset-converting, non-peg source): ACQUIRE the destination at the carried
     * realized proceeds when the OUTBOUND leg already parked its realize carry; otherwise park a
     * pending inbound (materialized at destination market value) for the OUTBOUND leg to attach to.
     */
    private AssetLedgerPoint.BasisEffect applyAssetConvertingSettlementInbound(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            int flowIndex,
            PositionState position,
            ReplayExecutionState replayState,
            BridgeSettlementPendingKey settlementKey,
            PendingInboundEnqueuer pendingInboundEnqueuer
    ) {
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
            return AssetLedgerPoint.BasisEffect.ACQUIRE;
        }
        pendingInboundEnqueuer.enqueue(transaction, flow, flowIndex, position, replayState, settlementKey);
        return AssetLedgerPoint.BasisEffect.ACQUIRE;
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
            LedgerPointCollector ledgerPointCollector,
            AssetLedgerPoint.BasisEffect basisEffect
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
                basisEffect
        );
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        if (value == null || value.signum() < 0) {
            return BigDecimal.ZERO;
        }
        return value;
    }
}
