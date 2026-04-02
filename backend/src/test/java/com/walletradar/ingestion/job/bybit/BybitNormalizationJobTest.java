package com.walletradar.ingestion.job.bybit;

import com.walletradar.domain.event.BybitNormalizationCompletedEvent;
import com.walletradar.domain.event.OnChainClarificationCompletedEvent;
import com.walletradar.ingestion.config.BybitNormalizationProperties;
import com.walletradar.session.application.SessionPipelineStateService;
import com.walletradar.telemetry.PipelineTelemetrySnapshot;
import com.walletradar.telemetry.PipelineTelemetrySnapshotService;
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
        when(bybitNormalizationService.processNextBatch(25)).thenReturn(2, 1, 0);
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
        SessionPipelineStateService pipelineStateService = org.mockito.Mockito.mock(SessionPipelineStateService.class);
        BybitNormalizationJob job = new BybitNormalizationJob(properties, bybitNormalizationService, pipelineTelemetrySnapshotService, publisher, pipelineStateService);
        int processed = job.runNormalization();

        assertThat(processed).isEqualTo(3);
        verify(pipelineTelemetrySnapshotService).snapshot();
    }

    @Test
    void clarificationCompletionPublishesBybitCompletionEvenForEmptyDrain() {
        BybitNormalizationProperties properties = new BybitNormalizationProperties();
        properties.setEnabled(true);
        properties.setBatchSize(25);
        when(bybitNormalizationService.processNextBatch(25)).thenReturn(0);
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
        SessionPipelineStateService pipelineStateService = org.mockito.Mockito.mock(SessionPipelineStateService.class);
        BybitNormalizationJob job = new BybitNormalizationJob(properties, bybitNormalizationService, pipelineTelemetrySnapshotService, publisher, pipelineStateService);

        job.onOnChainClarificationCompleted(new OnChainClarificationCompletedEvent("session-1", 0, "normalization-completed"));

        assertThat(events).singleElement().isInstanceOfSatisfying(BybitNormalizationCompletedEvent.class, event -> {
            assertThat(event.sessionId()).isEqualTo("session-1");
            assertThat(event.processed()).isZero();
            assertThat(event.trigger()).isEqualTo("clarification-completed");
        });
    }
}
