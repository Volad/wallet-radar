package com.walletradar.pricing.resolver.event;

import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.transaction.normalized.LeverageBorrowAnnotation;
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
        // ADR-028: the received collateral leg of an inferred leveraged buy must NOT inherit the
        // swap-implied (depressed) consideration price — its true basis is market spot, with the
        // value gap modelled as a synthetic borrow at replay. Skipping here lets the external
        // market source price it.
        if (isLeverageCollateralLeg(context)) {
            return Optional.empty();
        }
        // Guard: bail if any counterpart-role sibling shares the same canonical symbol.
        // Handles circular / wash-trade cases (e.g. ETH BUY priced against ETH SELL).
        // Same-direction legs sharing the same canonical (e.g. two aArbWBTC BUY legs from
        // an aggregator split route) are NOT circular and must NOT trigger this guard.
        if (hasCounterpartSameCanonicalFlow(context)) {
            return Optional.empty();
        }
        // Aggregate all same-direction same-exact-symbol legs as the price denominator so
        // that each leg receives the correct derived unit price.
        BigDecimal totalSameDirQty = computeTotalSameDirectionQty(context);

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
                .divide(totalSameDirQty, DIVISION_CONTEXT);
        return Optional.of(new PriceQuote(
                derivedPrice,
                PriceSource.SWAP_DERIVED,
                context.transaction().getBlockTimestamp(),
                firstQuote.quoteSymbol(),
                "swap-derived-multi:" + contributingCount
        ));
    }

    /**
     * True when the current flow is the received collateral leg (positive quantity) of a transaction
     * annotated as a leveraged buy whose collateral matches this leg's symbol.
     */
    private boolean isLeverageCollateralLeg(PriceResolutionContext context) {
        NormalizedTransaction transaction = context.transaction();
        if (!LeverageBorrowAnnotation.isLeveragedBuy(transaction)) {
            return false;
        }
        NormalizedTransaction.Flow flow = context.flow();
        if (flow.getQuantityDelta().signum() <= 0) {
            return false;
        }
        String collateralSymbol = LeverageBorrowAnnotation.collateralSymbol(transaction);
        return collateralSymbol != null
                && flow.getAssetSymbol() != null
                && collateralSymbol.equalsIgnoreCase(flow.getAssetSymbol());
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

    /** Returns true when a counterpart-role sibling shares the same canonical symbol as the
     *  current flow (circular derivation risk, e.g. ETH BUY priced against ETH SELL). */
    private boolean hasCounterpartSameCanonicalFlow(PriceResolutionContext context) {
        for (NormalizedTransaction.Flow sibling : context.flows()) {
            if (sibling == null
                    || sibling.getRole() == NormalizedLegRole.FEE
                    || sibling.getQuantityDelta() == null
                    || sibling.getQuantityDelta().signum() == 0) {
                continue;
            }
            if (!isCounterpartRole(context.flow(), sibling)) {
                continue;
            }
            if (CanonicalAssetCatalog.sameCanonicalSymbol(
                    context.flow().getAssetSymbol(),
                    sibling.getAssetSymbol())) {
                return true;
            }
        }
        return false;
    }

    /** Sums the absolute quantities of all same-direction same-exact-symbol flows (including
     *  the current flow). Uses exact symbol matching (not canonical family) so that different
     *  wrapped tokens (e.g. aArbWBTC vs cbBTC) are never merged.
     *  Falls back to the current flow's own quantity if no same-direction legs are found. */
    private BigDecimal computeTotalSameDirectionQty(PriceResolutionContext context) {
        BigDecimal total = BigDecimal.ZERO;
        NormalizedLegRole currentRole = context.flow().getRole();
        int currentSignum = context.flow().getQuantityDelta().signum();
        String currentSymbol = context.flow().getAssetSymbol();
        for (NormalizedTransaction.Flow f : context.flows()) {
            if (f == null
                    || f.getRole() == NormalizedLegRole.FEE
                    || f.getQuantityDelta() == null
                    || f.getQuantityDelta().signum() == 0) {
                continue;
            }
            boolean sameDir = (currentRole == NormalizedLegRole.BUY || currentRole == NormalizedLegRole.SELL)
                    ? f.getRole() == currentRole
                    : f.getQuantityDelta().signum() == currentSignum;
            if (!sameDir) {
                continue;
            }
            if (!currentSymbol.equalsIgnoreCase(f.getAssetSymbol())) {
                continue;
            }
            total = total.add(f.getQuantityDelta().abs());
        }
        return total.signum() > 0 ? total : context.flow().getQuantityDelta().abs();
    }
}
