package com.walletradar.application.pricing.resolver.external;

import com.walletradar.domain.common.PriceSource;
import com.walletradar.application.pricing.domain.PriceQuote;
import com.walletradar.application.pricing.domain.PriceRequest;

import java.util.Optional;

/**
 * One external historical market-data source.
 */
public interface ExternalPriceSource {

    PriceSource source();

    default boolean supports(PriceRequest request) {
        return true;
    }

    Optional<PriceQuote> resolve(PriceRequest request);
}
