package com.walletradar.pricing.resolver.event;

import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.pricing.domain.CanonicalAssetCatalog;
import com.walletradar.pricing.domain.PriceQuote;
import com.walletradar.pricing.domain.PriceResolutionContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Reuses already resolved underlying native price for wrapped/native aliases.
 */
@Component
@Order(30)
public class WrapperPriceResolver implements EventLocalPriceResolver {

    @Override
    public Optional<PriceQuote> resolve(PriceResolutionContext context) {
        for (int siblingIndex = 0; siblingIndex < context.flows().size(); siblingIndex++) {
            if (siblingIndex == context.flowIndex()) {
                continue;
            }
            NormalizedTransaction.Flow sibling = context.flows().get(siblingIndex);
            if (sibling == null || sibling.getRole() == NormalizedLegRole.FEE) {
                continue;
            }
            if (!CanonicalAssetCatalog.sameCanonicalSymbol(
                    context.flow().getAssetSymbol(),
                    sibling.getAssetSymbol()
            )) {
                continue;
            }
            Optional<PriceQuote> siblingQuote = context.resolvedQuote(siblingIndex);
            if (siblingQuote.isEmpty()) {
                continue;
            }
            return Optional.of(new PriceQuote(
                    siblingQuote.orElseThrow().unitPriceUsd(),
                    PriceSource.WRAPPER,
                    siblingQuote.orElseThrow().pricedAt(),
                    siblingQuote.orElseThrow().quoteSymbol(),
                    "wrapper-alias:" + siblingIndex
            ));
        }
        return Optional.empty();
    }
}
