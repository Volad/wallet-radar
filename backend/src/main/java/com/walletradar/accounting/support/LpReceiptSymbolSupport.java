package com.walletradar.accounting.support;

import java.util.Locale;

/**
 * Canonical {@code LP-RECEIPT:network:protocol:tokenId} identity shared by ingestion
 * materialization and AVCO replay so receipt pools do not split on network/protocol casing.
 */
public final class LpReceiptSymbolSupport {

    private static final String RECEIPT_PREFIX = "LP-RECEIPT:";
    private static final String LP_POSITION_PREFIX = "lp-position:";

    private LpReceiptSymbolSupport() {
    }

    public static String fromLpPositionCorrelation(String correlationId) {
        if (correlationId == null || correlationId.isBlank()) {
            return null;
        }
        String normalized = correlationId.trim();
        if (!normalized.toLowerCase(Locale.ROOT).startsWith(LP_POSITION_PREFIX)) {
            return null;
        }
        return canonicalizeSegments(normalized.substring(LP_POSITION_PREFIX.length()));
    }

    public static String canonicalize(String assetSymbol) {
        if (assetSymbol == null || assetSymbol.isBlank()) {
            return "";
        }
        String trimmed = assetSymbol.trim();
        if (!trimmed.toUpperCase(Locale.ROOT).startsWith(RECEIPT_PREFIX)) {
            return trimmed.toUpperCase(Locale.ROOT);
        }
        int prefixEnd = trimmed.indexOf(':');
        if (prefixEnd < 0) {
            return trimmed.toUpperCase(Locale.ROOT);
        }
        String body = trimmed.substring(prefixEnd + 1);
        return canonicalizeSegments(body);
    }

    public static boolean isLpReceiptSymbol(String assetSymbol) {
        return assetSymbol != null
                && !assetSymbol.isBlank()
                && assetSymbol.trim().toUpperCase(Locale.ROOT).startsWith(RECEIPT_PREFIX);
    }

    private static String canonicalizeSegments(String networkProtocolTokenId) {
        if (networkProtocolTokenId == null || networkProtocolTokenId.isBlank()) {
            return RECEIPT_PREFIX;
        }
        String[] parts = networkProtocolTokenId.split(":", 3);
        if (parts.length < 3) {
            return RECEIPT_PREFIX + networkProtocolTokenId.toUpperCase(Locale.ROOT);
        }
        return RECEIPT_PREFIX
                + parts[0].trim().toUpperCase(Locale.ROOT)
                + ':'
                + parts[1].trim().toUpperCase(Locale.ROOT)
                + ':'
                + parts[2].trim();
    }
}
