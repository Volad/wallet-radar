package com.walletradar.application.liquiditypools.application;

import com.walletradar.platform.common.refresh.RefreshStatus;
import com.walletradar.application.liquiditypools.persistence.LpPositionRefreshState;
import com.walletradar.application.liquiditypools.persistence.LpPositionRefreshStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Marks LP refresh rows stuck in {@link RefreshStatus#UPDATING} as failed so the UI can recover
 * when enrichment threads hang on RPC (no overall timeout on scheduled refresh path).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LpPositionRefreshStateWatchdog {

    private static final Duration STALE_UPDATING = Duration.ofMinutes(3);

    private final LpPositionRefreshStateRepository repository;
    private final LpPositionRefreshStateService refreshStateService;

    @Scheduled(fixedDelayString = "${walletradar.liquidity-pools.refresh-watchdog-interval-ms:60000}")
    void failStaleUpdating() {
        Instant cutoff = Instant.now().minus(STALE_UPDATING);
        List<LpPositionRefreshState> stale = repository.findByStatusAndStartedAtBefore(
                RefreshStatus.UPDATING, cutoff);
        for (LpPositionRefreshState state : stale) {
            log.warn("LP refresh watchdog failing stale UPDATING correlationId={} startedAt={}",
                    state.getCorrelationId(), state.getStartedAt());
            refreshStateService.markFailed(state.getCorrelationId(),
                    "Refresh timed out (stuck in UPDATING)");
        }
    }
}
