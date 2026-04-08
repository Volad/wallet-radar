package com.walletradar.integration;

import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.sync.BackfillSegment;
import com.walletradar.domain.sync.BackfillSegmentRepository;
import com.walletradar.session.application.IntegrationSyncStatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Owns shared persistence and replacement semantics for integration backfill
 * plans. Provider-specific planners only contribute segment definitions.
 */
@Service
@RequiredArgsConstructor
public class IntegrationBackfillPlanningService {

    private final BackfillSegmentRepository backfillSegmentRepository;
    private final List<IntegrationBackfillPlanner> planners;
    private final IntegrationSyncStatusService integrationSyncStatusService;

    public UserSession.IntegrationSyncState replanInitialBackfill(
            String sessionId,
            UserSession.SessionIntegration integration
    ) {
        if (integration == null || integration.getProvider() == null) {
            throw new IllegalArgumentException("Integration provider is required");
        }

        IntegrationBackfillPlanner planner = planners.stream()
                .filter(candidate -> candidate.supports(integration.getProvider()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No integration backfill planner registered for provider " + integration.getProvider()
                ));

        backfillSegmentRepository.deleteByIntegrationId(integration.getIntegrationId());

        Instant plannedAt = Instant.now();
        List<BackfillSegment> segments = planner.planInitialBackfill(sessionId, integration, plannedAt);
        if (!segments.isEmpty()) {
            backfillSegmentRepository.saveAll(segments);
        }

        UserSession.IntegrationSyncState syncState = new UserSession.IntegrationSyncState();
        syncState.setTotalSegments(segments.size());
        syncState.setCompletedSegments(0);
        syncState.setFailedSegments(0);
        syncState.setProgressPct(0);
        integrationSyncStatusService.initialize(integration, segments.size());
        return syncState;
    }
}
