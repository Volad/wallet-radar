package com.walletradar.pricing.resolver.event;

import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.pricing.domain.PriceQuote;
import com.walletradar.pricing.domain.PriceResolutionContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Optional;

/**
 * Derives swap-leg price from the canonical wallet-boundary execution ratio.
 */
@Component
@Order(20)
public class SwapDerivedPriceResolver implements EventLocalPriceResolver {

    private static final MathContext DIVISION_CONTEXT = MathContext.DECIMAL128;

    @Override
    public Optional<PriceQuote> resolve(PriceResolutionContext context) {
        if (context.transaction().getType() != NormalizedTransactionType.SWAP
                || context.flow().getRole() == NormalizedLegRole.FEE
                || context.flow().getQuantityDelta() == null
                || context.flow().getQuantityDelta().signum() == 0) {
            return Optional.empty();
        }

        for (int siblingIndex = 0; siblingIndex < context.flows().size(); siblingIndex++) {
            if (siblingIndex == context.flowIndex()) {
                continue;
            }
            NormalizedTransaction.Flow sibling = context.flows().get(siblingIndex);
            if (sibling == null
                    || sibling.getRole() == NormalizedLegRole.FEE
                    || sibling.getQuantityDelta() == null
                    || sibling.getQuantityDelta().signum() == 0) {
                continue;
            }
            Optional<PriceQuote> siblingQuote = context.resolvedQuote(siblingIndex);
            if (siblingQuote.isEmpty()) {
                continue;
            }
            BigDecimal siblingValue = sibling.getQuantityDelta().abs().multiply(siblingQuote.orElseThrow().unitPriceUsd());
            BigDecimal derivedPrice = siblingValue.divide(context.flow().getQuantityDelta().abs(), DIVISION_CONTEXT);
            return Optional.of(new PriceQuote(
                    derivedPrice,
                    PriceSource.SWAP_DERIVED,
                    context.transaction().getBlockTimestamp(),
                    siblingQuote.orElseThrow().quoteSymbol(),
                    "swap-derived:" + siblingIndex
            ));
        }
        return Optional.empty();
    }
}
