package com.walletradar.application.costbasis.domain;

/**
 * Reconciliation state between replayed quantity and external balance evidence.
 */
public enum ReconciliationStatus {
    MATCH,
    MISMATCH,
    NOT_APPLICABLE
}
