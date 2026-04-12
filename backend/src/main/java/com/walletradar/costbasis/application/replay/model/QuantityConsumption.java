package com.walletradar.costbasis.application.replay.model;

import java.math.BigDecimal;

public record QuantityConsumption(
        BigDecimal appliedQuantity,
        BigDecimal coveredQuantity,
        BigDecimal uncoveredQuantity,
        BigDecimal externalShortfallQuantity
) {
}
