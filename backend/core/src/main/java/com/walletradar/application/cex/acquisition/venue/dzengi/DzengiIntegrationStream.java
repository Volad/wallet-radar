package com.walletradar.application.cex.acquisition.venue.dzengi;

/**
 * Dzengi REST API streams for backfill segmentation.
 */
public enum DzengiIntegrationStream {
    LEDGER,
    DEPOSITS,
    WITHDRAWALS,
    /** Per-symbol fills; segment stream value is {@code MY_TRADES:<symbol>}. */
    MY_TRADES,
    /** Tokenized equity fills via v2 API; segment stream value is {@code MY_TRADES_V2:<symbol>}. */
    MY_TRADES_V2,
    TRADING_POSITIONS_HISTORY,
    /** One-shot exchangeInfo symbol catalog refresh. */
    EXCHANGE_INFO
}
