package com.walletradar.pricing.resolver.event;

import com.walletradar.pricing.domain.PriceQuote;
import com.walletradar.pricing.domain.PriceResolutionContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Runs event-local resolvers in order and returns the first deterministic quote.
 */
@Component
public class EventLocalPriceResolverChain {

    private final List<EventLocalPriceResolver> resolvers;

    public EventLocalPriceResolverChain(List<EventLocalPriceResolver> resolvers) {
        this.resolvers = List.copyOf(Objects.requireNonNull(resolvers, "resolvers"));
    }

    public Optional<PriceQuote> resolve(PriceResolutionContext context) {
        for (EventLocalPriceResolver resolver : resolvers) {
            Optional<PriceQuote> quote = resolver.resolve(context);
            if (quote.isPresent()) {
                return quote;
            }
        }
        return Optional.empty();
    }
}
