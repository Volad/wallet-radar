package com.walletradar.domain;

/**
 * How the USD price for an event was determined. Priority: STABLECOIN &gt; SWAP_DERIVED &gt; COINGECKO &gt; MANUAL &gt; UNKNOWN.
 */
public enum PriceSource {
    STABLECOIN,
    SWAP_DERIVED,
    COINGECKO,
    MANUAL,
    UNKNOWN
}
