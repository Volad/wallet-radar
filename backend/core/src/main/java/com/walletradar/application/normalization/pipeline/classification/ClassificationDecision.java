package com.walletradar.application.normalization.pipeline.classification;

import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.transaction.normalized.ClassificationSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;

import java.util.List;

/**
 * Internal decision contract used by the strangler refactor before mapping to the persisted classifier result.
 */
public record ClassificationDecision(
        NormalizedTransactionType type,
        NormalizedTransactionStatus status,
        ClassificationSource classifiedBy,
        ConfidenceLevel confidence,
        List<NormalizedTransaction.Flow> flows,
        List<String> missingDataReasons,
        String correlationId,
        Boolean continuityCandidate,
        String matchedCounterparty,
        Boolean excludedFromAccounting,
        String accountingExclusionReason,
        String protocolName,
        String protocolVersion
) {

    public static ClassificationDecision fromResult(OnChainClassificationResult result) {
        return new ClassificationDecision(
                result.type(),
                result.status(),
                result.classifiedBy(),
                result.confidence(),
                result.flows(),
                result.missingDataReasons(),
                result.correlationId(),
                result.continuityCandidate(),
                result.matchedCounterparty(),
                result.excludedFromAccounting(),
                result.accountingExclusionReason(),
                result.protocolName(),
                result.protocolVersion()
        );
    }
}
