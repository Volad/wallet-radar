package com.walletradar.domain.transaction.normalized;

/**
 * Canonical economic event type used by normalized transactions.
 */
public enum NormalizedTransactionType {
    SWAP,
    STAKING_DEPOSIT,
    STAKING_WITHDRAW,
    LP_ENTRY,
    LP_EXIT,
    LP_EXIT_PARTIAL,
    LP_EXIT_FINAL,
    LP_ADJUST,
    LP_POSITION_STAKE,
    LP_POSITION_UNSTAKE,
    LP_FEE_CLAIM,
    LENDING_DEPOSIT,
    LENDING_WITHDRAW,
    BORROW,
    REPAY,
    VAULT_DEPOSIT,
    VAULT_WITHDRAW,
    BRIDGE_OUT,
    BRIDGE_IN,
    PROTOCOL_CUSTODY_DEPOSIT,
    PROTOCOL_CUSTODY_WITHDRAW,
    REWARD_CLAIM,
    EXTERNAL_TRANSFER_OUT,
    EXTERNAL_INBOUND,
    INTERNAL_TRANSFER,
    APPROVE,
    ADMIN_CONFIG,
    WRAP,
    UNWRAP,
    UNKNOWN,
    MANUAL_COMPENSATING
}
