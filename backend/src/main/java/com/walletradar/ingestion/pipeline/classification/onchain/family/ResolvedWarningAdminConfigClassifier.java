package com.walletradar.ingestion.pipeline.classification.onchain.family;

import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.transaction.normalized.ClassificationSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.ingestion.pipeline.classification.ClassificationDecision;
import com.walletradar.ingestion.pipeline.classification.OnChainClassificationContext;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Admin-config classifier for resolved warning rows that must remain stable before
 * protocol-lifecycle handling.
 */
@Component
public class ResolvedWarningAdminConfigClassifier implements OnChainFamilyClassifier {

    private static final String FEE_BEARING_CLAIM_ADMIN_SELECTOR = "0xdc4b201d";

    @Override
    public OnChainClassificationInsertionPoint insertionPoint() {
        return OnChainClassificationInsertionPoint.PRE_PROTOCOL_REVIEW;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 500;
    }

    @Override
    public Optional<ClassificationDecision> classify(OnChainClassificationContext context) {
        if (!isResolvedWarningAdminConfig(context)) {
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

    private boolean isResolvedWarningAdminConfig(OnChainClassificationContext context) {
        if (!hasNegativeMovement(context)) {
            return false;
        }
        if (onlyInbound(context)) {
            return false;
        }
        return FEE_BEARING_CLAIM_ADMIN_SELECTOR.equals(context.view().methodId())
                || containsAny(context.view().functionName(), "claim(tuple pindata", "claim((tuple pindata");
    }

    private boolean hasNegativeMovement(OnChainClassificationContext context) {
        return context.movementLegs().stream()
                .anyMatch(leg -> leg.quantityDelta() != null && leg.quantityDelta().signum() < 0);
    }

    private boolean onlyInbound(OnChainClassificationContext context) {
        boolean hasPositive = false;
        for (var leg : context.movementLegs()) {
            if (leg.fee() || leg.quantityDelta() == null || leg.quantityDelta().signum() == 0) {
                continue;
            }
            if (leg.quantityDelta().signum() < 0) {
                return false;
            }
            hasPositive = true;
        }
        return hasPositive;
    }

    private boolean containsAny(String haystack, String... needles) {
        if (haystack == null) {
            return false;
        }
        for (String needle : needles) {
            if (needle != null && haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
