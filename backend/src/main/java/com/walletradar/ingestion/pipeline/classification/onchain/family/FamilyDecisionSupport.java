package com.walletradar.ingestion.pipeline.classification.onchain.family;

import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.ClassificationSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.ingestion.pipeline.classification.ClassificationDecision;
import com.walletradar.ingestion.pipeline.classification.support.ClarificationEligibilitySupport;
import com.walletradar.ingestion.pipeline.classification.support.OnChainClassificationSupport;
import com.walletradar.ingestion.pipeline.classification.support.PricingReadinessSupport;
import com.walletradar.ingestion.pipeline.classification.support.RawLeg;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * Small helper for family classifiers to build stable normalized decisions.
 */
public final class FamilyDecisionSupport {

    private FamilyDecisionSupport() {
    }

    public static ClassificationDecision build(
            NormalizedTransactionType type,
            NormalizedTransactionStatus status,
            ClassificationSource classifiedBy,
            ConfidenceLevel confidence,
            List<RawLeg> movementLegs,
            List<String> missingDataReasons
    ) {
        return new ClassificationDecision(
                type,
                status,
                classifiedBy,
                confidence,
                OnChainClassificationSupport.toFlows(movementLegs, type),
                missingDataReasons,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    public static ClassificationDecision buildWithView(
            OnChainRawTransactionView view,
            NormalizedTransactionType type,
            NormalizedTransactionStatus status,
            ClassificationSource classifiedBy,
            ConfidenceLevel confidence,
            List<NormalizedTransaction.Flow> flows,
            List<String> missingDataReasons
    ) {
        List<String> mergedMissingDataReasons = status == NormalizedTransactionStatus.PENDING_CLARIFICATION
                ? ClarificationEligibilitySupport.mergeClarificationReasons(view, type, missingDataReasons)
                : missingDataReasons;
        NormalizedTransactionStatus adjustedStatus = status;
        if (type == NormalizedTransactionType.SWAP && !hasWalletBoundarySwapShape(flows)) {
            adjustedStatus = NormalizedTransactionStatus.NEEDS_REVIEW;
            mergedMissingDataReasons = appendReason(mergedMissingDataReasons, "SWAP_SHAPE_INCOMPLETE");
            if (!hasBuyLeg(flows)) {
                mergedMissingDataReasons = appendReason(mergedMissingDataReasons, "SWAP_MISSING_BUY_LEG");
            }
            if (!hasSellLeg(flows)) {
                mergedMissingDataReasons = appendReason(mergedMissingDataReasons, "SWAP_MISSING_SELL_LEG");
            }
        }
        if (PricingReadinessSupport.requiresMovementEvidence(type, adjustedStatus)
                && !PricingReadinessSupport.hasNonFeeMovement(flows)) {
            adjustedStatus = NormalizedTransactionStatus.NEEDS_REVIEW;
            mergedMissingDataReasons = appendReason(mergedMissingDataReasons, "INSUFFICIENT_MOVEMENT_EVIDENCE");
        }
        return new ClassificationDecision(
                type,
                adjustedStatus,
                classifiedBy,
                confidence,
                flows,
                mergedMissingDataReasons,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    public static ClassificationDecision buildWithView(
            OnChainRawTransactionView view,
            NormalizedTransactionType type,
            NormalizedTransactionStatus status,
            ClassificationSource classifiedBy,
            ConfidenceLevel confidence,
            List<NormalizedTransaction.Flow> flows,
            List<String> missingDataReasons,
            String protocolName,
            String protocolVersion
    ) {
        List<String> mergedMissingDataReasons = status == NormalizedTransactionStatus.PENDING_CLARIFICATION
                ? ClarificationEligibilitySupport.mergeClarificationReasons(view, type, missingDataReasons)
                : missingDataReasons;
        NormalizedTransactionStatus adjustedStatus = status;
        if (type == NormalizedTransactionType.SWAP && !hasWalletBoundarySwapShape(flows)) {
            adjustedStatus = NormalizedTransactionStatus.NEEDS_REVIEW;
            mergedMissingDataReasons = appendReason(mergedMissingDataReasons, "SWAP_SHAPE_INCOMPLETE");
            if (!hasBuyLeg(flows)) {
                mergedMissingDataReasons = appendReason(mergedMissingDataReasons, "SWAP_MISSING_BUY_LEG");
            }
            if (!hasSellLeg(flows)) {
                mergedMissingDataReasons = appendReason(mergedMissingDataReasons, "SWAP_MISSING_SELL_LEG");
            }
        }
        if (PricingReadinessSupport.requiresMovementEvidence(type, adjustedStatus)
                && !PricingReadinessSupport.hasNonFeeMovement(flows)) {
            adjustedStatus = NormalizedTransactionStatus.NEEDS_REVIEW;
            mergedMissingDataReasons = appendReason(mergedMissingDataReasons, "INSUFFICIENT_MOVEMENT_EVIDENCE");
        }
        return new ClassificationDecision(
                type,
                adjustedStatus,
                classifiedBy,
                confidence,
                flows,
                mergedMissingDataReasons,
                null,
                null,
                null,
                null,
                null,
                protocolName,
                protocolVersion
        );
    }

    public static ClassificationDecision buildWithView(
            OnChainRawTransactionView view,
            NormalizedTransactionType type,
            NormalizedTransactionStatus status,
            ClassificationSource classifiedBy,
            ConfidenceLevel confidence,
            List<NormalizedTransaction.Flow> flows,
            List<String> missingDataReasons,
            boolean continuityCandidate,
            String matchedCounterparty,
            String protocolName,
            String protocolVersion
    ) {
        List<String> mergedMissingDataReasons = status == NormalizedTransactionStatus.PENDING_CLARIFICATION
                ? ClarificationEligibilitySupport.mergeClarificationReasons(view, type, missingDataReasons)
                : missingDataReasons;
        NormalizedTransactionStatus adjustedStatus = status;
        if (type == NormalizedTransactionType.SWAP && !hasWalletBoundarySwapShape(flows)) {
            adjustedStatus = NormalizedTransactionStatus.NEEDS_REVIEW;
            mergedMissingDataReasons = appendReason(mergedMissingDataReasons, "SWAP_SHAPE_INCOMPLETE");
            if (!hasBuyLeg(flows)) {
                mergedMissingDataReasons = appendReason(mergedMissingDataReasons, "SWAP_MISSING_BUY_LEG");
            }
            if (!hasSellLeg(flows)) {
                mergedMissingDataReasons = appendReason(mergedMissingDataReasons, "SWAP_MISSING_SELL_LEG");
            }
        }
        if (PricingReadinessSupport.requiresMovementEvidence(type, adjustedStatus)
                && !PricingReadinessSupport.hasNonFeeMovement(flows)) {
            adjustedStatus = NormalizedTransactionStatus.NEEDS_REVIEW;
            mergedMissingDataReasons = appendReason(mergedMissingDataReasons, "INSUFFICIENT_MOVEMENT_EVIDENCE");
        }
        return new ClassificationDecision(
                type,
                adjustedStatus,
                classifiedBy,
                confidence,
                flows,
                mergedMissingDataReasons,
                null,
                continuityCandidate,
                matchedCounterparty,
                null,
                null,
                protocolName,
                protocolVersion
        );
    }

    public static ClassificationDecision terminalUnknown(
            ClassificationSource classifiedBy,
            ConfidenceLevel confidence,
            List<RawLeg> movementLegs,
            List<String> missingDataReasons
    ) {
        return new ClassificationDecision(
                NormalizedTransactionType.UNKNOWN,
                NormalizedTransactionStatus.CONFIRMED,
                classifiedBy,
                confidence,
                OnChainClassificationSupport.toFlows(movementLegs, NormalizedTransactionType.ADMIN_CONFIG),
                missingDataReasons,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private static boolean hasWalletBoundarySwapShape(List<NormalizedTransaction.Flow> flows) {
        return hasBuyLeg(flows) && hasSellLeg(flows);
    }

    private static boolean hasBuyLeg(List<NormalizedTransaction.Flow> flows) {
        if (flows == null) {
            return false;
        }
        return flows.stream()
                .filter(flow -> flow != null && flow.getRole() == NormalizedLegRole.BUY)
                .anyMatch(flow -> flow.getQuantityDelta() != null && flow.getQuantityDelta().signum() > 0);
    }

    private static boolean hasSellLeg(List<NormalizedTransaction.Flow> flows) {
        if (flows == null) {
            return false;
        }
        return flows.stream()
                .filter(flow -> flow != null && flow.getRole() == NormalizedLegRole.SELL)
                .anyMatch(flow -> flow.getQuantityDelta() != null && flow.getQuantityDelta().signum() < 0);
    }

    private static List<String> appendReason(List<String> reasons, String reason) {
        if (reason == null || reason.isBlank()) {
            return reasons == null ? List.of() : reasons;
        }
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (reasons != null) {
            merged.addAll(reasons);
        }
        merged.add(reason);
        return List.copyOf(merged);
    }
}
