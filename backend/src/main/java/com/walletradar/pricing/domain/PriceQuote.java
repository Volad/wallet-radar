package com.walletradar.pricing.domain;

import com.walletradar.domain.common.PriceSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Resolved USD price for one asset at one event time.
 */
public record PriceQuote(
        BigDecimal unitPriceUsd,
        PriceSource source,
        Instant pricedAt,
        String quoteSymbol,
        String sourceReference
) {

    public PriceQuote {
        Objects.requireNonNull(unitPriceUsd, "unitPriceUsd");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(pricedAt, "pricedAt");
    }
}
