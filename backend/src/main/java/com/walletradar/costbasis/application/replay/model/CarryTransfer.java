package com.walletradar.costbasis.application.replay.model;

import java.math.BigDecimal;

public record CarryTransfer(
        BigDecimal quantity,
        BigDecimal coveredQuantity,
        BigDecimal uncoveredQuantity,
        BigDecimal costBasisUsd,
        BigDecimal avco,
        boolean pendingInbound,
        AssetKey assetKey
) {
    public static CarryTransfer pendingInbound(BigDecimal quantity, AssetKey assetKey) {
        return new CarryTransfer(quantity, BigDecimal.ZERO, quantity, BigDecimal.ZERO, null, true, assetKey);
    }
}
