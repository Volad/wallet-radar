package com.walletradar.application.pricing.resolver.event;

import com.walletradar.domain.common.PriceSource;
import com.walletradar.application.pricing.domain.PriceQuote;
import com.walletradar.application.pricing.domain.PriceResolutionContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Reuses exact execution price already carried by the canonical flow.
 */
@Component
@Order(10)
public class ExecutionPriceResolver implements EventLocalPriceResolver {

    @Override
    public Optional<PriceQuote> resolve(PriceResolutionContext context) {
        if (context.flow().getUnitPriceUsd() == null) {
            return Optional.empty();
        }

        PriceSource source = context.flow().getPriceSource();
        if (source != null
                && source != PriceSource.EXECUTION
                && source != PriceSource.MANUAL
                && source != PriceSource.UNKNOWN) {
            return Optional.empty();
        }

        return Optional.of(new PriceQuote(
                context.flow().getUnitPriceUsd(),
                source == PriceSource.MANUAL ? PriceSource.MANUAL : PriceSource.EXECUTION,
                context.transaction().getBlockTimestamp(),
                context.flow().getAssetSymbol(),
                "canonical-execution"
        ));
    }
}
