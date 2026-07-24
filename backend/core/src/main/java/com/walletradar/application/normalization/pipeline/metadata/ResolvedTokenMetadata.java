package com.walletradar.application.normalization.pipeline.metadata;

/**
 * Resolved token identity ({symbol, decimals}) plus provenance. Either field may be {@code null}
 * when unresolvable; {@link #unresolved()} is the explicit "not resolved" outcome that callers must
 * treat as unknown rather than substituting a wrong default (WS-7 no-silent-corruption rule).
 */
public record ResolvedTokenMetadata(String symbol, Integer decimals, Source source) {

    public enum Source {
        DESCRIPTOR_OVERRIDE,
        PERSISTENT_CACHE,
        LIVE_RESOLVER,
        UNRESOLVED
    }

    private static final ResolvedTokenMetadata UNRESOLVED =
            new ResolvedTokenMetadata(null, null, Source.UNRESOLVED);

    public static ResolvedTokenMetadata unresolved() {
        return UNRESOLVED;
    }

    public boolean isResolved() {
        return source != Source.UNRESOLVED && (symbol != null || decimals != null);
    }

    public boolean hasSymbol() {
        return symbol != null && !symbol.isBlank();
    }

    public boolean hasDecimals() {
        return decimals != null;
    }
}
