package com.walletradar.application.normalization.pipeline.classification.support;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;

import java.util.Locale;
import java.util.Map;

/**
 * C2 (R1): Per-network canonical WETH / wrapped-native addresses for the classification layer.
 *
 * <p>Used by {@link LpExitFeeDecomposer} and {@link LpNftClFlowMaterializer} to resolve
 * the WETH slot when a V3/Slipstream pool returns native ETH via {@code unwrapWETH9}. In that
 * case no ERC-20 Transfer reaches the wallet directly, so the fee fraction must be keyed by the
 * WETH contract address and applied to the native ETH flow.
 *
 * <p><strong>Cross-reference:</strong> mirrors
 * {@code AccountingAssetIdentitySupport.NATIVE_ALIAS_CONTRACTS} in the cost-basis layer.
 * Both maps must stay in sync when new networks are added.
 */
public final class NativeWrappedTokenSupport {

    // Canonical WETH contract addresses, normalized to lowercase.
    // Only networks where V3/Slipstream pools are confirmed to exist in the dataset are listed.
    // BSC (WBNB), AVALANCHE (WAVAX), MANTLE (WMNT): not currently in V3/Slipstream scope;
    // add here when confirmed.
    private static final Map<NetworkId, String> CANONICAL_WETH = Map.of(
            NetworkId.ETHEREUM, "0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2",
            NetworkId.ARBITRUM,  "0x82af49447d8a07e3bd95bd0d56f35241523fbab1",
            NetworkId.OPTIMISM,  "0x4200000000000000000000000000000000000006",
            NetworkId.BASE,      "0x4200000000000000000000000000000000000006",
            NetworkId.UNICHAIN,  "0x4200000000000000000000000000000000000006",
            NetworkId.ZKSYNC,    "0x5aea5775959fbc2557cc8789bc1bf90a239d9a91"
    );

    private NativeWrappedTokenSupport() {
    }

    /**
     * Returns the canonical WETH contract address (lowercase) for {@code networkId},
     * or {@code null} if this network is not in scope for V3/Slipstream native-ETH pools.
     */
    public static String canonicalWeth(NetworkId networkId) {
        if (networkId == null) {
            return null;
        }
        return CANONICAL_WETH.get(networkId);
    }

    /**
     * Returns {@code true} when {@code flow} is a native-asset flow (no ERC-20 contract address).
     */
    public static boolean isNativeAsset(NormalizedTransaction.Flow flow) {
        return flow != null && flow.getAssetContract() == null;
    }

    /**
     * Normalizes a WETH address to lowercase for map lookups.
     */
    public static String normalize(String address) {
        return address == null ? null : address.trim().toLowerCase(Locale.ROOT);
    }
}
