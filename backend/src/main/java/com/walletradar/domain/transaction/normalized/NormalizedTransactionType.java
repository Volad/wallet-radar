package com.walletradar.domain.transaction.normalized;

/**
 * Canonical transaction operation type (ADR-025).
 */
public enum NormalizedTransactionType {
    SWAP,
    STAKE_DEPOSIT,
    STAKE_WITHDRAWAL,
    LP_ENTRY,
    LP_EXIT,
    LP_EXIT_PARTIAL,
    LP_EXIT_FINAL,
    LP_ADJUST,
    LP_POSITION_STAKE,
    LP_POSITION_UNSTAKE,
    LP_FEE_CLAIM,
    LEND_DEPOSIT,
    LEND_WITHDRAWAL,
    BORROW,
    REPAY,
    EXTERNAL_TRANSFER_OUT,
    EXTERNAL_INBOUND,
    APPROVAL,
    UNCLASSIFIED,
    MANUAL_COMPENSATING
}
