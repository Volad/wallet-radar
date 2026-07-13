package com.walletradar.application.pricing.latest;

import com.walletradar.domain.common.PriceSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * A current-price quote from a single provider, normalized to USD and mapped to a canonical symbol.
 * All providers emit this common shape before persistence to {@code current_price_quotes}.
 */
public record NormalizedLatestQuote(
        /** Canonical market symbol (e.g. "ETH", "BTC", "TSLA"). */
        String canonicalSymbol,
        /** Price normalized to USD. */
        BigDecimal priceUsd,
        /** Raw quote currency before normalization: "USDT", "USDC", "USD". */
        String quoteCurrency,
        /** Data source identifier. */
        PriceSource source,
        /** Native ticker at the venue (e.g. "ETHUSDT", "ETH/USD", "TSLA."). */
        String sourceSymbol,
        /** Timestamp of the price observation. Use fetch time when the venue does not provide per-symbol timestamps. */
        Instant pricedAt
) {
    public NormalizedLatestQuote {
        Objects.requireNonNull(canonicalSymbol, "canonicalSymbol");
        Objects.requireNonNull(priceUsd, "priceUsd");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(pricedAt, "pricedAt");
    }
}
