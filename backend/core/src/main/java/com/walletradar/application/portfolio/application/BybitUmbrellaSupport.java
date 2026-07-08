package com.walletradar.ingestion.wallet.query;

import com.walletradar.costbasis.domain.AssetLedgerPoint;
import com.walletradar.domain.session.UserSession;
import com.walletradar.pricing.domain.CanonicalAssetCatalog;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Shared helpers for collapsing {@code bybit:<uid>:fund|uta|earn} ledger venues into a single umbrella
 * wallet ({@code bybit:<uid>}) and reconciling against live Bybit balances.
 */
public final class BybitUmbrellaSupport {

    private static final MathContext MC = MathContext.DECIMAL128;

    private BybitUmbrellaSupport() {
    }

    public static List<String> enabledBybitAccountRefs(UserSession session) {
        if (session.getIntegrations() == null || session.getIntegrations().isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        for (UserSession.SessionIntegration integration : session.getIntegrations()) {
            if (integration == null || integration.getStatus() == UserSession.IntegrationStatus.DISABLED) {
                continue;
            }
            String accountRef = integration.getAccountRef();
            if (accountRef == null || accountRef.isBlank()) {
                continue;
            }
            if (!accountRef.toUpperCase(Locale.ROOT).startsWith("BYBIT:")) {
                continue;
            }
            for (String bybitWalletRef : bybitDashboardWalletRefs(accountRef)) {
                refs.add(normalizeAddress(bybitWalletRef));
            }
        }
        return List.copyOf(refs);
    }

    public static List<String> bybitDashboardWalletRefs(String baseAccountRef) {
        if (baseAccountRef == null || baseAccountRef.isBlank()) {
            return List.of();
        }
        String normalized = baseAccountRef.trim();
        if (!normalized.toUpperCase(Locale.ROOT).startsWith("BYBIT:")) {
            return List.of(normalized);
        }
        if (normalized.split(":").length >= 3) {
            return List.of(normalized);
        }
        return List.of(normalized);
    }

    public static boolean bybitLedgerMatchesEnabledVenue(String ledgerWallet, Set<String> enabledBybitVenueRefs) {
        if (ledgerWallet == null || ledgerWallet.isBlank() || enabledBybitVenueRefs.isEmpty()) {
            return false;
        }
        String norm = normalizeAddress(ledgerWallet);
        if (!norm.startsWith("bybit:")) {
            return false;
        }
        String[] parts = norm.split(":", -1);
        if (parts.length < 3) {
            return enabledBybitVenueRefs.contains(norm);
        }
        String base = parts[0] + ":" + parts[1];
        return enabledBybitVenueRefs.contains(base);
    }

    public static String ledgerWalletKeyForAggregation(String walletAddress, Set<String> enabledBybitVenueRefs) {
        if (walletAddress == null || walletAddress.isBlank() || enabledBybitVenueRefs == null || enabledBybitVenueRefs.isEmpty()) {
            return normalizeAddress(walletAddress);
        }
        String norm = normalizeAddress(walletAddress);
        if (!norm.startsWith("bybit:")) {
            return norm;
        }
        String[] parts = norm.split(":", -1);
        if (parts.length < 3) {
            return norm;
        }
        String base = parts[0] + ":" + parts[1];
        String sub = parts[2];
        if (("fund".equals(sub) || "uta".equals(sub) || "earn".equals(sub)) && enabledBybitVenueRefs.contains(base)) {
            return base;
        }
        return norm;
    }

    public static BigDecimal bybitRawQuantityAfter(AssetLedgerPoint latestPoint) {
        return latestPoint == null ? BigDecimal.ZERO : zeroIfNull(latestPoint.getQuantityAfter());
    }

    public static List<String> priceLookupCandidates(String symbol) {
        String normalized = normalizeSymbol(symbol);
        if (normalized.isBlank()) {
            return List.of();
        }
        String canonical = CanonicalAssetCatalog.canonicalMarketSymbol(normalized);
        return switch (canonical) {
            case "BTC" -> List.of("BTC", "WBTC");
            case "ETH" -> List.of("ETH", "WETH");
            case "AVAX" -> List.of("AVAX", "WAVAX");
            case "MNT" -> List.of("MNT", "WMNT");
            default -> canonical.equals(normalized)
                    ? List.of(canonical)
                    : List.of(canonical, normalized);
        };
    }

    public static BigDecimal liveQuantityForCandidates(
            Map<String, BigDecimal> live,
            Collection<String> candidateSymbols,
            String fallbackSymbol
    ) {
        BigDecimal total = BigDecimal.ZERO;
        boolean anyMatched = false;
        for (String candidate : candidateSymbols) {
            String symbol = normalizeSymbol(candidate);
            if (symbol == null || symbol.isBlank()) {
                continue;
            }
            BigDecimal qty = live.get(symbol);
            if (qty != null && qty.signum() > 0) {
                total = total.add(qty, MC);
                anyMatched = true;
            }
        }
        if (!anyMatched) {
            String fallback = normalizeSymbol(fallbackSymbol);
            BigDecimal qty = fallback == null ? null : live.get(fallback);
            if (qty == null) {
                return BigDecimal.ZERO;
            }
            return qty;
        }
        return total;
    }

    public static BigDecimal clampToLive(BigDecimal quantity, BigDecimal coveredQuantity, BigDecimal totalCostBasisUsd, BigDecimal liveQty) {
        if (quantity == null || quantity.signum() <= 0) {
            return quantity;
        }
        if (liveQty == null || liveQty.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        if (quantity.compareTo(liveQty) <= 0) {
            return quantity;
        }
        return liveQty;
    }

    public static ScaledUmbrellaTotals scaleUmbrellaToLive(
            BigDecimal quantity,
            BigDecimal coveredQuantity,
            BigDecimal totalCostBasisUsd,
            BigDecimal liveQty
    ) {
        if (quantity == null || quantity.signum() <= 0) {
            return new ScaledUmbrellaTotals(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, false);
        }
        if (liveQty == null || liveQty.signum() <= 0) {
            return new ScaledUmbrellaTotals(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, true);
        }
        if (quantity.compareTo(liveQty) < 0) {
            return new ScaledUmbrellaTotals(
                    liveQty,
                    zeroIfNull(coveredQuantity),
                    zeroIfNull(totalCostBasisUsd),
                    BigDecimal.ONE,
                    false
            );
        }
        if (quantity.compareTo(liveQty) == 0) {
            return new ScaledUmbrellaTotals(
                    quantity,
                    zeroIfNull(coveredQuantity),
                    zeroIfNull(totalCostBasisUsd),
                    BigDecimal.ONE,
                    false
            );
        }
        BigDecimal scale = liveQty.divide(quantity, MC);
        return new ScaledUmbrellaTotals(
                liveQty,
                zeroIfNull(coveredQuantity).multiply(scale, MC),
                zeroIfNull(totalCostBasisUsd).multiply(scale, MC),
                scale,
                false
        );
    }

    public static String normalizeAddress(String address) {
        return address == null ? "" : address.trim().toLowerCase(Locale.ROOT);
    }

    public static String normalizeSymbol(String symbol) {
        return symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    public record ScaledUmbrellaTotals(
            BigDecimal quantity,
            BigDecimal coveredQuantity,
            BigDecimal totalCostBasisUsd,
            BigDecimal ledgerScale,
            boolean dropped
    ) {
    }
}
