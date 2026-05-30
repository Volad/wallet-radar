package com.walletradar.pricing.resolver.event;

import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.pricing.domain.CanonicalAssetCatalog;
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
        if (hasMultipleSameCanonicalFlows(context)) {
            return Optional.empty();
        }

        BigDecimal totalCounterpartValue = BigDecimal.ZERO;
        PriceQuote firstQuote = null;
        int contributingCount = 0;

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
            if (!isCounterpartRole(context.flow(), sibling)) {
                continue;
            }
            Optional<PriceQuote> siblingQuote = context.resolvedQuote(siblingIndex);
            if (siblingQuote.isEmpty()) {
                continue;
            }
            BigDecimal siblingValue = sibling.getQuantityDelta().abs()
                    .multiply(siblingQuote.orElseThrow().unitPriceUsd());
            totalCounterpartValue = totalCounterpartValue.add(siblingValue);
            if (firstQuote == null) {
                firstQuote = siblingQuote.orElseThrow();
            }
            contributingCount++;
        }

        if (firstQuote == null || totalCounterpartValue.signum() == 0) {
            return Optional.empty();
        }
        BigDecimal derivedPrice = totalCounterpartValue
                .divide(context.flow().getQuantityDelta().abs(), DIVISION_CONTEXT);
        return Optional.of(new PriceQuote(
                derivedPrice,
                PriceSource.SWAP_DERIVED,
                context.transaction().getBlockTimestamp(),
                firstQuote.quoteSymbol(),
                "swap-derived-multi:" + contributingCount
        ));
    }

    private static boolean isCounterpartRole(NormalizedTransaction.Flow current, NormalizedTransaction.Flow sibling) {
        NormalizedLegRole currentRole = current.getRole();
        NormalizedLegRole siblingRole = sibling.getRole();
        if (currentRole == NormalizedLegRole.BUY)  return siblingRole == NormalizedLegRole.SELL;
        if (currentRole == NormalizedLegRole.SELL) return siblingRole == NormalizedLegRole.BUY;
        // TRANSFER/other: accumulate only flows moving in the opposite direction
        if (siblingRole == NormalizedLegRole.FEE) return false;
        return sibling.getQuantityDelta().signum() != current.getQuantityDelta().signum();
    }

    private boolean hasMultipleSameCanonicalFlows(PriceResolutionContext context) {
        int sameCanonicalCount = 0;
        for (NormalizedTransaction.Flow sibling : context.flows()) {
            if (sibling == null
                    || sibling.getRole() == NormalizedLegRole.FEE
                    || sibling.getQuantityDelta() == null
                    || sibling.getQuantityDelta().signum() == 0) {
                continue;
            }
            if (CanonicalAssetCatalog.sameCanonicalSymbol(
                    context.flow().getAssetSymbol(),
                    sibling.getAssetSymbol()
            )) {
                sameCanonicalCount++;
                if (sameCanonicalCount > 1) {
                    return true;
                }
            }
        }
        return false;
    }
}
