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
        String underlyingPositionManager,
        // C1 (R7 LFJ): LFJ Liquidity Book pair-specific token addresses and binStep.
        // Non-null only for LFJ_LB_PAIR entries; null for all other handler types.
        String tokenX,
        String tokenY,
        Integer binStep
) {
    /**
     * Backward-compatible constructor (pre RC-5 / pre C1): no {@code underlyingPositionManager},
     * {@code tokenX}, {@code tokenY}, or {@code binStep}. Retained so existing direct constructions
     * (tests + a few classifiers) keep compiling without a wrapper mapping.
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
                protocolVersion, decomposeByLegs, specialHandler, null, null, null, null);
    }

    /**
     * Backward-compatible constructor (pre C1): no {@code tokenX}, {@code tokenY}, or
     * {@code binStep}. Retained for constructions that already supply {@code underlyingPositionManager}.
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
            ProtocolRegistrySpecialHandlerType specialHandler,
            String underlyingPositionManager
    ) {
        this(contractAddress, networks, family, role, eventType, confidence, protocolName,
                protocolVersion, decomposeByLegs, specialHandler, underlyingPositionManager,
                null, null, null);
    }

    public boolean supports(NetworkId networkId) {
        return networkId != null && networks != null && networks.contains(networkId);
    }

    public NormalizedTransactionType normalizedType() {
        return eventType == null ? null : eventType.toNormalizedType();
    }
}
