package com.walletradar.domain.common;

import java.util.Set;

/**
 * Per-network metadata loaded from {@code walletradar.networks.*} configuration.
 */
public record NetworkDescriptor(
        NetworkId networkId,
        NetworkAddressFormatKind addressFormat,
        String nativeSymbol,
        /** Accounting identity sentinel for native flows (e.g. {@code NATIVE:SOLANA}, {@code TONCOIN}). Null for EVM. */
        String nativeIdentity,
        /** Precision of the native token in decimal places (e.g. 9 for SOL/TON, 18 for EVM). Null when unset. */
        Integer nativeDecimals,
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
