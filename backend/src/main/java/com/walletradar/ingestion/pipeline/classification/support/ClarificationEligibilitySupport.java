package com.walletradar.ingestion.pipeline.classification.support;

import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.ingestion.pipeline.classification.reason.ClassificationReasonCode;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Decides whether a known on-chain transaction is genuinely receipt-clarifiable.
 */
public final class ClarificationEligibilitySupport {

    public static final String BRIDGE_PAIR_EVIDENCE_REQUIRED = ClassificationReasonCode.BRIDGE_PAIR_EVIDENCE_REQUIRED.code();

    private ClarificationEligibilitySupport() {
    }

    public static boolean requiresClarification(
            OnChainRawTransactionView view,
            NormalizedTransactionType type
    ) {
        if (view == null || type == null || type == NormalizedTransactionType.UNKNOWN) {
            return false;
        }
        if (!view.hasExecutionStatusEvidence()) {
            return true;
        }
        if (view.isFeePayer() && !view.hasGasPriceEvidence()) {
            return true;
        }
        if (view.isFeePayer() && !view.hasGasUsed()) {
            return true;
        }
        return view.isContractCreation() && !view.hasContractAddress();
    }

    public static List<String> requiredClarificationReasons(
            OnChainRawTransactionView view,
            NormalizedTransactionType type
    ) {
        if (view == null || type == null || type == NormalizedTransactionType.UNKNOWN) {
            return List.of();
        }

        List<String> reasons = new ArrayList<>();
        if (!view.hasExecutionStatusEvidence()) {
            reasons.add(ClassificationReasonCode.MISSING_EXECUTION_STATUS.code());
        }
        if (!view.hasEffectiveGasPriceEvidence()) {
            reasons.add(ClassificationReasonCode.MISSING_EFFECTIVE_GAS_PRICE.code());
        }
        if (view.isFeePayer() && !view.hasGasUsed()) {
            reasons.add(ClassificationReasonCode.MISSING_GAS_USED.code());
        }
        if (view.isContractCreation() && !view.hasContractAddress()) {
            reasons.add(ClassificationReasonCode.MISSING_CONTRACT_ADDRESS.code());
        }
        return List.copyOf(reasons);
    }

    public static List<String> mergeClarificationReasons(
            OnChainRawTransactionView view,
            NormalizedTransactionType type,
            List<String> existingReasons
    ) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (existingReasons != null) {
            for (String reason : existingReasons) {
                if (reason != null && !reason.isBlank()) {
                    merged.add(reason);
                }
            }
        }
        merged.addAll(requiredClarificationReasons(view, type));
        return List.copyOf(merged);
    }
}
