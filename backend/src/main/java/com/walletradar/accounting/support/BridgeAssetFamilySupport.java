package com.walletradar.accounting.support;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;

import java.util.Locale;

/**
 * Conservative bridge-family identity mapping used by continuity logic.
 *
 * <p>Unlike the general {@link AccountingAssetFamilySupport#continuityIdentity(String, String)}
 * which falls back to the per-chain contract address when no family mapping is found,
 * this bridge-specific variant <strong>never</strong> uses the contract address. Cross-chain
 * bridges assign different token contracts to the same asset on each chain (e.g., CAKE on
 * BASE vs. CAKE on BSC). Using a contract-based key would produce different bridge pending
 * keys for the BRIDGE_OUT and BRIDGE_IN legs, orphaning the corridor basis carry.</p>
 */
public final class BridgeAssetFamilySupport {

    private BridgeAssetFamilySupport() {
    }

    /**
     * Returns a symbol-stable bridge continuity identity that is consistent across chains.
     *
     * <ol>
     *   <li>Attempts a canonical family lookup via
     *       {@link AccountingAssetFamilySupport#continuityIdentity(String, String)} with
     *       {@code null} contract to bypass the contract-address fallback.</li>
     *   <li>Falls back to {@code "SYMBOL:<NORMALIZED_SYMBOL>"} so that the same token bridged
     *       on different networks always produces the same key.</li>
     * </ol>
     */
    public static String continuityIdentity(NormalizedTransaction.Flow flow) {
        if (flow == null) {
            return null;
        }
        String symbol = flow.getAssetSymbol();
        String normalized = symbol == null ? null : symbol.trim().toUpperCase(Locale.ROOT);
        if (normalized == null || normalized.isBlank()) {
            return null;
        }
        // Pass null contract to prevent per-chain contract-address fallback.
        String familyIdentity = AccountingAssetFamilySupport.continuityIdentity(normalized, null);
        if (familyIdentity != null) {
            return familyIdentity;
        }
        return "SYMBOL:" + normalized;
    }
}
