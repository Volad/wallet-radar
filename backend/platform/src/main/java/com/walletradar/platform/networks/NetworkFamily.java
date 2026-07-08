package com.walletradar.platform.networks;

import com.walletradar.domain.common.NetworkId;

/**
 * Groups {@link NetworkId} values by transport and address semantics (Track B2).
 * EVM chains share one family; Solana and TON have distinct address rules.
 *
 * <p>See {@code docs/reference/extensibility/add-a-network.md} for worked examples.
 */
public interface NetworkFamily {

    /** Stable family slug: {@code EVM}, {@code SOLANA}, {@code TON}. */
    String familyId();

    /** Whether this family handles the given network. */
    boolean supports(NetworkId networkId);

    /**
     * Normalize a wallet/contract address for the network (case, base58, workchain).
     * Implementations should delegate to {@link com.walletradar.domain.common.NetworkAddressFormat} where possible.
     */
    String normalizeAddress(NetworkId networkId, String rawAddress);

    /**
     * Optional Spring bean name of the default {@link NetworkAdapter} for this family.
     * Empty when adapter routing is network-specific within the family.
     */
    default String defaultAdapterBeanName() {
        return "";
    }
}
