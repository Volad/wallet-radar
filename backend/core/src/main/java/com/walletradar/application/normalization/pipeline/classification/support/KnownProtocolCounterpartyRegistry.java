package com.walletradar.application.normalization.pipeline.classification.support;

import com.walletradar.domain.common.NetworkId;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Static registry of known protocol counterparty addresses that should receive
 * protocol attribution when encountered in normalized transactions.
 */
public final class KnownProtocolCounterpartyRegistry {

    private KnownProtocolCounterpartyRegistry() {
    }

    public record ProtocolAttribution(String name, String counterpartyType, boolean asBridge) {
    }

    private record Key(NetworkId networkId, String address) {
    }

    private static final Map<Key, ProtocolAttribution> REGISTRY = Map.of(
            new Key(NetworkId.BASE, "0x8c826f795466e39acbff1bb4eeeb759609377ba1"),
            new ProtocolAttribution("LI.FI", "BRIDGE", false),

            new Key(NetworkId.BASE, "0xf70da97812cb96acdf810712aa562db8dfa3dbef"),
            new ProtocolAttribution("Relay", "BRIDGE", false),

            new Key(NetworkId.ZKSYNC, "0x1fa66e2b38d0cc496ec51f81c3e05e6a6708986f"),
            new ProtocolAttribution("rhino.fi", "BRIDGE", true),

            new Key(NetworkId.ZKSYNC, "0x91604f590d66ace8975eed6bd16cf55647d1c499"),
            new ProtocolAttribution("ZkSync Paymaster", "PROTOCOL", false)
    );

    public static Optional<ProtocolAttribution> lookup(NetworkId networkId, String address) {
        if (networkId == null || address == null || address.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(REGISTRY.get(
                new Key(networkId, address.toLowerCase(Locale.ROOT))
        ));
    }

    public static boolean isKnownProtocol(NetworkId networkId, String address) {
        return lookup(networkId, address).isPresent();
    }
}
