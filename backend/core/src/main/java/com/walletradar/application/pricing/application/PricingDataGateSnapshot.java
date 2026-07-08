package com.walletradar.application.pricing.application;

/**
 * Live pricing gate snapshot used before AVCO replay start.
 */
public record PricingDataGateSnapshot(
        long pendingPriceCount,
        long pendingClarificationCount,
        long pendingReclassificationCount,
        long needsReviewCount,
        long unresolvedPriceCount,
        long excludedNeedsReviewCount,
        boolean avcoReady
) {
}
