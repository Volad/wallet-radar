package com.walletradar.application.normalization.pipeline.solana;

import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;

/**
 * Output of {@link SolanaTransactionClassifier} — protocol identity and type for a single
 * Solana transaction.
 *
 * @param type          canonical normalized transaction type
 * @param protocolName  human-readable protocol name (may be {@code null} for raw transfers)
 * @param protocolKey   machine-stable protocol key matching {@code protocol-registry.json}
 * @param family        protocol family label (e.g. "LP", "LENDING", "AGGREGATOR", "DEX")
 * @param needsReview   when {@code true} the row should be flagged NEEDS_REVIEW
 */
public record SolanaClassificationResult(
        NormalizedTransactionType type,
        String protocolName,
        String protocolKey,
        String family,
        boolean needsReview
) {

    public static SolanaClassificationResult of(
            NormalizedTransactionType type,
            String protocolName,
            String protocolKey,
            String family
    ) {
        return new SolanaClassificationResult(type, protocolName, protocolKey, family, false);
    }

    public static SolanaClassificationResult unknown() {
        return new SolanaClassificationResult(NormalizedTransactionType.UNKNOWN, null, null, null, true);
    }

    public static SolanaClassificationResult transfer(NormalizedTransactionType direction) {
        return new SolanaClassificationResult(direction, null, null, null, false);
    }
}
