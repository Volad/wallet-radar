package com.walletradar.domain.transaction.normalized;

/**
 * Price-resolution signal for normalized transactions.
 */
public enum PricingStatus {
    NOT_REQUIRED,
    PENDING,
    RESOLVED,
    UNRESOLVED
}
