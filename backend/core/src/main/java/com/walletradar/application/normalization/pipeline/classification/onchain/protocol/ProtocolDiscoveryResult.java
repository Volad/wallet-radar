package com.walletradar.application.normalization.pipeline.classification.onchain.protocol;

import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistrySpecialHandlerType;

import java.util.List;
import java.util.Optional;

/**
 * Structured discovery output produced before protocol semantic classification.
 */
public record ProtocolDiscoveryResult(
        List<ProtocolMatch> matches,
        String methodDescription
) {

    public static ProtocolDiscoveryResult empty() {
        return new ProtocolDiscoveryResult(List.of(), null);
    }

    public Optional<ProtocolMatch> primaryMatch() {
        return matches == null || matches.isEmpty() ? Optional.empty() : Optional.of(matches.getFirst());
    }

    public boolean hasSpecialHandlerMatch() {
        return matches != null && matches.stream()
                .filter(match -> match != null)
                .anyMatch(match -> match.specialHandler() != null);
    }

    public Optional<ProtocolMatch> firstSpecialHandlerMatch(ProtocolRegistrySpecialHandlerType type) {
        if (type == null || matches == null || matches.isEmpty()) {
            return Optional.empty();
        }
        return matches.stream()
                .filter(match -> match != null)
                .filter(match -> type == match.specialHandler())
                .findFirst();
    }
}
