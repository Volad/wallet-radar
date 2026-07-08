package com.walletradar.application.normalization.pipeline.classification.reason;

import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;

import java.util.List;

/**
 * Centralized clarification status transition output.
 */
public record ClarificationDecision(
        NormalizedTransactionStatus status,
        List<String> missingDataReasons
) {
}
