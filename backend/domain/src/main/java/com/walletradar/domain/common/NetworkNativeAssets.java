package com.walletradar.domain.common;

import java.util.Set;
import java.util.function.Function;

/**
 * Runtime bridge so static cost-basis / normalization helpers can resolve per-network
 * native-asset metadata (native symbol, wrapped-native contract, native-alias contracts,
 * native identity sentinel, native decimals) without depending on Spring configuration types.
 *
 * <p>Mirrors {@link NetworkStablecoinContracts}: {@code NetworkRegistry} binds the lookups at
 * startup from the authoritative {@code network-descriptors.yml}, so there is a single source of
 * truth for wrapped-native / native-alias / native-identity / native-decimals data instead of
 * hardcoded per-class copies (W14).</p>
 */
public final class NetworkNativeAssets {

    private static volatile Function<NetworkId, String> nativeSymbolLookup = id -> null;
    private static volatile Function<NetworkId, String> wrappedNativeContractLookup = id -> null;
    private static volatile Function<NetworkId, Set<String>> nativeAliasContractsLookup = id -> Set.of();
    private static volatile Function<NetworkId, String> nativeIdentityLookup = id -> null;
    private static volatile Function<NetworkId, Integer> nativeDecimalsLookup = id -> null;

    private NetworkNativeAssets() {
    }

    public static void bind(
            Function<NetworkId, String> nativeSymbol,
            Function<NetworkId, String> wrappedNativeContract,
            Function<NetworkId, Set<String>> nativeAliasContracts,
            Function<NetworkId, String> nativeIdentity,
            Function<NetworkId, Integer> nativeDecimals
    ) {
        nativeSymbolLookup = nativeSymbol == null ? id -> null : nativeSymbol;
        wrappedNativeContractLookup = wrappedNativeContract == null ? id -> null : wrappedNativeContract;
        nativeAliasContractsLookup = nativeAliasContracts == null ? id -> Set.of() : nativeAliasContracts;
        nativeIdentityLookup = nativeIdentity == null ? id -> null : nativeIdentity;
        nativeDecimalsLookup = nativeDecimals == null ? id -> null : nativeDecimals;
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
     * Returns the wrapped-native contract for {@code networkId}, or {@code null} when the network
     * has no configured wrapped-native. EVM contracts are lowercased; non-EVM contracts (Solana
     * base58, TON) are case-preserved (W16).
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

    /**
     * Returns the accounting identity sentinel for the network's native token
     * (e.g. {@code NATIVE:SOLANA} for Solana, {@code TONCOIN} for TON), or {@code null} when
     * not configured (EVM networks currently have no sentinel — they use the native symbol directly).
     *
     * <p>This is the exact string stamped by the network's normalizer on native-asset flows and used
     * by balance providers, lending readers, and pricing providers as {@code assetContract} for
     * native holdings.</p>
     */
    public static String nativeIdentity(NetworkId networkId) {
        if (networkId == null) {
            return null;
        }
        return nativeIdentityLookup.apply(networkId);
    }

    /**
     * Returns the decimal precision of the network's native token (e.g. {@code 9} for SOL/TON,
     * {@code 18} for EVM chains), or {@code null} when not configured.
     */
    public static Integer nativeDecimals(NetworkId networkId) {
        if (networkId == null) {
            return null;
        }
        return nativeDecimalsLookup.apply(networkId);
    }
}
