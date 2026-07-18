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
 * Marks LP refresh rows stuck in {@link RefreshStatus#UPDATING} or {@link RefreshStatus#QUEUED}
 * as failed so the UI can recover when enrichment threads hang on RPC or when a QUEUED position
 * was never picked up by the processing loop (e.g. it became closed between enqueueing and
 * execution, so discoverOpenContexts did not return it).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LpPositionRefreshStateWatchdog {

    private static final Duration STALE_UPDATING = Duration.ofMinutes(3);
    private static final Duration STALE_QUEUED = Duration.ofMinutes(15);

    private final LpPositionRefreshStateRepository repository;
    private final LpPositionRefreshStateService refreshStateService;

    @Scheduled(fixedDelayString = "${walletradar.liquidity-pools.refresh-watchdog-interval-ms:60000}")
    void failStaleUpdating() {
        Instant updatingCutoff = Instant.now().minus(STALE_UPDATING);
        List<LpPositionRefreshState> staleUpdating = repository.findByStatusAndStartedAtBefore(
                RefreshStatus.UPDATING, updatingCutoff);
        for (LpPositionRefreshState state : staleUpdating) {
            log.warn("LP refresh watchdog failing stale UPDATING correlationId={} startedAt={}",
                    state.getCorrelationId(), state.getStartedAt());
            refreshStateService.markFailed(state.getCorrelationId(),
                    "Refresh timed out (stuck in UPDATING)");
        }

        // QUEUED rows whose processing loop never picked them up (position became closed between
        // enqueueing and execution, or the executor task failed before reaching the item).
        Instant queuedCutoff = Instant.now().minus(STALE_QUEUED);
        List<LpPositionRefreshState> staleQueued = repository.findByStatusAndRequestedAtBefore(
                RefreshStatus.QUEUED, queuedCutoff);
        for (LpPositionRefreshState state : staleQueued) {
            log.warn("LP refresh watchdog failing stale QUEUED correlationId={} requestedAt={}",
                    state.getCorrelationId(), state.getRequestedAt());
            refreshStateService.markFailed(state.getCorrelationId(),
                    "Refresh timed out (stuck in QUEUED — never picked up by processing loop)");
        }
    }
}
