package com.walletradar.domain.common;

import java.util.Set;
import java.util.function.Function;

/**
 * Runtime bridge so static cost-basis / normalization helpers can resolve per-network
 * native-asset metadata (native symbol, wrapped-native contract, native-alias contracts)
 * without depending on Spring configuration types.
 *
 * <p>Mirrors {@link NetworkStablecoinContracts}: {@code NetworkRegistry} binds the lookups at
 * startup from the authoritative {@code network-descriptors.yml}, so there is a single source of
 * truth for wrapped-native / native-alias data instead of hardcoded per-class copies.
 */
public final class NetworkNativeAssets {

    private static volatile Function<NetworkId, String> nativeSymbolLookup = id -> null;
    private static volatile Function<NetworkId, String> wrappedNativeContractLookup = id -> null;
    private static volatile Function<NetworkId, Set<String>> nativeAliasContractsLookup = id -> Set.of();

    private NetworkNativeAssets() {
    }

    public static void bind(
            Function<NetworkId, String> nativeSymbol,
            Function<NetworkId, String> wrappedNativeContract,
            Function<NetworkId, Set<String>> nativeAliasContracts
    ) {
        nativeSymbolLookup = nativeSymbol == null ? id -> null : nativeSymbol;
        wrappedNativeContractLookup = wrappedNativeContract == null ? id -> null : wrappedNativeContract;
        nativeAliasContractsLookup = nativeAliasContracts == null ? id -> Set.of() : nativeAliasContracts;
    }

    /**
     * Returns the native-asset symbol for {@code networkId} (e.g. {@code ETH}, {@code BNB}),
     * or {@code null} when unknown.
     */
    public static String nativeSymbol(NetworkId networkId) {
        if (networkId == null) {
            return null;
        }
        return nativeSymbolLookup.apply(networkId);
    }

    /**
     * Returns the wrapped-native contract (lowercase) for {@code networkId}, or {@code null} when
     * the network has no configured wrapped-native.
     */
    public static String wrappedNativeContract(NetworkId networkId) {
        if (networkId == null) {
            return null;
        }
        return wrappedNativeContractLookup.apply(networkId);
    }

    /**
     * Returns the set of contracts (lowercase) that are accounting-identical to the network's
     * native token — the union of the wrapped-native contract and any additional native-alias
     * contracts. Never {@code null} (empty set default).
     */
    public static Set<String> nativeAliasContracts(NetworkId networkId) {
        if (networkId == null) {
            return Set.of();
        }
        Set<String> contracts = nativeAliasContractsLookup.apply(networkId);
        return contracts == null ? Set.of() : contracts;
    }
}
