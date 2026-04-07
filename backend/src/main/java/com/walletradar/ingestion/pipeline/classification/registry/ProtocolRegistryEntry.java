package com.walletradar.ingestion.pipeline.classification.registry;

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
        ProtocolRegistrySpecialHandlerType specialHandler
) {
    public boolean supports(NetworkId networkId) {
        return networkId != null && networks != null && networks.contains(networkId);
    }

    public NormalizedTransactionType normalizedType() {
        return eventType == null ? null : eventType.toNormalizedType();
    }
}
