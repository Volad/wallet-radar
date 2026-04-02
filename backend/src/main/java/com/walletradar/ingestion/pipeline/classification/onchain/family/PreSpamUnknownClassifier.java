package com.walletradar.ingestion.pipeline.classification.onchain.family;

import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.transaction.normalized.ClassificationSource;
import com.walletradar.ingestion.pipeline.classification.ClassificationDecision;
import com.walletradar.ingestion.pipeline.classification.OnChainClassificationContext;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * UNKNOWN sink rules that must run after protocol lifecycle handling but before spam review.
 */
@Component
public class PreSpamUnknownClassifier implements OnChainFamilyClassifier {

    private static final String CLAIM_LIKE_AIRDROP_SELECTOR = "0x729ad39e";
    private static final String PENDING_REDEEM_REQUEST_SELECTOR = "0x5cfe2fe4";

    @Override
    public OnChainClassificationInsertionPoint insertionPoint() {
        return OnChainClassificationInsertionPoint.PRE_SPAM_REVIEW;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }

    @Override
    public Optional<ClassificationDecision> classify(OnChainClassificationContext context) {
        String functionKey = functionKey(context.view().functionName());
        if (PENDING_REDEEM_REQUEST_SELECTOR.equals(context.view().methodId())
                || "claimsharesandrequestredeem".equals(functionKey)) {
            return Optional.of(FamilyDecisionSupport.terminalUnknown(
                    ClassificationSource.METHOD_ID,
                    ConfidenceLevel.MEDIUM,
                    context.movementLegs(),
                    List.of("PENDING_REDEEM_REQUEST")
            ));
        }
        if ((CLAIM_LIKE_AIRDROP_SELECTOR.equals(context.view().methodId()) || "airdrop".equals(functionKey))
                && onlyInbound(context)) {
            return Optional.of(FamilyDecisionSupport.terminalUnknown(
                    ClassificationSource.METHOD_ID,
                    ConfidenceLevel.LOW,
                    context.movementLegs(),
                    List.of("CLAIM_LIKE_SPAM_OR_AIRDROP")
            ));
        }
        return Optional.empty();
    }

    private String functionKey(String functionName) {
        if (functionName == null) {
            return "";
        }
        String normalized = functionName.trim().toLowerCase(Locale.ROOT);
        int signatureSeparator = normalized.indexOf('(');
        if (signatureSeparator > 0) {
            return normalized.substring(0, signatureSeparator);
        }
        return normalized;
    }

    private boolean onlyInbound(OnChainClassificationContext context) {
        boolean hasInbound = context.movementLegs().stream()
                .anyMatch(leg -> !leg.fee() && leg.quantityDelta().signum() > 0);
        boolean hasOutbound = context.movementLegs().stream()
                .anyMatch(leg -> !leg.fee() && leg.quantityDelta().signum() < 0);
        return hasInbound && !hasOutbound;
    }
}
