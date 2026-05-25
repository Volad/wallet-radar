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
        BigDecimal provisionalBasisUsd
) {
    public CarryTransfer(
            BigDecimal quantity,
            BigDecimal coveredQuantity,
            BigDecimal uncoveredQuantity,
            BigDecimal costBasisUsd,
            BigDecimal avco,
            boolean pendingInbound,
            AssetKey assetKey
    ) {
        this(quantity, coveredQuantity, uncoveredQuantity, costBasisUsd, avco, pendingInbound, assetKey, BigDecimal.ZERO);
    }

    public static CarryTransfer pendingInbound(BigDecimal quantity, AssetKey assetKey, BigDecimal provisionalBasisUsd) {
        BigDecimal provisional = provisionalBasisUsd == null ? BigDecimal.ZERO : provisionalBasisUsd;
        return new CarryTransfer(quantity, BigDecimal.ZERO, quantity, BigDecimal.ZERO, null, true, assetKey, provisional);
    }

    public static CarryTransfer pendingInbound(BigDecimal quantity, AssetKey assetKey) {
        return pendingInbound(quantity, assetKey, BigDecimal.ZERO);
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
                updated
        );
    }
}
