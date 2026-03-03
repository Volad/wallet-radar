package com.walletradar.domain.transaction.normalized;

/**
 * Classification of an economic event. AVCO effect and realised P&amp;L per docs/01-domain.md.
 */
public enum EconomicEventType {
    SWAP_BUY,
    SWAP_SELL,
    STAKE_DEPOSIT,
    STAKE_WITHDRAWAL,
    LP_ENTRY,
    LP_EXIT,
    LP_EXIT_PARTIAL,
    LP_EXIT_FINAL,
    LP_ADJUST,
    LP_POSITION_STAKE,
    LP_POSITION_UNSTAKE,
    LP_POSITION_ENTRY,
    LP_POSITION_EXIT,
    LP_FEE_CLAIM,
    LEND_DEPOSIT,
    LEND_WITHDRAWAL,
    BORROW,
    REPAY,
    EXTERNAL_TRANSFER_OUT,
    /** Inbound transfer from external address. */
    EXTERNAL_INBOUND,
    /** Non-economic permission change (approve/permit/setApprovalForAll). */
    APPROVAL,
    MANUAL_COMPENSATING
}
