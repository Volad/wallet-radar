package com.walletradar.ingestion.pipeline.onchain.support;

/**
 * Deterministic raw ordering metadata resolved from tx-level or unanimous nested explorer evidence.
 */
public record ResolvedRawOrderingMetadata(
        Long epochSeconds,
        Integer transactionIndex
) {
}
