package com.walletradar.domain.transaction.raw;

/**
 * Status of raw transaction normalization (MVP v2 explorer-first).
 * PENDING: not yet normalized (or requires retry).
 * COMPLETE: normalized and persisted to canonical pipeline.
 */
public enum NormalizationStatus {
    PENDING,
    COMPLETE
}
