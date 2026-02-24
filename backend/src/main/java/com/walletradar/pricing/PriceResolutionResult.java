package com.walletradar.pricing;

import com.walletradar.domain.PriceSource;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Result of historical or spot price resolution. Either a price with source or UNKNOWN.
 */
@Getter
public class PriceResolutionResult {

    private static final PriceResolutionResult UNKNOWN = new PriceResolutionResult(null, PriceSource.UNKNOWN);

    private final BigDecimal priceUsd;
    private final PriceSource priceSource;

    public PriceResolutionResult(BigDecimal priceUsd, PriceSource priceSource) {
        this.priceUsd = priceUsd;
        this.priceSource = priceSource;
    }

    public static PriceResolutionResult known(BigDecimal priceUsd, PriceSource source) {
        if (priceUsd == null || source == null || source == PriceSource.UNKNOWN) {
            return UNKNOWN;
        }
        return new PriceResolutionResult(priceUsd, source);
    }

    public static PriceResolutionResult unknown() {
        return UNKNOWN;
    }

    public boolean isUnknown() {
        return priceSource == PriceSource.UNKNOWN || priceUsd == null;
    }

    public Optional<BigDecimal> getPriceUsd() {
        return Optional.ofNullable(priceUsd);
    }
}
