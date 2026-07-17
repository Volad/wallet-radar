package com.walletradar.application.normalization.pipeline.classification.support;

import com.walletradar.domain.common.NetworkId;

import java.util.Optional;
import java.util.function.BiFunction;

/**
 * Registry of known protocol counterparty addresses that should receive protocol attribution
 * when encountered in normalized transactions.
 *
 * <p>ADR-059 (Wave W2): the {@code (NetworkId, address) -> ProtocolAttribution} mapping now lives
 * in the {@code counterparty-hints.json} config plane (category {@code PROTOCOL_COUNTERPARTY}).
 * This class is a thin bind-backed adapter: {@code CounterpartyHintService} binds the lookup at
 * startup, and the public API (including the nested {@link ProtocolAttribution} record) is
 * unchanged so all existing call sites keep working.</p>
 */
public final class KnownProtocolCounterpartyRegistry {

    private static volatile BiFunction<NetworkId, String, Optional<ProtocolAttribution>> lookupBinding =
            (networkId, address) -> Optional.empty();

    private KnownProtocolCounterpartyRegistry() {
    }

    public record ProtocolAttribution(String name, String counterpartyType, boolean asBridge) {
    }

    /**
     * Binds the network-scoped counterparty lookup (called at startup by
     * {@code CounterpartyHintService}).
     */
    public static void bind(BiFunction<NetworkId, String, Optional<ProtocolAttribution>> lookup) {
        lookupBinding = lookup == null ? (networkId, address) -> Optional.empty() : lookup;
    }

    public static Optional<ProtocolAttribution> lookup(NetworkId networkId, String address) {
        if (networkId == null || address == null || address.isBlank()) {
            return Optional.empty();
        }
        return lookupBinding.apply(networkId, address);
    }

    public static boolean isKnownProtocol(NetworkId networkId, String address) {
        return lookup(networkId, address).isPresent();
    }
}
