package com.walletradar.ingestion.pipeline.classification.lp;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.ingestion.pipeline.classification.support.RawLeg;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic GMX V2 LP market correlation from settlement share symbols.
 */
public final class GmxMarketCorrelationSupport {

    private static final Pattern BRACKET_PAIR = Pattern.compile("\\[([^\\]]+)\\]", Pattern.CASE_INSENSITIVE);
    private static final Pattern GM_PREFIX = Pattern.compile("^GM:\\s*(.+)$", Pattern.CASE_INSENSITIVE);

    private GmxMarketCorrelationSupport() {
    }

    public static String correlationIdFromMovementLegs(OnChainRawTransactionView view, List<RawLeg> movementLegs) {
        if (view == null || movementLegs == null) {
            return null;
        }
        for (RawLeg leg : movementLegs) {
            if (leg == null || leg.fee() || leg.quantityDelta() == null || leg.quantityDelta().signum() <= 0) {
                continue;
            }
            String marketSlug = marketSlugFromShareSymbol(leg.assetSymbol());
            if (marketSlug != null) {
                return formatCorrelationId(view.networkId(), marketSlug);
            }
        }
        return null;
    }

    public static String correlationIdFromFlows(OnChainRawTransactionView view, List<NormalizedTransaction.Flow> flows) {
        if (view == null || flows == null) {
            return null;
        }
        for (NormalizedTransaction.Flow flow : flows) {
            if (flow == null || flow.getQuantityDelta() == null || flow.getQuantityDelta().signum() <= 0) {
                continue;
            }
            String marketSlug = marketSlugFromShareSymbol(flow.getAssetSymbol());
            if (marketSlug != null) {
                return formatCorrelationId(view.networkId(), marketSlug);
            }
        }
        return null;
    }

    public static String marketSlugFromShareSymbol(String assetSymbol) {
        if (assetSymbol == null || assetSymbol.isBlank()) {
            return null;
        }
        String normalized = assetSymbol.trim();
        if (!normalized.regionMatches(true, 0, "GM:", 0, 3)
                && !normalized.regionMatches(true, 0, "GLV", 0, 3)) {
            return null;
        }
        String marketLabel = normalized;
        Matcher gmMatcher = GM_PREFIX.matcher(normalized);
        if (gmMatcher.matches()) {
            marketLabel = gmMatcher.group(1).trim();
        }
        Matcher bracketMatcher = BRACKET_PAIR.matcher(marketLabel);
        if (bracketMatcher.find()) {
            marketLabel = bracketMatcher.group(1).trim();
        }
        return slugify(marketLabel);
    }

    public static String formatCorrelationId(NetworkId networkId, String marketSlug) {
        if (marketSlug == null || marketSlug.isBlank()) {
            return null;
        }
        String network = networkId == null ? "unknown" : networkId.name().toLowerCase(Locale.ROOT);
        return "gmx-lp:" + network + ":" + marketSlug;
    }

    private static String slugify(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        StringBuilder builder = new StringBuilder(value.length());
        for (char ch : value.trim().toLowerCase(Locale.ROOT).toCharArray()) {
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
