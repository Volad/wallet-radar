package com.walletradar.domain.transaction.normalized;

/**
 * Status pipeline for canonical normalized transactions.
 */
public enum NormalizedTransactionStatus {
    PENDING_CLARIFICATION,
    PENDING_RECLASSIFICATION,
    PENDING_PRICE,
    PENDING_STAT,
    CONFIRMED,
    NEEDS_REVIEW
}
