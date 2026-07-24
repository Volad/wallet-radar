package com.walletradar.application.normalization.pipeline.solana;

/**
 * Classification metadata for a single Solana program as declared in
 * {@code classpath:protocol-registry.json} (WS-12 / W12 source-of-truth consolidation).
 *
 * <p>All fields are sourced exclusively from the registry — no base58 string, protocol name,
 * family label, or display name is hardcoded in the classifier. The registry {@code event_type}
 * is a coarse hint only; the classifier's flow-shape logic remains the authority for the
 * emitted {@link com.walletradar.domain.transaction.normalized.NormalizedTransactionType}.</p>
 *
 * @param protocol       raw {@code protocol} field from the registry (e.g. "Meteora", "Kamino")
 * @param protocolKey    classifier-emitted protocol slug (from registry {@code classifier_key})
 * @param family         protocol family label (e.g. "LP", "LENDING", "YIELD"); may be
 *                       {@code null} for non-DeFi programs (e.g. Bubblegum / compressed NFT)
 * @param eventTypeHint  coarse event-type hint from registry {@code event_type}; the
 *                       classifier's flow-shape logic overrides this for LP and lending flows
 * @param displayName    human-readable protocol name emitted by the classifier (from registry
 *                       {@code name}); may differ from the {@code protocol} field when the
 *                       display name carries version/variant context (e.g. "Meteora DLMM (via
 *                       Hawksight)")
 */
public record SolanaProgramClass(
        String protocol,
        String protocolKey,
        String family,
        String eventTypeHint,
        String displayName
) {
}
