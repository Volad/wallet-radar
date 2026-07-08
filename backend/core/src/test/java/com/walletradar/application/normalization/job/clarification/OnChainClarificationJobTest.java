package com.walletradar.application.normalization.job.clarification;

import com.walletradar.domain.event.OnChainClarificationCompletedEvent;
import com.walletradar.domain.event.OnChainNormalizationCompletedEvent;
import com.walletradar.application.normalization.config.OnChainClarificationProperties;
import com.walletradar.application.session.application.SessionPipelineActivityService;
import com.walletradar.application.session.application.SessionPipelineStateService;
import com.walletradar.application.pipeline.config.JobHeartbeatProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OnChainClarificationJobTest {

    @Test
    @DisplayName("clarification job drains unified full-receipt clarification once")
    void clarificationJobDrainsUnifiedFullReceiptClarificationOnce() {
        OnChainClarificationProperties properties = new OnChainClarificationProperties();
        properties.setEnabled(true);
        properties.setThreads(1);
        properties.getFullReceipt().setEnabled(true);

        OnChainClarificationService metadataService = mock(OnChainClarificationService.class);
        when(metadataService.processNextBatch()).thenReturn(2, 0);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        SessionPipelineActivityService pipelineActivityService = mock(SessionPipelineActivityService.class);
        SessionPipelineStateService pipelineStateService = mock(SessionPipelineStateService.class);

        OnChainClarificationJob job = new OnChainClarificationJob(
                new JobHeartbeatProperties(),
                properties,
                metadataService,
                new ClarificationBatchDrainer(),
                publisher,
                pipelineActivityService,
                pipelineStateService
        );

        int processed = job.runClarification();

        assertThat(processed).isEqualTo(2);
        verify(metadataService, times(2)).processNextBatch();
    }

    @Test
    @DisplayName("clarification job starts immediately on normalization completion event")
    void clarificationJobStartsOnNormalizationCompletionEvent() {
        OnChainClarificationProperties properties = new OnChainClarificationProperties();
        properties.setEnabled(true);
        properties.setThreads(1);
        properties.getFullReceipt().setEnabled(false);

        OnChainClarificationService metadataService = mock(OnChainClarificationService.class);
        when(metadataService.processNextBatch()).thenReturn(4, 0);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        SessionPipelineActivityService pipelineActivityService = mock(SessionPipelineActivityService.class);
        SessionPipelineStateService pipelineStateService = mock(SessionPipelineStateService.class);

        OnChainClarificationJob job = new OnChainClarificationJob(
                new JobHeartbeatProperties(),
                properties,
                metadataService,
                new ClarificationBatchDrainer(),
                publisher,
                pipelineActivityService,
                pipelineStateService
        );

        job.onOnChainNormalizationCompleted(new OnChainNormalizationCompletedEvent("session-1", 5, "manual"));

        verify(metadataService, times(2)).processNextBatch();
    }

    @Test
    @DisplayName("pipeline-triggered clarification publishes completion event even when no rows were processed")
    void clarificationPublishesCompletionEventForEmptyPipelineDrain() {
        OnChainClarificationProperties properties = new OnChainClarificationProperties();
        properties.setEnabled(true);
        properties.setThreads(1);
        properties.getFullReceipt().setEnabled(false);

        OnChainClarificationService metadataService = mock(OnChainClarificationService.class);
        when(metadataService.processNextBatch()).thenReturn(0);

        List<Object> events = new ArrayList<>();
        ApplicationEventPublisher publisher = events::add;
        SessionPipelineActivityService pipelineActivityService = mock(SessionPipelineActivityService.class);
        SessionPipelineStateService pipelineStateService = mock(SessionPipelineStateService.class);

        OnChainClarificationJob job = new OnChainClarificationJob(
                new JobHeartbeatProperties(),
                properties,
                metadataService,
                new ClarificationBatchDrainer(),
                publisher,
                pipelineActivityService,
                pipelineStateService
        );

        job.onOnChainNormalizationCompleted(new OnChainNormalizationCompletedEvent("session-1", 0, "session-backfill-completed"));

        assertThat(events).singleElement().isInstanceOfSatisfying(OnChainClarificationCompletedEvent.class, event -> {
            assertThat(event.sessionId()).isEqualTo("session-1");
            assertThat(event.processed()).isZero();
            assertThat(event.trigger()).isEqualTo("normalization-completed");
        });
    }
}
