package com.walletradar.application.normalization.pipeline.classification.onchain.protocol;

import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryFamily;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryRole;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistrySpecialHandlerType;

/**
 * Concrete protocol signal discovered for the current transaction.
 */
public record ProtocolMatch(
        String protocolName,
        String protocolVersion,
        ProtocolRegistryFamily family,
        ProtocolRegistryRole role,
        ConfidenceLevel confidence,
        String contractAddress,
        String matchedAddress,
        String matchSource,
        ProtocolResourceDefinition resource,
        ProtocolRegistrySpecialHandlerType specialHandler
) {
}
