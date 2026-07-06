package com.walletradar.accounting.support;

import java.util.Locale;
import java.util.Set;

/**
 * RC-8 (ADR-014): single source of truth for <b>out-of-scope (OOS) asset families</b> — assets whose
 * economic lifecycle ends at the CEX boundary (Bybit) with no supported on-chain home, so their
 * held inventory / basis is excluded from the conservation {@code adjustedMTM}.
 *
 * <p>For the conservation identity {@code reportedPnL ≈ adjustedMTM − NEC} to balance, realized PnL
 * on these families must be excluded from {@code reportedPnL} <em>symmetrically</em> with their MtM
 * exclusion — otherwise OOS realized (e.g. TON, SOL) leaks into {@code conservationDelta}. This is the
 * only place the OOS family set is defined; callers must never re-test individual symbols ad hoc.
 *
 * <p>The set is requirement-defined (explicitly unsupported networks/asset families), not derived
 * from a transaction hash, wallet, or hand-curated live bucket.
 */
public final class OutOfScopeFamilySupport {

    /**
     * Accounting family identities with no supported on-chain home (lifecycle ends at the CEX).
     * Mirrors the families omitted from {@code adjustedMTM} in {@code PortfolioConservationGate}.
     */
    private static final Set<String> OUT_OF_SCOPE_FAMILY_IDENTITIES = Set.of(
            "FAMILY:SOL",
            "FAMILY:TON",
            "FAMILY:HYPEREVM"
    );

    /**
     * Raw symbols that resolve to an out-of-scope family even when no canonical family mapping
     * exists (e.g. CEX-only tickers booked as {@code SYMBOL:TON}).
     */
    private static final Set<String> OUT_OF_SCOPE_SYMBOLS = Set.of(
            "SOL",
            "BBSOL",
            "TON",
            "TONCOIN",
            "HYPE",
            "HYPEREVM",
            "WHYPE"
    );

    private OutOfScopeFamilySupport() {
    }

    /**
     * Whether a ledger point belongs to an out-of-scope family, using its persisted accounting
     * family identity first and falling back to the asset symbol (and the shared family resolver)
     * so CEX-only assets with no canonical family mapping are still recognized.
     */
    public static boolean isOutOfScopeFamily(String accountingFamilyIdentity, String assetSymbol) {
        if (matchesFamilyIdentity(accountingFamilyIdentity)) {
            return true;
        }
        String resolvedFamily = AccountingAssetFamilySupport.continuityIdentity(assetSymbol, null);
        if (matchesFamilyIdentity(resolvedFamily)) {
            return true;
        }
        return matchesSymbol(assetSymbol);
    }

    private static boolean matchesFamilyIdentity(String familyIdentity) {
        return familyIdentity != null
                && OUT_OF_SCOPE_FAMILY_IDENTITIES.contains(familyIdentity.trim().toUpperCase(Locale.ROOT));
    }

    private static boolean matchesSymbol(String assetSymbol) {
        if (assetSymbol == null || assetSymbol.isBlank()) {
            return false;
        }
        String normalized = assetSymbol.trim().toUpperCase(Locale.ROOT);
        if (normalized.startsWith("SYMBOL:")) {
            normalized = normalized.substring("SYMBOL:".length());
        }
        return OUT_OF_SCOPE_SYMBOLS.contains(normalized);
    }
}
