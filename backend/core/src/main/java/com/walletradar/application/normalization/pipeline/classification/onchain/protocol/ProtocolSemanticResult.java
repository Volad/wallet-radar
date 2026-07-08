package com.walletradar.application.normalization.pipeline.classification.onchain.protocol;

import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;

import java.util.List;
import java.util.Optional;

/**
 * Aggregated protocol semantic hints available to family classifiers.
 */
public record ProtocolSemanticResult(
        List<ProtocolSemanticHint> hints
) {

    public static ProtocolSemanticResult empty() {
        return new ProtocolSemanticResult(List.of());
    }

    public Optional<ProtocolSemanticHint> first(String protocolKey, String semanticType) {
        if (hints == null || hints.isEmpty()) {
            return Optional.empty();
        }
        return hints.stream()
                .filter(hint -> hint != null)
                .filter(hint -> protocolKey.equalsIgnoreCase(hint.protocolKey()))
                .filter(hint -> semanticType.equalsIgnoreCase(hint.semanticType()))
                .findFirst();
    }

    public Optional<ProtocolSemanticHint> firstBySuggestedType(NormalizedTransactionType type) {
        if (type == null || hints == null || hints.isEmpty()) {
            return Optional.empty();
        }
        return hints.stream()
                .filter(hint -> hint != null)
                .filter(hint -> type == hint.suggestedType())
                .findFirst();
    }
}
