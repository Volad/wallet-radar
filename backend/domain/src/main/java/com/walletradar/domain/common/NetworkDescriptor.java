package com.walletradar.domain.common;

import java.util.Set;

/**
 * Per-network metadata loaded from {@code walletradar.networks.*} configuration.
 */
public record NetworkDescriptor(
        NetworkId networkId,
        NetworkAddressFormatKind addressFormat,
        String nativeSymbol,
        String wrappedNativeContract,
        String wrappedNativeSymbol,
        Set<String> nativeAliasContracts,
        boolean walletSupported,
        boolean evmWalletSupported,
        Set<String> usdStableContracts,
        Set<String> ethFamilyContracts
) {
    public boolean isEvm() {
        return addressFormat == NetworkAddressFormatKind.EVM;
    }
}
