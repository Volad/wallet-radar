package com.walletradar.ingestion.adapter;

import com.walletradar.domain.ConfidenceLevel;
import com.walletradar.domain.NetworkId;
import com.walletradar.domain.NormalizedTransaction;

import java.util.List;
import java.util.Optional;

/**
 * Resolver for selective clarification of PENDING_CLARIFICATION normalized transactions.
 */
public interface TransactionClarificationResolver {

    boolean supports(NetworkId networkId);

    Optional<ClarificationResult> clarify(NormalizedTransaction transaction);

    record ClarificationResult(
            List<NormalizedTransaction.Leg> inferredLegs,
            String inferenceReason,
            ConfidenceLevel confidence
    ) {
    }
}
