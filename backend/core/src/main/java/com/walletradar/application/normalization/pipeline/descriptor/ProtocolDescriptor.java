package com.walletradar.application.normalization.pipeline.descriptor;

import java.util.List;
import java.util.Set;

/**
 * Protocol-level descriptor loaded from {@code protocol-descriptors/*.json}.
 */
public record ProtocolDescriptor(
        String protocol,
        String version,
        Set<ProtocolCapability> capabilities,
        ProtocolClassificationConfig classification,
        LpPresentationConfig lpPresentation,
        LendingConfig lending,
        ValuationSourceConfig valuationSource
) {
    public record ProtocolClassificationConfig(
            String semanticClassifier,
            Set<String> supportedFamilies
    ) {
    }

    public record LpPresentationConfig(
            String positionIdentityStrategy,
            List<String> receiptTokenPatterns
    ) {
    }

    public record LendingConfig(
            String marketRateSource,
            boolean supportsVariableDebt
    ) {
    }

    public record ValuationSourceConfig(
            String primarySource,
            List<String> fallbackSources
    ) {
    }
}
