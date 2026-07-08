package com.walletradar.application.cex.port;

import java.util.Set;

/**
 * Design-ready venue profile (Track B1). Declares identity and stream topology for a CEX integration.
 * Reference implementation: Bybit ({@code venueId = "bybit"}).
 * Guide: {@code docs/reference/extensibility/add-an-integration.md}.
 */
public interface CexVenueProfile {

    /** Stable venue slug, e.g. {@code bybit}. */
    String venueId();

    /** Logical ledger streams this venue exposes (e.g. {@code FUNDING_HISTORY}). */
    Set<String> supportedStreams();

    /**
     * Wallet ref suffixes for sub-account kinds (e.g. {@code :FUND}, {@code :UTA}, {@code :EARN}).
     * Align with {@link com.walletradar.canonical.correlation.CorrelationContract}.
     */
    Set<String> accountKindSuffixes();
}
