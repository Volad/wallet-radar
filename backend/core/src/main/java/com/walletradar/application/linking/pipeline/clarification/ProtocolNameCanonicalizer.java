package com.walletradar.application.linking.pipeline.clarification;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Canonical product-facing protocolName normalization.
 */
@Component
public class ProtocolNameCanonicalizer {

    private static final Map<String, String> CANONICAL_BY_LOWERCASE = Map.of(
            "lifi", "LI.FI",
            "fluid", "Fluid",
            "merkle", "Merkl",
            "paraswap", "Velora/ParaSwap",
            "para swap", "Velora/ParaSwap"
    );

    public String canonicalize(String protocolName) {
        if (protocolName == null || protocolName.isBlank()) {
            return null;
        }
        String trimmed = protocolName.trim();
        return CANONICAL_BY_LOWERCASE.getOrDefault(trimmed.toLowerCase(Locale.ROOT), trimmed);
    }

    public boolean needsCanonicalization(String protocolName) {
        if (protocolName == null || protocolName.isBlank()) {
            return false;
        }
        return !protocolName.trim().equals(canonicalize(protocolName));
    }

    public List<String> legacyExactNames() {
        return List.of("LiFi", "FLUID", "Merkle", "Paraswap", "ParaSwap");
    }
}
