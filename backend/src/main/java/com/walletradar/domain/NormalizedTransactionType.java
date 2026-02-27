package com.walletradar.domain;

/**
 * Canonical transaction operation type (ADR-025).
 */
public enum NormalizedTransactionType {
    SWAP,
    INTERNAL_TRANSFER,
    STAKE_DEPOSIT,
    STAKE_WITHDRAWAL,
    LP_ENTRY,
    LP_EXIT,
    LEND_DEPOSIT,
    LEND_WITHDRAWAL,
    BORROW,
    REPAY,
    EXTERNAL_TRANSFER_OUT,
    EXTERNAL_INBOUND,
    MANUAL_COMPENSATING
}
