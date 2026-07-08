package com.walletradar.application.normalization.pipeline.classification.onchain.protocol;

import org.springframework.core.Ordered;

import java.util.List;

/**
 * Emits protocol-owned lifecycle hints ahead of generic family classification.
 */
public interface ProtocolSemanticClassifier {

    default int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    List<ProtocolSemanticHint> classify(ProtocolSemanticContext context);
}
