package com.walletradar.ingestion.pipeline.classification.onchain.family;

import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.transaction.normalized.ClassificationSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.ingestion.pipeline.classification.ClassificationDecision;
import com.walletradar.ingestion.pipeline.classification.OnChainClassificationContext;
import com.walletradar.ingestion.pipeline.classification.support.OnChainClassificationSupport;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * LBHooks fee-claim classifier that resolves protocol-owned fee rows before generic fallback.
 */
@Component
public class LpFeeClaimClassifier implements OnChainFamilyClassifier {

    private static final String LB_HOOKS_CLAIM_SELECTOR = "0x4e71d92d";

    @Override
    public OnChainClassificationInsertionPoint insertionPoint() {
        return OnChainClassificationInsertionPoint.PRE_PROTOCOL_REVIEW;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 50;
    }

    @Override
    public Optional<ClassificationDecision> classify(OnChainClassificationContext context) {
        if (!onlyInbound(context)) {
            return Optional.empty();
        }
        String functionKey = functionKey(context.view().functionName());
        if ((LB_HOOKS_CLAIM_SELECTOR.equals(context.view().methodId()) || "claim".equals(functionKey))
                && contains(context.view().functionName(), "lbhooks")) {
            return Optional.of(new ClassificationDecision(
                    NormalizedTransactionType.LP_FEE_CLAIM,
                    OnChainClassificationSupport.initialStatus(context.view(), NormalizedTransactionType.LP_FEE_CLAIM, ConfidenceLevel.LOW),
                    ClassificationSource.FUNCTION_NAME,
                    ConfidenceLevel.LOW,
                    OnChainClassificationSupport.toFlows(context.movementLegs(), NormalizedTransactionType.LP_FEE_CLAIM),
                    List.of(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            ));
        }
        return Optional.empty();
    }

    private boolean onlyInbound(OnChainClassificationContext context) {
        boolean hasInbound = context.movementLegs().stream()
                .anyMatch(leg -> !leg.fee() && leg.quantityDelta().signum() > 0);
        boolean hasOutbound = context.movementLegs().stream()
                .anyMatch(leg -> !leg.fee() && leg.quantityDelta().signum() < 0);
        return hasInbound && !hasOutbound;
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

    private boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }
}
