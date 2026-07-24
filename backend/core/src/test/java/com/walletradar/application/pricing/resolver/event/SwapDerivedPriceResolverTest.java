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

    // ──────────────────────────────────────────────────────────────
    // Multi-sell guard tests (hasMultipleDistinctNonStableSellAssets)
    // ──────────────────────────────────────────────────────────────

    @Test
    void multiNonStableSell_sellFlowReturnsEmpty() {
        // Bybit convert: BBSOL + ETH + XRP + USDT → MNT
        // ETH SELL flow must be skipped so external market price applies.
        NormalizedTransaction tx = swapTx(List.of(
                flow("BBSOL", "-0.000030",  NormalizedLegRole.SELL),
                flow("ETH",   "-0.00000272", NormalizedLegRole.SELL),
                flow("XRP",   "-0.000050",  NormalizedLegRole.SELL),
                flow("USDT",  "-0.0001",    NormalizedLegRole.SELL),
                flow("MNT",   "0.032190",   NormalizedLegRole.BUY)
        ));
        Map<Integer, PriceQuote> resolved = Map.of(
                4, quote(new BigDecimal("0.002107"))
        );

        // All non-stablecoin SELL flows (BBSOL, ETH, XRP) must return empty
        assertThat(RESOLVER.resolve(context(tx, 0, resolved))).isEmpty();
        assertThat(RESOLVER.resolve(context(tx, 1, resolved))).isEmpty();
        assertThat(RESOLVER.resolve(context(tx, 2, resolved))).isEmpty();
    }

    @Test
    void multiNonStableSell_buyFlowNotAffectedByGuard() {
        // The BUY side (MNT) must NOT be blocked by the multi-sell guard,
        // so once SELL legs have external prices the BUY can still use SWAP_DERIVED.
        NormalizedTransaction tx = swapTx(List.of(
                flow("BBSOL", "-0.000030",   NormalizedLegRole.SELL),
                flow("ETH",   "-0.00000272", NormalizedLegRole.SELL),
                flow("XRP",   "-0.000050",   NormalizedLegRole.SELL),
                flow("MNT",   "0.032190",    NormalizedLegRole.BUY)
        ));
        // Suppose external chain priced each SELL:
        Map<Integer, PriceQuote> resolved = Map.of(
                0, quote(new BigDecimal("150.00")),   // BBSOL
                1, quote(new BigDecimal("2500.00")),  // ETH
                2, quote(new BigDecimal("2.50"))      // XRP
        );
        PriceResolutionContext mntCtx = context(tx, 3, resolved);

        Optional<PriceQuote> result = RESOLVER.resolve(mntCtx);

        // MNT BUY should receive a SWAP_DERIVED price aggregated from all SELL values
        assertThat(result).isPresent();
        assertThat(result.get().source()).isEqualTo(PriceSource.SWAP_DERIVED);
    }

    @Test
    void singleNonStableSellPlusStablecoin_guardDoesNotFire() {
        // ETH SELL + USDT SELL → BTC BUY: only 1 distinct non-stablecoin SELL (ETH).
        // Guard must NOT fire; ETH SELL is still priced by SWAP_DERIVED.
        NormalizedTransaction tx = swapTx(List.of(
                flow("ETH",  "-0.01",  NormalizedLegRole.SELL),
                flow("USDT", "-25.00", NormalizedLegRole.SELL),
                flow("BTC",  "0.0004", NormalizedLegRole.BUY)
        ));
        Map<Integer, PriceQuote> resolved = Map.of(
                2, quote(new BigDecimal("60000.00"))  // BTC buy side priced → derive ETH price
        );
        // Actually for ETH SELL we need BTC BUY to be on the counterpart side already resolved.
        // Let's test from ETH's perspective with BTC priced.
        Optional<PriceQuote> result = RESOLVER.resolve(context(tx, 0, resolved));

        // Guard did not fire (only 1 non-stablecoin SELL asset); derivation attempted from BTC.
        // Since BTC is priced and is a counterpart BUY, result may be present.
        // We only assert guard didn't block — result may be empty if USDT unresolved.
        // The key test: resolver was not blocked by multi-sell guard.
        // (asserting isEmpty would mean swap-derived didn't have the BTC price yet in sibling map)
        // We have BTC at index 2 in resolved, so should produce a price.
        assertThat(result).isPresent();
    }

    // ──────────────────────────────────────────────────────────────
    // WS-6 (B4): swap-derived-first for Solana / TON long-tail basis
    // ──────────────────────────────────────────────────────────────

    @Test
    void solanaSwap_stableAnchorLeg_derivesMemecoinUsdBasis() {
        // Jupiter swap: 50 USDC (anchor $1) → 1000 SNAI. SNAI has no CEX/feed quote, but its USD
        // basis is derived from the stable anchor leg: $50 / 1000 = $0.05.
        NormalizedTransaction tx = swapTx(List.of(
                flow("USDC", "-50.00", NormalizedLegRole.SELL),
                flow("SNAI", "1000.0", NormalizedLegRole.BUY)
        ));
        Map<Integer, PriceQuote> resolved = Map.of(0, quote(new BigDecimal("1.00")));

        Optional<PriceQuote> result = RESOLVER.resolve(context(tx, 1, resolved));

        assertThat(result).isPresent();
        assertThat(result.get().source()).isEqualTo(PriceSource.SWAP_DERIVED);
        assertThat(result.get().unitPriceUsd()).isEqualByComparingTo("0.05");
    }

    @Test
    void solanaSwap_nativeSolAnchorLeg_derivesMemecoinUsdBasis() {
        // 0.5 SOL (anchor priced $200) → 4000 DUKO → DUKO basis = $100 / 4000 = $0.025.
        NormalizedTransaction tx = swapTx(List.of(
                flow("SOL",  "-0.5",   NormalizedLegRole.SELL),
                flow("DUKO", "4000.0", NormalizedLegRole.BUY)
        ));
        Map<Integer, PriceQuote> resolved = Map.of(0, quote(new BigDecimal("200.00")));

        Optional<PriceQuote> result = RESOLVER.resolve(context(tx, 1, resolved));

        assertThat(result).isPresent();
        assertThat(result.get().unitPriceUsd()).isEqualByComparingTo("0.025");
    }

    @Test
    void wsolCanonicalisesToSol_circularGuardFires_soWsolLegIsNotADistinctAsset() {
        // A wSOL SELL vs native SOL BUY is the SAME asset (wSOL→SOL canonical). The circular guard
        // must fire so wSOL is never treated as a distinct counterpart that self-prices SOL.
        NormalizedTransaction tx = swapTx(List.of(
                flow("wSOL", "-1.0", NormalizedLegRole.SELL),
                flow("SOL",  "1.0",  NormalizedLegRole.BUY)
        ));
        Map<Integer, PriceQuote> resolved = Map.of(0, quote(new BigDecimal("200.00")));

        Optional<PriceQuote> result = RESOLVER.resolve(context(tx, 1, resolved));

        assertThat(result).isEmpty();
    }

    @Test
    void wsolAnchorLeg_derivesMemecoinUsdBasis() {
        // wSOL (canonical SOL) as the priced counterpart anchor still derives the memecoin's basis;
        // it differs in symbol from the memecoin, so the circular guard does NOT fire.
        NormalizedTransaction tx = swapTx(List.of(
                flow("wSOL", "-0.25",  NormalizedLegRole.SELL),
                flow("PIAI", "500.0",  NormalizedLegRole.BUY)
        ));
        Map<Integer, PriceQuote> resolved = Map.of(0, quote(new BigDecimal("200.00")));

        Optional<PriceQuote> result = RESOLVER.resolve(context(tx, 1, resolved));

        assertThat(result).isPresent();
        assertThat(result.get().unitPriceUsd()).isEqualByComparingTo("0.10"); // $50 / 500
    }

    @Test
    void tonSwap_nativeTonAnchorLeg_derivesJettonUsdBasis() {
        // Ston.fi swap: 20 TON (anchor priced $6) → 200 STON → STON basis = $120 / 200 = $0.60.
        NormalizedTransaction tx = swapTx(List.of(
                flow("TON",  "-20.0",  NormalizedLegRole.SELL),
                flow("STON", "200.0",  NormalizedLegRole.BUY)
        ));
        Map<Integer, PriceQuote> resolved = Map.of(0, quote(new BigDecimal("6.00")));

        Optional<PriceQuote> result = RESOLVER.resolve(context(tx, 1, resolved));

        assertThat(result).isPresent();
        assertThat(result.get().unitPriceUsd()).isEqualByComparingTo("0.60");
    }

    @Test
    void illiquidToIlliquidSwap_noAnchor_returnsEmptySoAssetStaysUnpriced() {
        // Both legs are illiquid memecoins with NO stable/native anchor and no resolved sibling
        // quote. Swap-derived alone cannot produce a USD value → returns empty → the asset flows
        // to the external chain and ultimately to an explicit UNPRICED state (never a $0 basis).
        NormalizedTransaction tx = swapTx(List.of(
                flow("DOOD", "-100.0", NormalizedLegRole.SELL),
                flow("SNAI", "250.0",  NormalizedLegRole.BUY)
        ));
        Map<Integer, PriceQuote> resolved = Map.of(); // neither leg priced

        assertThat(RESOLVER.resolve(context(tx, 0, resolved))).isEmpty();
        assertThat(RESOLVER.resolve(context(tx, 1, resolved))).isEmpty();
    }

    @Test
    void zeroQuantityLeg_ignoredAsSibling_soNettedPtonNeverPricesCounterpart() {
        // WS-2 nets proxy-TON (pTON) to 0 before pricing. A zero-quantity pTON leg must never act as
        // a swap sibling: here TON (priced anchor) → STON derives correctly and the pTON leg (qty 0)
        // is ignored entirely, so it cannot re-enter as a phantom-priced counterpart.
        NormalizedTransaction tx = swapTx(List.of(
                flow("TON",  "-20.0", NormalizedLegRole.SELL),
                flow("PTON", "0",     NormalizedLegRole.BUY),
                flow("STON", "200.0", NormalizedLegRole.BUY)
        ));
        Map<Integer, PriceQuote> resolved = Map.of(0, quote(new BigDecimal("6.00")));

        Optional<PriceQuote> result = RESOLVER.resolve(context(tx, 2, resolved));

        assertThat(result).isPresent();
        assertThat(result.get().unitPriceUsd()).isEqualByComparingTo("0.60"); // $120 / 200, pTON ignored
    }

    @Test
    void stableToStableSwapWithDustBuyLeg_defersDustToExternalMarketPricing() {
        // Real Jupiter RFQ (4BYVQwQ...): SELL 65.377 USDC ($1) → BUY 65.330 USDT ($1) + a dust
        // BUY of 0.000010245 SOL. The SOL BUY's only opposite-role counterpart is the USDC SELL,
        // but that value is already balanced by the USDT BUY. Without the guard, swap-derived would
        // price 0.000010245 SOL at $6.4M/SOL (poisoning the SOL basis and the FEE/WRAPPER gas leg).
        // The guard defers the dust leg to the external market chain instead.
        NormalizedTransaction tx = swapTx(List.of(
                flow("USDC", "-65.376999",  NormalizedLegRole.SELL),
                flow("USDT", "65.329599",   NormalizedLegRole.BUY),
                flow("SOL",  "0.000010245", NormalizedLegRole.BUY)
        ));
        Map<Integer, PriceQuote> resolved = Map.of(
                0, quote(new BigDecimal("1.00")),  // USDC
                1, quote(new BigDecimal("1.00"))   // USDT
        );

        Optional<PriceQuote> result = RESOLVER.resolve(context(tx, 2, resolved));

        assertThat(result).isEmpty();
    }

    @Test
    void stableToStableSwapWithDustSellLeg_defersDustToExternalMarketPricing() {
        // Symmetric case: SELL 65.377 USDC + dust SELL 0.00001 SOL → BUY 65.330 USDT. The SOL SELL's
        // counterpart (USDT BUY) is already balanced by the USDC SELL, so the SOL SELL defers.
        NormalizedTransaction tx = swapTx(List.of(
                flow("USDC", "-65.376999",   NormalizedLegRole.SELL),
                flow("SOL",  "-0.000010245", NormalizedLegRole.SELL),
                flow("USDT", "65.329599",    NormalizedLegRole.BUY)
        ));
        Map<Integer, PriceQuote> resolved = Map.of(
                0, quote(new BigDecimal("1.00")),  // USDC
                2, quote(new BigDecimal("1.00"))   // USDT
        );

        Optional<PriceQuote> result = RESOLVER.resolve(context(tx, 1, resolved));

        assertThat(result).isEmpty();
    }

    @Test
    void majorAssetSoldForMemecoin_notDerivedFromMemecoinPrice() {
        // Real Meteora swap (4uPJvz1…): SELL 0.1 SOL → BUY 6932 COM (memecoin). COM's external
        // price ($0.1192) is unreliable; deriving SOL from it yields $8,263/SOL. SOL is a reliable
        // anchor (CoinGecko-listed) and its only counterpart (COM) is long-tail → the SOL SELL must
        // defer to external so it takes its own market price (~$194); COM then derives from SOL.
        NormalizedTransaction tx = swapTx(List.of(
                flow("SOL", "-0.1",       NormalizedLegRole.SELL),
                flow("COM", "6932.056674", NormalizedLegRole.BUY)
        ));
        Map<Integer, PriceQuote> resolved = Map.of(
                1, quote(new BigDecimal("0.1192"))  // COM (unreliable long-tail external)
        );

        Optional<PriceQuote> result = RESOLVER.resolve(context(tx, 0, resolved));

        assertThat(result).isEmpty();
    }

    @Test
    void memecoinBoughtWithSol_stillDerivesFromSolAnchor() {
        // The long-tail leg (COM) IS still swap-derived from the reliable SOL anchor: SELL 0.1 SOL
        // ($194) → COM = $19.4 / 6932 ≈ $0.0028. Confirms the guard is asymmetric (only blocks
        // deriving the anchor from long-tail, not the reverse).
        NormalizedTransaction tx = swapTx(List.of(
                flow("SOL", "-0.1",        NormalizedLegRole.SELL),
                flow("COM", "6932.056674", NormalizedLegRole.BUY)
        ));
        Map<Integer, PriceQuote> resolved = Map.of(
                0, quote(new BigDecimal("194.00"))  // SOL anchor
        );

        Optional<PriceQuote> result = RESOLVER.resolve(context(tx, 1, resolved));

        assertThat(result).isPresent();
        BigDecimal expected = new BigDecimal("19.40").divide(new BigDecimal("6932.056674"), java.math.MathContext.DECIMAL128);
        assertThat(result.get().unitPriceUsd()).isEqualByComparingTo(expected);
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
