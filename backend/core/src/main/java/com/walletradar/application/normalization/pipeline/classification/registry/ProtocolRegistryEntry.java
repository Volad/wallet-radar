package com.walletradar.application.normalization.pipeline.classification.registry;

import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;

import java.util.Set;

/**
 * Loaded runtime entry from the protocol registry resource.
 */
public record ProtocolRegistryEntry(
        String contractAddress,
        Set<NetworkId> networks,
        ProtocolRegistryFamily family,
        ProtocolRegistryRole role,
        ProtocolRegistryEventType eventType,
        ConfidenceLevel confidence,
        String protocolName,
        String protocolVersion,
        boolean decomposeByLegs,
        ProtocolRegistrySpecialHandlerType specialHandler,
        String underlyingPositionManager
) {
    /**
     * Backward-compatible constructor (pre RC-5): no {@code underlyingPositionManager}. Retained so
     * existing direct constructions (tests + a few classifiers) keep compiling without a wrapper
     * mapping.
     */
    public ProtocolRegistryEntry(
            String contractAddress,
            Set<NetworkId> networks,
            ProtocolRegistryFamily family,
            ProtocolRegistryRole role,
            ProtocolRegistryEventType eventType,
            ConfidenceLevel confidence,
            String protocolName,
            String protocolVersion,
            boolean decomposeByLegs,
            ProtocolRegistrySpecialHandlerType specialHandler
    ) {
        this(contractAddress, networks, family, role, eventType, confidence, protocolName,
                protocolVersion, decomposeByLegs, specialHandler, null);
    }

    public boolean supports(NetworkId networkId) {
        return networkId != null && networks != null && networks.contains(networkId);
    }

    public NormalizedTransactionType normalizedType() {
        return eventType == null ? null : eventType.toNormalizedType();
    }
}
