package com.walletradar.application.pricing.resolver.event;

import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.application.pricing.domain.PriceQuote;
import com.walletradar.application.pricing.domain.PriceResolutionContext;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SwapDerivedPriceResolverTest {

    private static final Instant NOW = Instant.parse("2025-01-21T00:00:00Z");
    private static final SwapDerivedPriceResolver RESOLVER = new SwapDerivedPriceResolver();

    @Test
    void singleSellAndBuy_priceFromSingleSell() {
        // 1 USDC SELL → 0.0003 ETH BUY: price = 1.00 / 0.0003 = 3333.33
        NormalizedTransaction tx = swapTx(List.of(
                flow("USDC",  "-1.00",  NormalizedLegRole.SELL),
                flow("ETH",   "0.0003", NormalizedLegRole.BUY)
        ));
        Map<Integer, PriceQuote> resolved = Map.of(
                0, quote(new BigDecimal("1.00"))
        );
        PriceResolutionContext ctx = context(tx, 1, resolved);

        Optional<PriceQuote> result = RESOLVER.resolve(ctx);

        assertThat(result).isPresent();
        assertThat(result.get().unitPriceUsd())
                .isEqualByComparingTo(new BigDecimal("1.00").divide(new BigDecimal("0.0003"), java.math.MathContext.DECIMAL128));
        assertThat(result.get().sourceReference()).isEqualTo("swap-derived-multi:1");
    }

    @Test
    void twoSellFlowsSameAsset_priceFromSumOfBothSells() {
        // 2× USDC SELL (10 each) → 0.006908 ETH BUY: price = 20 / 0.006908
        NormalizedTransaction tx = swapTx(List.of(
                flow("USDC", "-10.00",   NormalizedLegRole.SELL),
                flow("USDC", "-10.00",   NormalizedLegRole.SELL),
                flow("ETH",  "0.006908", NormalizedLegRole.BUY)
        ));
        Map<Integer, PriceQuote> resolved = Map.of(
                0, quote(new BigDecimal("1.00")),
                1, quote(new BigDecimal("1.00"))
        );
        PriceResolutionContext ctx = context(tx, 2, resolved);

        Optional<PriceQuote> result = RESOLVER.resolve(ctx);

        assertThat(result).isPresent();
        BigDecimal expected = new BigDecimal("20.00").divide(new BigDecimal("0.006908"), java.math.MathContext.DECIMAL128);
        assertThat(result.get().unitPriceUsd()).isEqualByComparingTo(expected);
        assertThat(result.get().sourceReference()).isEqualTo("swap-derived-multi:2");
    }

    @Test
    void twoSellFlowsOneUnpriced_priceFromPricedSellOnly() {
        // USDC[0] has price, USDC[1] does not → only priced sibling contributes
        NormalizedTransaction tx = swapTx(List.of(
                flow("USDC", "-10.00",   NormalizedLegRole.SELL),
                flow("USDC", "-10.00",   NormalizedLegRole.SELL),
                flow("ETH",  "0.006908", NormalizedLegRole.BUY)
        ));
        Map<Integer, PriceQuote> resolved = Map.of(
                0, quote(new BigDecimal("1.00"))
                // index 1 intentionally absent
        );
        PriceResolutionContext ctx = context(tx, 2, resolved);

        Optional<PriceQuote> result = RESOLVER.resolve(ctx);

        assertThat(result).isPresent();
        // Only 10 USDC contributes (not 20)
        BigDecimal expected = new BigDecimal("10.00").divide(new BigDecimal("0.006908"), java.math.MathContext.DECIMAL128);
        assertThat(result.get().unitPriceUsd()).isEqualByComparingTo(expected);
        assertThat(result.get().sourceReference()).isEqualTo("swap-derived-multi:1");
    }

    @Test
    void wethSellPlusEthSellPlusEthBuy_guardFires_returnsEmpty() {
        // WETH SELL + ETH BUY + ETH SELL — ETH/WETH are canonical equivalents.
        // hasCounterpartSameCanonicalFlow detects a counterpart-role SELL sibling (WETH) that
        // shares the same canonical as the current ETH BUY flow → guard fires → no derivation.
        NormalizedTransaction tx = swapTx(List.of(
                flow("WETH", "-0.05",  NormalizedLegRole.SELL),
                flow("ETH",  "0.10",   NormalizedLegRole.BUY),
                flow("ETH",  "-0.05",  NormalizedLegRole.SELL)
        ));
        Map<Integer, PriceQuote> resolved = Map.of(
                0, quote(new BigDecimal("3000.00")),
                2, quote(new BigDecimal("3000.00"))
        );
        // Resolve from the perspective of flow at index 1 (ETH BUY)
        PriceResolutionContext ctx = context(tx, 1, resolved);

        Optional<PriceQuote> result = RESOLVER.resolve(ctx);

        assertThat(result).isEmpty();
    }

    // ──────────────────────────────────────────────────────────────
    // B-NEW-1: dual same-direction BUY leg aggregation tests
    // ──────────────────────────────────────────────────────────────

    @Test
    void twoAarbWbtcBuyLegsOneUsdcSell_eachBuyLegGetsDerivedPrice() {
        // KyberSwap Arbitrum 0xdef59c37: USDC -15 @ $1 → aArbWBTC +5.3e-7 + aArbWBTC +0.00020196
        // Derived price = $15 / (5.3e-7 + 0.00020196) ≈ $74,077 for each BUY leg.
        NormalizedTransaction tx = swapTx(List.of(
                flow("USDC",      "-15.00",        NormalizedLegRole.SELL),
                flow("aArbWBTC",  "0.000000530",   NormalizedLegRole.BUY),
                flow("aArbWBTC",  "0.000201960",   NormalizedLegRole.BUY)
        ));
        Map<Integer, PriceQuote> resolved = Map.of(
                0, quote(new BigDecimal("1.00"))
        );

        BigDecimal totalBuyQty = new BigDecimal("0.000000530").add(new BigDecimal("0.000201960"));
        BigDecimal expectedPrice = new BigDecimal("15.00").divide(totalBuyQty, java.math.MathContext.DECIMAL128);

        // Resolve for the first BUY leg (index 1)
        PriceResolutionContext ctx1 = context(tx, 1, resolved);
        Optional<PriceQuote> result1 = RESOLVER.resolve(ctx1);
        assertThat(result1).isPresent();
        assertThat(result1.get().unitPriceUsd()).isEqualByComparingTo(expectedPrice);
        assertThat(result1.get().sourceReference()).isEqualTo("swap-derived-multi:1");

        // Resolve for the second BUY leg (index 2) — must yield the same unit price
        PriceResolutionContext ctx2 = context(tx, 2, resolved);
        Optional<PriceQuote> result2 = RESOLVER.resolve(ctx2);
        assertThat(result2).isPresent();
        assertThat(result2.get().unitPriceUsd()).isEqualByComparingTo(expectedPrice);
        assertThat(result2.get().sourceReference()).isEqualTo("swap-derived-multi:1");
    }

    @Test
    void twoAarbWbtcBuyLegsOneUsdcSellUnpriced_returnsEmpty() {
        // Same structure as above but USDC price not available → no derivation possible.
        NormalizedTransaction tx = swapTx(List.of(
                flow("USDC",      "-15.00",        NormalizedLegRole.SELL),
                flow("aArbWBTC",  "0.000000530",   NormalizedLegRole.BUY),
                flow("aArbWBTC",  "0.000201960",   NormalizedLegRole.BUY)
        ));
        Map<Integer, PriceQuote> resolved = Map.of();  // USDC not priced

        Optional<PriceQuote> result1 = RESOLVER.resolve(context(tx, 1, resolved));
        assertThat(result1).isEmpty();

        Optional<PriceQuote> result2 = RESOLVER.resolve(context(tx, 2, resolved));
        assertThat(result2).isEmpty();
    }

    @Test
    void leverageCollateralBuyLeg_skipsSwapDerived_soMarketSpotApplies() {
        // ADR-028: a leveraged buy's collateral leg must NOT inherit the depressed swap-implied price
        // ($1,005 / 0.86155 = ~$1,167); skipping here lets the external market source price it.
        NormalizedTransaction tx = swapTx(List.of(
                flow("USDC",  "-1005.30", NormalizedLegRole.SELL),
                flow("cmETH", "0.86155",  NormalizedLegRole.BUY)
        ));
        com.walletradar.domain.transaction.normalized.LeverageBorrowAnnotation.write(
                tx, true, "LEVERAGE_ROUTER_SELECTOR",
                "evm-lev:MANTLE:0xcmeth:0xwallet", "0xcmeth", "cmETH");
        Map<Integer, PriceQuote> resolved = Map.of(0, quote(new BigDecimal("1.00")));

        Optional<PriceQuote> result = RESOLVER.resolve(context(tx, 1, resolved));

        assertThat(result).isEmpty();
    }

    @Test
    void leverageConsiderationSellLeg_stillSwapDerivable() {
        // Only the received collateral leg is skipped; the paid leg is unaffected.
        NormalizedTransaction tx = swapTx(List.of(
                flow("USDC",  "-1005.30", NormalizedLegRole.SELL),
                flow("cmETH", "0.86155",  NormalizedLegRole.BUY)
        ));
        com.walletradar.domain.transaction.normalized.LeverageBorrowAnnotation.write(
                tx, true, "LEVERAGE_ROUTER_SELECTOR",
                "evm-lev:MANTLE:0xcmeth:0xwallet", "0xcmeth", "cmETH");
        Map<Integer, PriceQuote> resolved = Map.of(1, quote(new BigDecimal("3300.00")));

        Optional<PriceQuote> result = RESOLVER.resolve(context(tx, 0, resolved));

        assertThat(result).isPresent();
    }

    // ──────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────

    private static NormalizedTransaction swapTx(List<NormalizedTransaction.Flow> flows) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setType(NormalizedTransactionType.SWAP);
        tx.setBlockTimestamp(NOW);
        tx.setFlows(new ArrayList<>(flows));
        return tx;
    }

    private static NormalizedTransaction.Flow flow(String symbol, String qty, NormalizedLegRole role) {
        NormalizedTransaction.Flow f = new NormalizedTransaction.Flow();
        f.setAssetSymbol(symbol);
        f.setQuantityDelta(new BigDecimal(qty));
        f.setRole(role);
        return f;
    }

    private static PriceQuote quote(BigDecimal unitPrice) {
        return new PriceQuote(unitPrice, PriceSource.COINGECKO, NOW, "USD", "test");
    }

    private static PriceResolutionContext context(NormalizedTransaction tx, int flowIndex,
                                                   Map<Integer, PriceQuote> resolved) {
        return new PriceResolutionContext(tx, tx.getFlows().get(flowIndex), flowIndex, resolved);
    }
}
