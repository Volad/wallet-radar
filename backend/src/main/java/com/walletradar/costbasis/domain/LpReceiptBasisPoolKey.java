package com.walletradar.costbasis.domain;

public record LpReceiptBasisPoolKey(
        String universeId,
        String lpCorrelationId,
        String assetIdentity
) {
}
