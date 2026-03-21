package com.walletradar.ingestion.pipeline.classification.support;

import com.walletradar.domain.common.NetworkId;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

/**
 * Native and wrapped-native symbol/address mapping for classifier heuristics.
 */
@Component
public class NativeAssetSymbolResolver {

    private static final Map<NetworkId, String> NATIVE_SYMBOLS = Map.ofEntries(
            Map.entry(NetworkId.ETHEREUM, "ETH"),
            Map.entry(NetworkId.ARBITRUM, "ETH"),
            Map.entry(NetworkId.OPTIMISM, "ETH"),
            Map.entry(NetworkId.BASE, "ETH"),
            Map.entry(NetworkId.UNICHAIN, "ETH"),
            Map.entry(NetworkId.ZKSYNC, "ETH"),
            Map.entry(NetworkId.LINEA, "ETH"),
            Map.entry(NetworkId.KATANA, "ETH"),
            Map.entry(NetworkId.BSC, "BNB"),
            Map.entry(NetworkId.POLYGON, "MATIC"),
            Map.entry(NetworkId.AVALANCHE, "AVAX"),
            Map.entry(NetworkId.MANTLE, "MNT"),
            Map.entry(NetworkId.PLASMA, "XPL")
    );

    private static final Map<NetworkId, String> WRAPPED_NATIVE_CONTRACTS = Map.ofEntries(
            Map.entry(NetworkId.ETHEREUM, "0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2"),
            Map.entry(NetworkId.ARBITRUM, "0x82af49447d8a07e3bd95bd0d56f35241523fbab1"),
            Map.entry(NetworkId.OPTIMISM, "0x4200000000000000000000000000000000000006"),
            Map.entry(NetworkId.BASE, "0x4200000000000000000000000000000000000006"),
            Map.entry(NetworkId.UNICHAIN, "0x4200000000000000000000000000000000000006"),
            Map.entry(NetworkId.POLYGON, "0x7ceb23fd6bc0add59e62ac25578270cff1b9f619"),
            Map.entry(NetworkId.ZKSYNC, "0x5aea5775959fbc2557cc8789bc1bf90a239d9a91"),
            Map.entry(NetworkId.LINEA, "0xe5d7c2a44ffddf6b295a15c148167daaaf5cf34f"),
            Map.entry(NetworkId.MANTLE, "0xdeaddeaddeaddeaddeaddeaddeaddeaddead1111"),
            Map.entry(NetworkId.BSC, "0xbb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c"),
            Map.entry(NetworkId.AVALANCHE, "0xb31f66aa3c1e785363f0875a1b74e27b85fd66c7")
    );

    private static final Map<NetworkId, String> WRAPPED_NATIVE_SYMBOLS = Map.ofEntries(
            Map.entry(NetworkId.ETHEREUM, "WETH"),
            Map.entry(NetworkId.ARBITRUM, "WETH"),
            Map.entry(NetworkId.OPTIMISM, "WETH"),
            Map.entry(NetworkId.BASE, "WETH"),
            Map.entry(NetworkId.UNICHAIN, "WETH"),
            Map.entry(NetworkId.ZKSYNC, "WETH"),
            Map.entry(NetworkId.LINEA, "WETH"),
            Map.entry(NetworkId.KATANA, "WETH"),
            Map.entry(NetworkId.BSC, "WBNB"),
            Map.entry(NetworkId.POLYGON, "WETH"),
            Map.entry(NetworkId.AVALANCHE, "WAVAX"),
            Map.entry(NetworkId.MANTLE, "WMNT")
    );

    public String nativeSymbol(NetworkId networkId) {
        return NATIVE_SYMBOLS.getOrDefault(networkId, "UNKNOWN_NATIVE");
    }

    public String wrappedNativeContract(NetworkId networkId) {
        return WRAPPED_NATIVE_CONTRACTS.get(networkId);
    }

    public String wrappedNativeSymbol(NetworkId networkId) {
        return WRAPPED_NATIVE_SYMBOLS.get(networkId);
    }

    public boolean isWrappedNative(NetworkId networkId, String contractAddress) {
        String configured = wrappedNativeContract(networkId);
        if (configured == null || contractAddress == null) {
            return false;
        }
        return configured.equals(contractAddress.trim().toLowerCase(Locale.ROOT));
    }
}
