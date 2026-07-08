package com.walletradar.application.cex.acquisition.venue.bybit;

import com.walletradar.costbasis.application.port.CexLiveBalancePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class BybitCexLiveBalancePortAdapter implements CexLiveBalancePort {

    private final BybitLiveBalanceService bybitLiveBalanceService;

    @Override
    public Optional<SnapshotView> getSnapshotView(String integrationId) {
        return bybitLiveBalanceService.getSnapshotView(integrationId)
                .map(view -> new SnapshotView(
                        Availability.valueOf(view.availability().name()),
                        view.umbrella(),
                        view.fetchedAt()
                ));
    }
}
