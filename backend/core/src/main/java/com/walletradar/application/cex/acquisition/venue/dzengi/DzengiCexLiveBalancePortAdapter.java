package com.walletradar.application.cex.acquisition.venue.dzengi;

import com.walletradar.application.costbasis.application.port.CexLiveBalancePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class DzengiCexLiveBalancePortAdapter implements CexLiveBalancePort {

    private final DzengiLiveBalanceService dzengiLiveBalanceService;

    @Override
    public Optional<SnapshotView> getSnapshotView(String integrationId) {
        return dzengiLiveBalanceService.getSnapshotView(integrationId)
                .map(view -> new SnapshotView(
                        Availability.valueOf(view.availability().name()),
                        view.umbrella(),
                        view.fetchedAt()
                ));
    }
}
