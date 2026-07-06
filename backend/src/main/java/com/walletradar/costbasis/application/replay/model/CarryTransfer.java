package com.walletradar.costbasis.application.replay.model;

import java.math.BigDecimal;

public record CarryTransfer(
        BigDecimal quantity,
        BigDecimal coveredQuantity,
        BigDecimal uncoveredQuantity,
        BigDecimal costBasisUsd,
        BigDecimal avco,
        BigDecimal netCostBasisUsd,
        BigDecimal netAvco,
        boolean pendingInbound,
        AssetKey assetKey,
        BigDecimal provisionalBasisUsd,
        /**
         * The FlowRef of the inbound flow that created this pending-inbound carry.
         * Set when {@code pendingInbound=true} and the caller knows its own flowIndex.
         * Used by {@code attachLateBridgeCarryToPendingInbound} to activate any pre-built
         * pass-through corridor reservation (bridge late-carry ordering invariant, ADR-020).
         */
        FlowRef sourceFlowRef,
        /**
         * Issue 2 (ADR-043): whether a {@code pendingInbound=true} carry's quantity has already been
         * added to the destination position. A materialized pending inbound (the normal
         * materialize-then-refine path) had its covered quantity + provisional basis applied at
         * enqueue time, so a late-attaching authoritative carry must only REFINE the basis (never
         * re-add quantity → double credit). An UNMATERIALIZED pending inbound is the zero-quantity
         * defer produced when {@code materializePendingInbound} could not price an unpriced boundary
         * leg (Optional.empty): its quantity was never added, so the late-attaching carry must
         * MATERIALIZE the quantity onto the destination (restores the LTC :EARN 0.75 @ $41.54 that
         * FIX-B otherwise dropped). Meaningful only for {@code pendingInbound=true}; defaults
         * {@code true} for every other carry.
         */
        boolean materialized
) {
    public CarryTransfer {
        if (netCostBasisUsd == null) {
            netCostBasisUsd = costBasisUsd == null ? BigDecimal.ZERO : costBasisUsd;
        }
        if (netAvco == null) {
            netAvco = avco;
        }
    }

    public CarryTransfer(
            BigDecimal quantity,
            BigDecimal coveredQuantity,
            BigDecimal uncoveredQuantity,
            BigDecimal costBasisUsd,
            BigDecimal avco,
            BigDecimal netCostBasisUsd,
            BigDecimal netAvco,
            boolean pendingInbound,
            AssetKey assetKey,
            BigDecimal provisionalBasisUsd,
            FlowRef sourceFlowRef
    ) {
        this(quantity, coveredQuantity, uncoveredQuantity, costBasisUsd, avco,
                netCostBasisUsd, netAvco, pendingInbound, assetKey, provisionalBasisUsd, sourceFlowRef, true);
    }

    public CarryTransfer(
            BigDecimal quantity,
            BigDecimal coveredQuantity,
            BigDecimal uncoveredQuantity,
            BigDecimal costBasisUsd,
            BigDecimal avco,
            BigDecimal netCostBasisUsd,
            BigDecimal netAvco,
            boolean pendingInbound,
            AssetKey assetKey
    ) {
        this(quantity, coveredQuantity, uncoveredQuantity, costBasisUsd, avco,
                netCostBasisUsd, netAvco, pendingInbound, assetKey, BigDecimal.ZERO, null, true);
    }

    public static CarryTransfer pendingInbound(BigDecimal quantity, AssetKey assetKey, BigDecimal provisionalBasisUsd, FlowRef sourceFlowRef) {
        BigDecimal provisional = provisionalBasisUsd == null ? BigDecimal.ZERO : provisionalBasisUsd;
        return new CarryTransfer(quantity, BigDecimal.ZERO, quantity, BigDecimal.ZERO, null, BigDecimal.ZERO, null, true, assetKey, provisional, sourceFlowRef, true);
    }

    public static CarryTransfer pendingInbound(BigDecimal quantity, AssetKey assetKey, BigDecimal provisionalBasisUsd) {
        BigDecimal provisional = provisionalBasisUsd == null ? BigDecimal.ZERO : provisionalBasisUsd;
        return new CarryTransfer(quantity, BigDecimal.ZERO, quantity, BigDecimal.ZERO, null, BigDecimal.ZERO, null, true, assetKey, provisional, null, true);
    }

    public static CarryTransfer pendingInbound(BigDecimal quantity, AssetKey assetKey) {
        return pendingInbound(quantity, assetKey, BigDecimal.ZERO);
    }

    /**
     * Issue 2 (ADR-043): a deferred UNMATERIALIZED pending inbound — the zero-quantity defer produced
     * when {@code materializePendingInbound} could not price an unpriced boundary leg. Its quantity is
     * NOT yet on the destination; the late-attaching authoritative carry materializes it (see
     * {@code TransferReplayHandler.attachLateCarryToPendingInbound}).
     */
    public static CarryTransfer pendingInboundUnmaterialized(BigDecimal quantity, AssetKey assetKey, FlowRef sourceFlowRef) {
        return new CarryTransfer(quantity, BigDecimal.ZERO, quantity, BigDecimal.ZERO, null, BigDecimal.ZERO, null, true, assetKey, BigDecimal.ZERO, sourceFlowRef, false);
    }

    /**
     * Creates a new pending-inbound carry with reduced quantity and proportionally scaled
     * provisional basis. Used when a partial outbound leg attaches carry to an N-leg bundle
     * pending inbound: the remaining pending inbound is re-enqueued with the leftover qty and
     * the proportional share of the original provisional that has not yet been replaced.
     *
     * @param newQty         the remaining uncovered quantity after the partial leg
     * @param newProvisional the remaining provisional basis (original − portion already replaced)
     */
    public CarryTransfer withReducedQuantityAndProvisional(BigDecimal newQty, BigDecimal newProvisional) {
        return new CarryTransfer(
                newQty,
                BigDecimal.ZERO,
                newQty,
                costBasisUsd,
                avco,
                netCostBasisUsd,
                netAvco,
                pendingInbound,
                assetKey,
                newProvisional,
                sourceFlowRef,
                materialized
        );
    }

    public CarryTransfer withAdditionalProvisionalBasis(BigDecimal additionalProvisionalBasisUsd) {
        if (additionalProvisionalBasisUsd == null || additionalProvisionalBasisUsd.signum() == 0) {
            return this;
        }
        BigDecimal updated = (provisionalBasisUsd == null ? BigDecimal.ZERO : provisionalBasisUsd)
                .add(additionalProvisionalBasisUsd);
        return new CarryTransfer(
                quantity,
                coveredQuantity,
                uncoveredQuantity,
                costBasisUsd,
                avco,
                netCostBasisUsd,
                netAvco,
                pendingInbound,
                assetKey,
                updated,
                sourceFlowRef,
                materialized
        );
    }
}
