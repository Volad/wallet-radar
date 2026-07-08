package com.walletradar.application.linking.pipeline.clarification;

/**
 * Persisted metadata resolution states used by coverage telemetry.
 */
public final class MetadataResolutionState {

    public static final String RESOLVED_EXACT = "RESOLVED_EXACT";
    public static final String RESOLVED_FAMILY = "RESOLVED_FAMILY";
    public static final String TERMINAL_METADATA_ONLY = "TERMINAL_METADATA_ONLY";
    public static final String IRREDUCIBLE_EVIDENCE_MISSING = "IRREDUCIBLE_EVIDENCE_MISSING";
    public static final String UNSUPPORTED_SCOPE = "UNSUPPORTED_SCOPE";

    private MetadataResolutionState() {
    }

    public static boolean isTerminal(String state) {
        return TERMINAL_METADATA_ONLY.equals(state)
                || IRREDUCIBLE_EVIDENCE_MISSING.equals(state)
                || UNSUPPORTED_SCOPE.equals(state);
    }
}
