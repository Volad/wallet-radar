package com.walletradar.pricing.domain;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;

import java.util.Locale;
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

    private static final Map<NetworkId, Set<String>> USD_STABLE_CONTRACTS = Map.ofEntries(
            Map.entry(NetworkId.ETHEREUM, Set.of(
                    "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48",
                    "0xdac17f958d2ee523a2206206994597c13d831ec7",
                    "0x6b175474e89094c44da98b954eedeac495271d0f",
                    "0xc5f0f7b66764f6ec8c8dff7ba683102295e16409",
                    "0x4c9edd5852cd905f086c759e8383e09bff1e68b3"
            )),
            Map.entry(NetworkId.ARBITRUM, Set.of(
                    "0xaf88d065e77c8cc2239327c5edb3a432268e5831",
                    "0xfd086bc7cd5c481dcc9c85ebe478a1c0b69fcbb9",
                    "0xda10009cbd5d07dd0cecc66161fc93d7c9000da1",
                    "0x5d3a1ff2b6bab83b63cd9ad0787074081a52ef34"
            )),
            Map.entry(NetworkId.OPTIMISM, Set.of(
                    "0x0b2c639c533813f4aa9d7837caf62653d097ff85",
                    "0x94b008aa00579c1307b0ef2c499ad98a8ce58e58",
                    "0x01bff41798a0bcf287b996046ca68b395dbc1071"
            )),
            Map.entry(NetworkId.BASE, Set.of(
                    "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913",
                    "0xfde4c96c8593536e31f229ea8f37b2ada2699bb2",
                    "0xd9aaec86b65d86f6a7b5b1b0c42ffa531710b6ca"
            )),
            Map.entry(NetworkId.BSC, Set.of(
                    "0x55d398326f99059ff775485246999027b3197955",
                    "0x8ac76a51cc950d9822d68b83fe1ad97b32cd580d"
            )),
            Map.entry(NetworkId.POLYGON, Set.of(
                    "0x2791bca1f2de4661ed88a30c99a7a9449aa84174",
                    "0x3c499c542cef5e3811e1192ce70d8cc03d5c3359",
                    "0xc2132d05d31c914a87c6611c10748aeb04b58e8f"
            )),
            Map.entry(NetworkId.AVALANCHE, Set.of(
                    "0xb97ef9ef8734c71904d8002f8b6bc66dd9c48a6e",
                    "0x9702230a8ea53601f5cd2dc00fdbc13d4df4a8c7",
                    "0xfc421ad3c883bf9e7c4f42de845c4e4405799e73",
                    "0x00000000efe302beaa2b3e6e1b18d08d69a9012a"
            )),
            Map.entry(NetworkId.MANTLE, Set.of(
                    "0x09bc4e0d864854c6afb6eb9a9cdf58ac190d0df9",
                    "0x201eba5cc46d216ce6dc03f6a759e8e766e956ae",
                    "0x779ded0c9e1022225f8e0630b35a9b54be713736",
                    "0x5d3a1ff2b6bab83b63cd9ad0787074081a52ef34"
            )),
            Map.entry(NetworkId.LINEA, Set.of(
                    "0xa219439258ca9da29e9cc4ce5596924745e12b93"
            )),
            Map.entry(NetworkId.UNICHAIN, Set.of(
                    "0x078d782b760474a361dda0af3839290b0ef57ad6",
                    "0x9151434b16b9763660705744891fa906f660ecc5"
            )),
            Map.entry(NetworkId.ZKSYNC, Set.of(
                    "0x1d17cbcf0d6d143135ae902365d2e5e2a16538d4",
                    "0x3355df6d4c9c3035724fd0e3914de96a5a83aaf4"
            )),
            Map.entry(NetworkId.PLASMA, Set.of(
                    "0xb8ce59fc3717ada4c02eadf9682a9e934f625ebb"
            ))
    );

    private static final Map<String, String> SYMBOL_ALIASES = Map.ofEntries(
            Map.entry("WETH", "ETH"),
            Map.entry("AETHWETH", "ETH"),
            Map.entry("AARBWETH", "ETH"),
            Map.entry("ALINWETH", "ETH"),
            Map.entry("AMANWETH", "ETH"),
            Map.entry("AZKSWETH", "ETH"),
            Map.entry("WBNB", "BNB"),
            Map.entry("WAVAX", "AVAX"),
            Map.entry("WMNT", "MNT"),
            Map.entry("WXPL", "XPL"),
            Map.entry("WXPL9", "XPL"),
            Map.entry("WBTC", "BTC"),
            Map.entry("USDBC", "USDC"),
            Map.entry("USD₮0", "USDT"),
            Map.entry("USDT0", "USDT"),
            Map.entry("USDT", "USDT"),
            Map.entry("POL", "MATIC")
    );

    private static final Map<String, String> COINGECKO_IDS = Map.ofEntries(
            Map.entry("ETH", "ethereum"),
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
            Map.entry("CAKE", "pancakeswap-token"),
            Map.entry("WSTETH", "wrapped-steth"),
            Map.entry("STETH", "staked-ether"),
            Map.entry("CBETH", "coinbase-wrapped-staked-eth"),
            Map.entry("PENDLE", "pendle"),
            Map.entry("BAL", "balancer"),
            Map.entry("UNI", "uniswap"),
            Map.entry("COMP", "compound-governance-token"),
            Map.entry("MORPHO", "morpho"),
            Map.entry("ZK", "zksync")
    );

    private CanonicalAssetCatalog() {
    }

    public static boolean isUsdStablecoin(
            NetworkId networkId,
            String assetContract,
            String assetSymbol,
            NormalizedTransactionSource source
    ) {
        String normalizedContract = normalizeContract(assetContract);
        if (normalizedContract != null) {
            return USD_STABLE_CONTRACTS.getOrDefault(networkId, Set.of()).contains(normalizedContract);
        }
        return source == NormalizedTransactionSource.BYBIT
                && assetSymbol != null
                && USD_STABLE_SYMBOLS.contains(normalizeSymbol(assetSymbol));
    }

    public static String canonicalMarketSymbol(String assetSymbol) {
        String normalized = normalizeSymbol(assetSymbol);
        return SYMBOL_ALIASES.getOrDefault(normalized, normalized);
    }

    public static Optional<String> coinGeckoId(String assetSymbol) {
        return Optional.ofNullable(COINGECKO_IDS.get(canonicalMarketSymbol(assetSymbol)));
    }

    public static boolean isEuroStablecoin(String assetSymbol) {
        return EUR_STABLE_SYMBOLS.contains(canonicalMarketSymbol(assetSymbol));
    }

    public static String normalizeContract(String contract) {
        if (contract == null || contract.isBlank()) {
            return null;
        }
        return contract.trim().toLowerCase(Locale.ROOT);
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
}
