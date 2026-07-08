package com.walletradar.application.normalization.pipeline.classification.support;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.platform.networks.descriptor.NetworkRegistry;
import org.springframework.stereotype.Component;

/**
 * Native and wrapped-native symbol/address mapping for classifier heuristics.
 */
@Component
public class NativeAssetSymbolResolver {

    private final NetworkRegistry networkRegistry;

    public NativeAssetSymbolResolver(NetworkRegistry networkRegistry) {
        this.networkRegistry = networkRegistry;
    }

    public String nativeSymbol(NetworkId networkId) {
        return networkRegistry.nativeSymbol(networkId);
    }

    public String wrappedNativeContract(NetworkId networkId) {
        return networkRegistry.wrappedNativeContract(networkId);
    }

    public String wrappedNativeSymbol(NetworkId networkId) {
        return networkRegistry.wrappedNativeSymbol(networkId);
    }

    public boolean isWrappedNative(NetworkId networkId, String contractAddress) {
        return networkRegistry.isWrappedNative(networkId, contractAddress);
    }

    public boolean isNativeAliasContract(NetworkId networkId, String contractAddress) {
        return networkRegistry.isNativeAliasContract(networkId, contractAddress);
    }
}
