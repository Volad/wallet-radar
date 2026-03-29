package com.walletradar.pricing.resolver.external;

import com.walletradar.domain.common.PriceSource;
import com.walletradar.pricing.domain.PriceQuote;
import com.walletradar.pricing.domain.PriceRequest;

import java.util.Optional;

/**
 * One external historical market-data source.
 */
public interface ExternalPriceSource {

    PriceSource source();

    Optional<PriceQuote> resolve(PriceRequest request);
}
