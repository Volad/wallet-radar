package com.walletradar.application.liquiditypools.application;

import com.walletradar.platform.common.refresh.RefreshTrigger;
import com.walletradar.application.liquiditypools.enrichment.LpPositionContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Executor;

@Slf4j
@Service
@RequiredArgsConstructor
public class LpRefreshOrchestrator {

    private static final Duration SESSION_MIN_INTERVAL = Duration.ofMinutes(5);

    private final LpPositionRefreshService refreshService;
    private final LpPositionRefreshStateService refreshStateService;
    @Qualifier(com.walletradar.platform.common.config.AsyncConfig.PIPELINE_STAGE_EXECUTOR)
    private final Executor pipelineStageExecutor;

    public void triggerRefreshPosition(String sessionId, String correlationId) {
        refreshStateService.markQueued(sessionId, correlationId, RefreshTrigger.MANUAL);
        pipelineStageExecutor.execute(() -> executeRefreshPosition(sessionId, correlationId));
    }

    public void triggerRefreshAllOpenForSession(String sessionId, RefreshTrigger trigger) {
        Map<String, LpPositionContext> contexts = refreshService.discoverOpenContextsForSession(sessionId);
        for (String correlationId : contexts.keySet()) {
            refreshStateService.markQueued(sessionId, correlationId, trigger);
        }
        pipelineStageExecutor.execute(() -> executeRefreshSession(sessionId, trigger));
    }

    public void triggerRefreshAllOpenForSession(String sessionId) {
        triggerRefreshAllOpenForSession(sessionId, RefreshTrigger.BULK);
    }

    public void triggerRefreshAllOpenPositions(RefreshTrigger trigger) {
        for (String sessionId : refreshService.discoverSessionIdsWithOpenPositions()) {
            triggerRefreshAllOpenForSession(sessionId, trigger);
        }
    }

    public boolean shouldSkipSessionRefresh(String sessionId, String triggerLabel) {
        if (refreshStateService.anyActive(sessionId)) {
            return true;
        }
        return refreshStateService.lastSessionRefreshAt(sessionId)
                .map(last -> Duration.between(last, Instant.now()).compareTo(SESSION_MIN_INTERVAL) < 0)
                .orElse(false);
    }

    private void executeRefreshPosition(String sessionId, String correlationId) {
        try {
            refreshService.refreshOnDemandWithState(sessionId, correlationId);
        } catch (Exception error) {
            log.warn("LP async refresh failed sessionId={} correlationId={} error={}",
                    sessionId, correlationId, error.toString());
            refreshStateService.markFailed(correlationId, error.toString());
        }
    }

    private void executeRefreshSession(String sessionId, RefreshTrigger trigger) {
        try {
            refreshService.refreshAllOpenForSessionWithState(sessionId, trigger);
        } catch (Exception error) {
            log.warn("LP async session refresh failed sessionId={} trigger={} error={}",
                    sessionId, trigger, error.toString());
        }
    }
}
