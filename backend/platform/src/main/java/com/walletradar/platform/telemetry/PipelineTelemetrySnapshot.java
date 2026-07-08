package com.walletradar.platform.telemetry;

/**
 * Live pipeline counters used for operational visibility across stages.
 */
public record PipelineTelemetrySnapshot(
        long onChainNormalizedCount,
        long bybitNormalizedCount,
        long pendingStatCount,
        long unmatchedBybitBridgeCount,
        long orphanUtaLegCount,
        long unresolvedPriceCount,
        long needsReviewCount,
        long excludedNeedsReviewCount
) {
}
