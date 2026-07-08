package com.walletradar.application.pricing.resolver.event;

import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.application.pricing.domain.CanonicalAssetCatalog;
import com.walletradar.application.pricing.domain.PriceQuote;
import com.walletradar.application.pricing.domain.PriceResolutionContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Reuses a sibling flow's resolved quote when the current flow is a 1:1 pegged-native
 * receipt ({@link CanonicalAssetCatalog#isPeggedNative}) sharing the same canonical market
 * symbol (e.g. CMETH and ETH both canonicalize to ETH).
 *
 * <p>Differs from {@link WrapperPriceResolver} only in the stamped {@link PriceSource}:
 * {@code PEGGED_NATIVE} signals the replay engine that spot-basis fallback is semantically
 * valid when continuity carry leaves residual uncov.</p>
 */
@Component
@Order(31)
public class PeggedNativePriceResolver implements EventLocalPriceResolver {

    @Override
    public Optional<PriceQuote> resolve(PriceResolutionContext context) {
        if (context.flow() == null
                || context.flow().getAssetSymbol() == null
                || !CanonicalAssetCatalog.isPeggedNative(context.flow().getAssetSymbol())) {
            return Optional.empty();
        }
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
            PriceQuote base = siblingQuote.orElseThrow();
            return Optional.of(new PriceQuote(
                    base.unitPriceUsd(),
                    PriceSource.PEGGED_NATIVE,
                    base.pricedAt(),
                    base.quoteSymbol(),
                    "pegged-native:" + siblingIndex
            ));
        }
        return Optional.empty();
    }
}
