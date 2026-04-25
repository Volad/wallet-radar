package com.walletradar.ingestion.job.normalization;

import com.walletradar.domain.event.OnChainClarificationCompletedEvent;
import com.walletradar.domain.event.OnChainReclassificationCompletedEvent;
import com.walletradar.ingestion.config.OnChainNormalizationProperties;
import com.walletradar.ingestion.job.clarification.ClarificationBatchDrainer;
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

class OnChainReclassificationJobTest {

    @Test
    @DisplayName("manual reclassification publishes completion event after drained batches")
    void manualReclassificationPublishesCompletionEventAfterDrain() {
        OnChainNormalizationProperties properties = new OnChainNormalizationProperties();
        properties.setEnabled(true);
        OnChainReclassificationService service = mock(OnChainReclassificationService.class);
        when(service.processNextBatch()).thenReturn(2, 1, 0);
        List<Object> events = new ArrayList<>();

        OnChainReclassificationJob job = job(properties, service, events::add);

        int processed = job.runReclassification();

        assertThat(processed).isEqualTo(3);
        assertThat(events).singleElement().isInstanceOfSatisfying(OnChainReclassificationCompletedEvent.class, event -> {
            assertThat(event.processed()).isEqualTo(3);
            assertThat(event.trigger()).isEqualTo("manual");
        });
    }

    @Test
    @DisplayName("manual reclassification does not publish event when nothing was processed")
    void manualReclassificationDoesNotPublishEmptyCompletionEvent() {
        OnChainNormalizationProperties properties = new OnChainNormalizationProperties();
        properties.setEnabled(true);
        OnChainReclassificationService service = mock(OnChainReclassificationService.class);
        when(service.processNextBatch()).thenReturn(0);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);

        OnChainReclassificationJob job = job(properties, service, publisher);

        int processed = job.runReclassification();

        assertThat(processed).isZero();
        verify(publisher, never()).publishEvent(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("clarification completion publishes reclassification completion even for empty drain")
    void clarificationCompletionPublishesEmptyReclassificationCompletionEvent() {
        OnChainNormalizationProperties properties = new OnChainNormalizationProperties();
        properties.setEnabled(true);
        OnChainReclassificationService service = mock(OnChainReclassificationService.class);
        when(service.processNextBatch()).thenReturn(0);
        List<Object> events = new ArrayList<>();

        OnChainReclassificationJob job = job(properties, service, events::add);

        job.onOnChainClarificationCompleted(new OnChainClarificationCompletedEvent("session-1", 0, "clarification"));

        assertThat(events).singleElement().isInstanceOfSatisfying(OnChainReclassificationCompletedEvent.class, event -> {
            assertThat(event.sessionId()).isEqualTo("session-1");
            assertThat(event.processed()).isZero();
            assertThat(event.trigger()).isEqualTo("on-chain-clarification-completed");
        });
    }

    private static OnChainReclassificationJob job(
            OnChainNormalizationProperties properties,
            OnChainReclassificationService service,
            ApplicationEventPublisher publisher
    ) {
        return new OnChainReclassificationJob(
                properties,
                service,
                new ClarificationBatchDrainer(),
                publisher,
                mock(SessionPipelineActivityService.class),
                mock(SessionPipelineStateService.class)
        );
    }
}
