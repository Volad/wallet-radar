package com.walletradar.costbasis.application.replay.model;

import java.math.BigDecimal;

public record CarryTransfer(
        BigDecimal quantity,
        BigDecimal coveredQuantity,
        BigDecimal uncoveredQuantity,
        BigDecimal costBasisUsd,
        BigDecimal avco,
        boolean pendingInbound,
        AssetKey assetKey,
        BigDecimal provisionalBasisUsd,
        /**
         * The FlowRef of the inbound flow that created this pending-inbound carry.
         * Set when {@code pendingInbound=true} and the caller knows its own flowIndex.
         * Used by {@code attachLateBridgeCarryToPendingInbound} to activate any pre-built
         * pass-through corridor reservation (bridge late-carry ordering invariant, ADR-020).
         */
        FlowRef sourceFlowRef
) {
    public CarryTransfer(
            BigDecimal quantity,
            BigDecimal coveredQuantity,
            BigDecimal uncoveredQuantity,
            BigDecimal costBasisUsd,
            BigDecimal avco,
            boolean pendingInbound,
            AssetKey assetKey,
            BigDecimal provisionalBasisUsd
    ) {
        this(quantity, coveredQuantity, uncoveredQuantity, costBasisUsd, avco, pendingInbound, assetKey, provisionalBasisUsd, null);
    }

    public CarryTransfer(
            BigDecimal quantity,
            BigDecimal coveredQuantity,
            BigDecimal uncoveredQuantity,
            BigDecimal costBasisUsd,
            BigDecimal avco,
            boolean pendingInbound,
            AssetKey assetKey
    ) {
        this(quantity, coveredQuantity, uncoveredQuantity, costBasisUsd, avco, pendingInbound, assetKey, BigDecimal.ZERO, null);
    }

    public static CarryTransfer pendingInbound(BigDecimal quantity, AssetKey assetKey, BigDecimal provisionalBasisUsd, FlowRef sourceFlowRef) {
        BigDecimal provisional = provisionalBasisUsd == null ? BigDecimal.ZERO : provisionalBasisUsd;
        return new CarryTransfer(quantity, BigDecimal.ZERO, quantity, BigDecimal.ZERO, null, true, assetKey, provisional, sourceFlowRef);
    }

    public static CarryTransfer pendingInbound(BigDecimal quantity, AssetKey assetKey, BigDecimal provisionalBasisUsd) {
        BigDecimal provisional = provisionalBasisUsd == null ? BigDecimal.ZERO : provisionalBasisUsd;
        return new CarryTransfer(quantity, BigDecimal.ZERO, quantity, BigDecimal.ZERO, null, true, assetKey, provisional, null);
    }

    public static CarryTransfer pendingInbound(BigDecimal quantity, AssetKey assetKey) {
        return pendingInbound(quantity, assetKey, BigDecimal.ZERO);
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
                pendingInbound,
                assetKey,
                newProvisional,
                sourceFlowRef
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
                pendingInbound,
                assetKey,
                updated,
                sourceFlowRef
        );
    }
}
