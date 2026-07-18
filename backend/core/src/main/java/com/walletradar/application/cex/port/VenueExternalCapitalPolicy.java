package com.walletradar.application.cex.port;

import java.math.BigDecimal;

/**
 * Venue-specific external-capital accounting policy — evaluated ONCE at normalization time to
 * stamp venue-neutral markers ({@code externalCapitalBoundary}, {@code externalCapitalEligibleUsd})
 * onto normalized transaction rows.
 *
 * <p>Post-normalization consumers read the stamped markers only — they never call this policy
 * again and never depend on {@code VenueRegistry}.</p>
 *
 * <p>Ingestion-plane SPI.</p>
 *
 * <h3>Venue policies:</h3>
 * <ul>
 *   <li><b>Bybit</b>: stablecoin-only FUND inflows, $5 floor, non-universe counterparty</li>
 *   <li><b>Dzengi</b>: fiat BYN + USD + any priced asset, $5 floor (ADR-050)</li>
 * </ul>
 */
public interface VenueExternalCapitalPolicy {

    /**
     * Returns the minimum USD value for an inbound flow to count as external capital.
     * Flows below this threshold are dust / test deposits.
     */
    BigDecimal minimumInflowUsd();

    /**
     * Returns true if the given asset symbol qualifies as an external-capital asset for this venue.
     *
     * @param assetSymbol normalised uppercase symbol (e.g. {@code USDT}, {@code BYN}, {@code ETH})
     */
    boolean isEligibleInflowAsset(String assetSymbol);

    /**
     * Returns true if the given wallet-ref sub-account is the capital-gate sub-account for this venue.
     * For venues without sub-account splits (Dzengi), always returns true.
     *
     * <p>Example: Bybit returns true only for the {@code :FUND} sub-account.
     * Dzengi returns true for any (single) wallet-ref.</p>
     *
     * @param walletAddress the full normalized walletAddress from the normalized row
     */
    boolean isCapitalGateWallet(String walletAddress);
}
