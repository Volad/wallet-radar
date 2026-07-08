package com.walletradar.integration;

import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.sync.BackfillSegment;
import com.walletradar.domain.sync.BackfillSegmentRepository;
import com.walletradar.application.session.application.IntegrationSyncStatusService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IntegrationBackfillPlanningServiceTest {

    @Mock
    private BackfillSegmentRepository backfillSegmentRepository;
    @Mock
    private IntegrationBackfillPlanner planner;
    @Mock
    private IntegrationSyncStatusService integrationSyncStatusService;

    @Test
    void persistsSegmentsThroughSharedPlanningService() {
        IntegrationBackfillPlanningService service = new IntegrationBackfillPlanningService(
                backfillSegmentRepository,
                List.of(planner),
                integrationSyncStatusService
        );

        UserSession.SessionIntegration integration = new UserSession.SessionIntegration();
        integration.setIntegrationId("BYBIT-33625378");
        integration.setProvider(UserSession.IntegrationProvider.BYBIT);
        integration.setAccountRef("BYBIT:33625378");

        BackfillSegment segment = new BackfillSegment();
        segment.setId("seg-1");
        segment.setSessionId("session-1");
        segment.setIntegrationId("BYBIT-33625378");
        segment.setSourceKind(BackfillSegment.SourceKind.INTEGRATION);

        when(planner.supports(UserSession.IntegrationProvider.BYBIT)).thenReturn(true);
        when(planner.planInitialBackfill(org.mockito.ArgumentMatchers.eq("session-1"), org.mockito.ArgumentMatchers.same(integration), org.mockito.ArgumentMatchers.any(Instant.class)))
                .thenReturn(List.of(segment));

        UserSession.IntegrationSyncState syncState = service.replanInitialBackfill("session-1", integration);

        verify(backfillSegmentRepository).deleteByIntegrationId("BYBIT-33625378");
        ArgumentCaptor<List<BackfillSegment>> captor = ArgumentCaptor.forClass(List.class);
        verify(backfillSegmentRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).containsExactly(segment);
        assertThat(syncState.getTotalSegments()).isEqualTo(1);
        assertThat(syncState.getCompletedSegments()).isZero();
        assertThat(syncState.getFailedSegments()).isZero();
        assertThat(syncState.getProgressPct()).isZero();
        verify(integrationSyncStatusService).initialize(integration, 1);
    }

    @Test
    void replansIncrementalSegmentsThroughSharedPlanningService() {
        IntegrationBackfillPlanningService service = new IntegrationBackfillPlanningService(
                backfillSegmentRepository,
                List.of(planner),
                integrationSyncStatusService
        );

        UserSession.SessionIntegration integration = new UserSession.SessionIntegration();
        integration.setIntegrationId("BYBIT-33625378");
        integration.setProvider(UserSession.IntegrationProvider.BYBIT);
        integration.setAccountRef("BYBIT:33625378");

        BackfillSegment segment = new BackfillSegment();
        segment.setId("seg-2");
        segment.setSessionId("session-1");
        segment.setIntegrationId("BYBIT-33625378");
        segment.setSourceKind(BackfillSegment.SourceKind.INTEGRATION);

        when(planner.supports(UserSession.IntegrationProvider.BYBIT)).thenReturn(true);
        when(planner.planIncrementalBackfill(
                org.mockito.ArgumentMatchers.eq("session-1"),
                org.mockito.ArgumentMatchers.same(integration),
                org.mockito.ArgumentMatchers.eq(Instant.parse("2026-04-10T09:00:00Z")),
                org.mockito.ArgumentMatchers.eq(Instant.parse("2026-04-10T10:00:00Z")),
                org.mockito.ArgumentMatchers.any(Instant.class)
        )).thenReturn(List.of(segment));

        UserSession.IntegrationSyncState syncState = service.replanIncrementalBackfill(
                "session-1",
                integration,
                Instant.parse("2026-04-10T09:00:00Z"),
                Instant.parse("2026-04-10T10:00:00Z")
        );

        verify(backfillSegmentRepository).deleteByIntegrationId("BYBIT-33625378");
        verify(backfillSegmentRepository).saveAll(List.of(segment));
        assertThat(syncState.getTotalSegments()).isEqualTo(1);
        verify(integrationSyncStatusService).initialize(integration, 1);
    }
}
