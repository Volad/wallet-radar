package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.application.pricing.domain.CanonicalAssetCatalog;

/**
 * BR-2: makes the "peg-neutral" bridge assumption explicit and checked.
 *
 * <p>When an orphan bridge inbound has no in-session source leg to carry basis from, the fallback
 * market-prices it as a fresh acquisition. For a USD/EUR-pegged stablecoin this is basis-neutral
 * (cost basis ≈ market value at/near peg). For a non-stable asset the unseen source basis is lost,
 * so the leg must NOT be silently accepted as irreducible — it is flagged for audit instead.</p>
 */
public final class PegNeutralBridgeAssumptionSupport {

    /**
     * Audit flag stamped on an orphan bridge inbound that is market-priced as irreducible while its
     * carried basis could not be verified peg-neutral.
     */
    public static final String NON_PEG_BASIS_UNVERIFIED_REASON = "BRIDGE_ORPHAN_NON_PEG_BASIS_UNVERIFIED";

    private PegNeutralBridgeAssumptionSupport() {
    }

    /**
     * Whether the asset symbol is a USD- or EUR-pegged stablecoin at/near peg, so a market-priced
     * acquisition is an acceptable (basis-neutral) substitute for carried basis.
     */
    public static boolean isPegNeutral(String assetSymbol) {
        return CanonicalAssetCatalog.isUsdStablecoinBySymbol(assetSymbol)
                || CanonicalAssetCatalog.isEuroStablecoin(assetSymbol);
    }

    /**
     * Returns the principal positive-quantity inbound symbol's peg-neutrality, defaulting to
     * {@code false} (not silently peg-neutral) when no principal inbound flow is present.
     */
    public static boolean isPegNeutralInbound(NormalizedTransaction inbound) {
        if (inbound == null) {
            return false;
        }
        return BridgePairLinkSupport.selectPrimaryPrincipalFlow(inbound, 1)
                .map(flow -> isPegNeutral(flow.getAssetSymbol()))
                .orElse(false);
    }
}
