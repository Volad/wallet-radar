package com.walletradar.application.normalization.pipeline.classification.baseline;

/**
 * Canonical flow entry used by row-level baseline export.
 */
public record CanonicalFlowSnapshot(
        int index,
        String assetSymbol,
        String assetAddress,
        String networkId,
        String direction,
        String assetRole,
        String quantity,
        boolean priceable,
        String counterparty,
        String continuityGroup,
        String notes
) {
}
