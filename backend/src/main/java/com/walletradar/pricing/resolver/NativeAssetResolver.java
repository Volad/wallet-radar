package com.walletradar.pricing.resolver;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.pricing.HistoricalPriceRequest;
import com.walletradar.pricing.PriceResolutionResult;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves native and wrapped-native assets via deterministic aliases before generic contract lookup.
 * This avoids relying on per-network wrapped token CoinGecko coverage for canonical gas assets.
 */
@Component
public class NativeAssetResolver {

    private static final String ETH_CANONICAL = "0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee";
    private static final String POLYGON_CANONICAL = "0x0d500b1d8e8ef31e21c99d1db9a6444d3adf1270";
    private static final String BSC_CANONICAL = "0xbb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c";
    private static final String AVALANCHE_CANONICAL = "0xb31f66aa3c1e785363f0875a1b74e27b85fd66c7";
    private static final String MANTLE_CANONICAL = "0x78c1b0c915c4faa5fffa6cabf0219da63d7f4cb8";
    private static final String ZKSYNC_PSEUDO_NATIVE = "0x000000000000000000000000000000000000800a";

    private static final Map<String, String> CANONICAL_BY_NETWORK = Map.ofEntries(
            Map.entry("ETHEREUM", ETH_CANONICAL),
            Map.entry("ARBITRUM", ETH_CANONICAL),
            Map.entry("OPTIMISM", ETH_CANONICAL),
            Map.entry("BASE", ETH_CANONICAL),
            Map.entry("LINEA", ETH_CANONICAL),
            Map.entry("UNICHAIN", ETH_CANONICAL),
            Map.entry("POLYGON", POLYGON_CANONICAL),
            Map.entry("BSC", BSC_CANONICAL),
            Map.entry("AVALANCHE", AVALANCHE_CANONICAL),
            Map.entry("MANTLE", MANTLE_CANONICAL),
            Map.entry("ZKSYNC", ETH_CANONICAL)
    );

    private final CoinGeckoHistoricalResolver coinGeckoHistoricalResolver;

    public NativeAssetResolver(CoinGeckoHistoricalResolver coinGeckoHistoricalResolver) {
        this.coinGeckoHistoricalResolver = coinGeckoHistoricalResolver;
    }

    public PriceResolutionResult resolve(HistoricalPriceRequest request) {
        if (request == null || request.getAssetContract() == null || request.getNetworkId() == null) {
            return PriceResolutionResult.unknown();
        }

        String canonicalContract = canonicalContract(request.getNetworkId(), request.getAssetContract());
        if (canonicalContract == null) {
            return PriceResolutionResult.unknown();
        }

        HistoricalPriceRequest aliased = copyRequestWithContract(request, canonicalContract);
        return coinGeckoHistoricalResolver.resolve(aliased);
    }

    private static HistoricalPriceRequest copyRequestWithContract(HistoricalPriceRequest request, String canonicalContract) {
        HistoricalPriceRequest copy = new HistoricalPriceRequest();
        copy.setAssetContract(canonicalContract);
        copy.setNetworkId(request.getNetworkId());
        copy.setBlockTimestamp(request.getBlockTimestamp() != null ? request.getBlockTimestamp() : Instant.now());
        copy.setCounterpartContract(request.getCounterpartContract());
        copy.setCounterpartAmount(request.getCounterpartAmount());
        copy.setOurAmount(request.getOurAmount());
        return copy;
    }

    private static String canonicalContract(NetworkId networkId, String assetContract) {
        String normalizedContract = normalizeContract(assetContract);
        String networkKey = networkId.name();
        String nativeCanonical = CANONICAL_BY_NETWORK.get(networkKey);
        if (nativeCanonical == null || normalizedContract == null) {
            return null;
        }

        return switch (networkKey) {
            case "ETHEREUM" -> equalsAny(normalizedContract,
                    ETH_CANONICAL,
                    "0xc02aa39b223fe8d0a0e5c4f27ead9083c756cc2") ? ETH_CANONICAL : null;
            case "ARBITRUM" -> equalsAny(normalizedContract,
                    ETH_CANONICAL,
                    "0x82af49447d8a07e3bd95bd0d56f35241523fbab1") ? ETH_CANONICAL : null;
            case "OPTIMISM", "BASE", "UNICHAIN" -> equalsAny(normalizedContract,
                    ETH_CANONICAL,
                    "0x4200000000000000000000000000000000000006") ? ETH_CANONICAL : null;
            case "LINEA" -> equalsAny(normalizedContract,
                    ETH_CANONICAL,
                    "0xe5d7c2a44ffddf6b295a15c148167daaaf5cf34f") ? ETH_CANONICAL : null;
            case "ZKSYNC" -> equalsAny(normalizedContract,
                    ETH_CANONICAL,
                    ZKSYNC_PSEUDO_NATIVE,
                    "0x5aea5775959fbc2557cc8789bc1bf90a239d9a91") ? ETH_CANONICAL : null;
            case "POLYGON" -> equalsAny(normalizedContract,
                    POLYGON_CANONICAL,
                    ETH_CANONICAL) ? POLYGON_CANONICAL : null;
            case "BSC" -> equalsAny(normalizedContract,
                    BSC_CANONICAL,
                    ETH_CANONICAL) ? BSC_CANONICAL : null;
            case "AVALANCHE" -> equalsAny(normalizedContract,
                    AVALANCHE_CANONICAL,
                    ETH_CANONICAL) ? AVALANCHE_CANONICAL : null;
            case "MANTLE" -> equalsAny(normalizedContract,
                    MANTLE_CANONICAL,
                    ETH_CANONICAL) ? MANTLE_CANONICAL : null;
            default -> null;
        };
    }

    private static boolean equalsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && value.equals(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeContract(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
