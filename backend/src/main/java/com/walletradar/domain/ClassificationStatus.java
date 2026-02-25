package com.walletradar.domain;

/**
 * Status of classification for a raw transaction (ADR-020).
 * PENDING: not yet classified; processor selects these.
 * COMPLETE: classified and economic events upserted.
 * FAILED: classification error; retry without re-fetch.
 */
public enum ClassificationStatus {
    PENDING,
    COMPLETE,
    FAILED
}
