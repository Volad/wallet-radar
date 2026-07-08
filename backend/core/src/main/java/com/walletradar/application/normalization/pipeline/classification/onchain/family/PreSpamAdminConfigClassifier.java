package com.walletradar.application.normalization.pipeline.classification.onchain.family;

import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.transaction.normalized.ClassificationSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.application.normalization.pipeline.classification.ClassificationDecision;
import com.walletradar.application.normalization.pipeline.classification.OnChainClassificationContext;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Admin-config rules that still need to run before spam review.
 */
@Component
public class PreSpamAdminConfigClassifier implements OnChainFamilyClassifier {

    private static final String FEE_BEARING_CLAIM_ADMIN_SELECTOR = "0xdc4b201d";

    @Override
    public OnChainClassificationInsertionPoint insertionPoint() {
        return OnChainClassificationInsertionPoint.PRE_SPAM_REVIEW;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 200;
    }

    @Override
    public Optional<ClassificationDecision> classify(OnChainClassificationContext context) {
        if (!isFeeBearingClaimAdmin(context)) {
            return Optional.empty();
        }
        return Optional.of(FamilyDecisionSupport.build(
                NormalizedTransactionType.ADMIN_CONFIG,
                NormalizedTransactionStatus.CONFIRMED,
                ClassificationSource.METHOD_ID,
                ConfidenceLevel.MEDIUM,
                context.movementLegs(),
                List.of("FEE_BEARING_CLAIM_ADMIN_ACTION")
        ));
    }

    private boolean isFeeBearingClaimAdmin(OnChainClassificationContext context) {
        String functionName = context.view().functionName();
        boolean functionMatch = functionName != null
                && (functionName.contains("claim(tuple pinData") || functionName.contains("claim((tuple pinData"));
        return FEE_BEARING_CLAIM_ADMIN_SELECTOR.equals(context.view().methodId()) || functionMatch;
    }
}
