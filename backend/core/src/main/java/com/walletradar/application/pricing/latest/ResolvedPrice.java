package com.walletradar.application.pricing.latest;

import com.walletradar.domain.common.PriceSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * A single deterministically selected USD price for a canonical symbol.
 * Produced by {@link LatestPriceSelectionPolicy} and returned by {@link CurrentPriceReadService}.
 */
public record ResolvedPrice(
        BigDecimal priceUsd,
        PriceSource source,
        Instant pricedAt,
        /** True when pricedAt is older than {@code walletradar.pricing.latest.stale-after-ms}. */
        boolean stale
) {
    public ResolvedPrice {
        Objects.requireNonNull(priceUsd, "priceUsd");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(pricedAt, "pricedAt");
    }
}
