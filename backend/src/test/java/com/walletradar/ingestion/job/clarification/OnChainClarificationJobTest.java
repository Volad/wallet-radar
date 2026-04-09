package com.walletradar.ingestion.job.clarification;

import com.walletradar.domain.event.OnChainClarificationCompletedEvent;
import com.walletradar.domain.event.OnChainNormalizationCompletedEvent;
import com.walletradar.ingestion.config.OnChainClarificationProperties;
import com.walletradar.ingestion.pipeline.clarification.ProtocolNameEnrichmentService;
import com.walletradar.session.application.SessionPipelineStateService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OnChainClarificationJobTest {

    @Test
    @DisplayName("clarification job drains metadata and full receipt passes when both are enabled")
    void clarificationJobDrainsMetadataAndFullReceiptPassesWhenBothAreEnabled() {
        OnChainClarificationProperties properties = new OnChainClarificationProperties();
        properties.setEnabled(true);
        properties.getFullReceipt().setEnabled(true);

        OnChainClarificationService metadataService = mock(OnChainClarificationService.class);
        OnChainReceiptClarificationService receiptService = mock(OnChainReceiptClarificationService.class);
        ClarificationPostProcessingHandler clarificationPostProcessingHandler = mock(ClarificationPostProcessingHandler.class);
        ProtocolNameEnrichmentService protocolNameEnrichmentService = mock(ProtocolNameEnrichmentService.class);
        when(metadataService.processNextBatch()).thenReturn(2, 0);
        when(receiptService.processNextBatch()).thenReturn(3, 0);
        when(clarificationPostProcessingHandler.reconcileBridgePairs(500)).thenReturn(1);
        when(protocolNameEnrichmentService.processNextBatch(100)).thenReturn(4, 0);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        SessionPipelineStateService pipelineStateService = mock(SessionPipelineStateService.class);

        OnChainClarificationJob job = new OnChainClarificationJob(
                properties,
                metadataService,
                receiptService,
                new ClarificationBatchDrainer(),
                clarificationPostProcessingHandler,
                protocolNameEnrichmentService,
                publisher,
                pipelineStateService
        );

        int processed = job.runClarification();

        assertThat(processed).isEqualTo(10);
        verify(metadataService, times(2)).processNextBatch();
        verify(receiptService, times(2)).processNextBatch();
        verify(clarificationPostProcessingHandler).reconcileBridgePairs(500);
        verify(protocolNameEnrichmentService, times(2)).processNextBatch(100);
    }

    @Test
    @DisplayName("clarification job skips full receipt pass when it is disabled")
    void clarificationJobSkipsFullReceiptPassWhenItIsDisabled() {
        OnChainClarificationProperties properties = new OnChainClarificationProperties();
        properties.setEnabled(true);
        properties.getFullReceipt().setEnabled(false);

        OnChainClarificationService metadataService = mock(OnChainClarificationService.class);
        OnChainReceiptClarificationService receiptService = mock(OnChainReceiptClarificationService.class);
        ClarificationPostProcessingHandler clarificationPostProcessingHandler = mock(ClarificationPostProcessingHandler.class);
        ProtocolNameEnrichmentService protocolNameEnrichmentService = mock(ProtocolNameEnrichmentService.class);
        when(metadataService.processNextBatch()).thenReturn(1, 0);
        when(clarificationPostProcessingHandler.reconcileBridgePairs(500)).thenReturn(0);
        when(protocolNameEnrichmentService.processNextBatch(100)).thenReturn(0);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        SessionPipelineStateService pipelineStateService = mock(SessionPipelineStateService.class);

        OnChainClarificationJob job = new OnChainClarificationJob(
                properties,
                metadataService,
                receiptService,
                new ClarificationBatchDrainer(),
                clarificationPostProcessingHandler,
                protocolNameEnrichmentService,
                publisher,
                pipelineStateService
        );

        int processed = job.runClarification();

        assertThat(processed).isEqualTo(1);
        verify(metadataService, times(2)).processNextBatch();
        verify(receiptService, never()).processNextBatch();
        verify(clarificationPostProcessingHandler).reconcileBridgePairs(500);
        verify(protocolNameEnrichmentService, times(1)).processNextBatch(100);
    }

    @Test
    @DisplayName("clarification job starts immediately on normalization completion event")
    void clarificationJobStartsOnNormalizationCompletionEvent() {
        OnChainClarificationProperties properties = new OnChainClarificationProperties();
        properties.setEnabled(true);
        properties.getFullReceipt().setEnabled(false);

        OnChainClarificationService metadataService = mock(OnChainClarificationService.class);
        OnChainReceiptClarificationService receiptService = mock(OnChainReceiptClarificationService.class);
        ClarificationPostProcessingHandler clarificationPostProcessingHandler = mock(ClarificationPostProcessingHandler.class);
        ProtocolNameEnrichmentService protocolNameEnrichmentService = mock(ProtocolNameEnrichmentService.class);
        when(metadataService.processNextBatch()).thenReturn(4, 0);
        when(clarificationPostProcessingHandler.reconcileBridgePairs(500)).thenReturn(2);
        when(protocolNameEnrichmentService.processNextBatch(100)).thenReturn(1, 0);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        SessionPipelineStateService pipelineStateService = mock(SessionPipelineStateService.class);

        OnChainClarificationJob job = new OnChainClarificationJob(
                properties,
                metadataService,
                receiptService,
                new ClarificationBatchDrainer(),
                clarificationPostProcessingHandler,
                protocolNameEnrichmentService,
                publisher,
                pipelineStateService
        );

        job.onOnChainNormalizationCompleted(new OnChainNormalizationCompletedEvent("session-1", 5, "manual"));

        verify(metadataService, times(2)).processNextBatch();
        verify(receiptService, never()).processNextBatch();
        verify(clarificationPostProcessingHandler).reconcileBridgePairs(500);
        verify(protocolNameEnrichmentService, times(2)).processNextBatch(100);
    }

    @Test
    @DisplayName("pipeline-triggered clarification publishes completion event even when no rows were processed")
    void clarificationPublishesCompletionEventForEmptyPipelineDrain() {
        OnChainClarificationProperties properties = new OnChainClarificationProperties();
        properties.setEnabled(true);
        properties.getFullReceipt().setEnabled(false);

        OnChainClarificationService metadataService = mock(OnChainClarificationService.class);
        OnChainReceiptClarificationService receiptService = mock(OnChainReceiptClarificationService.class);
        ClarificationPostProcessingHandler clarificationPostProcessingHandler = mock(ClarificationPostProcessingHandler.class);
        ProtocolNameEnrichmentService protocolNameEnrichmentService = mock(ProtocolNameEnrichmentService.class);
        when(metadataService.processNextBatch()).thenReturn(0);
        when(clarificationPostProcessingHandler.reconcileBridgePairs(500)).thenReturn(0);
        when(protocolNameEnrichmentService.processNextBatch(100)).thenReturn(0);

        List<Object> events = new ArrayList<>();
        ApplicationEventPublisher publisher = events::add;
        SessionPipelineStateService pipelineStateService = mock(SessionPipelineStateService.class);

        OnChainClarificationJob job = new OnChainClarificationJob(
                properties,
                metadataService,
                receiptService,
                new ClarificationBatchDrainer(),
                clarificationPostProcessingHandler,
                protocolNameEnrichmentService,
                publisher,
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
