package com.walletradar.application.costbasis.application.replay.handler;

import com.walletradar.application.costbasis.application.replay.model.AssetKey;
import com.walletradar.application.costbasis.application.replay.model.BridgePendingKey;
import com.walletradar.application.costbasis.application.replay.model.BridgeSettlementPendingKey;
import com.walletradar.application.costbasis.application.replay.model.CarryTransfer;
import com.walletradar.application.costbasis.application.replay.model.FlowRef;
import com.walletradar.application.costbasis.application.replay.model.PositionSnapshot;
import com.walletradar.application.costbasis.application.replay.model.PositionState;
import com.walletradar.application.costbasis.application.replay.model.TransferPendingKey;
import com.walletradar.application.costbasis.application.replay.persistence.LedgerPointCollector;
import com.walletradar.application.costbasis.application.replay.state.PositionStore;
import com.walletradar.application.costbasis.application.replay.state.ReplayExecutionState;
import com.walletradar.application.costbasis.application.replay.support.BybitCarrySourceResolver;
import com.walletradar.application.costbasis.application.replay.support.ContinuityCarryService;
import com.walletradar.application.costbasis.application.replay.support.ReplayFlowSupport;
import com.walletradar.application.costbasis.application.replay.support.ReplayMarketAuthority;
import com.walletradar.application.costbasis.application.replay.support.ReplayPendingTransferKeyFactory;
import com.walletradar.application.costbasis.application.replay.support.ReplayPendingTransferMatcher;
import com.walletradar.application.costbasis.application.replay.support.ReplayTransferClassifier;
import com.walletradar.application.costbasis.application.replay.support.TransferEarnPrincipalReplaySupport;
import com.walletradar.application.costbasis.domain.AssetLedgerPoint;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Deque;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class BridgeTransferReplaySupport {

    private static final MathContext MC = MathContext.DECIMAL128;

    private final ReplayFlowSupport flowSupport;
    private final ReplayPendingTransferKeyFactory keyFactory;
    private final ReplayTransferClassifier classifier;
    private final ContinuityCarryService continuityCarryService;
    private final ReplayPendingTransferMatcher matcher;
    private final ReplayMarketAuthority replayMarketAuthority;

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

    public void enqueuePendingInbound(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            int flowIndex,
            PositionState position,
            ReplayExecutionState replayState,
            com.walletradar.application.costbasis.application.replay.model.PendingTransferKey key
    ) {
        FlowRef sourceFlowRef = flowSupport.flowRef(transaction, flowIndex);
        boolean permitUncovered = !classifier.usesBybitVenueInternalCarryQueue(transaction)
                || TransferEarnPrincipalReplaySupport.isBybitEarnPrincipalPaired(transaction);
        Optional<BigDecimal> provisionalBasis = flowSupport.materializePendingInbound(
                transaction,
                flow,
                position,
                permitUncovered
        );
        if (provisionalBasis.isEmpty()) {
            if (TransferEarnPrincipalReplaySupport.isBybitEarnPrincipalPaired(transaction)) {
                replayState.pendingTransfers().queue(key)
                        .addLast(CarryTransfer.pendingInboundUnmaterialized(
                                flow.getQuantityDelta().abs(),
                                position.assetKey(),
                                sourceFlowRef
                        ));
            }
            return;
        }
        replayState.pendingTransfers().queue(key)
                .addLast(CarryTransfer.pendingInbound(
                        flow.getQuantityDelta().abs(),
                        position.assetKey(),
                        provisionalBasis.orElse(BigDecimal.ZERO),
                        sourceFlowRef
                ));
    }

    public void attachLateCarryToPendingInbound(
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
        CarryTransfer effectiveCarry = classifier.usesBybitVenueInternalCarryQueue(transaction)
                || TransferEarnPrincipalReplaySupport.isBybitEarnPrincipalPaired(transaction)
                ? continuityCarryService.internalAccountInboundCarry(carry, pendingInbound.quantity(), pendingInbound.assetKey())
                : continuityCarryService.sliceCarryTransfer(carry, pendingInbound.quantity(), pendingInbound.assetKey());
        if (!pendingInbound.materialized()) {
            flowSupport.restoreToPosition(
                    effectiveCarry.quantity(),
                    destination,
                    flowSupport.pegFlooredStablecoinCarryBasis(
                            destination.assetKey(),
                            effectiveCarry.coveredQuantity(),
                            effectiveCarry.costBasisUsd()
                    ),
                    effectiveCarry.netCostBasisUsd(),
                    effectiveCarry.uncoveredQuantity(),
                    effectiveCarry.avco()
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
            return;
        }
        BigDecimal resolvedQuantity = effectiveCarry.quantity().min(
                destination.uncoveredQuantity() == null ? BigDecimal.ZERO : destination.uncoveredQuantity()
        );
        destination.setUncoveredQuantity(nonNegative(
                destination.uncoveredQuantity().subtract(resolvedQuantity, MC)
                        .add(effectiveCarry.uncoveredQuantity(), MC)
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
                AssetLedgerPoint.BasisEffect.CARRY_IN
        );
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        return value.signum() < 0 ? BigDecimal.ZERO : value;
    }

    public AssetLedgerPoint.BasisEffect applyPendingTransfer(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            int flowIndex,
            PositionState position,
            ReplayExecutionState replayState,
            TransferPendingKey transferKey,
            EarnBundleTransferReplaySupport earnBundleTransferReplaySupport,
            CarryTransferReplaySupport carryTransferReplaySupport
    ) {
        boolean corridorTransfer = classifier.isCorridorTransfer(transaction);
        // Rekeyed FUND→UTA carries share bridge-style (qty-agnostic) matching with corridors:
        // Bybit reports slightly different amounts on the FUND-debit vs the UTA-credit leg, so the
        // strict qty-compatible matcher fails. Bridge matching bypasses that check.
        boolean useBridgeMatching = corridorTransfer || classifier.isRekeyedVenueTransfer(transaction);

        if (flow.getQuantityDelta().signum() < 0) {
            boolean venueInternal = classifier.usesBybitVenueInternalCarryQueue(transaction);
            BigDecimal outboundQty = flow.getQuantityDelta().abs();
            PositionState carrySource = carryTransferReplaySupport.resolveCarrySourcePosition(
                    transaction, flow, position, replayState, corridorTransfer, outboundQty);
            BybitCarrySourceResolver.BybitDrainPlan plan = BybitCarrySourceResolver.plan(
                    position, carrySource, replayState.positions(), outboundQty);
            CarryTransfer carry = null;
            for (BybitCarrySourceResolver.DrainSlice slice : plan.slices()) {
                CarryTransfer sliceCarry = earnBundleTransferReplaySupport.drainCarrySlice(
                        transaction,
                        flow,
                        flowIndex,
                        slice.position(),
                        slice.quantity(),
                        position,
                        corridorTransfer,
                        venueInternal,
                        replayState
                );
                carry = carry == null
                        ? sliceCarry
                        : continuityCarryService.mergeCarryTransfers(position.assetKey(), carry, sliceCarry);
            }
            Deque<CarryTransfer> queue = replayState.pendingTransfers().queue(transferKey);
            int pendingInboundIndex = useBridgeMatching
                    ? matcher.findUniqueBridgeQueueIndex(queue, true)
                    : TransferEarnPrincipalReplaySupport.multiSourceEarnPrincipalBundle(transaction)
                    ? matcher.findUniqueBridgeQueueIndex(queue, true)
                    : matcher.findUniqueCompatibleQueueIndex(queue, true, carry.quantity());
            if (pendingInboundIndex >= 0) {
                CarryTransfer pendingInbound = matcher.removeQueueElement(queue, pendingInboundIndex);
                if (TransferEarnPrincipalReplaySupport.multiSourceEarnPrincipalBundle(transaction)
                        && pendingInbound.quantity() != null
                        && carry.quantity() != null
                        && pendingInbound.quantity().subtract(carry.quantity(), MC)
                        .compareTo(earnBundleTransferReplaySupport.earnPrincipalPartialMatchThreshold()) > 0) {
                    BigDecimal fullProvisional = pendingInbound.provisionalBasisUsd() != null
                            ? pendingInbound.provisionalBasisUsd() : BigDecimal.ZERO;
                    BigDecimal scaledProvisional = pendingInbound.quantity().signum() > 0
                            ? fullProvisional.multiply(
                            carry.quantity().divide(pendingInbound.quantity(), MC), MC)
                            : BigDecimal.ZERO;
                    BigDecimal remainingProvisional = fullProvisional.subtract(scaledProvisional, MC);
                    CarryTransfer slicedForAttach = new CarryTransfer(
                            carry.quantity(),
                            BigDecimal.ZERO,
                            carry.quantity(),
                            BigDecimal.ZERO,
                            null,
                            BigDecimal.ZERO,
                            null,
                            true,
                            pendingInbound.assetKey(),
                            scaledProvisional,
                            pendingInbound.sourceFlowRef(),
                            pendingInbound.materialized()
                    );
                    attachLateCarryToPendingInbound(
                            transaction,
                            flow,
                            flowIndex,
                            replayState.positions(),
                            slicedForAttach,
                            carry,
                            replayState.ledgerPointCollector()
                    );
                    BigDecimal remainingQty = pendingInbound.quantity().subtract(carry.quantity(), MC);
                    queue.addFirst(pendingInbound.withReducedQuantityAndProvisional(remainingQty, remainingProvisional));
                } else {
                    attachLateCarryToPendingInbound(
                            transaction,
                            flow,
                            flowIndex,
                            replayState.positions(),
                            pendingInbound,
                            carry,
                            replayState.ledgerPointCollector()
                    );
                }
                if (queue.isEmpty()) {
                    replayState.pendingTransfers().remove(transferKey);
                }
                return flowSupport.continuityBasisEffect(transaction, flow);
            }
            queue.addLast(carry);
            return flowSupport.continuityBasisEffect(transaction, flow);
        }

        Deque<CarryTransfer> queue = replayState.pendingTransfers().find(transferKey);
        int carryIndex = useBridgeMatching
                ? matcher.findUniqueBridgeQueueIndex(queue, false)
                : TransferEarnPrincipalReplaySupport.multiSourceEarnPrincipalBundle(transaction)
                ? matcher.findUniqueBridgeQueueIndex(queue, false)
                : matcher.findUniqueCompatibleQueueIndex(queue, false, flow.getQuantityDelta().abs());
        if (carryIndex >= 0) {
            if (TransferEarnPrincipalReplaySupport.multiSourceEarnPrincipalBundle(transaction) && !corridorTransfer) {
                BigDecimal remaining = flow.getQuantityDelta().abs();
                while (remaining.signum() > 0) {
                    int nextCarryIndex = matcher.findUniqueBridgeQueueIndex(queue, false);
                    if (nextCarryIndex < 0) {
                        break;
                    }
                    CarryTransfer carry = matcher.removeQueueElement(queue, nextCarryIndex);
                    BigDecimal inboundQuantity = remaining.min(carry.quantity());
                    CarryTransfer effectiveCarry = continuityCarryService.internalAccountInboundCarry(
                            carry,
                            inboundQuantity,
                            position.assetKey()
                    );
                    flowSupport.restoreToPosition(
                            inboundQuantity,
                            position,
                            flowSupport.pegCappedStablecoinCarryBasis(
                                    position.assetKey(),
                                    CarryTransferReplaySupport.inboundCoveredQuantity(inboundQuantity, effectiveCarry),
                                    effectiveCarry.costBasisUsd()),
                            flowSupport.pegCappedStablecoinCarryBasis(
                                    position.assetKey(),
                                    CarryTransferReplaySupport.inboundCoveredQuantity(inboundQuantity, effectiveCarry),
                                    effectiveCarry.netCostBasisUsd()),
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
                    remaining = remaining.subtract(inboundQuantity, MC);
                }
                if (queue.isEmpty()) {
                    replayState.pendingTransfers().remove(transferKey);
                }
                if (remaining.signum() <= 0) {
                    return flowSupport.continuityBasisEffect(transaction, flow);
                }
                NormalizedTransaction.Flow remainderFlow = EarnBundleTransferReplaySupport.flowWithQuantity(flow, remaining);
                enqueuePendingInbound(transaction, remainderFlow, flowIndex, position, replayState, transferKey);
                return flowSupport.continuityBasisEffect(transaction, flow);
            }
            CarryTransfer carry = matcher.removeQueueElement(queue, carryIndex);
            boolean pairedCarryAuthoritative = TransferEarnPrincipalReplaySupport.isBybitEarnPrincipalPaired(transaction);
            if (!pairedCarryAuthoritative) {
                carry = earnBundleTransferReplaySupport.normalizeBybitEarnProductCarry(
                        transaction,
                        flow,
                        carry,
                        replayState,
                        position,
                        position.assetKey(),
                        EarnBundleTransferReplaySupport.derivePositionAvco(position)
                );
            }
            BigDecimal inboundQuantity = flow.getQuantityDelta().abs();
            boolean venueStyleInbound = classifier.usesBybitVenueInternalCarryQueue(transaction)
                    || pairedCarryAuthoritative;
            CarryTransfer effectiveCarry = venueStyleInbound
                    ? continuityCarryService.internalAccountInboundCarry(carry, inboundQuantity, position.assetKey())
                    : continuityCarryService.sliceCarryTransfer(carry, inboundQuantity, position.assetKey());
            if (!pairedCarryAuthoritative) {
                effectiveCarry = earnBundleTransferReplaySupport.backfillEarnPrincipalInboundCarry(
                        transaction,
                        flow,
                        inboundQuantity,
                        position,
                        effectiveCarry,
                        replayState
                );
            }
            BigDecimal cappedInboundBasis = flowSupport.pegCappedStablecoinCarryBasis(
                    position.assetKey(),
                    CarryTransferReplaySupport.inboundCoveredQuantity(flow.getQuantityDelta().abs(), effectiveCarry),
                    effectiveCarry.costBasisUsd());
            BigDecimal cappedNetBasis = flowSupport.pegCappedStablecoinCarryBasis(
                    position.assetKey(),
                    CarryTransferReplaySupport.inboundCoveredQuantity(flow.getQuantityDelta().abs(), effectiveCarry),
                    effectiveCarry.netCostBasisUsd());
            flowSupport.restoreToPosition(
                    flow.getQuantityDelta().abs(),
                    position,
                    cappedInboundBasis,
                    cappedNetBasis,
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

        if (classifier.isCexWithdrawalCorridorInbound(transaction, flow)) {
            BigDecimal qty = flow.getQuantityDelta();
            BigDecimal acquisitionCost = replayMarketAuthority.resolve(transaction, flow)
                    .map(ReplayMarketAuthority.ResolvedMarketPrice::unitPriceUsd)
                    .map(price -> qty.multiply(price, MC))
                    .orElse(null);
            if (acquisitionCost != null && acquisitionCost.signum() > 0) {
                flowSupport.applyBuyWithAcquisitionCost(flow, position, acquisitionCost);
            } else {
                flowSupport.applyUnknownTransfer(flow, position);
            }
            return AssetLedgerPoint.BasisEffect.ACQUIRE;
        }

        enqueuePendingInbound(transaction, flow, flowIndex, position, replayState, transferKey);
        return flowSupport.continuityBasisEffect(transaction, flow);
    }
}
