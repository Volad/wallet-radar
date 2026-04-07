package com.walletradar.ingestion.pipeline.classification.reason;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.ingestion.pipeline.classification.support.ClarificationEligibilitySupport;
import com.walletradar.ingestion.pipeline.clarification.ReceiptClarificationEligibilitySupport;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Centralized gateway for clarification reasons, eligibility, and failure transitions.
 */
@Service
public class ClarificationPolicyService {

    public List<String> mergeClassifierReasons(
            OnChainRawTransactionView view,
            NormalizedTransactionType type,
            List<String> existingReasons
    ) {
        return ClarificationEligibilitySupport.mergeClarificationReasons(view, type, existingReasons);
    }

    public ClarificationDecision nextFailureDecision(
            NormalizedTransaction normalizedTransaction,
            RawTransaction rawTransaction,
            String runtimeReason,
            int nextAttempts,
            int maxAttempts
    ) {
        List<String> reasons = initialReasons(normalizedTransaction, rawTransaction);
        addReason(reasons, runtimeReason);

        if (nextAttempts >= Math.max(1, maxAttempts)) {
            addReason(reasons, ClassificationReasonCode.CLARIFICATION_ATTEMPTS_EXHAUSTED.code());
            return new ClarificationDecision(NormalizedTransactionStatus.NEEDS_REVIEW, List.copyOf(reasons));
        }
        return new ClarificationDecision(NormalizedTransactionStatus.PENDING_CLARIFICATION, List.copyOf(reasons));
    }

    public ClarificationDecision nextReceiptFailureDecision(
            NormalizedTransaction normalizedTransaction,
            String runtimeReason
    ) {
        List<String> reasons = normalizedTransaction.getMissingDataReasons() == null
                ? new ArrayList<>()
                : new ArrayList<>(normalizedTransaction.getMissingDataReasons());
        addReason(reasons, runtimeReason);
        return new ClarificationDecision(normalizedTransaction.getStatus(), List.copyOf(reasons));
    }

    public boolean isReceiptClarificationEligible(
            NormalizedTransaction normalizedTransaction,
            OnChainRawTransactionView view
    ) {
        return ReceiptClarificationEligibilitySupport.isEligible(normalizedTransaction, view);
    }

    private List<String> initialReasons(
            NormalizedTransaction normalizedTransaction,
            RawTransaction rawTransaction
    ) {
        List<String> existingReasons = normalizedTransaction.getMissingDataReasons() == null
                ? List.of()
                : normalizedTransaction.getMissingDataReasons();
        if (rawTransaction == null) {
            return new ArrayList<>(existingReasons);
        }
        return new ArrayList<>(mergeClassifierReasons(
                OnChainRawTransactionView.wrap(rawTransaction),
                normalizedTransaction.getType(),
                existingReasons
        ));
    }

    private void addReason(List<String> reasons, String reason) {
        if (reason != null && !reason.isBlank() && !reasons.contains(reason)) {
            reasons.add(reason);
        }
    }
}
