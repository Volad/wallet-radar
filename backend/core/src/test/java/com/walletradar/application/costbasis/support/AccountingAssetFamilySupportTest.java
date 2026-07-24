package com.walletradar.application.costbasis.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AccountingAssetFamilySupportTest {

    @Test
    void mapsWrappedBitcoinAndAaveWrappedBitcoinIntoBtcFamily() {
        assertThat(AccountingAssetFamilySupport.continuityIdentity("BTC", null)).isEqualTo("FAMILY:BTC");
        assertThat(AccountingAssetFamilySupport.continuityIdentity("WBTC", null)).isEqualTo("FAMILY:BTC");
        assertThat(AccountingAssetFamilySupport.continuityIdentity("aArbWBTC", null)).isEqualTo("FAMILY:BTC");
    }

    @Test
    void mapsAaveWrappedAvaxAndMantleIntoCanonicalFamilies() {
        assertThat(AccountingAssetFamilySupport.continuityIdentity("AVAX", null)).isEqualTo("FAMILY:AVAX");
        assertThat(AccountingAssetFamilySupport.continuityIdentity("aAvaWAVAX", null)).isEqualTo("FAMILY:AVAX");
        assertThat(AccountingAssetFamilySupport.continuityIdentity("aAvaSAVAX", null)).isEqualTo("FAMILY:SAVAX");
        assertThat(AccountingAssetFamilySupport.continuityIdentity("sAVAX", null)).isEqualTo("FAMILY:SAVAX");
        assertThat(AccountingAssetFamilySupport.continuityIdentity("MNT", null)).isEqualTo("FAMILY:MNT");
        assertThat(AccountingAssetFamilySupport.continuityIdentity("WMNT", null)).isEqualTo("FAMILY:MNT");
    }

    @Test
    void mapsAaveReceiptSymbolsPerUnderlyingAcrossChains() {
        assertThat(AccountingAssetFamilySupport.continuityIdentity("AAVAUSDC", null)).isEqualTo("FAMILY:USDC");
        assertThat(AccountingAssetFamilySupport.continuityIdentity("ABASUSDC", null)).isEqualTo("FAMILY:USDC");
        assertThat(AccountingAssetFamilySupport.continuityIdentity("AARBARB", null)).isEqualTo("FAMILY:ARB");
    }

    @Test
    void mapsUsdeAliasesIntoDedicatedFamily() {
        assertThat(AccountingAssetFamilySupport.continuityIdentity("USDE", null)).isEqualTo("FAMILY:USDE");
        assertThat(AccountingAssetFamilySupport.continuityIdentity("USDe", null)).isEqualTo("FAMILY:USDE");
        assertThat(AccountingAssetFamilySupport.continuityIdentity(
                "USDe",
                "0x5d3a1ff2b6bab83b63cd9ad0787074081a52ef34"
        )).isEqualTo("FAMILY:USDE");
    }

    @Test
    void mapsC1EthIntoEthFamilyAndC2IntoOwnFamilies() {
        assertThat(AccountingAssetFamilySupport.continuityIdentity("aWETH", null)).isEqualTo("FAMILY:ETH");
        assertThat(AccountingAssetFamilySupport.continuityIdentity("aBasWETH", null)).isEqualTo("FAMILY:ETH");
        assertThat(AccountingAssetFamilySupport.continuityIdentity("VBETH", null)).isEqualTo("FAMILY:ETH");
        assertThat(AccountingAssetFamilySupport.continuityIdentity("cmETH", null)).isEqualTo("FAMILY:METH");
        assertThat(AccountingAssetFamilySupport.continuityIdentity("CMETH", null)).isEqualTo("FAMILY:METH");
        assertThat(AccountingAssetFamilySupport.continuityIdentity("WSTETH", null)).isEqualTo("FAMILY:WSTETH");
        assertThat(AccountingAssetFamilySupport.continuityIdentity("EWETH-1", null)).isEqualTo("FAMILY:EWETH");
        assertThat(AccountingAssetFamilySupport.continuityIdentity("EWEETH-1", null)).isEqualTo("FAMILY:EWEETH");
    }

    @Test
    void mapsExtendedStablecoinFamilyAliasesForLendingReceipts() {
        assertThat(AccountingAssetFamilySupport.continuityIdentity("aZksUSDC", null)).isEqualTo("FAMILY:USDC");
        assertThat(AccountingAssetFamilySupport.continuityIdentity("aOptUSDC", null)).isEqualTo("FAMILY:USDC");
        assertThat(AccountingAssetFamilySupport.continuityIdentity("fUSDC", null)).isEqualTo("FAMILY:USDC");
        assertThat(AccountingAssetFamilySupport.continuityIdentity("fUSDT", null)).isEqualTo("FAMILY:USDT");
        assertThat(AccountingAssetFamilySupport.continuityIdentity("vbUSDT", null)).isEqualTo("FAMILY:USDT");
    }

    @Test
    void mapsExtendedMantleFamilyAlias() {
        assertThat(AccountingAssetFamilySupport.continuityIdentity("aManWMNT", null)).isEqualTo("FAMILY:MNT");
    }

    @Test
    void mapsLpReceiptSymbolsToDedicatedFamily() {
        assertThat(AccountingAssetFamilySupport.continuityIdentity(
                "LP-RECEIPT:arbitrum:pancakeswap:196975",
                null
        )).isEqualTo("FAMILY:LP_RECEIPT");
        assertThat(AccountingAssetFamilySupport.isLpReceiptSymbol("LP-RECEIPT:base:pancakeswap:477096")).isTrue();
        assertThat(AccountingAssetFamilySupport.isLpReceiptSymbol("ETH")).isFalse();
    }

    @Test
    void pendleLpTokensAndStakedWrappersResolveToLpReceiptFamilyByGrammarNotSpelling() {
        // ADR-081 (C7): Pendle LP tokens follow the deterministic `-LPT` naming convention. The bare
        // PENDLE-LPT, the eqb/pnp staked wrappers, and per-market `<market>-LPT` all resolve to the
        // LP-receipt continuity family — a protocol convention, not a curated per-token bucket.
        assertThat(AccountingAssetFamilySupport.isLpReceiptSymbol("PENDLE-LPT")).isTrue();
        assertThat(AccountingAssetFamilySupport.isLpReceiptSymbol("eqbPENDLE-LPT")).isTrue();
        assertThat(AccountingAssetFamilySupport.isLpReceiptSymbol("pnpPENDLE-LPT")).isTrue();
        assertThat(AccountingAssetFamilySupport.continuityIdentity("PENDLE-LPT", null))
                .isEqualTo("FAMILY:LP_RECEIPT");
        assertThat(AccountingAssetFamilySupport.continuityIdentity("eqbPENDLE-LPT", null))
                .isEqualTo("FAMILY:LP_RECEIPT");
    }

    @Test
    void plainPendleGovernanceTokenStaysPricedSpotAsset() {
        // Guard: the plain PENDLE reward/governance token does NOT end in `-LPT` and must remain a
        // priced spot asset (never swept into the LP-receipt family).
        assertThat(AccountingAssetFamilySupport.isLpReceiptSymbol("PENDLE")).isFalse();
        assertThat(AccountingAssetFamilySupport.continuityIdentity("PENDLE", null))
                .isNotEqualTo("FAMILY:LP_RECEIPT");
    }

    @Test
    void lpReceiptHoldingIsRecognizedByFamilyIdentityForNovelReceiptSymbols() {
        // ADR-080/ADR-081 (C7): a receipt routed to FAMILY:LP_RECEIPT via its LP correlationId is
        // excluded by IDENTITY even when the symbol itself carries no recognizable suffix (e.g. MLP).
        assertThat(AccountingAssetFamilySupport.isLpReceiptFamilyIdentity("FAMILY:LP_RECEIPT")).isTrue();
        assertThat(AccountingAssetFamilySupport.isLpReceiptFamilyIdentity("FAMILY:ETH")).isFalse();
        assertThat(AccountingAssetFamilySupport.isLpReceiptHolding("FAMILY:LP_RECEIPT", "MLP", null)).isTrue();
        // Novel receipt symbol with no persisted family but recognizable grammar.
        assertThat(AccountingAssetFamilySupport.isLpReceiptHolding("FAMILY:ETH", "PENDLE-LPT", null)).isTrue();
        // Genuine spot holding is never excluded.
        assertThat(AccountingAssetFamilySupport.isLpReceiptHolding("FAMILY:SOL", "SSE", null)).isFalse();
        assertThat(AccountingAssetFamilySupport.isLpReceiptHolding(null, "ETH", null)).isFalse();
    }

    @Test
    void aaveZkSyncZkReceiptFoldsIntoZkFamilyInsteadOfStrandingOnRawContract() {
        // Gap 3: aZksZK (Aave-zkSync aToken) is a 1:1 redeemable receipt of native ZK and folds into
        // FAMILY:ZK (mirrors ARB/AARBARB), rather than being stranded on its raw aToken contract.
        assertThat(AccountingAssetFamilySupport.continuityIdentity("ZK", null)).isEqualTo("FAMILY:ZK");
        assertThat(AccountingAssetFamilySupport.continuityIdentity("aZksZK", null)).isEqualTo("FAMILY:ZK");
        assertThat(AccountingAssetFamilySupport.continuityIdentity(
                "aZksZK", "0xd6cd2c0fc55936498726cacc497832052a9b2d1b")).isEqualTo("FAMILY:ZK");
    }

    @Test
    void pendleLpReceiptIsFullyIsolatedFromEveryPricedSpotSurface() {
        // Gap 2 (accepted outcome — hidden+isolated): a residual PENDLE-LPT sitting in
        // FAMILY:LP_RECEIPT is excluded from (1) the dashboard spot surface, (2) the spot/ETH family
        // AVCO/covered-qty aggregation, and (3) therefore the portfolio total — with no double-count
        // against lp_receipt_basis_pools (there is no FAMILY:LP_RECEIPT priced page).
        assertThat(AccountingAssetFamilySupport.isLpReceiptHolding("FAMILY:LP_RECEIPT", "PENDLE-LPT", "0xmarket"))
                .isTrue();
        // Excluded from the ETH family page by symbol grammar even if a caller passed FAMILY:ETH.
        assertThat(AccountingAssetFamilySupport.includeInSpotFamilyTimelineAggregation(
                "FAMILY:ETH", "PENDLE-LPT", "FAMILY:LP_RECEIPT")).isFalse();
        // Excluded from its own persisted LP-receipt family page too (no priced LP-receipt surface).
        assertThat(AccountingAssetFamilySupport.includeInSpotFamilyTimelineAggregation(
                "FAMILY:LP_RECEIPT", "PENDLE-LPT", "FAMILY:LP_RECEIPT")).isFalse();
    }

    @Test
    void cmethKeepsMethLpCostBasisFamilyAndIsNeverAnLpReceipt() {
        // Gap 2 guard (ADR-047 no +$2,228 spike): cmETH is a separate C1 family (FAMILY:METH), a
        // priced spot holding — it must NOT be folded into FAMILY:LP_RECEIPT nor excluded as a receipt.
        assertThat(AccountingAssetFamilySupport.continuityIdentity("cmETH", null)).isEqualTo("FAMILY:METH");
        assertThat(AccountingAssetFamilySupport.isLpReceiptHolding("FAMILY:METH", "cmETH", null)).isFalse();
        assertThat(AccountingAssetFamilySupport.includeInSpotFamilyTimelineAggregation(
                "FAMILY:METH", "CMETH", "FAMILY:METH")).isTrue();
    }

    @Test
    void confusableLookalikeSymbolsNeverCollapseIntoCanonicalFamily() {
        String cyrillicUsdc = "U\u0405D\u0421";
        assertThat(AccountingAssetFamilySupport.continuityIdentity(cyrillicUsdc, null))
                .isNotEqualTo("FAMILY:USDC");
        assertThat(AccountingAssetFamilySupport.continuityIdentity(cyrillicUsdc, null))
                .isEqualTo("SYMBOL:" + cyrillicUsdc.toUpperCase());
        assertThat(AccountingAssetFamilySupport.continuityIdentity(cyrillicUsdc, "0xDEADBEEF"))
                .isEqualTo("0xdeadbeef");
        assertThat(AccountingAssetFamilySupport.continuityIdentity("USD₮0", null)).isEqualTo("FAMILY:USDT");
    }

    @Test
    void ethFamilyTimelineIncludesOnlyC1Members() {
        assertThat(AccountingAssetFamilySupport.includeInSpotFamilyTimelineAggregation("FAMILY:ETH", "ETH")).isTrue();
        assertThat(AccountingAssetFamilySupport.includeInSpotFamilyTimelineAggregation("FAMILY:ETH", "WETH")).isTrue();
        assertThat(AccountingAssetFamilySupport.includeInSpotFamilyTimelineAggregation("FAMILY:ETH", "AMANWETH")).isTrue();
        assertThat(AccountingAssetFamilySupport.includeInSpotFamilyTimelineAggregation("FAMILY:ETH", "CMETH")).isFalse();
        assertThat(AccountingAssetFamilySupport.includeInSpotFamilyTimelineAggregation("FAMILY:ETH", "WSTETH")).isFalse();
        assertThat(AccountingAssetFamilySupport.includeInSpotFamilyTimelineAggregation("FAMILY:METH", "CMETH")).isTrue();
        assertThat(AccountingAssetFamilySupport.includeInSpotFamilyTimelineAggregation("FAMILY:BTC", "WBTC")).isTrue();
    }

    @Test
    void nativeTonResolvesToCanonicalTonFamilyAndUnifiesWithCexTon() {
        // Native on-chain TON arrives with the raw contract; Bybit TON arrives symbol-only. Both must
        // resolve to FAMILY:TON so the on-chain and CEX pools merge instead of fragmenting into
        // `toncoin` vs `SYMBOL:TON` (the defect that emptied the TON move-basis chart).
        assertThat(AccountingAssetFamilySupport.continuityIdentity("TON", null)).isEqualTo("FAMILY:TON");
        assertThat(AccountingAssetFamilySupport.continuityIdentity("TON", "TONCOIN")).isEqualTo("FAMILY:TON");
    }

    @Test
    void persistedFamilyIsAuthoritativeSoRawContractFamiliesAreNotDropped() {
        // Legacy symbol-only recompute would drop these: a raw-contract family never equals the
        // symbol-derived family. Passing the persisted accountingFamilyIdentity keeps them in the chart.
        assertThat(AccountingAssetFamilySupport.includeInSpotFamilyTimelineAggregation(
                "toncoin", "TON", "toncoin")).isTrue();
        assertThat(AccountingAssetFamilySupport.includeInSpotFamilyTimelineAggregation(
                "2ffbqcrqdtj6idsvj3afdqtqnqao9qqocvpauaruvf5t",
                "SNAI",
                "2ffbqcrqdtj6idsvj3afdqtqnqao9qqocvpauaruvf5t")).isTrue();
        // A point whose persisted family differs from the requested page is still excluded.
        assertThat(AccountingAssetFamilySupport.includeInSpotFamilyTimelineAggregation(
                "FAMILY:TON", "SNAI", "someothermint")).isFalse();
        // LP receipts remain excluded regardless of persisted family.
        assertThat(AccountingAssetFamilySupport.includeInSpotFamilyTimelineAggregation(
                "FAMILY:LP_RECEIPT", "SOL-LP-USDC", "FAMILY:LP_RECEIPT")).isFalse();
    }
}
