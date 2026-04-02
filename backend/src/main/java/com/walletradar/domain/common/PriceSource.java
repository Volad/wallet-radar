package com.walletradar.domain.common;

/**
 * Canonical source of USD pricing used by normalized transaction flows.
 */
public enum PriceSource {
    STABLECOIN,
    EXECUTION,
    SWAP_DERIVED,
    WRAPPER,
    ECB,
    BYBIT,
    BINANCE,
    COINGECKO,
    MANUAL,
    UNKNOWN
}
