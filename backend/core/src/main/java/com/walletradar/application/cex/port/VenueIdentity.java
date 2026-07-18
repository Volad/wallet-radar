package com.walletradar.application.cex.port;

import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;

/**
 * Extended venue identity capability — extends {@link CexVenueProfile} with routing predicates
 * and the normalized source discriminator.
 *
 * <p>Part of the segregated venue SPI (ingestion-plane only).
 * Downstream post-normalization consumers must NOT depend on this interface.</p>
 */
public interface VenueIdentity extends CexVenueProfile {

    /**
     * Uppercase provider prefix used in canonical wallet-ref grammar, e.g. {@code BYBIT}, {@code DZENGI}.
     * Must match what is persisted in {@code walletAddress} / {@code accountRef} fields.
     */
    String providerPrefix();

    /**
     * Canonical normalized-transaction source for this venue.
     * One enum value per venue is the only unavoidable closed-enum edit when adding a new venue.
     */
    NormalizedTransactionSource normalizedSource();

    /**
     * Returns true if the given {@code accountRef} / wallet-ref string belongs to this venue
     * (prefix ownership check, case-insensitive).
     */
    default boolean ownsRef(String accountRef) {
        if (accountRef == null || accountRef.isBlank()) {
            return false;
        }
        String prefix = providerPrefix() + ":";
        return accountRef.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    /**
     * Returns true if the given integration id (e.g. {@code BYBIT-abc123}) belongs to this venue.
     * Default implementation: checks if integrationId starts with {@code providerPrefix() + "-"}.
     */
    default boolean ownsIntegrationId(String integrationId) {
        if (integrationId == null || integrationId.isBlank()) {
            return false;
        }
        String prefix = providerPrefix() + "-";
        return integrationId.regionMatches(true, 0, prefix, 0, prefix.length());
    }
}
