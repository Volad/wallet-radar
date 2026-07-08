package com.walletradar.application.costbasis.domain;

public record LpReceiptBasisPoolKey(
        String universeId,
        String lpCorrelationId,
        String assetIdentity
) {
}
