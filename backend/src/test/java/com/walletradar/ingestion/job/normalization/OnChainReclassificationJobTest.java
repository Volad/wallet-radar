package com.walletradar.ingestion.job.normalization;

import com.walletradar.domain.event.OnChainClarificationCompletedEvent;
import com.walletradar.domain.event.OnChainReclassificationCompletedEvent;
import com.walletradar.ingestion.config.OnChainNormalizationProperties;
import com.walletradar.ingestion.job.clarification.ClarificationBatchDrainer;
import com.walletradar.ingestion.job.clarification.OnChainClarificationService;
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
        when(service.processNextBatch(org.mockito.ArgumentMatchers.nullable(String.class))).thenReturn(2, 1, 0);
        OnChainClarificationService clarificationService = mock(OnChainClarificationService.class);
        when(clarificationService.processConfirmedFluidReceiptBatch()).thenReturn(0);
        List<Object> events = new ArrayList<>();

        OnChainReclassificationJob job = job(properties, service, clarificationService, events::add);

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
        when(service.processNextBatch(org.mockito.ArgumentMatchers.nullable(String.class))).thenReturn(0);
        OnChainClarificationService clarificationService = mock(OnChainClarificationService.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);

        OnChainReclassificationJob job = job(properties, service, clarificationService, publisher);

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
        when(service.processNextBatch(org.mockito.ArgumentMatchers.nullable(String.class))).thenReturn(0);
        OnChainClarificationService clarificationService = mock(OnChainClarificationService.class);
        List<Object> events = new ArrayList<>();

        OnChainReclassificationJob job = job(properties, service, clarificationService, events::add);

        job.onOnChainClarificationCompleted(new OnChainClarificationCompletedEvent("session-1", 0, "clarification"));

        assertThat(events).singleElement().isInstanceOfSatisfying(OnChainReclassificationCompletedEvent.class, event -> {
            assertThat(event.sessionId()).isEqualTo("session-1");
            assertThat(event.processed()).isZero();
            assertThat(event.trigger()).isEqualTo("on-chain-clarification-completed");
        });
    }

    @Test
    @DisplayName("post-reclassification Fluid recovery delays final completion until recovered rows are reclassified")
    void postReclassificationFluidRecoveryDelaysFinalCompletionUntilRecoveredRowsAreReclassified() {
        OnChainNormalizationProperties properties = new OnChainNormalizationProperties();
        properties.setEnabled(true);
        OnChainReclassificationService service = mock(OnChainReclassificationService.class);
        when(service.processNextBatch(org.mockito.ArgumentMatchers.nullable(String.class))).thenReturn(5, 0);
        OnChainClarificationService clarificationService = mock(OnChainClarificationService.class);
        when(clarificationService.processConfirmedFluidReceiptBatch()).thenReturn(2);
        List<Object> events = new ArrayList<>();

        OnChainReclassificationJob job = job(properties, service, clarificationService, events::add);

        int processed = job.runReclassification();

        assertThat(processed).isEqualTo(5);
        assertThat(events).singleElement().isInstanceOfSatisfying(OnChainClarificationCompletedEvent.class, event -> {
            assertThat(event.processed()).isEqualTo(2);
            assertThat(event.trigger()).isEqualTo("post-reclassification-fluid-recovery");
        });
    }

    @Test
    @DisplayName("post-reclassification recovery trigger publishes final reclassification completion")
    void postReclassificationRecoveryTriggerPublishesFinalReclassificationCompletion() {
        OnChainNormalizationProperties properties = new OnChainNormalizationProperties();
        properties.setEnabled(true);
        OnChainReclassificationService service = mock(OnChainReclassificationService.class);
        when(service.processNextBatch(org.mockito.ArgumentMatchers.nullable(String.class))).thenReturn(2, 0);
        OnChainClarificationService clarificationService = mock(OnChainClarificationService.class);
        List<Object> events = new ArrayList<>();

        OnChainReclassificationJob job = job(properties, service, clarificationService, events::add);

        job.onOnChainClarificationCompleted(new OnChainClarificationCompletedEvent(
                "session-1",
                2,
                "post-reclassification-fluid-recovery"
        ));

        assertThat(events).singleElement().isInstanceOfSatisfying(OnChainReclassificationCompletedEvent.class, event -> {
            assertThat(event.sessionId()).isEqualTo("session-1");
            assertThat(event.processed()).isEqualTo(2);
            assertThat(event.trigger()).isEqualTo("post-reclassification-fluid-recovery");
        });
        verify(clarificationService, never()).processConfirmedFluidReceiptBatch();
    }

    private static OnChainReclassificationJob job(
            OnChainNormalizationProperties properties,
            OnChainReclassificationService service,
            ApplicationEventPublisher publisher
    ) {
        return job(properties, service, mock(OnChainClarificationService.class), publisher);
    }

    private static OnChainReclassificationJob job(
            OnChainNormalizationProperties properties,
            OnChainReclassificationService service,
            OnChainClarificationService clarificationService,
            ApplicationEventPublisher publisher
    ) {
        return new OnChainReclassificationJob(
                properties,
                service,
                clarificationService,
                new ClarificationBatchDrainer(),
                publisher,
                mock(SessionPipelineActivityService.class),
                mock(SessionPipelineStateService.class)
        );
    }
}
