package com.walletradar.application.normalization.pipeline.classification.reason;

import com.walletradar.domain.common.NetworkAddressFormat;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.application.normalization.pipeline.classification.support.ClarificationEligibilitySupport;
import com.walletradar.application.linking.pipeline.clarification.ReceiptClarificationEligibilitySupport;
import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;
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
            NormalizedTransactionStatus status = rawTransaction == null
                    ? NormalizedTransactionStatus.NEEDS_REVIEW
                    : NormalizedTransactionStatus.PENDING_RECLASSIFICATION;
            return new ClarificationDecision(status, List.copyOf(reasons));
        }
        return new ClarificationDecision(NormalizedTransactionStatus.PENDING_CLARIFICATION, List.copyOf(reasons));
    }

    public ClarificationDecision nextReceiptFailureDecision(
            NormalizedTransaction normalizedTransaction,
            RawTransaction rawTransaction,
            String runtimeReason,
            int nextAttempts,
            int maxAttempts
    ) {
        List<String> reasons = normalizedTransaction.getMissingDataReasons() == null
                ? new ArrayList<>()
                : new ArrayList<>(normalizedTransaction.getMissingDataReasons());
        addReason(reasons, runtimeReason);
        NormalizedTransactionStatus status;
        if (rawTransaction == null) {
            status = NormalizedTransactionStatus.NEEDS_REVIEW;
        } else if (nextAttempts >= Math.max(1, maxAttempts)) {
            addReason(reasons, ClassificationReasonCode.CLARIFICATION_ATTEMPTS_EXHAUSTED.code());
            status = NormalizedTransactionStatus.PENDING_RECLASSIFICATION;
        } else {
            status = normalizedTransaction.getStatus();
        }
        return new ClarificationDecision(status, List.copyOf(reasons));
    }

    public boolean isReceiptClarificationEligible(
            NormalizedTransaction normalizedTransaction,
            OnChainRawTransactionView view
    ) {
        // Defense in depth (with the EVM-family selection filter in
        // PendingReceiptClarificationQueryService): full-receipt clarification decodes EVM receipts,
        // so a non-EVM (Solana/TON) row must never be treated as eligible even if it reaches this
        // gate. NetworkAddressFormat.isEvm is the config-driven reusable predicate (bound to
        // NetworkRegistry at startup), not a hardcoded enum list.
        NetworkId networkId = normalizedTransaction == null ? null : normalizedTransaction.getNetworkId();
        if (networkId != null && !NetworkAddressFormat.isEvm(networkId)) {
            return false;
        }
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
