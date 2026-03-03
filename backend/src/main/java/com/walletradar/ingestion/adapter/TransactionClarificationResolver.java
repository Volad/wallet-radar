package com.walletradar.ingestion.adapter;

import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;

import java.util.List;
import java.util.Optional;

/**
 * Resolver for selective clarification of PENDING_CLARIFICATION normalized transactions.
 */
public interface TransactionClarificationResolver {

    boolean supports(NetworkId networkId);

    Optional<ClarificationResult> clarify(NormalizedTransaction transaction);

    record ClarificationResult(
            List<NormalizedTransaction.Flow> inferredFlows,
            String inferenceReason,
            ConfidenceLevel confidence
    ) {
    }
}
