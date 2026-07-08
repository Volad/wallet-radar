package com.walletradar.application.cex.job.bybit;

import com.walletradar.domain.event.BybitNormalizationCompletedEvent;
import com.walletradar.domain.event.SessionBackfillCompletedEvent;
import com.walletradar.application.normalization.config.BybitNormalizationProperties;
import com.walletradar.session.application.SessionPipelineActivityService;
import com.walletradar.session.application.SessionPipelineStateService;
import com.walletradar.platform.telemetry.PipelineTelemetrySnapshot;
import com.walletradar.platform.telemetry.PipelineTelemetrySnapshotService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BybitNormalizationJobTest {

    @Mock
    private BybitNormalizationService bybitNormalizationService;
    @Mock
    private PipelineTelemetrySnapshotService pipelineTelemetrySnapshotService;

    @Test
    void runNormalizationProcessesUntilQueueIsEmpty() {
        BybitNormalizationProperties properties = new BybitNormalizationProperties();
        properties.setEnabled(true);
        properties.setBatchSize(25);
        when(bybitNormalizationService.processNextBatch(25, null)).thenReturn(2, 1, 0);
        when(pipelineTelemetrySnapshotService.snapshot()).thenReturn(new PipelineTelemetrySnapshot(
                10L,
                3L,
                0L,
                1L,
                1L,
                2L,
                0L,
                2L
        ));

        ApplicationEventPublisher publisher = org.mockito.Mockito.mock(ApplicationEventPublisher.class);
        SessionPipelineActivityService pipelineActivityService = org.mockito.Mockito.mock(SessionPipelineActivityService.class);
        SessionPipelineStateService pipelineStateService = org.mockito.Mockito.mock(SessionPipelineStateService.class);
        BybitNormalizationJob job = new BybitNormalizationJob(
                properties,
                bybitNormalizationService,
                pipelineTelemetrySnapshotService,
                publisher,
                pipelineActivityService,
                pipelineStateService
        );
        int processed = job.runNormalization();

        assertThat(processed).isEqualTo(3);
        verify(pipelineTelemetrySnapshotService).snapshot();
    }

    @Test
    void sessionBackfillCompletionPublishesBybitCompletionEvenForEmptyDrain() {
        BybitNormalizationProperties properties = new BybitNormalizationProperties();
        properties.setEnabled(true);
        properties.setBatchSize(25);
        when(bybitNormalizationService.processNextBatch(25, "session-1")).thenReturn(0);
        when(pipelineTelemetrySnapshotService.snapshot()).thenReturn(new PipelineTelemetrySnapshot(
                10L,
                3L,
                0L,
                1L,
                1L,
                2L,
                0L,
                2L
        ));

        List<Object> events = new ArrayList<>();
        ApplicationEventPublisher publisher = events::add;
        SessionPipelineActivityService pipelineActivityService = org.mockito.Mockito.mock(SessionPipelineActivityService.class);
        SessionPipelineStateService pipelineStateService = org.mockito.Mockito.mock(SessionPipelineStateService.class);
        BybitNormalizationJob job = new BybitNormalizationJob(
                properties,
                bybitNormalizationService,
                pipelineTelemetrySnapshotService,
                publisher,
                pipelineActivityService,
                pipelineStateService
        );

        job.onSessionBackfillCompleted(new SessionBackfillCompletedEvent("session-1", 1, 1));

        assertThat(events).singleElement().isInstanceOfSatisfying(BybitNormalizationCompletedEvent.class, event -> {
            assertThat(event.sessionId()).isEqualTo("session-1");
            assertThat(event.processed()).isZero();
            assertThat(event.trigger()).isEqualTo("session-backfill-completed");
        });
    }
}
