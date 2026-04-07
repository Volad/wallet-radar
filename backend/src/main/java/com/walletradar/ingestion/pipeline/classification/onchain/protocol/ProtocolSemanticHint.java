package com.walletradar.ingestion.pipeline.classification.onchain.protocol;

import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;

/**
 * Protocol-owned lifecycle hint emitted before family classification.
 */
public record ProtocolSemanticHint(
        String protocolKey,
        String semanticType,
        String protocolName,
        String protocolVersion,
        String correlationId,
        NormalizedTransactionType suggestedType,
        ConfidenceLevel confidence
) {
}
