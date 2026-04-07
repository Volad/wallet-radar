package com.walletradar.ingestion.pipeline.classification.onchain.family;

import com.walletradar.ingestion.pipeline.classification.ClassificationDecision;
import com.walletradar.ingestion.pipeline.classification.OnChainClassificationContext;
import org.springframework.core.Ordered;

import java.util.Optional;

/**
 * Family-level classifier evaluated inside the staged on-chain classification orchestrator.
 */
public interface OnChainFamilyClassifier extends Ordered {

    OnChainClassificationInsertionPoint insertionPoint();

    Optional<ClassificationDecision> classify(OnChainClassificationContext context);

    @Override
    default int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
