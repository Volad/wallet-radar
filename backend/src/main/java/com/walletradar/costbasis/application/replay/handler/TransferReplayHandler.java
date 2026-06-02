package com.walletradar.costbasis.application.replay.handler;

import com.walletradar.costbasis.application.replay.model.AssetKey;
import com.walletradar.costbasis.application.replay.model.BridgePendingKey;
import com.walletradar.costbasis.application.replay.model.BridgeSettlementPendingKey;
import com.walletradar.costbasis.application.replay.model.CarryTransfer;
import com.walletradar.costbasis.application.replay.model.ContinuityBucket;
import com.walletradar.costbasis.application.replay.model.FlowRef;
import com.walletradar.costbasis.application.replay.model.PassThroughCorridorPlan;
import com.walletradar.costbasis.application.replay.model.PositionSnapshot;
import com.walletradar.costbasis.application.replay.model.PositionState;
import com.walletradar.costbasis.application.replay.model.TransferPendingKey;
import com.walletradar.costbasis.application.replay.persistence.LedgerPointCollector;
import com.walletradar.costbasis.application.replay.state.PositionStore;
import com.walletradar.costbasis.application.replay.state.ReplayExecutionState;
import com.walletradar.costbasis.application.replay.support.ContinuityCarryService;
import com.walletradar.costbasis.application.replay.support.ReplayFlowSupport;
import com.walletradar.costbasis.application.replay.support.ReplayMarketAuthority;
import com.walletradar.costbasis.application.replay.support.ReplayPendingTransferKeyFactory;
import com.walletradar.costbasis.application.replay.support.ReplayPendingTransferMatcher;
import com.walletradar.costbasis.application.replay.support.ReplayTransferClassifier;
import com.walletradar.costbasis.domain.AssetLedgerPoint;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.ingestion.pipeline.bybit.BybitEarnPrincipalTransferPairer;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Component
public class TransferReplayHandler {

    private static final MathContext MC = MathContext.DECIMAL128;

    /**
     * P0-A: Earn-principal carry is considered "dust" when the total cost basis is below this
     * threshold. Dust indicates that the EARN position was populated with an incorrect synthetic
     * AVCO from mis-priced normalization (e.g., CMETH at $4 instead of ~$2280). Market authority
     * is used to replace the dust carry with the authoritative lot basis.
     */
    private static final java.math.BigDecimal EARN_PRINCIPAL_DUST_BASIS_THRESHOLD = new java.math.BigDecimal("100");

    private final ReplayFlowSupport flowSupport;
    private final ContinuityCarryService continuityCarryService;
    private final ReplayPendingTransferKeyFactory keyFactory;
    private final ReplayTransferClassifier classifier;
    private final ReplayPendingTransferMatcher matcher;
    private final ReplayMarketAuthority replayMarketAuthority;

    public TransferReplayHandler(
            ReplayFlowSupport flowSupport,
            ContinuityCarryService continuityCarryService,
            ReplayPendingTransferKeyFactory keyFactory,
            ReplayTransferClassifier classifier,
            ReplayPendingTransferMatcher matcher,
            ReplayMarketAuthority replayMarketAuthority
    ) {
        this.flowSupport = flowSupport;
        this.continuityCarryService = continuityCarryService;
        this.keyFactory = keyFactory;
        this.classifier = classifier;
        this.matcher = matcher;
        this.replayMarketAuthority = replayMarketAuthority;
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
                            replayState.reservedPassThroughCarries(),
                            preserveBucketOutboundCoverage(transaction)
                    ),
                    replayState.continuity().bucket(keyFactory.continuityKey(transaction, flow))
            );
            flowSupport.purgeOrphanBasisWhenEmpty(position);
            return flowSupport.continuityBasisEffect(transaction, flow);
        }
        if (classifier.isBucketInbound(transaction, flow)) {
            ContinuityBucket bucket = replayState.continuity().bucket(keyFactory.continuityKey(transaction, flow));
            if (keyFactory.usesWrapperCompositeBucket(transaction)) {
                restoreFullBucket(flow, position, bucket);
            } else {
                restoreFromContinuityBucket(flow, position, bucket);
            }
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

        boolean corridorTransfer = classifier.isCorridorTransfer(transaction);

        if (flow.getQuantityDelta().signum() < 0) {
            boolean venueInternal = classifier.usesBybitVenueInternalCarryQueue(transaction);
            // P0-C: Resolve carry source before drain. For :FUND corridor outbounds with zero
            // inventory, falls back to umbrella BYBIT:UID so the proportional-carry override fires
            // correctly (ADR-019 Rule 1, B-BYBIT-CORRIDOR-2 sub-pattern A fix).
            PositionState carrySource = resolveCarrySourcePosition(
                    transaction, position, replayState, corridorTransfer);
            BigDecimal corridorOutboundSliceAvco = corridorTransfer
                    ? derivePositionAvco(carrySource)
                    : null;
            // B-3: capture pre-drain totals for proportional carry basis (ADR-019 Rule 1)
            BigDecimal preDrainTotalBasis = carrySource.totalCostBasisUsd();
            BigDecimal preDrainTotalQty   = carrySource.quantity();
            BigDecimal preDrainAvco = derivePositionAvco(carrySource);
            CarryTransfer carry = continuityCarryService.removeTransferCarry(
                    transaction,
                    flow,
                    flowIndex,
                    carrySource,
                    replayState.passThroughCorridorPlan(),
                    replayState.reservedPassThroughCarries(),
                    venueInternal || isBybitEarnPrincipalPaired(transaction)
            );
            carry = normalizeBybitEarnProductCarry(
                    transaction,
                    flow,
                    carry,
                    replayState,
                    carrySource,
                    carrySource.assetKey(),
                    preDrainAvco
            );
            // P0-A: For earn-principal outbound, override dust carry with authoritative lot basis.
            carry = applyEarnPrincipalLotCarryOverride(transaction, flow, carry, carrySource, replayState);
            // P0-C: For corridor outbound, compute carry basis as proportional slice of total
            // position cost (ADR-019 Rule 1 amended). Do NOT use perWalletAvco × movedQty — that
            // divides by covered-qty only and inflates basis when uncoveredQuantity > 0.
            if (corridorOutboundSliceAvco != null && corridorOutboundSliceAvco.signum() > 0) {
                BigDecimal movedQty = flow.getQuantityDelta().abs();
                BigDecimal corridorCarryBasis;
                if (preDrainTotalQty != null && preDrainTotalQty.signum() > 0
                        && preDrainTotalBasis != null && preDrainTotalBasis.signum() > 0) {
                    corridorCarryBasis = preDrainTotalBasis
                            .multiply(movedQty, MC)
                            .divide(preDrainTotalQty, MC);
                } else {
                    corridorCarryBasis = movedQty.multiply(corridorOutboundSliceAvco, MC);
                }
                // Cap: guard against movedQty > preDrainTotalQty edge case (shortfall scenario)
                if (preDrainTotalBasis != null && preDrainTotalBasis.signum() > 0) {
                    corridorCarryBasis = corridorCarryBasis.min(preDrainTotalBasis);
                }
                carry = continuityCarryService.buildExplicitCarryTransfer(
                        movedQty, corridorCarryBasis, carrySource.assetKey()
                );
            }
            Deque<CarryTransfer> queue = replayState.pendingTransfers().queue(transferKey);
            int pendingInboundIndex = corridorTransfer
                    ? matcher.findUniqueBridgeQueueIndex(queue, true)
                    : matcher.findUniqueCompatibleQueueIndex(queue, true, carry.quantity());
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
        int carryIndex = corridorTransfer
                ? matcher.findUniqueBridgeQueueIndex(queue, false)
                : matcher.findUniqueCompatibleQueueIndex(queue, false, flow.getQuantityDelta().abs());
        if (carryIndex >= 0) {
            CarryTransfer carry = matcher.removeQueueElement(queue, carryIndex);
            carry = normalizeBybitEarnProductCarry(
                    transaction,
                    flow,
                    carry,
                    replayState,
                    position,
                    position.assetKey(),
                    derivePositionAvco(position)
            );
            BigDecimal inboundQuantity = flow.getQuantityDelta().abs();
            boolean venueStyleInbound = classifier.usesBybitVenueInternalCarryQueue(transaction)
                    || isBybitEarnPrincipalPaired(transaction);
            CarryTransfer effectiveCarry = venueStyleInbound
                    ? continuityCarryService.internalAccountInboundCarry(carry, inboundQuantity, position.assetKey())
                    : continuityCarryService.sliceCarryTransfer(carry, inboundQuantity, position.assetKey());
            effectiveCarry = backfillEarnPrincipalInboundCarry(
                    transaction,
                    flow,
                    inboundQuantity,
                    position,
                    effectiveCarry,
                    replayState
            );
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

        // BYBIT-CORRIDOR inbound with no matching carry: Bybit is a CEX so no on-chain CARRY_OUT
        // will ever arrive to resolve the pending inbound. Apply spot-price acquisition immediately
        // instead of leaving this as an unresolvable pending entry with zero cost basis.
        if (classifier.isBybitCexCorridor(transaction)) {
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
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            int flowIndex,
            PositionState position,
            ReplayExecutionState replayState,
            com.walletradar.costbasis.application.replay.model.PendingTransferKey key
    ) {
        FlowRef sourceFlowRef = flowSupport.flowRef(transaction, flowIndex);
        boolean permitUncovered = !classifier.usesBybitVenueInternalCarryQueue(transaction)
                && !isBybitEarnPrincipalPaired(transaction);
        Optional<BigDecimal> provisionalBasis = flowSupport.materializePendingInbound(
                transaction,
                flow,
                position,
                permitUncovered
        );
        if (provisionalBasis.isEmpty()) {
            if (isBybitEarnPrincipalPaired(transaction)) {
                replayState.pendingTransfers().queue(key)
                        .addLast(CarryTransfer.pendingInbound(
                                flow.getQuantityDelta().abs(),
                                position.assetKey(),
                                BigDecimal.ZERO,
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

        enqueuePendingInbound(transaction, flow, flowIndex, position, replayState, bridgeTransferKey);
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

        enqueuePendingInbound(transaction, flow, flowIndex, position, replayState, settlementKey);
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
            // Cycle/19: Bybit bundle transfers use proportional basis to prevent the
            // shortfall spiral that erodes coverage on each UTA↔FUND↔EARN round-trip.
            CarryTransfer carry = continuityCarryService.removeTransferCarry(
                    transaction,
                    flow,
                    flowIndex,
                    position,
                    replayState.passThroughCorridorPlan(),
                    replayState.reservedPassThroughCarries(),
                    true
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

    /**
     * For wrapper-composite buckets (VAULT_WITHDRAW, STAKING_WITHDRAW returning a
     * different-denomination receipt), drain the ENTIRE bucket carry instead of a
     * proportional slice. The receipt token (e.g., mevUSDC shares) and the returned asset
     * (USDC) have incompatible quantity scales — proportional slicing yields ~$0 basis.
     */
    private void restoreFullBucket(
            NormalizedTransaction.Flow flow,
            PositionState position,
            ContinuityBucket bucket
    ) {
        CarryTransfer carry = continuityCarryService.drainFullBucket(bucket, position.assetKey());
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
        CarryTransfer effectiveCarry = classifier.usesBybitVenueInternalCarryQueue(transaction)
                || isBybitEarnPrincipalPaired(transaction)
                ? continuityCarryService.internalAccountInboundCarry(carry, pendingInbound.quantity(), pendingInbound.assetKey())
                : continuityCarryService.sliceCarryTransfer(carry, pendingInbound.quantity(), pendingInbound.assetKey());
        // Cycle/19: resolve the full carry quantity against the pending inbound's uncovered
        // portion — the carry represents the actual transfer and its covered portion provides
        // real basis. The uncovered portion from the carry stays uncovered at the destination.
        BigDecimal resolvedQuantity = effectiveCarry.quantity().min(
                destination.uncoveredQuantity() == null ? BigDecimal.ZERO : destination.uncoveredQuantity()
        );
        destination.setUncoveredQuantity(nonNegative(
                destination.uncoveredQuantity().subtract(resolvedQuantity, MC)
                        .add(effectiveCarry.uncoveredQuantity(), MC)
        ));
        // Cycle/19: when the pending inbound was materialised with a provisional spot-basis,
        // replace it with the authoritative carry basis instead of stacking on top.
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

    /**
     * ADR-020: When BRIDGE_IN fires before its paired BRIDGE_OUT (late-carry ordering), the
     * authoritative carry is applied here. If the BRIDGE_IN was part of a pre-built pass-through
     * corridor (e.g. BRIDGE_IN → LENDING_DEPOSIT on the same network), this method must activate
     * the reservation so the downstream consumer can use {@code takeReservedCarry} instead of
     * draining the depleted family pool.
     */
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

  private CarryTransfer backfillEarnPrincipalInboundCarry(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            BigDecimal inboundQuantity,
            PositionState position,
            CarryTransfer effectiveCarry,
            ReplayExecutionState replayState
    ) {
        if (!isBybitEarnPrincipalPaired(transaction)
                || effectiveCarry == null
                || inboundQuantity == null
                || inboundQuantity.signum() <= 0) {
            return effectiveCarry;
        }
        BigDecimal cost = effectiveCarry.costBasisUsd() == null ? BigDecimal.ZERO : effectiveCarry.costBasisUsd();
        if (cost.signum() > 0) {
            return effectiveCarry;
        }
        BigDecimal avco = resolveEarnPrincipalFallbackAvco(transaction, flow, position, replayState);
        return continuityCarryService.syntheticBybitEarnProductCarry(
                flow,
                inboundQuantity,
                position.assetKey(),
                avco
        );
    }

    /**
     * P0-A: For earn-principal outbound transfers, override a dust carry with the authoritative
     * lot basis = movedQty × marketAvco. This corrects cases where the EARN position was
     * populated with a stale synthetic AVCO (e.g., CMETH at $4 vs. ~$2280) from incorrect
     * spot pricing at subscription time. Only overrides when carry cost is below the dust
     * threshold ({@link #EARN_PRINCIPAL_DUST_BASIS_THRESHOLD}).
     */
    private CarryTransfer applyEarnPrincipalLotCarryOverride(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            CarryTransfer carry,
            PositionState carrySource,
            ReplayExecutionState replayState
    ) {
        if (!isBybitEarnPrincipalPaired(transaction) || flow.getQuantityDelta().signum() >= 0) {
            return carry;
        }
        BigDecimal currentCost = carry == null || carry.costBasisUsd() == null
                ? BigDecimal.ZERO
                : carry.costBasisUsd();
        if (currentCost.compareTo(EARN_PRINCIPAL_DUST_BASIS_THRESHOLD) >= 0) {
            return carry;
        }
        BigDecimal movedQty = flow.getQuantityDelta().abs();
        if (movedQty == null || movedQty.signum() <= 0) {
            return carry;
        }
        // Use historical cache (not flow-embedded price) since the flow's unitPriceUsd may be
        // the mis-assigned spot price from earn subscription normalization.
        BigDecimal marketAvco = replayMarketAuthority.resolveFromCacheOrCatalog(transaction, flow)
                .map(ReplayMarketAuthority.ResolvedMarketPrice::unitPriceUsd)
                .orElse(null);
        if (marketAvco == null || marketAvco.signum() <= 0) {
            return carry;
        }
        BigDecimal lotBasis = movedQty.multiply(marketAvco, MC);
        if (lotBasis.compareTo(currentCost) <= 0) {
            return carry;
        }
        return continuityCarryService.buildExplicitCarryTransfer(movedQty, lotBasis, carrySource.assetKey());
    }

    private static boolean isBybitEarnPrincipalPaired(NormalizedTransaction transaction) {
        if (transaction == null) {
            return false;
        }
        String correlationId = transaction.getCorrelationId();
        if (correlationId == null
                || !correlationId.startsWith(BybitEarnPrincipalTransferPairer.EARN_PRINCIPAL_CORRELATION_PREFIX)) {
            return false;
        }
        return Boolean.TRUE.equals(transaction.getContinuityCandidate())
                || (transaction.getWalletAddress() != null
                && transaction.getWalletAddress().toUpperCase(Locale.ROOT).endsWith(":EARN"))
                || (transaction.getWalletAddress() != null
                && !transaction.getWalletAddress().toUpperCase(Locale.ROOT).endsWith(":EARN"));
    }

    /**
     * Resolves the position from which carry basis should be drained for an outbound transfer.
     *
     * <p>Two fallback paths are supported:
     * <ul>
     *   <li><b>:EARN path</b> — earn-principal outbound rows are booked on {@code :EARN}; when
     *       the deposit path landed no covered basis on the slice, drain from the umbrella instead.
     *   <li><b>:FUND corridor path (B-BYBIT-CORRIDOR-2 sub-pattern A)</b> — Bybit's API does not
     *       expose the internal UTA→FUND transfer that precedes every on-chain withdrawal. When
     *       the FUND position has zero inventory at outbound time, fall back to the umbrella
     *       {@code BYBIT:UID} position so the proportional-carry override fires correctly.
     * </ul>
     */
    private static PositionState resolveCarrySourcePosition(
            NormalizedTransaction transaction,
            PositionState flowPosition,
            ReplayExecutionState replayState,
            boolean isCorridorTransfer
    ) {
        if (transaction == null || flowPosition == null || replayState == null) {
            return flowPosition;
        }
        String wallet = transaction.getWalletAddress();
        if (wallet == null) {
            return flowPosition;
        }
        String walletUpper = wallet.toUpperCase(Locale.ROOT);

        // :EARN path — unchanged behaviour
        String correlationId = transaction.getCorrelationId();
        if (correlationId != null
                && correlationId.startsWith(BybitEarnPrincipalTransferPairer.EARN_PRINCIPAL_CORRELATION_PREFIX)
                && walletUpper.endsWith(":EARN")) {
            if (hasEarnPrincipalCarryBasis(flowPosition)) {
                return flowPosition;
            }
            String umbrellaWallet = wallet.substring(0, wallet.length() - ":EARN".length());
            return replayState.position(umbrellaKeyFor(flowPosition.assetKey(), umbrellaWallet));
        }

        // :FUND corridor path — sub-pattern A: FUND has zero inventory because the UTA→FUND
        // internal step is not exposed by Bybit's API. Fall back to the umbrella BYBIT:UID
        // position so the corridor proportional-carry override (ADR-019 Rule 1) fires.
        // Sub-pattern B (FUND qty>0 but AVCO=0) is intentionally NOT covered here — it requires
        // a separate fix to the UNIVERSAL_TRANSFER inbound carry path.
        if (isCorridorTransfer
                && walletUpper.endsWith(":FUND")
                && !hasFundCarryInventory(flowPosition)) {
            String umbrellaWallet = wallet.substring(0, wallet.length() - ":FUND".length());
            return replayState.position(umbrellaKeyFor(flowPosition.assetKey(), umbrellaWallet));
        }

        return flowPosition;
    }

    /**
     * Returns {@code true} when the FUND position holds non-zero quantity, meaning it has
     * real inventory that should be drained instead of falling back to the umbrella.
     * Deliberately checks quantity only (not basis), so sub-pattern B (qty>0, AVCO=0) is
     * excluded and remains out of scope.
     */
    private static boolean hasFundCarryInventory(PositionState position) {
        if (position == null) {
            return false;
        }
        BigDecimal qty = position.quantity();
        return qty != null && qty.signum() > 0;
    }

    private static AssetKey umbrellaKeyFor(AssetKey flowKey, String umbrellaWallet) {
        return new AssetKey(
                umbrellaWallet,
                flowKey.networkId(),
                flowKey.assetContract(),
                flowKey.assetSymbol(),
                flowKey.assetIdentity()
        );
    }

    private static boolean hasEarnPrincipalCarryBasis(PositionState position) {
        if (position == null) {
            return false;
        }
        BigDecimal quantity = position.quantity();
        if (quantity == null || quantity.signum() <= 0) {
            return false;
        }
        BigDecimal basis = position.totalCostBasisUsd();
        if (basis != null && basis.signum() > 0) {
            return true;
        }
        BigDecimal uncovered = position.uncoveredQuantity() == null ? BigDecimal.ZERO : position.uncoveredQuantity();
        BigDecimal covered = quantity.subtract(uncovered, MC);
        if (covered.signum() <= 0) {
            return false;
        }
        BigDecimal avco = position.perWalletAvco();
        return avco != null && avco.signum() > 0;
    }

    private CarryTransfer normalizeBybitEarnProductCarry(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            CarryTransfer carry,
            ReplayExecutionState replayState,
            PositionState carrySourcePosition,
            AssetKey assetKey,
            BigDecimal preResolvedAvco
    ) {
        if (!classifier.usesBybitVenueInternalCarryQueue(transaction)
                && !isBybitEarnPrincipalPaired(transaction)) {
            return carry;
        }
        BigDecimal requested = flow.getQuantityDelta().abs();
        if (carry != null && carry.quantity() != null && carry.quantity().signum() > 0) {
            BigDecimal carryCost = carry.costBasisUsd() == null ? BigDecimal.ZERO : carry.costBasisUsd();
            if (carry.coveredQuantity() != null
                    && carry.coveredQuantity().signum() > 0
                    && carryCost.signum() > 0) {
                return carry;
            }
            requested = carry.quantity();
        }
        if (requested.signum() <= 0) {
            return carry;
        }
        BigDecimal fallbackAvco = preResolvedAvco;
        if (fallbackAvco == null || fallbackAvco.signum() <= 0) {
            fallbackAvco = resolveEarnPrincipalFallbackAvco(
                    transaction,
                    flow,
                    carrySourcePosition,
                    replayState
            );
        }
        return continuityCarryService.syntheticBybitEarnProductCarry(
                flow,
                requested,
                assetKey,
                fallbackAvco
        );
    }

    private BigDecimal resolveEarnPrincipalFallbackAvco(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            PositionState primaryPosition,
            ReplayExecutionState replayState
    ) {
        BigDecimal avco = derivePositionAvco(primaryPosition);
        if (avco != null && avco.signum() > 0) {
            return avco;
        }
        if (isBybitEarnPrincipalPaired(transaction) && replayState != null && transaction != null) {
            AssetKey flowKey = primaryPosition == null ? null : primaryPosition.assetKey();
            if (flowKey != null) {
                String wallet = transaction.getWalletAddress();
                if (wallet != null) {
                    String uid = extractBybitUid(wallet);
                    if (uid != null) {
                        AssetKey umbrellaKey = new AssetKey(
                                "BYBIT:" + uid,
                                flowKey.networkId(),
                                flowKey.assetContract(),
                                flowKey.assetSymbol(),
                                flowKey.assetIdentity()
                        );
                        avco = firstPositiveAvco(replayState.position(umbrellaKey));
                        if (avco != null) {
                            return avco;
                        }
                        AssetKey earnKey = new AssetKey(
                                "BYBIT:" + uid + ":EARN",
                                flowKey.networkId(),
                                flowKey.assetContract(),
                                flowKey.assetSymbol(),
                                flowKey.assetIdentity()
                        );
                        avco = firstPositiveAvco(replayState.position(earnKey));
                        if (avco != null) {
                            return avco;
                        }
                        AssetKey fundKey = new AssetKey(
                                "BYBIT:" + uid + ":FUND",
                                flowKey.networkId(),
                                flowKey.assetContract(),
                                flowKey.assetSymbol(),
                                flowKey.assetIdentity()
                        );
                        avco = firstPositiveAvco(replayState.position(fundKey));
                        if (avco != null) {
                            return avco;
                        }
                    }
                }
            }
        }
        return replayMarketAuthority.resolve(transaction, flow)
                .map(ReplayMarketAuthority.ResolvedMarketPrice::unitPriceUsd)
                .orElse(null);
    }

    private static BigDecimal firstPositiveAvco(PositionState position) {
        BigDecimal avco = derivePositionAvco(position);
        if (avco != null && avco.signum() > 0) {
            return avco;
        }
        return null;
    }

    private static String extractBybitUid(String walletAddress) {
        if (walletAddress == null || !walletAddress.toUpperCase(Locale.ROOT).startsWith("BYBIT:")) {
            return null;
        }
        String without = walletAddress.substring("BYBIT:".length());
        int colon = without.indexOf(':');
        return colon > 0 ? without.substring(0, colon) : without;
    }

    private static BigDecimal derivePositionAvco(PositionState position) {
        if (position == null) {
            return null;
        }
        BigDecimal avco = position.perWalletAvco();
        if (avco != null && avco.signum() > 0) {
            return avco;
        }
        BigDecimal quantity = position.quantity();
        BigDecimal basis = position.totalCostBasisUsd();
        if (quantity == null || basis == null || quantity.signum() <= 0 || basis.signum() <= 0) {
            return avco;
        }
        BigDecimal uncovered = position.uncoveredQuantity() == null ? BigDecimal.ZERO : position.uncoveredQuantity();
        BigDecimal covered = quantity.subtract(uncovered, MC);
        if (covered.signum() <= 0) {
            return basis.divide(quantity, MC);
        }
        return basis.divide(covered, MC);
    }

    private static boolean preserveBucketOutboundCoverage(NormalizedTransaction transaction) {
        if (transaction == null || transaction.getType() == null) {
            return false;
        }
        return switch (transaction.getType()) {
            case VAULT_WITHDRAW,
                    LENDING_WITHDRAW,
                    PROTOCOL_CUSTODY_WITHDRAW,
                    STAKING_WITHDRAW,
                    LP_EXIT,
                    LP_EXIT_PARTIAL,
                    LP_EXIT_FINAL -> true;
            default -> false;
        };
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        return value.signum() < 0 ? BigDecimal.ZERO : value;
    }
}
