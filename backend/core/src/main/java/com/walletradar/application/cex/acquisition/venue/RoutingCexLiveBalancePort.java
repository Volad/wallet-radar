package com.walletradar.application.cex.acquisition.venue;

import com.walletradar.application.cex.acquisition.venue.bybit.BybitCexLiveBalancePortAdapter;
import com.walletradar.application.cex.acquisition.venue.dzengi.DzengiCexLiveBalancePortAdapter;
import com.walletradar.application.costbasis.application.port.CexLiveBalancePort;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Routes live-balance reads to the venue-specific CEX adapter by integration id prefix.
 */
@Component
@Primary
public class RoutingCexLiveBalancePort implements CexLiveBalancePort {

    private final BybitCexLiveBalancePortAdapter bybitAdapter;
    private final DzengiCexLiveBalancePortAdapter dzengiAdapter;

    public RoutingCexLiveBalancePort(
            BybitCexLiveBalancePortAdapter bybitAdapter,
            DzengiCexLiveBalancePortAdapter dzengiAdapter
    ) {
        this.bybitAdapter = bybitAdapter;
        this.dzengiAdapter = dzengiAdapter;
    }

    @Override
    public Optional<SnapshotView> getSnapshotView(String integrationId) {
        if (integrationId == null || integrationId.isBlank()) {
            return Optional.empty();
        }
        if (integrationId.startsWith("DZENGI-")) {
            return dzengiAdapter.getSnapshotView(integrationId);
        }
        if (integrationId.startsWith("BYBIT-")) {
            return bybitAdapter.getSnapshotView(integrationId);
        }
        return Optional.empty();
    }
}
