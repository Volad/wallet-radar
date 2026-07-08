package com.walletradar.domain.common;

import java.util.Set;
import java.util.function.Function;

/**
 * Runtime bridge so static pricing helpers can resolve per-network USD-stable contracts
 * without depending on Spring configuration types.
 */
public final class NetworkStablecoinContracts {

    private static volatile Function<NetworkId, Set<String>> lookup = networkId -> Set.of();

    private NetworkStablecoinContracts() {
    }

    public static void bind(Function<NetworkId, Set<String>> resolver) {
        lookup = resolver == null ? id -> Set.of() : resolver;
    }

    public static Set<String> forNetwork(NetworkId networkId) {
        if (networkId == null) {
            return Set.of();
        }
        Set<String> contracts = lookup.apply(networkId);
        return contracts == null ? Set.of() : contracts;
    }
}
