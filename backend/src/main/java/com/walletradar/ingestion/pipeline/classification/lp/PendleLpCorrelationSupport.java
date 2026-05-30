package com.walletradar.ingestion.pipeline.classification.lp;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.ingestion.pipeline.classification.support.RawLeg;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/**
 * Pendle LP market correlation for fungible PT/LPT receipt tokens (no NFT position pool).
 */
public final class PendleLpCorrelationSupport {

    private PendleLpCorrelationSupport() {
    }

    /**
     * Detects LP_ENTRY or LP_EXIT from movement legs that contain Pendle LP tokens.
     *
     * Logic:
     * - If any Pendle LP leg is outbound (negative qty) → LP_EXIT.
     *   This covers both direct LP_EXIT (PENDLE-LPT burned) and Zap-out (eqbPENDLE-LPT
     *   briefly received then returned to the distributor — the outbound leg confirms exit).
     * - Else if net Pendle LP quantity is positive → LP_ENTRY.
     * - Else → null (e.g. no Pendle token in legs).
     */
    public static NormalizedTransactionType lpTypeFromMovementLegs(List<RawLeg> movementLegs) {
        if (movementLegs == null) {
            return null;
        }
        boolean hasOutbound = false;
        boolean hasInbound = false;
        for (RawLeg leg : movementLegs) {
            if (leg == null || leg.fee()) {
                continue;
            }
            String marketId = marketIdFromSymbol(leg.assetSymbol());
            if (marketId != null && leg.quantityDelta() != null) {
                if (leg.quantityDelta().signum() < 0) {
                    hasOutbound = true;
                } else if (leg.quantityDelta().signum() > 0) {
                    hasInbound = true;
                }
            }
        }
        if (hasOutbound && !hasInbound) {
            return NormalizedTransactionType.LP_EXIT;
        }
        if (hasInbound && !hasOutbound) {
            return NormalizedTransactionType.LP_ENTRY;
        }
        return null;
    }

    /**
     * Returns true if the function name or method ID unambiguously indicates a Pendle-family
     * LP exit (Zap-out pattern), even when the Pendle LP token circulates (in+out, net=0).
     * Used as a fallback classification hint when the token-flow direction is ambiguous.
     */
    public static boolean isZapOutFunctionSignature(OnChainRawTransactionView view) {
        if (view == null) {
            return false;
        }
        String fn = view.functionName();
        if (fn != null && fn.toLowerCase(Locale.ROOT).contains("zapout")) {
            return true;
        }
        String methodId = view.methodId();
        return "0x8b284b0e".equalsIgnoreCase(methodId); // zapOutV3SingleToken (Equilibria)
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
        // Must be an LP position receipt token (ending in -LPT), not the plain Pendle governance token.
        // "PENDLE" alone is the reward/governance token and must return null.
        if (!normalized.endsWith("-LPT")) {
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
