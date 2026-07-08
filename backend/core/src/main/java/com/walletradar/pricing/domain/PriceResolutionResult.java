package com.walletradar.pricing.domain;

import java.util.Optional;

/**
 * Result of one pricing attempt for one canonical flow.
 */
public record PriceResolutionResult(
        Optional<PriceQuote> quote,
        boolean priceUnknown
) {

    public static PriceResolutionResult resolved(PriceQuote quote) {
        return new PriceResolutionResult(Optional.of(quote), false);
    }

    public static PriceResolutionResult unresolved() {
        return new PriceResolutionResult(Optional.empty(), true);
    }
}
