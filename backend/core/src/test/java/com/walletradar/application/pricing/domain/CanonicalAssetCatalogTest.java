package com.walletradar.application.pricing.domain;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.common.NetworkStablecoinContracts;
import com.walletradar.testsupport.NetworkTestFixtures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalAssetCatalogTest {

    @BeforeAll
    static void bindNetworkStablecoinContracts() {
        NetworkStablecoinContracts.bind(
                networkId -> NetworkTestFixtures.registry().usdStableContracts(networkId)
        );
    }

    @Test
    void auditedAaveWethReceiptAliasesCollapseToEth() {
        assertThat(CanonicalAssetCatalog.canonicalMarketSymbol("aEthWETH")).isEqualTo("ETH");
        assertThat(CanonicalAssetCatalog.canonicalMarketSymbol("aArbWETH")).isEqualTo("ETH");
        assertThat(CanonicalAssetCatalog.canonicalMarketSymbol("aLinWETH")).isEqualTo("ETH");
        assertThat(CanonicalAssetCatalog.canonicalMarketSymbol("aManWETH")).isEqualTo("ETH");
        assertThat(CanonicalAssetCatalog.canonicalMarketSymbol("aZksWETH")).isEqualTo("ETH");
    }

    @Test
    void auditedAaveAvaxReceiptAliasesCollapseToAvax() {
        assertThat(CanonicalAssetCatalog.canonicalMarketSymbol("sAVAX")).isEqualTo("AVAX");
        assertThat(CanonicalAssetCatalog.canonicalMarketSymbol("aAvaWAVAX")).isEqualTo("AVAX");
        assertThat(CanonicalAssetCatalog.canonicalMarketSymbol("aAvaSAVAX")).isEqualTo("AVAX");
    }

    @Test
    void katanaBridgedEthPricesAsEthButC2DerivativeResolvesItself() {
        // ADR-054 §6 / plan §7c: VBETH is C1 (ETH bridged to Katana) → price as ETH. yvvbETH is a
        // C2 ETH-derivative that must resolve its OWN market USD price (DefiLlama-by-contract), so it
        // is no longer aliased to ETH.
        assertThat(CanonicalAssetCatalog.canonicalMarketSymbol("vbETH")).isEqualTo("ETH");
        assertThat(CanonicalAssetCatalog.canonicalMarketSymbol("yvvbETH")).isEqualTo("YVVBETH");
    }

    @Test
    void c2EthDerivativesResolveOwnMarketSymbolNotEth() {
        // ADR-054 §6 / plan §7c: CMETH/METH/WEETH/YVVBETH resolve their own market USD price and must
        // NOT be aliased to ETH. BBSOL stays canonicalised to SOL.
        assertThat(CanonicalAssetCatalog.canonicalMarketSymbol("CMETH")).isEqualTo("CMETH");
        assertThat(CanonicalAssetCatalog.canonicalMarketSymbol("METH")).isEqualTo("METH");
        assertThat(CanonicalAssetCatalog.canonicalMarketSymbol("WEETH")).isEqualTo("WEETH");
        assertThat(CanonicalAssetCatalog.canonicalMarketSymbol("YVVBETH")).isEqualTo("YVVBETH");
        assertThat(CanonicalAssetCatalog.canonicalMarketSymbol("VBETH")).isEqualTo("ETH");
        assertThat(CanonicalAssetCatalog.canonicalMarketSymbol("BBSOL")).isEqualTo("SOL");
    }

    @Test
    void c2EthDerivativesAreNoLongerPeggedNative() {
        // ADR-054 §6 / plan §7c: removing these from PEGGED_NATIVE_SYMBOLS stops
        // PeggedNativePriceResolver from inheriting the ETH sibling quote.
        assertThat(CanonicalAssetCatalog.isPeggedNative("CMETH")).isFalse();
        assertThat(CanonicalAssetCatalog.isPeggedNative("METH")).isFalse();
        assertThat(CanonicalAssetCatalog.isPeggedNative("WEETH")).isFalse();
        // BBSOL remains a pegged-native receipt.
        assertThat(CanonicalAssetCatalog.isPeggedNative("BBSOL")).isTrue();
    }

    @Test
    void wstEthLastResortCoinGeckoIdIsWrappedStethNotEth() {
        assertThat(CanonicalAssetCatalog.coinGeckoId("wstETH")).contains("wrapped-steth");
    }

    @Test
    void c2EthDerivativeLastResortCoinGeckoIdsResolveTheTokenItself() {
        assertThat(CanonicalAssetCatalog.coinGeckoId("CMETH")).contains("mantle-restaked-eth");
        assertThat(CanonicalAssetCatalog.coinGeckoId("METH")).contains("mantle-staked-ether");
        assertThat(CanonicalAssetCatalog.coinGeckoId("WEETH")).contains("wrapped-eeth");
        // No confirmed CoinGecko id for YVVBETH — must stay empty (DefiLlama-by-contract resolves it).
        assertThat(CanonicalAssetCatalog.coinGeckoId("YVVBETH")).isEmpty();
    }

    @Test
    void wstEthNoLongerFallsBackToStEthOrEthMarkets() {
        // ADR-054 §6 / plan §7c: wstETH resolves via its own DefiLlama contract price; the STETH/ETH
        // exchange fallback is removed so the market quote is not distorted toward the ETH peg.
        assertThat(CanonicalAssetCatalog.exchangeMarketSymbols("wstETH"))
                .containsExactly("WSTETH")
                .doesNotContain("STETH", "ETH");
    }

    @Test
    void wstUsrUsesResolvCoinGeckoMarketForHistoricalTotalValuation() {
        assertThat(CanonicalAssetCatalog.coinGeckoId("wstUSR")).contains("resolv-wstusr");
        assertThat(CanonicalAssetCatalog.coinGeckoId("USR")).contains("resolv-usr");
    }

    @Test
    void pricingSkippedLowCapSymbolsAreMarkedSkippedAndHaveNoCoinGeckoId() {
        // Cycle/9 S5: known low-cap / delisted symbols with no resolvable historical USD source.
        for (String symbol : new String[] {"PAWS", "AURA", "EUL", "AGLD", "WLKN", "CUDIS", "TON"}) {
            assertThat(CanonicalAssetCatalog.isPricingSkipped(symbol))
                    .as("expected %s to be pricing-skipped", symbol)
                    .isTrue();
            assertThat(CanonicalAssetCatalog.coinGeckoId(symbol))
                    .as("expected no CoinGecko id for %s", symbol)
                    .isEmpty();
        }
    }

    @Test
    void pricedSymbolsAreNotMarkedPricingSkipped() {
        assertThat(CanonicalAssetCatalog.isPricingSkipped("ETH")).isFalse();
        assertThat(CanonicalAssetCatalog.isPricingSkipped("BTC")).isFalse();
        assertThat(CanonicalAssetCatalog.isPricingSkipped("USDC")).isFalse();
        assertThat(CanonicalAssetCatalog.isPricingSkipped(null)).isFalse();
        assertThat(CanonicalAssetCatalog.isPricingSkipped("")).isFalse();
    }

    @Test
    void stablecoinATokenAliasesResolveOneDollarParity() {
        // F-1 / AC-5: stablecoin aTokens must reach $1, not $2. Curated whitelist aliases only.
        assertThat(CanonicalAssetCatalog.isUsdStablecoin(NetworkId.MANTLE, "0xreceipt", "AMANUSDC", null)).isTrue();
        assertThat(CanonicalAssetCatalog.isUsdStablecoin(NetworkId.ARBITRUM, "0xreceipt", "aArbUSDT", null)).isTrue();
        assertThat(CanonicalAssetCatalog.isUsdStablecoin(NetworkId.BASE, "0xreceipt", "aBasUSDC", null)).isTrue();
        // Bare USDE symbol with no contract still parity.
        assertThat(CanonicalAssetCatalog.isUsdStablecoin(NetworkId.ETHEREUM, null, "USDE", null)).isTrue();
    }

    @Test
    void scamStablecoinSymbolWithUnknownContractIsNotPricedAsOneDollar() {
        // Security: a token whose symbol is exactly "USDC" but whose contract is unknown must NOT
        // resolve $1 parity (contract-first deny preserved).
        assertThat(CanonicalAssetCatalog.isUsdStablecoin(NetworkId.ETHEREUM, "0xdeadbeef", "USDC", null)).isFalse();
    }

    @Test
    void legitimateUsdtZeroIsNotFlaggedAsConfusableAndStaysCanonical() {
        // ₮ is U+20AE (TUGRIK SIGN) — the real Tether USDT0 glyph; must remain canonical USDT.
        assertThat(CanonicalAssetCatalog.isConfusableSymbol("USD₮0")).isFalse();
        assertThat(CanonicalAssetCatalog.canonicalMarketSymbol("USD₮0")).isEqualTo("USDT");
        assertThat(CanonicalAssetCatalog.isUsdStablecoinBySymbol("USD₮0")).isTrue();
    }

    @Test
    void asciiStablecoinsAreNotConfusable() {
        for (String symbol : new String[] {"USDC", "USDT", "USDE", "ETH", "USDT0"}) {
            assertThat(CanonicalAssetCatalog.isConfusableSymbol(symbol))
                    .as("expected %s to be plain ASCII (not confusable)", symbol)
                    .isFalse();
        }
    }

    @Test
    void homoglyphScamSymbolsAreFlaggedAndNeverAliasedOrPriced() {
        // Cyrillic U+0405 (Ѕ) + U+0421 (С) spoofing USDC.
        String cyrillicUsdc = "U\u0405D\u0421";
        // Lisu spoof of USDC.
        String lisuUsdc = "\uA4F4\uA4E2\uA4D3\u0421";
        // Cyrillic spoof of USDT.
        String cyrillicUsdt = "U\u0405DT";
        // Zero-width-injected USDT (U+200B between letters).
        String zeroWidthUsdt = "U\u200BSDT";
        for (String scam : new String[] {cyrillicUsdc, lisuUsdc, cyrillicUsdt, zeroWidthUsdt}) {
            assertThat(CanonicalAssetCatalog.isConfusableSymbol(scam))
                    .as("expected %s to be flagged confusable", scam)
                    .isTrue();
            assertThat(CanonicalAssetCatalog.isUsdStablecoinBySymbol(scam))
                    .as("expected %s NOT to be priced as a USD stablecoin", scam)
                    .isFalse();
            assertThat(CanonicalAssetCatalog.isUsdStablecoin(null, null, scam, null))
                    .as("expected %s NOT to resolve $1 parity", scam)
                    .isFalse();
            // Must not be aliased onto a canonical ticker (stays its own raw symbol).
            assertThat(CanonicalAssetCatalog.canonicalMarketSymbol(scam))
                    .as("expected %s NOT to alias to a canonical ticker", scam)
                    .isEqualTo(CanonicalAssetCatalog.normalizeSymbol(scam));
            // F-5(a): a spoofed lookalike must never inherit a canonical cross-network price.
            assertThat(CanonicalAssetCatalog.isCrossNetworkPriceResolvable(scam))
                    .as("expected %s NOT to be cross-network price resolvable", scam)
                    .isFalse();
            assertThat(CanonicalAssetCatalog.marketEquivalentSymbols(scam))
                    .as("expected %s to yield no cross-network price candidates", scam)
                    .isEmpty();
        }
    }

    @Test
    void crossNetworkPriceResolvableForFungibleCanonicalMajors() {
        for (String symbol : new String[] {"ETH", "WETH", "MNT", "WMNT", "BTC", "SOL", "XRP", "LINK", "ONDO", "USDC"}) {
            assertThat(CanonicalAssetCatalog.isCrossNetworkPriceResolvable(symbol))
                    .as("expected %s to be cross-network price resolvable", symbol)
                    .isTrue();
        }
    }

    @Test
    void crossNetworkPriceNotResolvableForUnknownLowCapSymbol() {
        // The gate (used by ReplayMarketAuthority before any candidate lookup) rejects unknown
        // low-cap symbols so they can never inherit an unrelated asset's cross-network price.
        assertThat(CanonicalAssetCatalog.isCrossNetworkPriceResolvable("ZZZUNKNOWN")).isFalse();
    }

    @Test
    void marketEquivalentSymbolsOrderLegThenCanonicalThenWrappedNative() {
        assertThat(CanonicalAssetCatalog.marketEquivalentSymbols("WETH"))
                .containsExactly("WETH", "ETH");
        assertThat(CanonicalAssetCatalog.marketEquivalentSymbols("ETH"))
                .containsExactly("ETH", "WETH");
        assertThat(CanonicalAssetCatalog.marketEquivalentSymbols("MNT"))
                .containsExactly("MNT", "WMNT");
    }

    @Test
    void xrpResolvesCanonicalPricingIdentity() {
        assertThat(CanonicalAssetCatalog.coinGeckoId("XRP")).contains("ripple");
    }
}
