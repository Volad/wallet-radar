package com.walletradar.domain;

/**
 * Classification of an economic event. AVCO effect and realised P&amp;L per docs/01-domain.md.
 */
public enum EconomicEventType {
    SWAP_BUY,
    SWAP_SELL,
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
    MANUAL_COMPENSATING
}
