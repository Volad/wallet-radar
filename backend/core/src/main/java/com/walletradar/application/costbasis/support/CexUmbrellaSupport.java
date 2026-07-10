package com.walletradar.application.costbasis.support;

import com.walletradar.application.costbasis.domain.AssetLedgerPoint;
import com.walletradar.application.pricing.domain.CanonicalAssetCatalog;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.wallet.WalletDomainKind;
import com.walletradar.domain.wallet.WalletRef;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Venue-neutral helpers for collapsing CEX ledger wallets into umbrella keys and reconciling
 * against live balances.
 *
 * <p>Replaces the former venue-specific BybitUmbrellaSupport and DzengiUmbrellaSupport helpers
 * with a single implementation keyed by {@link WalletRef#umbrellaKey()}.</p>
 *
 * <p>Umbrella-key convention: lowercase {@code PROVIDER:uid} without sub-account suffix,
 * e.g. {@code bybit:123456}, {@code dzengi:abc_def}.</p>
 *
 * <p>Pure static utility — no Spring, no ports, no venue registry.</p>
 */
public final class CexUmbrellaSupport {

    private static final MathContext MC = MathContext.DECIMAL128;

    private CexUmbrellaSupport() {
    }

    /**
     * Returns the lowercase umbrella keys of all enabled (non-DISABLED) CEX integrations
     * in the session. Works for any venue (Bybit, Dzengi, future venues).
     */
    public static List<String> enabledCexAccountRefs(UserSession session) {
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
            WalletRef ref = WalletRef.parse(accountRef);
            if (ref.domain() != WalletDomainKind.CEX) {
                continue;
            }
            refs.add(ref.umbrellaKey().toLowerCase(Locale.ROOT));
        }
        return List.copyOf(refs);
    }

    /**
     * Returns true if the given ledger {@code walletAddress} belongs to one of the
     * enabled CEX umbrella refs (case-insensitive).
     *
     * <p>Sub-account suffixes are stripped via {@link WalletRef#umbrellaKey()} before comparison,
     * so {@code BYBIT:123456:FUND} matches the umbrella {@code bybit:123456}.</p>
     */
    public static boolean cexLedgerMatchesEnabledVenue(String walletAddress, Set<String> enabledCexVenueRefs) {
        if (walletAddress == null || walletAddress.isBlank() || enabledCexVenueRefs.isEmpty()) {
            return false;
        }
        WalletRef ref = WalletRef.parse(walletAddress);
        if (ref.domain() != WalletDomainKind.CEX) {
            return false;
        }
        return enabledCexVenueRefs.contains(ref.umbrellaKey().toLowerCase(Locale.ROOT));
    }

    /**
     * Returns the aggregation key for a ledger wallet address.
     *
     * <p>For CEX wallets that belong to one of the enabled umbrella refs, returns the
     * umbrella key (strips sub-account suffix). Otherwise returns the lowercased address.</p>
     */
    public static String ledgerWalletKeyForAggregation(String walletAddress, Set<String> enabledCexVenueRefs) {
        if (walletAddress == null || walletAddress.isBlank()) {
            return normalizeAddress(walletAddress);
        }
        WalletRef ref = WalletRef.parse(walletAddress);
        if (ref.domain() != WalletDomainKind.CEX) {
            return normalizeAddress(walletAddress);
        }
        String umbrellaKey = ref.umbrellaKey().toLowerCase(Locale.ROOT);
        if (enabledCexVenueRefs == null || !enabledCexVenueRefs.contains(umbrellaKey)) {
            return normalizeAddress(walletAddress);
        }
        return umbrellaKey;
    }

    // ---- pure math helpers (ported from BybitUmbrellaSupport) ----

    public static BigDecimal cexRawQuantityAfter(AssetLedgerPoint latestPoint) {
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
