package com.walletradar.application.normalization.pipeline.classification.support;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.common.NetworkNativeAssets;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;

import java.util.Locale;

/**
 * C2 (R1): Per-network canonical wrapped-native (WETH/WBNB/WMATIC/WAVAX/WMNT/WXPL…) resolver
 * for the classification layer.
 *
 * <p>Used by {@link LpExitFeeDecomposer} and {@link LpNftClFlowMaterializer} to resolve the
 * wrapped-native slot when a V3/Slipstream pool returns the native asset via {@code unwrapWETH9}
 * (or the chain-equivalent unwrap). In that case no ERC-20 Transfer reaches the wallet directly,
 * so the fee fraction must be keyed by the wrapped-native contract address and applied to the
 * native flow. On non-ETH-native chains the wrapped-native contract (WBNB/WMATIC/WAVAX/WMNT/WXPL)
 * is the correct key for the same reason.
 *
 * <p><strong>Source of truth:</strong> the wrapped-native contract is read from
 * {@code network-descriptors.yml} via {@link NetworkNativeAssets} — a single source shared with
 * the cost-basis layer ({@code AccountingAssetIdentitySupport}), so there is no per-class map to
 * keep in sync. Coverage now spans every network with a configured wrapped-native (Ethereum,
 * Arbitrum, Optimism, Base, Unichain, zkSync, Linea, Katana, BSC, Polygon, Avalanche, Mantle,
 * Plasma).
 */
public final class NativeWrappedTokenSupport {

    private NativeWrappedTokenSupport() {
    }

    /**
     * Returns the canonical wrapped-native contract address (lowercase) for {@code networkId}
     * from {@code network-descriptors.yml}, or {@code null} if the network has no configured
     * wrapped-native.
     */
    public static String canonicalWeth(NetworkId networkId) {
        return networkId == null ? null : NetworkNativeAssets.wrappedNativeContract(networkId);
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
