package com.walletradar.application.cex.port;

import com.walletradar.application.costbasis.application.port.CexLiveBalancePort;

import java.util.Optional;

/**
 * Optional live-balance adapter capability for a venue.
 *
 * <p>Venues that support live balance snapshots (Bybit, Dzengi) implement this capability
 * in addition to {@link VenueIdentity}. Venues without live balance support omit it.</p>
 *
 * <p>Ingestion-plane SPI — routing is done by {@code VenueRegistry}, which is an
 * ingestion-plane-only component.</p>
 */
public interface VenueLiveBalanceCapability {

    /**
     * Returns the live-balance port for this venue, or empty if live balance is not supported.
     */
    Optional<CexLiveBalancePort> liveBalancePort();
}
