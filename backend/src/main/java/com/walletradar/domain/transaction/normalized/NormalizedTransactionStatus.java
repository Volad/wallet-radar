package com.walletradar.domain.transaction.normalized;

/**
 * Status pipeline for canonical normalized transactions (ADR-025).
 */
public enum NormalizedTransactionStatus {
    PENDING_CLARIFICATION,
    PENDING_PRICE,
    PENDING_STAT,
    CONFIRMED,
    NEEDS_REVIEW
}
