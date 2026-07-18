package com.walletradar.domain.transaction.normalized;

/**
 * Canonical role of a normalized asset flow.
 */
public enum NormalizedLegRole {
    BUY,
    SELL,
    TRANSFER,
    FEE,
    /**
     * Fee income received at LP exit (V3 / Slipstream Collect − DecreaseLiquidity delta).
     * Booked as zero-cost acquisition in the Net AVCO lane (income stays implicit per product decision).
     * Must NOT be included in {@code directlyReturnedIdentities} for cross-asset drain in replay.
     */
    LP_FEE_INCOME
}
