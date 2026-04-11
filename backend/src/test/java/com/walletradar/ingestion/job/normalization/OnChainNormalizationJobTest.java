package com.walletradar.ingestion.job.normalization;

import com.walletradar.domain.event.OnChainNormalizationCompletedEvent;
import com.walletradar.domain.event.SessionBackfillCompletedEvent;
import com.walletradar.ingestion.config.OnChainNormalizationProperties;
import com.walletradar.session.application.SessionPipelineActivityService;
import com.walletradar.session.application.SessionPipelineStateService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OnChainNormalizationJobTest {

    @Test
    @DisplayName("normalization job publishes completion event after draining processed batches")
    void normalizationJobPublishesCompletionEvent() {
        OnChainNormalizationProperties properties = new OnChainNormalizationProperties();
        properties.setEnabled(true);

        OnChainNormalizationService normalizationService = mock(OnChainNormalizationService.class);
        when(normalizationService.processNextBatch()).thenReturn(2, 3, 0);

        List<Object> events = new ArrayList<>();
        ApplicationEventPublisher publisher = events::add;
        SessionPipelineActivityService pipelineActivityService = mock(SessionPipelineActivityService.class);
        SessionPipelineStateService pipelineStateService = mock(SessionPipelineStateService.class);

        OnChainNormalizationJob job = new OnChainNormalizationJob(
                properties,
                normalizationService,
                publisher,
                pipelineActivityService,
                pipelineStateService
        );

        int processed = job.runNormalization();

        assertThat(processed).isEqualTo(5);
        assertThat(events).singleElement().isInstanceOfSatisfying(OnChainNormalizationCompletedEvent.class, event -> {
            assertThat(event.processed()).isEqualTo(5);
            assertThat(event.trigger()).isEqualTo("manual");
        });
    }

    @Test
    @DisplayName("normalization job does not publish completion event when nothing was processed")
    void normalizationJobDoesNotPublishEventForEmptyDrain() {
        OnChainNormalizationProperties properties = new OnChainNormalizationProperties();
        properties.setEnabled(true);

        OnChainNormalizationService normalizationService = mock(OnChainNormalizationService.class);
        when(normalizationService.processNextBatch()).thenReturn(0);

        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        SessionPipelineActivityService pipelineActivityService = mock(SessionPipelineActivityService.class);
        SessionPipelineStateService pipelineStateService = mock(SessionPipelineStateService.class);

        OnChainNormalizationJob job = new OnChainNormalizationJob(
                properties,
                normalizationService,
                publisher,
                pipelineActivityService,
                pipelineStateService
        );

        int processed = job.runNormalization();

        assertThat(processed).isZero();
        verify(publisher, never()).publishEvent(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("live-session trigger publishes completion event even for empty drain")
    void liveSessionTriggerPublishesCompletionEventForEmptyDrain() {
        OnChainNormalizationProperties properties = new OnChainNormalizationProperties();
        properties.setEnabled(true);

        OnChainNormalizationService normalizationService = mock(OnChainNormalizationService.class);
        when(normalizationService.processNextBatch()).thenReturn(0);

        List<Object> events = new ArrayList<>();
        ApplicationEventPublisher publisher = events::add;
        SessionPipelineActivityService pipelineActivityService = mock(SessionPipelineActivityService.class);
        SessionPipelineStateService pipelineStateService = mock(SessionPipelineStateService.class);

        OnChainNormalizationJob job = new OnChainNormalizationJob(
                properties,
                normalizationService,
                publisher,
                pipelineActivityService,
                pipelineStateService
        );

        job.onSessionBackfillCompleted(new SessionBackfillCompletedEvent("session-1", 2, 26));

        assertThat(events).singleElement().isInstanceOfSatisfying(OnChainNormalizationCompletedEvent.class, event -> {
            assertThat(event.sessionId()).isEqualTo("session-1");
            assertThat(event.processed()).isZero();
            assertThat(event.trigger()).contains("session-backfill-completed:session-1");
        });
    }
}
