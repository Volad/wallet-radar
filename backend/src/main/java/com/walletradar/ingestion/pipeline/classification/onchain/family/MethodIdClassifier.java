package com.walletradar.ingestion.pipeline.classification.onchain.family;

import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.transaction.normalized.ClassificationSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.ingestion.pipeline.classification.ClassificationDecision;
import com.walletradar.ingestion.pipeline.classification.OnChainClassificationContext;
import com.walletradar.ingestion.pipeline.classification.support.DirectMethodIdSupport;
import com.walletradar.ingestion.pipeline.classification.support.OnChainClassificationSupport;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Direct selector fallback that runs before the remaining function-name and heuristic stages.
 */
@Component
public class MethodIdClassifier implements OnChainFamilyClassifier {

    @Override
    public OnChainClassificationInsertionPoint insertionPoint() {
        return OnChainClassificationInsertionPoint.FINAL_FALLBACK;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }

    @Override
    public Optional<ClassificationDecision> classify(OnChainClassificationContext context) {
        NormalizedTransactionType type = DirectMethodIdSupport.resolveType(context.view().methodId());
        if (type == null) {
            return Optional.empty();
        }

        ConfidenceLevel confidence = type == NormalizedTransactionType.APPROVE
                ? ConfidenceLevel.HIGH
                : ConfidenceLevel.MEDIUM;
        return Optional.of(FamilyDecisionSupport.buildWithView(
                context.view(),
                type,
                OnChainClassificationSupport.initialStatus(context.view(), type, ConfidenceLevel.MEDIUM),
                ClassificationSource.METHOD_ID,
                confidence,
                OnChainClassificationSupport.toFlows(context.movementLegs(), type),
                List.of()
        ));
    }
}
