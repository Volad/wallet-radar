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
        return replacePlan(
                sessionId,
                integration,
                plannerFor(integration),
                planner -> planner.planInitialBackfill(sessionId, integration, Instant.now())
        );
    }

    public UserSession.IntegrationSyncState replanIncrementalBackfill(
            String sessionId,
            UserSession.SessionIntegration integration,
            Instant from,
            Instant to
    ) {
        if (integration == null || integration.getProvider() == null) {
            throw new IllegalArgumentException("Integration provider is required");
        }
        Instant plannedAt = Instant.now();
        return replacePlan(
                sessionId,
                integration,
                plannerFor(integration),
                planner -> planner.planIncrementalBackfill(sessionId, integration, from, to, plannedAt)
        );
    }

    public UserSession.IntegrationSyncState replanWindowBackfill(
            String sessionId,
            UserSession.SessionIntegration integration,
            Instant from,
            Instant to,
            String syncStatusId
    ) {
        if (integration == null || integration.getProvider() == null) {
            throw new IllegalArgumentException("Integration provider is required");
        }
        Instant plannedAt = Instant.now();
        return replacePlan(
                sessionId,
                integration,
                plannerFor(integration),
                planner -> attachSyncStatusId(
                        planner.planIncrementalBackfill(sessionId, integration, from, to, plannedAt),
                        syncStatusId
                )
        );
    }

    private UserSession.IntegrationSyncState replacePlan(
            String sessionId,
            UserSession.SessionIntegration integration,
            IntegrationBackfillPlanner planner,
            java.util.function.Function<IntegrationBackfillPlanner, List<BackfillSegment>> planFactory
    ) {
        if (integration == null || integration.getProvider() == null) {
            throw new IllegalArgumentException("Integration provider is required");
        }

        backfillSegmentRepository.deleteByIntegrationId(integration.getIntegrationId());

        List<BackfillSegment> segments = planFactory.apply(planner);
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

    private IntegrationBackfillPlanner plannerFor(UserSession.SessionIntegration integration) {
        return planners.stream()
                .filter(candidate -> candidate.supports(integration.getProvider()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No integration backfill planner registered for provider " + integration.getProvider()
                ));
    }

    private List<BackfillSegment> attachSyncStatusId(List<BackfillSegment> segments, String syncStatusId) {
        if (segments == null || segments.isEmpty() || syncStatusId == null || syncStatusId.isBlank()) {
            return segments == null ? List.of() : segments;
        }
        for (BackfillSegment segment : segments) {
            if (segment != null) {
                segment.setSyncStatusId(syncStatusId);
            }
        }
        return segments;
    }
}
