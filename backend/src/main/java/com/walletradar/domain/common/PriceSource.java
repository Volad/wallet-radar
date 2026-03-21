package com.walletradar.domain.common;

/**
 * Canonical source of USD pricing used by normalized transaction flows.
 */
public enum PriceSource {
    STABLECOIN,
    SWAP_DERIVED,
    WRAPPER,
    COINGECKO,
    MANUAL,
    UNKNOWN
}
