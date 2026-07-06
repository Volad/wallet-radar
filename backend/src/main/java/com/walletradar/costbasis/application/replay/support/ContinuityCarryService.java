package com.walletradar.costbasis.application.replay.support;

import com.walletradar.costbasis.application.replay.model.AssetKey;
import com.walletradar.costbasis.application.replay.model.CarryTransfer;
import com.walletradar.domain.common.PriceSource;
import com.walletradar.pricing.domain.CanonicalAssetCatalog;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.costbasis.application.replay.model.ContinuityBucket;
import com.walletradar.costbasis.application.replay.model.ContinuityKey;
import com.walletradar.costbasis.application.replay.model.FlowRef;
import com.walletradar.costbasis.application.replay.model.PassThroughCorridor;
import com.walletradar.costbasis.application.replay.model.PassThroughCorridorPlan;
import com.walletradar.costbasis.application.replay.model.PositionState;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class ContinuityCarryService {

    private static final MathContext MC = MathContext.DECIMAL128;

    private final GenericFlowReplayEngine genericFlowReplayEngine;
    private final ReplayFlowSupport flowSupport;

    public ContinuityCarryService(
            GenericFlowReplayEngine genericFlowReplayEngine,
            ReplayFlowSupport flowSupport
    ) {
        this.genericFlowReplayEngine = genericFlowReplayEngine;
        this.flowSupport = flowSupport;
    }

    public ContinuityBucket continuityBucket(
            Map<ContinuityKey, ContinuityBucket> continuityBuckets,
            ContinuityKey continuityKey
    ) {
        return continuityBuckets.computeIfAbsent(continuityKey, ignored -> new ContinuityBucket());
    }

    public void moveToBucket(CarryTransfer carry, ContinuityBucket bucket) {
        if (carry == null || bucket == null) {
            return;
        }
        bucket.add(carry);
    }

    public CarryTransfer takeFromBucket(
            ContinuityBucket bucket,
            BigDecimal requestedQuantity,
            AssetKey assetKey
    ) {
        if (bucket == null || bucket.quantity().signum() == 0) {
            return null;
        }
        return bucket.take(requestedQuantity, assetKey);
    }

    /**
     * Drains the complete carry from the bucket regardless of quantity — used when the
     * outbound receipt token has a different denomination from the inbound return asset
     * (e.g., mevUSDC shares vs. USDC units). The full bucket basis is restored to the
     * position even when the returned quantity is much smaller than the receipt quantity.
     */
    public CarryTransfer drainFullBucket(ContinuityBucket bucket, AssetKey assetKey) {
        if (bucket == null || bucket.totalQuantity().signum() == 0) {
            return null;
        }
        return bucket.drainAll(assetKey);
    }

    public CarryTransfer takeReservedCarry(
            Map<FlowRef, CarryTransfer> reservedPassThroughCarries,
            FlowRef flowRef,
            BigDecimal requestedQuantity,
            AssetKey assetKey
    ) {
        if (reservedPassThroughCarries == null || flowRef == null) {
            return null;
        }
        CarryTransfer reservedCarry = reservedPassThroughCarries.remove(flowRef);
        if (reservedCarry == null) {
            return null;
        }
        return sliceCarryTransfer(reservedCarry, requestedQuantity, assetKey);
    }

    public void reservePassThroughCarry(
            PassThroughCorridorPlan passThroughCorridorPlan,
            FlowRef inboundFlowRef,
            CarryTransfer carry,
            Map<FlowRef, CarryTransfer> reservedPassThroughCarries
    ) {
        if (passThroughCorridorPlan == null
                || inboundFlowRef == null
                || reservedPassThroughCarries == null
                || carry == null
                || carry.quantity() == null
                || carry.quantity().signum() <= 0) {
            return;
        }
        PassThroughCorridor corridor = passThroughCorridorPlan.byInboundFlowRef().get(inboundFlowRef);
        if (corridor == null) {
            return;
        }
        reservedPassThroughCarries.put(
                corridor.outboundFlowRef(),
                sliceCarryTransfer(carry, corridor.reservedQuantity(), carry.assetKey())
        );
    }

    public CarryTransfer removeTransferCarry(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            int flowIndex,
            PositionState position,
            PassThroughCorridorPlan passThroughCorridorPlan,
            Map<FlowRef, CarryTransfer> reservedPassThroughCarries
    ) {
        return removeTransferCarry(
                transaction, flow, flowIndex, position,
                passThroughCorridorPlan, reservedPassThroughCarries, false
        );
    }

    /**
     * Family-custody and same-tx receipt pairing are authoritative for basis movement.
     * Pass-through corridor slices from an earlier bridge must not override them.
     */
    /**
     * When a stale pass-through reserve drained quantity but not the basis that was promoted
     * on the position after the reserve was captured, realign the carry to the basis that
     * actually left the wallet on this outbound leg.
     */
    public CarryTransfer alignCarryToRemovedBasis(
            CarryTransfer carry,
            BigDecimal basisRemovedFromPosition,
            AssetKey assetKey
    ) {
        return alignCarryToRemovedBasis(carry, basisRemovedFromPosition, basisRemovedFromPosition, assetKey);
    }

    /**
     * ADR-040 Change 2: net-aware variant of {@link #alignCarryToRemovedBasis}. Accepts the actual
     * net basis removed from the position so the carry's net lane reflects the true net cost that
     * left the wallet, not a clone of the tax basis.
     */
    public CarryTransfer alignCarryToRemovedBasis(
            CarryTransfer carry,
            BigDecimal basisRemovedFromPosition,
            BigDecimal netBasisRemovedFromPosition,
            AssetKey assetKey
    ) {
        if (carry == null
                || basisRemovedFromPosition == null
                || basisRemovedFromPosition.signum() <= 0) {
            return carry;
        }
        BigDecimal carryCost = carry.costBasisUsd() == null ? BigDecimal.ZERO : carry.costBasisUsd();
        if (carryCost.compareTo(basisRemovedFromPosition) >= 0) {
            return carry;
        }
        BigDecimal quantity = carry.quantity() == null ? BigDecimal.ZERO : carry.quantity();
        BigDecimal coveredQuantity = carry.coveredQuantity() == null ? BigDecimal.ZERO : carry.coveredQuantity();
        BigDecimal uncoveredQuantity = carry.uncoveredQuantity() == null ? BigDecimal.ZERO : carry.uncoveredQuantity();
        if (coveredQuantity.signum() <= 0 && quantity.signum() > 0) {
            coveredQuantity = quantity;
            uncoveredQuantity = BigDecimal.ZERO;
        }
        BigDecimal avco = coveredQuantity.signum() > 0
                ? safeDivide(basisRemovedFromPosition, coveredQuantity)
                : carry.avco();
        BigDecimal netBasis = netBasisRemovedFromPosition != null ? netBasisRemovedFromPosition : basisRemovedFromPosition;
        BigDecimal netAvco = coveredQuantity.signum() > 0
                ? safeDivide(netBasis, coveredQuantity)
                : carry.netAvco();
        return new CarryTransfer(
                quantity,
                coveredQuantity,
                uncoveredQuantity,
                basisRemovedFromPosition,
                avco,
                netBasis,
                netAvco,
                false,
                assetKey
        );
    }

    public CarryTransfer removeFamilyCustodyOutboundCarry(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            int flowIndex,
            PositionState position
    ) {
        return removeTransferCarry(
                transaction,
                flow,
                flowIndex,
                position,
                PassThroughCorridorPlan.empty(),
                new LinkedHashMap<>(),
                true
        );
    }

    /**
     * @param preserveCoverage when {@code true}, spreads basis proportionally across the full
     *                         quantity instead of only the covered portion. Used for Bybit
     *                         internal sub-account transfers (UTA↔FUND↔EARN) where "uncovered"
     *                         is a replay-ordering artifact, not a genuine unknown.
     */
    public CarryTransfer removeTransferCarry(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            int flowIndex,
            PositionState position,
            PassThroughCorridorPlan passThroughCorridorPlan,
            Map<FlowRef, CarryTransfer> reservedPassThroughCarries,
            boolean preserveCoverage
    ) {
        FlowRef flowRef = flowSupport.flowRef(transaction, flowIndex);
        CarryTransfer reservedPortion = takeReservedCarry(
                reservedPassThroughCarries,
                flowRef,
                flow.getQuantityDelta().abs(),
                position.assetKey()
        );
        if (reservedPortion == null) {
            return preserveCoverage
                    ? genericFlowReplayEngine.removeFromPositionPreservingCoverage(flow, position)
                    : genericFlowReplayEngine.removeFromPosition(flow, position);
        }
        consumeReservedCarry(position, reservedPortion);
        BigDecimal remainingQuantity = nonNegative(flow.getQuantityDelta().abs().subtract(reservedPortion.quantity(), MC));
        if (remainingQuantity.signum() == 0) {
            return absorbOrphanBasisIfPositionEmpty(position, reservedPortion);
        }
        NormalizedTransaction.Flow remainderFlow = flowSupport.copyFlowWithQuantity(
                flow,
                flow.getQuantityDelta().signum() < 0 ? remainingQuantity.negate() : remainingQuantity
        );
        CarryTransfer pooledRemainder = preserveCoverage
                ? genericFlowReplayEngine.removeFromPositionPreservingCoverage(remainderFlow, position)
                : genericFlowReplayEngine.removeFromPosition(remainderFlow, position);
        return absorbOrphanBasisIfPositionEmpty(
                position,
                mergeCarryTransfers(position.assetKey(), reservedPortion, pooledRemainder)
        );
    }

    /**
     * Cycle/17 R7: when a pass-through corridor reserves a carry, the basis is captured at
     * reserve time (BRIDGE_IN restore). The R6 inbound shortfall spot fallback can promote
     * basis on the position AFTER the reserve, leaving the reserved slice with stale {@code 0}
     * basis. When the outbound flow later consumes the carry it drains qty but not basis,
     * leaving an orphan basis on the (now empty) position. This helper drains that residual
     * into the outgoing carry so the wrapper/LP bucket receives the full basis.
     */
    private CarryTransfer absorbOrphanBasisIfPositionEmpty(PositionState position, CarryTransfer reservedPortion) {
        if (position == null
                || position.quantity() == null
                || position.quantity().signum() != 0
                || position.totalCostBasisUsd() == null
                || position.totalCostBasisUsd().signum() <= 0) {
            return reservedPortion;
        }
        BigDecimal orphanBasis = position.totalCostBasisUsd();
        BigDecimal orphanNetBasis = position.netTotalCostBasisUsd();
        position.setTotalCostBasisUsd(BigDecimal.ZERO);
        position.setPerWalletAvco(null);
        position.setNetTotalCostBasisUsd(BigDecimal.ZERO);
        position.setPerWalletNetAvco(null);
        BigDecimal quantity = reservedPortion.quantity() == null ? BigDecimal.ZERO : reservedPortion.quantity();
        BigDecimal covered = reservedPortion.coveredQuantity() == null ? BigDecimal.ZERO : reservedPortion.coveredQuantity();
        BigDecimal uncovered = reservedPortion.uncoveredQuantity() == null ? BigDecimal.ZERO : reservedPortion.uncoveredQuantity();
        BigDecimal updatedBasis = (reservedPortion.costBasisUsd() == null ? BigDecimal.ZERO : reservedPortion.costBasisUsd())
                .add(orphanBasis, MC);
        BigDecimal updatedNetBasis = (reservedPortion.netCostBasisUsd() == null ? BigDecimal.ZERO : reservedPortion.netCostBasisUsd())
                .add(orphanNetBasis, MC);
        if (covered.signum() <= 0 && quantity.signum() > 0 && updatedBasis.signum() > 0) {
            covered = quantity;
            uncovered = BigDecimal.ZERO;
        }
        BigDecimal updatedAvco = covered.signum() > 0 ? updatedBasis.divide(covered, MC) : reservedPortion.avco();
        BigDecimal updatedNetAvco = covered.signum() > 0 ? updatedNetBasis.divide(covered, MC) : reservedPortion.netAvco();
        return new CarryTransfer(
                quantity,
                covered,
                uncovered,
                updatedBasis,
                updatedAvco,
                updatedNetBasis,
                updatedNetAvco,
                reservedPortion.pendingInbound(),
                reservedPortion.assetKey()
        );
    }

    public void reservePassThroughCarryIfPlanned(
            NormalizedTransaction transaction,
            int flowIndex,
            CarryTransfer carry,
            PassThroughCorridorPlan passThroughCorridorPlan,
            Map<FlowRef, CarryTransfer> reservedPassThroughCarries
    ) {
        reservePassThroughCarry(
                passThroughCorridorPlan,
                flowSupport.flowRef(transaction, flowIndex),
                carry,
                reservedPassThroughCarries
        );
    }

    public void consumeReservedCarry(PositionState position, CarryTransfer carry) {
        if (position == null || carry == null || carry.quantity() == null || carry.quantity().signum() <= 0) {
            return;
        }
        position.setQuantity(nonNegative(position.quantity().subtract(carry.quantity(), MC)));
        position.setUncoveredQuantity(nonNegative(position.uncoveredQuantity().subtract(carry.uncoveredQuantity(), MC)));
        position.setTotalCostBasisUsd(nonNegative(position.totalCostBasisUsd().subtract(carry.costBasisUsd(), MC)));
        position.setNetTotalCostBasisUsd(nonNegative(position.netTotalCostBasisUsd().subtract(carry.netCostBasisUsd(), MC)));
        genericFlowReplayEngine.recomputePerWalletAvco(position);
    }

    public CarryTransfer sliceCarryTransfer(
            CarryTransfer carry,
            BigDecimal requestedQuantity,
            AssetKey assetKey
    ) {
        if (carry == null || requestedQuantity == null || requestedQuantity.signum() <= 0) {
            return emptyCarry(assetKey);
        }
        // Cycle/18 R9: orphan basis (qty drained to zero, basis remains) must flow through the
        // slicer as fully covered carry — otherwise ARB→BYBIT corridors lose ~$1k+ per leg.
        if (carry.quantity() != null
                && carry.quantity().signum() == 0
                && carry.costBasisUsd() != null
                && carry.costBasisUsd().signum() > 0) {
            BigDecimal orphanBasis = carry.costBasisUsd();
            BigDecimal orphanNetBasis = carry.netCostBasisUsd() == null ? orphanBasis : carry.netCostBasisUsd();
            BigDecimal avco = safeDivide(orphanBasis, requestedQuantity);
            BigDecimal netAvco = safeDivide(orphanNetBasis, requestedQuantity);
            return new CarryTransfer(
                    requestedQuantity,
                    requestedQuantity,
                    BigDecimal.ZERO,
                    orphanBasis,
                    avco,
                    orphanNetBasis,
                    netAvco,
                    false,
                    assetKey
            );
        }
        BigDecimal effectiveQuantity = requestedQuantity.min(carry.quantity());
        BigDecimal effectiveCoveredQuantity = effectiveQuantity.min(carry.coveredQuantity());
        BigDecimal effectiveUncoveredQuantity = nonNegative(effectiveQuantity.subtract(effectiveCoveredQuantity, MC));
        BigDecimal effectiveAvco = carry.avco();
        BigDecimal effectiveCost = effectiveAvco == null
                ? BigDecimal.ZERO
                : effectiveCoveredQuantity.multiply(effectiveAvco, MC);
        BigDecimal effectiveNetAvco = carry.netAvco();
        BigDecimal effectiveNetCost = effectiveNetAvco == null
                ? BigDecimal.ZERO
                : effectiveCoveredQuantity.multiply(effectiveNetAvco, MC);
        if (effectiveNetCost.signum() == 0
                && carry.netCostBasisUsd() != null
                && carry.quantity() != null
                && carry.quantity().signum() > 0) {
            effectiveNetCost = carry.netCostBasisUsd().multiply(
                    effectiveQuantity.divide(carry.quantity(), MC),
                    MC
            );
            effectiveNetAvco = effectiveCoveredQuantity.signum() > 0
                    ? safeDivide(effectiveNetCost, effectiveCoveredQuantity)
                    : carry.netAvco();
        }
        return new CarryTransfer(
                effectiveQuantity,
                effectiveCoveredQuantity,
                effectiveUncoveredQuantity,
                effectiveCost,
                effectiveAvco,
                effectiveNetCost,
                effectiveNetAvco,
                false,
                assetKey
        );
    }

    /**
     * Bybit venue-internal inbound (FUND/UTA/EARN): preserve transferred basis as fully covered
     * at the destination so a high uncovered ratio on the source slice cannot explode per-wallet AVCO.
     */
    /**
     * Earn Flexible Savings redemption arrives as {@code EARN_FLEXIBLE_SAVING} on an empty
     * {@code :EARN} position (qty=0, shortfall-only removal). Queue an authoritative carry slice
     * so the matching FUND inbound does not materialise as uncovered REALLOCATE_IN.
     */
    public CarryTransfer syntheticBybitEarnProductCarry(
            NormalizedTransaction.Flow flow,
            BigDecimal requestedQuantity,
            AssetKey assetKey,
            BigDecimal fallbackAvcoUsd
    ) {
        if (requestedQuantity == null || requestedQuantity.signum() <= 0) {
            return emptyCarry(assetKey);
        }
        BigDecimal avco = null;
        if (flow != null
                && flow.getUnitPriceUsd() != null
                && flow.getUnitPriceUsd().signum() > 0
                && flow.getPriceSource() != null
                && flow.getPriceSource() != PriceSource.UNKNOWN) {
            avco = flow.getUnitPriceUsd();
        } else if (fallbackAvcoUsd != null && fallbackAvcoUsd.signum() > 0) {
            avco = fallbackAvcoUsd;
        } else if (flow != null && CanonicalAssetCatalog.isUsdStablecoinBySymbol(flow.getAssetSymbol())) {
            avco = BigDecimal.ONE;
        }
        BigDecimal basis = avco == null ? BigDecimal.ZERO : requestedQuantity.multiply(avco, MC);
        BigDecimal covered = basis.signum() > 0 ? requestedQuantity : BigDecimal.ZERO;
        BigDecimal uncovered = requestedQuantity.subtract(covered, MC);
        // Synthetic earn-product carry: net = tax (no separate reward-discount evidence)
        return new CarryTransfer(
                requestedQuantity, covered, uncovered,
                basis, avco, basis, avco,
                false, assetKey
        );
    }

    public CarryTransfer internalAccountInboundCarry(
            CarryTransfer sourceCarry,
            BigDecimal destinationQuantity,
            AssetKey assetKey
    ) {
        if (sourceCarry == null || destinationQuantity == null || destinationQuantity.signum() <= 0) {
            return emptyCarry(assetKey);
        }
        BigDecimal sourceQuantity = sourceCarry.quantity();
        if (sourceQuantity == null || sourceQuantity.signum() <= 0) {
            return bridgeInboundCarry(sourceCarry, destinationQuantity, assetKey);
        }
        BigDecimal sliceQuantity = destinationQuantity.min(sourceQuantity);
        BigDecimal sourceBasis = sourceCarry.costBasisUsd() == null ? BigDecimal.ZERO : sourceCarry.costBasisUsd();
        BigDecimal sourceNetBasis = sourceCarry.netCostBasisUsd() == null ? sourceBasis : sourceCarry.netCostBasisUsd();
        BigDecimal allocatedBasis = sourceBasis.multiply(sliceQuantity.divide(sourceQuantity, MC), MC);
        BigDecimal allocatedNetBasis = sourceNetBasis.multiply(sliceQuantity.divide(sourceQuantity, MC), MC);
        BigDecimal avco = sliceQuantity.signum() <= 0 ? null : safeDivide(allocatedBasis, sliceQuantity);
        BigDecimal netAvco = sliceQuantity.signum() <= 0 ? null : safeDivide(allocatedNetBasis, sliceQuantity);
        return new CarryTransfer(
                sliceQuantity,
                sliceQuantity,
                BigDecimal.ZERO,
                allocatedBasis,
                avco,
                allocatedNetBasis,
                netAvco,
                false,
                assetKey
        );
    }

    public CarryTransfer bridgeInboundCarry(
            CarryTransfer sourceCarry,
            BigDecimal destinationQuantity,
            AssetKey assetKey
    ) {
        if (sourceCarry == null || destinationQuantity == null || destinationQuantity.signum() <= 0) {
            return emptyCarry(assetKey);
        }
        if (sourceCarry.quantity() != null
                && sourceCarry.quantity().signum() == 0
                && sourceCarry.costBasisUsd() != null
                && sourceCarry.costBasisUsd().signum() > 0) {
            BigDecimal orphanBasis = sourceCarry.costBasisUsd();
            // ADR-040 Change 2: propagate source net basis (or fall back to tax when absent)
            BigDecimal orphanNetBasis = sourceCarry.netCostBasisUsd() != null
                    ? sourceCarry.netCostBasisUsd() : orphanBasis;
            BigDecimal avco = safeDivide(orphanBasis, destinationQuantity);
            BigDecimal netAvco = safeDivide(orphanNetBasis, destinationQuantity);
            return new CarryTransfer(
                    destinationQuantity, destinationQuantity, BigDecimal.ZERO,
                    orphanBasis, avco, orphanNetBasis, netAvco,
                    false, assetKey
            );
        }
        BigDecimal sourceQuantity = sourceCarry.quantity();
        BigDecimal sourceCovered = sourceCarry.coveredQuantity() == null
                ? BigDecimal.ZERO
                : sourceCarry.coveredQuantity();
        if (sourceCovered.signum() <= 0
                && sourceCarry.costBasisUsd() != null
                && sourceCarry.costBasisUsd().signum() > 0
                && sourceQuantity != null
                && sourceQuantity.signum() > 0) {
            sourceCovered = sourceQuantity;
        }
        BigDecimal coveredQuantity = destinationQuantity.min(sourceCovered);
        BigDecimal uncoveredQuantity = nonNegative(destinationQuantity.subtract(coveredQuantity, MC));
        BigDecimal costBasisUsd = sourceCarry.costBasisUsd() == null ? BigDecimal.ZERO : sourceCarry.costBasisUsd();
        // ADR-040 Change 2: propagate source net basis (or fall back to tax when absent)
        BigDecimal netCostBasisUsd = sourceCarry.netCostBasisUsd() != null
                ? sourceCarry.netCostBasisUsd() : costBasisUsd;
        BigDecimal avco = coveredQuantity.signum() <= 0 ? null : safeDivide(costBasisUsd, coveredQuantity);
        BigDecimal netAvco = coveredQuantity.signum() <= 0 ? null : safeDivide(netCostBasisUsd, coveredQuantity);
        return new CarryTransfer(
                destinationQuantity, coveredQuantity, uncoveredQuantity,
                costBasisUsd, avco, netCostBasisUsd, netAvco,
                false, assetKey
        );
    }

    public CarryTransfer bridgeSettlementInboundCarry(
            CarryTransfer sourceCarry,
            BigDecimal destinationQuantity,
            AssetKey assetKey
    ) {
        if (sourceCarry == null || destinationQuantity == null || destinationQuantity.signum() <= 0) {
            return emptyCarry(assetKey);
        }
        BigDecimal sourceQuantity = sourceCarry.quantity();
        if (sourceQuantity == null || sourceQuantity.signum() <= 0) {
            if (sourceCarry.costBasisUsd() != null && sourceCarry.costBasisUsd().signum() > 0) {
                BigDecimal orphanBasis = sourceCarry.costBasisUsd();
                // ADR-040 Change 2: propagate source net basis
                BigDecimal orphanNetBasis = sourceCarry.netCostBasisUsd() != null
                        ? sourceCarry.netCostBasisUsd() : orphanBasis;
                BigDecimal avco = safeDivide(orphanBasis, destinationQuantity);
                BigDecimal netAvco = safeDivide(orphanNetBasis, destinationQuantity);
                return new CarryTransfer(
                        destinationQuantity, destinationQuantity, BigDecimal.ZERO,
                        orphanBasis, avco, orphanNetBasis, netAvco,
                        false, assetKey
                );
            }
            return new CarryTransfer(
                    destinationQuantity, BigDecimal.ZERO, destinationQuantity,
                    BigDecimal.ZERO, null, BigDecimal.ZERO, null,
                    false, assetKey
            );
        }
        BigDecimal sourceCoveredQuantity = sourceCarry.coveredQuantity().min(sourceQuantity);
        BigDecimal coverageRatio = safeDivide(sourceCoveredQuantity, sourceQuantity);
        BigDecimal coveredQuantity = coverageRatio == null
                ? BigDecimal.ZERO
                : destinationQuantity.multiply(coverageRatio, MC).min(destinationQuantity);
        BigDecimal uncoveredQuantity = nonNegative(destinationQuantity.subtract(coveredQuantity, MC));
        BigDecimal costBasisUsd = sourceCarry.costBasisUsd();
        // ADR-040 Change 2: propagate source net basis
        BigDecimal netCostBasisUsd = sourceCarry.netCostBasisUsd() != null
                ? sourceCarry.netCostBasisUsd() : costBasisUsd;
        BigDecimal avco = coveredQuantity.signum() <= 0 ? null : safeDivide(costBasisUsd, coveredQuantity);
        BigDecimal netAvco = coveredQuantity.signum() <= 0 ? null : safeDivide(netCostBasisUsd, coveredQuantity);
        return new CarryTransfer(
                destinationQuantity, coveredQuantity, uncoveredQuantity,
                costBasisUsd, avco, netCostBasisUsd, netAvco,
                false, assetKey
        );
    }

    public CarryTransfer mergeCarryTransfers(
            AssetKey assetKey,
            CarryTransfer left,
            CarryTransfer right
    ) {
        BigDecimal quantity = safeAdd(left == null ? null : left.quantity(), right == null ? null : right.quantity());
        BigDecimal coveredQuantity = safeAdd(left == null ? null : left.coveredQuantity(), right == null ? null : right.coveredQuantity());
        BigDecimal uncoveredQuantity = safeAdd(left == null ? null : left.uncoveredQuantity(), right == null ? null : right.uncoveredQuantity());
        BigDecimal costBasisUsd = safeAdd(left == null ? null : left.costBasisUsd(), right == null ? null : right.costBasisUsd());
        BigDecimal netCostBasisUsd = safeAdd(left == null ? null : left.netCostBasisUsd(), right == null ? null : right.netCostBasisUsd());
        BigDecimal avco = coveredQuantity.signum() <= 0 ? null : safeDivide(costBasisUsd, coveredQuantity);
        BigDecimal netAvco = coveredQuantity.signum() <= 0 ? null : safeDivide(netCostBasisUsd, coveredQuantity);
        return new CarryTransfer(quantity, coveredQuantity, uncoveredQuantity, costBasisUsd, avco, netCostBasisUsd, netAvco, false, assetKey);
    }

    /**
     * Builds a fully-covered carry with an explicit basis, bypassing position-state AVCO.
     * Used for earn-principal lot carry (P0-A) and corridor outbound slice carry (P0-C)
     * where position-derived basis can be stale or incorrectly priced.
     */
    public CarryTransfer buildExplicitCarryTransfer(BigDecimal qty, BigDecimal basis, AssetKey assetKey) {
        if (qty == null || qty.signum() <= 0 || basis == null || basis.signum() <= 0) {
            return emptyCarry(assetKey);
        }
        BigDecimal avco = safeDivide(basis, qty);
        // net = tax for explicit/synthetic basis (earn-principal lot override)
        return new CarryTransfer(qty, qty, BigDecimal.ZERO, basis, avco, basis, avco, false, assetKey);
    }

    /**
     * ADR-040 Change 2: net-aware variant for corridor proportional carry. Accepts the actual
     * net basis removed from the position so the carry's net lane reflects the true net cost
     * exported (rewards/LP-fees discount is preserved across the corridor).
     */
    public CarryTransfer buildExplicitCarryTransfer(
            BigDecimal qty, BigDecimal basis, BigDecimal netBasis, AssetKey assetKey) {
        if (qty == null || qty.signum() <= 0 || basis == null || basis.signum() <= 0) {
            return emptyCarry(assetKey);
        }
        BigDecimal avco = safeDivide(basis, qty);
        BigDecimal effectiveNet = netBasis != null ? netBasis : basis;
        BigDecimal netAvco = safeDivide(effectiveNet, qty);
        return new CarryTransfer(qty, qty, BigDecimal.ZERO, basis, avco, effectiveNet, netAvco, false, assetKey);
    }

    private CarryTransfer emptyCarry(AssetKey assetKey) {
        return new CarryTransfer(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, null, BigDecimal.ZERO, null,
                false, assetKey
        );
    }

    private BigDecimal safeAdd(BigDecimal left, BigDecimal right) {
        return (left == null ? BigDecimal.ZERO : left).add(right == null ? BigDecimal.ZERO : right, MC);
    }

    private static BigDecimal safeDivide(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.signum() == 0) {
            return null;
        }
        return numerator.divide(denominator, MC);
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        return value.signum() < 0 ? BigDecimal.ZERO : value;
    }
}
