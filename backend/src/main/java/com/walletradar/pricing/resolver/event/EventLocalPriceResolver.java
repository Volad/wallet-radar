package com.walletradar.pricing.resolver.event;

import com.walletradar.pricing.domain.PriceQuote;
import com.walletradar.pricing.domain.PriceResolutionContext;

import java.util.Optional;

/**
 * Resolves price from canonical transaction-local evidence only.
 */
public interface EventLocalPriceResolver {

    Optional<PriceQuote> resolve(PriceResolutionContext context);
}
