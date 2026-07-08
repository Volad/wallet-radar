package com.walletradar.application.lending.application;

import com.walletradar.application.lending.view.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.Executor;

@Slf4j
@Service
@RequiredArgsConstructor
public class LendingRefreshOrchestrator {

    private final LendingOnDemandRefreshService refreshService;
    private final LendingGroupRefreshStateService refreshStateService;
    private final SessionLendingQueryService lendingQueryService;
    @Qualifier(com.walletradar.platform.common.config.AsyncConfig.PIPELINE_STAGE_EXECUTOR)
    private final Executor pipelineStageExecutor;

    public void triggerRefreshGroup(String sessionId, String groupId) {
        refreshStateService.markQueued(sessionId, groupId, com.walletradar.platform.common.refresh.RefreshTrigger.MANUAL);
        pipelineStageExecutor.execute(() -> executeRefreshGroup(sessionId, groupId));
    }

    public void triggerRefreshAllOpenGroups(String sessionId) {
        lendingQueryService.findSessionLending(sessionId).ifPresent(view -> {
            for (LendingGroupView group : view.groups()) {
                if ("OPEN".equals(group.status())) {
                    refreshStateService.markQueued(
                            sessionId,
                            group.id(),
                            com.walletradar.platform.common.refresh.RefreshTrigger.BULK
                    );
                }
            }
            pipelineStageExecutor.execute(() -> executeRefreshAllOpenGroups(sessionId));
        });
    }

    private void executeRefreshGroup(String sessionId, String groupId) {
        try {
            refreshService.refreshGroupWithState(sessionId, groupId);
        } catch (Exception error) {
            log.warn("Lending async group refresh failed sessionId={} groupId={} error={}",
                    sessionId, groupId, error.toString());
            refreshStateService.markFailed(groupId, error.toString());
        }
    }

    private void executeRefreshAllOpenGroups(String sessionId) {
        try {
            refreshService.refreshAllOpenGroupsWithState(sessionId);
        } catch (Exception error) {
            log.warn("Lending async session refresh failed sessionId={} error={}",
                    sessionId, error.toString());
        }
    }

    public List<String> openGroupIds(String sessionId) {
        return lendingQueryService.findSessionLending(sessionId)
                .map(view -> view.groups().stream()
                        .filter(group -> "OPEN".equals(group.status()))
                        .map(LendingGroupView::id)
                        .toList())
                .orElse(List.of());
    }
}
