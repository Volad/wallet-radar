package com.walletradar.application.pricing.resolver.event;

import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.transaction.normalized.LeverageBorrowAnnotation;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.application.pricing.domain.CanonicalAssetCatalog;
import com.walletradar.application.pricing.domain.PriceQuote;
import com.walletradar.application.pricing.domain.PriceResolutionContext;
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

    /**
     * When same-role priced sibling legs already account for ≥95% of the counterpart value, the
     * current leg is treated as incidental (dust) and deferred to external market pricing rather
     * than absorbing the whole counterpart value.
     */
    private static final BigDecimal SAME_ROLE_BALANCE_THRESHOLD = new BigDecimal("0.95");

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
        // Guard: for SELL flows in a multi-asset sell swap, SWAP_DERIVED incorrectly assigns the
        // full buy-side value to each sell flow independently (e.g. 4-asset convert → MNT gives
        // ETH a price of $24k because it divides the entire MNT value by tiny ETH qty). When ≥2
        // distinct non-stablecoin SELL assets exist, defer to the standard external pricing chain
        // so each SELL gets a market price; the BUY side then correctly aggregates their values.
        if (context.flow().getRole() == NormalizedLegRole.SELL
                && hasMultipleDistinctNonStableSellAssets(context)) {
            return Optional.empty();
        }
        // Guard: bail if any counterpart-role sibling shares the same canonical symbol.
        // Handles circular / wash-trade cases (e.g. ETH BUY priced against ETH SELL).
        // Same-direction legs sharing the same canonical (e.g. two aArbWBTC BUY legs from
        // an aggregator split route) are NOT circular and must NOT trigger this guard.
        if (hasCounterpartSameCanonicalFlow(context)) {
            return Optional.empty();
        }
        // Guard: never derive a reliable/major asset (CoinGecko-listed native or stablecoin) FROM a
        // counterpart that is itself a long-tail asset lacking a reliable feed. Real case: a
        // 0.1 SOL → 6932 COM (memecoin) swap derived SOL at $8,263/unit off COM's questionable
        // external price, corrupting realised PnL and cost basis. When the current leg is a reliable
        // anchor and EVERY counterpart leg is long-tail, defer to the external chain: the anchor is
        // priced at its own market and the long-tail counterpart derives from IT instead. Deriving a
        // major asset from a stablecoin/major counterpart (USDC→ETH) stays allowed. Catalog-backed,
        // no hardcoded per-asset gate.
        if (isReliableAnchorAsset(context.flow())
                && hasCounterpartRoleSibling(context)
                && !anyCounterpartIsReliableAnchor(context)) {
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
        // Guard: when this leg's opposite-role counterpart value is already (nearly) fully explained
        // by OTHER same-role priced sibling legs, this leg is incidental to the real trade. Example:
        // a USDC→USDT stablecoin swap (SELL USDC / BUY USDT) that also emits a dust SOL BUY. The SOL
        // BUY's only counterpart is the USDC SELL, but that value is already matched by the USDT BUY,
        // so deriving here would dump the whole $65 onto 0.00001 SOL (→ $6.4M/SOL) and the FEE/WRAPPER
        // leg would inherit it as bogus gas. Defer to the external market chain so the dust leg is
        // priced at spot. Applies to any network/asset — never a hardcoded symbol gate.
        BigDecimal sameRolePricedValue = sameRoleOtherSymbolPricedValue(context);
        if (sameRolePricedValue.signum() > 0
                && sameRolePricedValue.compareTo(
                        totalCounterpartValue.multiply(SAME_ROLE_BALANCE_THRESHOLD)) >= 0) {
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
     * A "reliable anchor" asset has its own trustworthy USD feed: a CoinGecko-listed asset (which
     * includes USD stablecoins such as USDC/USDT that are mapped in the catalog). Long-tail
     * memecoins/jettons without a CoinGecko id are not anchors and must not be used to price a
     * major asset via the swap ratio.
     */
    private boolean isReliableAnchorAsset(NormalizedTransaction.Flow flow) {
        if (flow == null || flow.getAssetSymbol() == null || flow.getAssetSymbol().isBlank()) {
            return false;
        }
        return CanonicalAssetCatalog.coinGeckoId(flow.getAssetSymbol()).isPresent();
    }

    /** True when at least one counterpart-role sibling (non-FEE, non-zero) exists for the current flow. */
    private boolean hasCounterpartRoleSibling(PriceResolutionContext context) {
        for (NormalizedTransaction.Flow sibling : context.flows()) {
            if (sibling == null
                    || sibling == context.flow()
                    || sibling.getRole() == NormalizedLegRole.FEE
                    || sibling.getQuantityDelta() == null
                    || sibling.getQuantityDelta().signum() == 0) {
                continue;
            }
            if (isCounterpartRole(context.flow(), sibling)) {
                return true;
            }
        }
        return false;
    }

    /** True when any counterpart-role sibling is itself a reliable anchor asset. */
    private boolean anyCounterpartIsReliableAnchor(PriceResolutionContext context) {
        for (NormalizedTransaction.Flow sibling : context.flows()) {
            if (sibling == null
                    || sibling == context.flow()
                    || sibling.getRole() == NormalizedLegRole.FEE
                    || sibling.getQuantityDelta() == null
                    || sibling.getQuantityDelta().signum() == 0) {
                continue;
            }
            if (isCounterpartRole(context.flow(), sibling) && isReliableAnchorAsset(sibling)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Sum of |quantity|×price for already-priced sibling legs that share the current leg's role
     * (BUY/SELL) but a DIFFERENT exact symbol. Used to detect legs whose opposite-role counterpart
     * value is already balanced by other same-role legs (e.g. a stablecoin↔stablecoin swap that
     * also emits a dust leg). Returns zero for non-BUY/SELL current roles so TRANSFER-style flows
     * are unaffected.
     */
    private BigDecimal sameRoleOtherSymbolPricedValue(PriceResolutionContext context) {
        NormalizedLegRole currentRole = context.flow().getRole();
        if (currentRole != NormalizedLegRole.BUY && currentRole != NormalizedLegRole.SELL) {
            return BigDecimal.ZERO;
        }
        String currentSymbol = context.flow().getAssetSymbol();
        BigDecimal total = BigDecimal.ZERO;
        for (int siblingIndex = 0; siblingIndex < context.flows().size(); siblingIndex++) {
            if (siblingIndex == context.flowIndex()) {
                continue;
            }
            NormalizedTransaction.Flow sibling = context.flows().get(siblingIndex);
            if (sibling == null
                    || sibling.getRole() != currentRole
                    || sibling.getQuantityDelta() == null
                    || sibling.getQuantityDelta().signum() == 0) {
                continue;
            }
            if (currentSymbol != null && currentSymbol.equalsIgnoreCase(sibling.getAssetSymbol())) {
                continue;
            }
            Optional<PriceQuote> siblingQuote = context.resolvedQuote(siblingIndex);
            if (siblingQuote.isEmpty()) {
                continue;
            }
            total = total.add(sibling.getQuantityDelta().abs()
                    .multiply(siblingQuote.orElseThrow().unitPriceUsd()));
        }
        return total;
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

    /**
     * Returns {@code true} when the transaction contains ≥2 distinct non-stablecoin SELL assets
     * (excluding FEE flows and zero-quantity flows). In such cases each SELL flow must be priced
     * by the external market chain; SWAP_DERIVED would otherwise attribute the full buy-side value
     * to every SELL independently, producing wildly inflated unit prices for small-quantity legs.
     */
    private boolean hasMultipleDistinctNonStableSellAssets(PriceResolutionContext context) {
        long distinctCount = context.flows().stream()
                .filter(f -> f != null
                        && f.getRole() == NormalizedLegRole.SELL
                        && f.getQuantityDelta() != null
                        && f.getQuantityDelta().signum() != 0
                        && f.getAssetSymbol() != null
                        && !CanonicalAssetCatalog.isUsdStablecoinBySymbol(f.getAssetSymbol()))
                .map(f -> f.getAssetSymbol().toLowerCase(java.util.Locale.ROOT))
                .distinct()
                .count();
        return distinctCount >= 2;
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
