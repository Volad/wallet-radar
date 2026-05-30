package com.walletradar.ingestion.pipeline.classification.lp;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.ingestion.pipeline.classification.support.RawLeg;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;

import java.util.List;
import java.util.Locale;

/**
 * Pendle LP market correlation for fungible PT/LPT receipt tokens (no NFT position pool).
 */
public final class PendleLpCorrelationSupport {

    private PendleLpCorrelationSupport() {
    }

    public static String correlationIdFromMovementLegs(OnChainRawTransactionView view, List<RawLeg> movementLegs) {
        if (view == null || movementLegs == null) {
            return null;
        }
        for (RawLeg leg : movementLegs) {
            if (leg == null || leg.fee() || leg.assetContract() == null || leg.assetContract().isBlank()) {
                continue;
            }
            String marketId = marketIdFromSymbol(leg.assetSymbol());
            if (marketId != null) {
                return formatCorrelationId(view.networkId(), marketId);
            }
        }
        return null;
    }

    public static String marketIdFromSymbol(String assetSymbol) {
        if (assetSymbol == null || assetSymbol.isBlank()) {
            return null;
        }
        String normalized = assetSymbol.trim().toUpperCase(Locale.ROOT);
        // Strip Equilibria staking wrapper prefix: eqbPENDLE-LPT → PENDLE-LPT
        if (normalized.startsWith("EQB") && (normalized.contains("PENDLE") || normalized.contains("-LPT"))) {
            normalized = normalized.substring(3);
        }
        if (!normalized.contains("PENDLE") && !normalized.contains("-LPT")) {
            return null;
        }
        return slugify(normalized);
    }

    public static String formatCorrelationId(NetworkId networkId, String marketId) {
        if (marketId == null || marketId.isBlank()) {
            return null;
        }
        String network = networkId == null ? "unknown" : networkId.name().toLowerCase(Locale.ROOT);
        return "pendle-lp:" + network + ":" + marketId;
    }

    private static String slugify(String value) {
        StringBuilder builder = new StringBuilder(value.length());
        for (char ch : value.toLowerCase(Locale.ROOT).toCharArray()) {
            if ((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')) {
                builder.append(ch);
            } else if (builder.length() == 0 || builder.charAt(builder.length() - 1) != '-') {
                builder.append('-');
            }
        }
        String slug = builder.toString();
        if (slug.endsWith("-")) {
            slug = slug.substring(0, slug.length() - 1);
        }
        return slug.isBlank() ? null : slug;
    }
}
