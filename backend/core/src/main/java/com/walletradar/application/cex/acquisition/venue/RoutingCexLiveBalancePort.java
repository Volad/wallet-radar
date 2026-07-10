package com.walletradar.application.cex.acquisition.venue;

import com.walletradar.application.costbasis.application.port.CexLiveBalancePort;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Routes live-balance reads to the venue-specific CEX adapter via {@link VenueRegistry}.
 *
 * <p>Replaces hardcoded prefix {@code if}s with registry-based dispatch
 * ({@code registry.liveBalancePortFor(integrationId)}). Adding a new venue only requires
 * registering its {@link com.walletradar.application.cex.port.VenueLiveBalanceCapability}
 * implementation as a Spring component — no edits needed here.</p>
 */
@Component
@Primary
@RequiredArgsConstructor
public class RoutingCexLiveBalancePort implements CexLiveBalancePort {

    private final VenueRegistry venueRegistry;

    @Override
    public Optional<SnapshotView> getSnapshotView(String integrationId) {
        return venueRegistry.liveBalancePortFor(integrationId)
                .flatMap(port -> port.getSnapshotView(integrationId));
    }
}
