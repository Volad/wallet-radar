package com.walletradar.application.cex.port;

import java.util.List;
import java.util.Set;

/**
 * Venue wallet topology — declares how an integration's account ref maps to ledger wallet-ref strings.
 *
 * <p>Venues with sub-account splits (Bybit: FUND / UTA / EARN) override {@link #expandSubAccountRefs}.
 * Flat single-wallet venues (Dzengi) use the provided no-op defaults.</p>
 *
 * <p>Ingestion-plane SPI — must NOT be injected into post-normalization consumers.</p>
 */
public interface VenueWalletModel {

    /**
     * Given a base account ref (e.g. {@code BYBIT:123456}), returns the full set of
     * sub-account wallet-ref strings that should be tracked as ledger rows.
     *
     * <p>Default (flat venue): returns a single-element list containing the ref itself.</p>
     */
    default List<String> expandSubAccountRefs(String baseAccountRef) {
        if (baseAccountRef == null || baseAccountRef.isBlank()) {
            return List.of();
        }
        return List.of(baseAccountRef.trim());
    }

    /**
     * Returns the umbrella wallet-ref strings shown on the dashboard for the given base account ref.
     * For venues without sub-account splits this is the same as {@link #expandSubAccountRefs}.
     *
     * <p>Default: delegates to {@link #expandSubAccountRefs}.</p>
     */
    default List<String> dashboardWalletRefs(String baseAccountRef) {
        return expandSubAccountRefs(baseAccountRef);
    }

    /**
     * Returns true if the given ledger {@code walletAddress} belongs to the umbrella identified
     * by {@code umbrellaKey} (e.g. {@code bybit:123456}).
     *
     * <p>Default: case-insensitive equality (works for flat/single-wallet venues).</p>
     */
    default boolean ledgerMatchesUmbrella(String walletAddress, String umbrellaKey) {
        if (walletAddress == null || umbrellaKey == null) {
            return false;
        }
        return walletAddress.equalsIgnoreCase(umbrellaKey);
    }

    /**
     * Returns the set of sub-account kind labels for this venue (e.g. {@code FUND}, {@code UTA}).
     * Used to recognise sub-account suffixes in {@code walletAddress} strings.
     * Empty by default (flat venues have no sub-accounts).
     */
    default Set<String> subAccountKinds() {
        return Set.of();
    }
}
