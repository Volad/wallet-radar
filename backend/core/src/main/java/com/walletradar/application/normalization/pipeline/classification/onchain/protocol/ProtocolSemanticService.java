package com.walletradar.application.normalization.pipeline.classification.onchain.protocol;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * Aggregates protocol-owned lifecycle hints from dedicated semantic classifiers.
 */
@Service
public class ProtocolSemanticService {

    private final List<ProtocolSemanticClassifier> protocolSemanticClassifiers;

    @Autowired
    public ProtocolSemanticService(List<ProtocolSemanticClassifier> protocolSemanticClassifiers) {
        this.protocolSemanticClassifiers = protocolSemanticClassifiers.stream()
                .sorted(Comparator.comparingInt(ProtocolSemanticClassifier::getOrder))
                .toList();
    }

    private ProtocolSemanticService() {
        this.protocolSemanticClassifiers = List.of();
    }

    public static ProtocolSemanticService noop() {
        return new ProtocolSemanticService();
    }

    public ProtocolSemanticResult classify(ProtocolSemanticContext context) {
        if (context == null || protocolSemanticClassifiers.isEmpty()) {
            return ProtocolSemanticResult.empty();
        }
        List<ProtocolSemanticHint> hints = protocolSemanticClassifiers.stream()
                .flatMap(classifier -> classifier.classify(context).stream())
                .toList();
        return hints.isEmpty() ? ProtocolSemanticResult.empty() : new ProtocolSemanticResult(hints);
    }
}
