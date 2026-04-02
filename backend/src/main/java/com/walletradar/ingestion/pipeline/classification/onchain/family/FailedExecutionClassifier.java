package com.walletradar.ingestion.pipeline.classification.onchain.family;

import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.transaction.normalized.ClassificationSource;
import com.walletradar.ingestion.pipeline.classification.ClassificationDecision;
import com.walletradar.ingestion.pipeline.classification.OnChainClassificationContext;
import com.walletradar.ingestion.pipeline.classification.reason.ClassificationReasonCode;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Terminal guard for failed executions.
 */
@Component
public class FailedExecutionClassifier implements OnChainFamilyClassifier {

    @Override
    public OnChainClassificationInsertionPoint insertionPoint() {
        return OnChainClassificationInsertionPoint.EARLY_GUARDS;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Optional<ClassificationDecision> classify(OnChainClassificationContext context) {
        if (!context.view().isFailedExecution()) {
            return Optional.empty();
        }
        return Optional.of(FamilyDecisionSupport.terminalUnknown(
                ClassificationSource.HEURISTIC,
                ConfidenceLevel.LOW,
                context.movementLegs(),
                List.of(ClassificationReasonCode.FAILED_TRANSACTION.code())
        ));
    }
}
