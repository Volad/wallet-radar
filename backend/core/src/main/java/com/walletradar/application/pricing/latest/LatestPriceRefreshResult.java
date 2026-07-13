package com.walletradar.application.pricing.latest;

/**
 * Statistics from one latest-price refresh cycle.
 */
public record LatestPriceRefreshResult(
        int tracked,
        int resolvedBybit,
        int resolvedDzengi,
        int pricedByNeither,
        int divergences,
        boolean bybitOk,
        boolean dzengiOk
) {
    public static LatestPriceRefreshResult empty() {
        return new LatestPriceRefreshResult(0, 0, 0, 0, 0, false, false);
    }
}
