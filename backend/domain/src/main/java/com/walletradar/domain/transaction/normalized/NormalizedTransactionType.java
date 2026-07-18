package com.walletradar.domain.transaction.normalized;

/**
 * Canonical economic event type used by normalized transactions.
 */
public enum NormalizedTransactionType {
    SWAP,
    STAKING_DEPOSIT,
    STAKING_WITHDRAW_REQUEST,
    STAKING_WITHDRAW,
    LP_ENTRY_REQUEST,
    LP_ENTRY_SETTLEMENT,
    LP_EXIT_REQUEST,
    LP_EXIT_SETTLEMENT,
    LP_ENTRY,
    LP_EXIT,
    LP_EXIT_PARTIAL,
    LP_EXIT_FINAL,
    LP_ADJUST,
    LP_POSITION_STAKE,
    LP_POSITION_UNSTAKE,
    LP_FEE_CLAIM,
    LENDING_DEPOSIT,
    LENDING_LOOP_OPEN,
    LENDING_LOOP_REBALANCE,
    LENDING_LOOP_DECREASE,
    LENDING_LOOP_CLOSE,
    LENDING_WITHDRAW,
    /** Bybit CEX Flexible Savings principal redemption (EARN account → FUND/UTA). */
    EARN_FLEXIBLE_SAVING,
    BORROW,
    REPAY,
    VAULT_DEPOSIT,
    VAULT_WITHDRAW,
    BRIDGE_OUT,
    BRIDGE_IN,
    DEX_ORDER_REQUEST,
    DEX_ORDER_SETTLEMENT,
    DERIVATIVE_ORDER_REQUEST,
    DERIVATIVE_ORDER_EXECUTION,
    DERIVATIVE_ORDER_CANCEL,
    DERIVATIVE_POSITION_INCREASE,
    DERIVATIVE_POSITION_DECREASE,
    PROTOCOL_CUSTODY_DEPOSIT,
    PROTOCOL_CUSTODY_WITHDRAW,
    REWARD_CLAIM,
    EXTERNAL_TRANSFER_OUT,
    EXTERNAL_TRANSFER_IN,
    /**
     * Fiat withdrawal to a real-world bank/card account — a sub-type of EXTERNAL_TRANSFER_OUT
     * that signals money leaving the investment ecosystem (e.g. Dzengi BYN/USD withdrawal
     * via MASTERCARD, BANK, ERIP). Used for P&amp;L and exit-flow reporting without changing
     * accounting treatment (NEC outflow, same as EXTERNAL_TRANSFER_OUT).
     */
    FIAT_EXIT,
    SPONSORED_GAS_IN,
    INTERNAL_TRANSFER,
    APPROVE,
    /** Standalone fee or interest charge (e.g. Bybit loan interest, bonus recollect). */
    FEE,
    /** CEX leverage/CFD position settlement — cash P&L only, no underlying custody (Dzengi). */
    CEX_DERIVATIVE_SETTLEMENT,
    ADMIN_CONFIG,
    WRAP,
    UNWRAP,
    NFT_MINT,
    UNKNOWN,
    MANUAL_COMPENSATING;
}
