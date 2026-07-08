package com.walletradar.application.pricing.domain;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.common.NetworkStablecoinContracts;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;

import java.util.Locale;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Canonical asset and alias policy used by pricing without depending on ingestion internals.
 */
public final class CanonicalAssetCatalog {

    private static final Set<String> USD_STABLE_SYMBOLS = Set.of(
            "USDC",
            "USDT",
            "USD₮0",
            "USDT0",
            "USDB",
            "USDBC",
            "USDE",
            "GHO",
            "AUSD"
    );

    private static final Set<String> EUR_STABLE_SYMBOLS = Set.of(
            "EURC",
            "EUROC"
    );

    private static final Map<String, String> SYMBOL_ALIASES = Map.ofEntries(
            Map.entry("WETH", "ETH"),
            // Aave aToken ETH family — all deployed networks. Receipt tokens trade 1:1 with WETH/ETH.
            // Naming convention: a{NetworkAbbrev}WETH on Aave V3, or aWETH on V2 / networks without prefix.
            Map.entry("AWETH", "ETH"),       // Base (aWETH), Ethereum V2 (aWETH)
            Map.entry("AETHWETH", "ETH"),    // Ethereum V3
            Map.entry("AARBWETH", "ETH"),    // Arbitrum
            Map.entry("AOPTWETH", "ETH"),    // Optimism
            Map.entry("APOLWETH", "ETH"),    // Polygon
            Map.entry("ABASWETH", "ETH"),    // Base V3 (if deployed with network prefix)
            Map.entry("ALINWETH", "ETH"),    // Linea
            Map.entry("AMANWETH", "ETH"),    // Mantle
            Map.entry("AZKSWETH", "ETH"),    // zkSync
            Map.entry("ASCRWETH", "ETH"),    // Scroll
            Map.entry("VBETH", "ETH"),
            Map.entry("YVVBETH", "ETH"),
            // Aave aToken BTC family — WBTC receipt tokens trade 1:1 with WBTC (= BTC for pricing).
            Map.entry("AWBTC", "BTC"),       // Ethereum V2 (aWBTC)
            Map.entry("AETHWBTC", "BTC"),    // Ethereum V3
            Map.entry("AARBWBTC", "BTC"),    // Arbitrum (verified in DB: aArbWBTC)
            Map.entry("AOPTWBTC", "BTC"),    // Optimism
            Map.entry("APOLWBTC", "BTC"),    // Polygon
            Map.entry("AAVAWBTC", "BTC"),    // Avalanche
            Map.entry("ABASWBTC", "BTC"),    // Base
            Map.entry("WBTC", "BTC"),
            Map.entry("WBNB", "BNB"),
            Map.entry("WAVAX", "AVAX"),
            Map.entry("SAVAX", "AVAX"),
            Map.entry("AAVAWAVAX", "AVAX"),
            Map.entry("AAVASAVAX", "AVAX"),
            Map.entry("WMNT", "MNT"),
            Map.entry("WXPL", "XPL"),
            Map.entry("WXPL9", "XPL"),
            Map.entry("USDBC", "USDC"),
            Map.entry("USD₮0", "USDT"),
            Map.entry("USDT0", "USDT"),
            Map.entry("POL", "MATIC"),
            Map.entry("CMETH", "ETH"),
            Map.entry("METH", "ETH"),
            Map.entry("WEETH", "ETH"),
            Map.entry("BBSOL", "SOL"),
            // Aave aToken USD-stable family
            Map.entry("AAVAUSDC", "USDC"),
            Map.entry("AMANUSDC", "USDC"),
            Map.entry("AARBUSDC", "USDC"),
            Map.entry("AETHUSDC", "USDC"),
            Map.entry("AOPTUSDC", "USDC"),   // Optimism
            Map.entry("APOLUSDC", "USDC"),   // Polygon
            Map.entry("ABASUSDC", "USDC"),   // Base
            Map.entry("ALINUSDC", "USDC"),   // Linea
            Map.entry("AETHUSDT", "USDT"),
            Map.entry("AARBUSDT", "USDT"),   // Arbitrum
            Map.entry("AOPTUSDT", "USDT"),   // Optimism
            Map.entry("APOLUSDT", "USDT"),   // Polygon
            Map.entry("AAVAUSDT", "USDT"),   // Avalanche (verified in DB: aAvaUSDT-like)
            // Aave aToken other assets
            Map.entry("AMANWMNT", "MNT"),
            Map.entry("AARBARB", "ARB"),
            Map.entry("AZKSZK", "ZK"),
            Map.entry("AETHGHO", "GHO"),
            Map.entry("AAVAGHO", "GHO"),     // Avalanche (verified in DB: aAvaGHO)
            Map.entry("AAAVEURC", "EURC"),   // Avalanche (aAvaEURC)
            Map.entry("VBUSDC", "USDC"),
            // Sonne Finance (Compound fork) receipt tokens — trade 1:1 with underlying
            Map.entry("SOUSDC", "USDC"),
            Map.entry("SOETH", "ETH"),
            Map.entry("SOWBTC", "BTC")
    );

    private static final Map<String, String> COINGECKO_IDS = Map.ofEntries(
            Map.entry("ETH", "ethereum"),
            Map.entry("SOL", "solana"),
            Map.entry("BTC", "bitcoin"),
            Map.entry("BNB", "binancecoin"),
            Map.entry("AVAX", "avalanche-2"),
            Map.entry("MATIC", "matic-network"),
            Map.entry("ARB", "arbitrum"),
            Map.entry("OP", "optimism"),
            Map.entry("MNT", "mantle"),
            Map.entry("USDC", "usd-coin"),
            Map.entry("USDT", "tether"),
            Map.entry("DAI", "dai"),
            Map.entry("GHO", "gho-token"),
            Map.entry("USDE", "ethena-usde"),
            Map.entry("EURC", "euro-coin"),
            Map.entry("USR", "resolv-usr"),
            Map.entry("WSTUSR", "resolv-wstusr"),
            Map.entry("CAKE", "pancakeswap-token"),
            Map.entry("WSTETH", "staked-ether"),
            Map.entry("STETH", "staked-ether"),
            Map.entry("CBETH", "coinbase-wrapped-staked-eth"),
            Map.entry("PENDLE", "pendle"),
            Map.entry("BAL", "balancer"),
            Map.entry("UNI", "uniswap"),
            Map.entry("COMP", "compound-governance-token"),
            Map.entry("MORPHO", "morpho"),
            Map.entry("ZK", "zksync"),
            // Cycle/8 S1: Add CoinGecko ids for previously unmapped Bybit-traded and on-chain
            // assets so REWARD_CLAIM and reclassified EXTERNAL_TRANSFER_IN BUY flows can resolve a
            // historical USD price (otherwise basis defaults to $0 and AVCO coverage drifts).
            Map.entry("DOGE", "dogecoin"),
            Map.entry("LTC", "litecoin"),
            Map.entry("XRP", "ripple"),
            Map.entry("LINK", "chainlink"),
            Map.entry("LDO", "lido-dao"),
            Map.entry("ONDO", "ondo-finance"),
            Map.entry("ZORA", "zora"),
            Map.entry("XPL", "plasma"),
            Map.entry("LRT2", "ether-fi-staked-eth"),
            Map.entry("AAVE", "aave"),
            Map.entry("CRV", "curve-dao-token"),
            Map.entry("MKR", "maker"),
            Map.entry("SNX", "havven")
    );

    private static final Map<String, List<String>> EXCHANGE_MARKET_FALLBACKS = Map.ofEntries(
            Map.entry("WSTETH", List.of("STETH", "ETH")),
            Map.entry("STETH", List.of("ETH"))
    );

    /**
     * Cycle/9 S5: Low-cap / delisted symbols that have neither a CoinGecko id nor a working
     * exchange spot pair on Bybit/Binance. Pricing should not block on them, and they are
     * excluded from the conservation coverage gate denominator.
     *
     * <p>This list reflects the audit at the close of Cycle 8 / Cycle 9 reset. Symbols can be
     * removed once a price source becomes available.</p>
     */
    private static final Set<String> PRICING_SKIPPED_SYMBOLS = Set.of(
            "PAWS",
            "AURA",
            "EUL",
            "AGLD",
            "WLKN",
            "CUDIS",
            "TON"
    );

    /**
     * Cycle/15 R5 F3: 1:1 pegged liquid-staking / restaking receipts that have a deterministic
     * canonical underlying. These deserve market-spot fallback even when arriving on a TRANSFER
     * leg whose continuity carry failed (e.g. Bybit corridor with empty source sub-account).
     *
     * <p>Whitelist is intentionally narrow: only symbols whose alias to a marketable canonical
     * is unambiguous and where the protocol guarantees ~1:1 economic equivalence (custodial
     * Bybit-issued CMETH, Mantle native staked ETH METH, EtherFi weETH, Bybit liquid SOL BBSOL).
     * Aave / Morpho aTokens are NOT included because their basis carry is handled by the
     * family-equivalent custody path.</p>
     */
    private static final Set<String> PEGGED_NATIVE_SYMBOLS = Set.of(
            "CMETH",
            "METH",
            "WEETH",
            "BBSOL"
    );

    /**
     * Non-ASCII code points that are legitimately part of a canonical symbol and therefore must
     * NOT trigger the confusable-symbol guard. {@code ₮} (U+20AE, TUGRIK SIGN) is the real glyph
     * used by Tether's {@code USD₮0} (USDT0) ticker.
     */
    private static final Set<Integer> ALLOWED_NON_ASCII_CODE_POINTS = Set.of(0x20AE);

    private CanonicalAssetCatalog() {
    }

    /**
     * Returns true for contract-backed stablecoins and for symbol-only USD stablecoins
     * that are already trusted by the accounting family contract.
     */
    public static boolean isUsdStablecoin(
            NetworkId networkId,
            String assetContract,
            String assetSymbol,
            NormalizedTransactionSource source
    ) {
        // F-1: curated stablecoin aToken / receipt aliases (e.g. AMANUSDC -> USDC) resolve $1
        // parity even though their own contract is the receipt contract. This uses the SYMBOL_ALIASES
        // whitelist, so a scam token whose symbol merely looks like "USDC" but whose contract is
        // unknown is still denied (handled by the contract-first branch below).
        if (assetSymbol != null && !isConfusableSymbol(assetSymbol)) {
            String canonicalAlias = SYMBOL_ALIASES.get(normalizeSymbol(assetSymbol));
            if (canonicalAlias != null && USD_STABLE_SYMBOLS.contains(canonicalAlias)) {
                return true;
            }
        }
        String normalizedContract = normalizeContract(assetContract);
        if (normalizedContract != null) {
            return NetworkStablecoinContracts.forNetwork(networkId).contains(normalizedContract);
        }
        if (assetSymbol == null || isConfusableSymbol(assetSymbol)) {
            return false;
        }
        return USD_STABLE_SYMBOLS.contains(normalizeSymbol(assetSymbol));
    }

    public static String canonicalMarketSymbol(String assetSymbol) {
        String normalized = normalizeSymbol(assetSymbol);
        // A confusable lookalike (e.g. Cyrillic "UЅDС") must never be aliased onto a canonical
        // ticker — treat it as a distinct, unpriced asset so a spoofed token cannot inherit the
        // price, CoinGecko id, or family identity of the asset it impersonates.
        if (isConfusableSymbol(normalized)) {
            return normalized;
        }
        return SYMBOL_ALIASES.getOrDefault(normalized, normalized);
    }

    public static Optional<String> coinGeckoId(String assetSymbol) {
        return Optional.ofNullable(COINGECKO_IDS.get(canonicalMarketSymbol(assetSymbol)));
    }

    public static List<String> exchangeMarketSymbols(String assetSymbol) {
        String canonical = canonicalMarketSymbol(assetSymbol);
        if (canonical.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        candidates.add(canonical);
        candidates.addAll(EXCHANGE_MARKET_FALLBACKS.getOrDefault(canonical, List.of()));
        return List.copyOf(candidates);
    }

    public static boolean isEuroStablecoin(String assetSymbol) {
        return EUR_STABLE_SYMBOLS.contains(canonicalMarketSymbol(assetSymbol));
    }

    /**
     * Cycle/9 S5: returns {@code true} for symbols that have no resolvable historical USD
     * price source. Callers use this to short-circuit pricing pipelines and to exclude the
     * symbol from coverage gates so that the absence of a price does not destroy AVCO
     * accuracy for the rest of the portfolio.
     */
    public static boolean isPricingSkipped(String assetSymbol) {
        return PRICING_SKIPPED_SYMBOLS.contains(canonicalMarketSymbol(assetSymbol));
    }

    /**
     * Returns {@code true} when the symbol (or its canonical alias) is a USD-pegged stablecoin.
     * Used by the replay engine as a $1 pricing fallback when no market quote is available.
     */
    public static boolean isUsdStablecoinBySymbol(String assetSymbol) {
        if (assetSymbol == null) {
            return false;
        }
        return USD_STABLE_SYMBOLS.contains(canonicalMarketSymbol(assetSymbol));
    }

    /**
     * Cycle/15 R5 F3: returns {@code true} for 1:1 pegged liquid-staking / restaking
     * receipts whose basis can safely be reconstructed from the canonical underlying's spot
     * when continuity carry fails.
     */
    public static boolean isPeggedNative(String assetSymbol) {
        if (assetSymbol == null || isConfusableSymbol(assetSymbol)) {
            return false;
        }
        return PEGGED_NATIVE_SYMBOLS.contains(normalizeSymbol(assetSymbol));
    }

    /**
     * Confusable-symbol guard (F-6, business-analyst invariant).
     *
     * <p>Returns {@code true} when a ticker contains a non-ASCII character that is not on the
     * legitimate allow-list ({@code ₮} for real {@code USD₮0}). Spoofed lookalikes such as Cyrillic
     * {@code UЅDС}, Lisu {@code ꓴꓢꓓС}, Cyrillic {@code UЅDT}, or zero-width-injected variants must
     * never be aliased to, priced as, or share family identity with the canonical USD/ETH asset
     * they impersonate. The guard is symbol-shape based (no hard-coded scam ticker list) so a new
     * spoofed asset class cannot silently re-breach conservation.</p>
     */
    public static boolean isConfusableSymbol(String assetSymbol) {
        if (assetSymbol == null) {
            return false;
        }
        String trimmed = assetSymbol.trim();
        for (int index = 0; index < trimmed.length(); ) {
            int codePoint = trimmed.codePointAt(index);
            if (codePoint > 0x7F && !ALLOWED_NON_ASCII_CODE_POINTS.contains(codePoint)) {
                return true;
            }
            index += Character.charCount(codePoint);
        }
        return false;
    }

    public static String normalizeContract(String contract) {
        if (contract == null || contract.isBlank()) {
            return null;
        }
        return contract.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Returns {@code true} when the contract is a registry-known canonical asset on the network
     * (currently the curated USD-stablecoin contract set). Used by the spoof-token quarantine to
     * avoid quarantining a legitimate asset whose contract is canonical even if a caller passes an
     * unexpected symbol — a confusable lookalike spoof is, by construction, never a canonical
     * contract.
     */
    public static boolean isKnownCanonicalContract(NetworkId networkId, String assetContract) {
        String normalizedContract = normalizeContract(assetContract);
        if (normalizedContract == null) {
            return false;
        }
        return NetworkStablecoinContracts.forNetwork(networkId).contains(normalizedContract);
    }

    public static String normalizeSymbol(String symbol) {
        if (symbol == null) {
            return "";
        }
        return symbol.trim().toUpperCase(Locale.ROOT);
    }

    public static boolean sameCanonicalSymbol(String left, String right) {
        return Objects.equals(canonicalMarketSymbol(left), canonicalMarketSymbol(right));
    }

    /**
     * F-5(a): returns {@code true} when a canonical asset's USD price is fungible across networks
     * and contracts, so a market-at-timestamp quote stored under any chain's representation of the
     * asset can be safely reused for a corridor/bridge inbound leg that arrived without a paired
     * source basis.
     *
     * <p>The gate is intentionally conservative: only assets with a recognised pricing identity
     * (a CoinGecko id, a 1:1 pegged-native receipt, or a USD-pegged stablecoin) qualify, and any
     * confusable homoglyph ticker is rejected. This prevents a spoofed lookalike or an unknown
     * low-cap symbol from inheriting an unrelated asset's price across networks.</p>
     */
    public static boolean isCrossNetworkPriceResolvable(String assetSymbol) {
        if (assetSymbol == null || isConfusableSymbol(assetSymbol)) {
            return false;
        }
        return coinGeckoId(assetSymbol).isPresent()
                || isPeggedNative(assetSymbol)
                || isUsdStablecoinBySymbol(assetSymbol);
    }

    /**
     * F-5(a): ordered candidate symbols under which a cross-network market-at-timestamp quote for
     * this asset may have been cached. The order is the leg's own symbol, then its canonical
     * market symbol, then the wrapped-native form (e.g. {@code WETH}/{@code WMNT}) — enough to find
     * a same-minute quote priced on any network without pulling in premium-bearing LST aliases
     * (e.g. {@code WEETH}) that would distort the price. Empty for confusable tickers.
     */
    public static List<String> marketEquivalentSymbols(String assetSymbol) {
        if (assetSymbol == null || isConfusableSymbol(assetSymbol)) {
            return List.of();
        }
        String normalized = normalizeSymbol(assetSymbol);
        if (normalized.isBlank()) {
            return List.of();
        }
        String canonical = canonicalMarketSymbol(assetSymbol);
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        candidates.add(normalized);
        if (!canonical.isBlank()) {
            candidates.add(canonical);
            candidates.add("W" + canonical);
        }
        return List.copyOf(candidates);
    }
}
