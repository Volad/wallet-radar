package com.walletradar.domain.transaction.normalized;

/**
 * Classification source used to derive the canonical normalized transaction type.
 */
public enum ClassificationSource {
    PROTOCOL_REGISTRY,
    METHOD_ID,
    FUNCTION_NAME,
    HEURISTIC,
    MANUAL
}
