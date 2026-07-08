package com.walletradar.application.normalization.pipeline.classification.onchain.protocol;

import java.util.Optional;

/**
 * Lookup contract for protocol-local discovery metadata.
 */
@FunctionalInterface
public interface ProtocolResourceCatalog {

    ProtocolResourceCatalog EMPTY = (protocolName, protocolVersion) -> Optional.empty();

    Optional<ProtocolResourceDefinition> find(String protocolName, String protocolVersion);

    static ProtocolResourceCatalog empty() {
        return EMPTY;
    }
}
