package com.walletradar.ingestion.job.clarification;

import com.walletradar.domain.event.OnChainNormalizationCompletedEvent;
import com.walletradar.ingestion.config.OnChainClarificationProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
        when(metadataService.processNextBatch()).thenReturn(2, 0);
        when(receiptService.processNextBatch()).thenReturn(3, 0);

        OnChainClarificationJob job = new OnChainClarificationJob(properties, metadataService, receiptService);

        int processed = job.runClarification();

        assertThat(processed).isEqualTo(5);
        verify(metadataService, times(2)).processNextBatch();
        verify(receiptService, times(2)).processNextBatch();
    }

    @Test
    @DisplayName("clarification job skips full receipt pass when it is disabled")
    void clarificationJobSkipsFullReceiptPassWhenItIsDisabled() {
        OnChainClarificationProperties properties = new OnChainClarificationProperties();
        properties.setEnabled(true);
        properties.getFullReceipt().setEnabled(false);

        OnChainClarificationService metadataService = mock(OnChainClarificationService.class);
        OnChainReceiptClarificationService receiptService = mock(OnChainReceiptClarificationService.class);
        when(metadataService.processNextBatch()).thenReturn(1, 0);

        OnChainClarificationJob job = new OnChainClarificationJob(properties, metadataService, receiptService);

        int processed = job.runClarification();

        assertThat(processed).isEqualTo(1);
        verify(metadataService, times(2)).processNextBatch();
        verify(receiptService, never()).processNextBatch();
    }

    @Test
    @DisplayName("clarification job starts immediately on normalization completion event")
    void clarificationJobStartsOnNormalizationCompletionEvent() {
        OnChainClarificationProperties properties = new OnChainClarificationProperties();
        properties.setEnabled(true);
        properties.getFullReceipt().setEnabled(false);

        OnChainClarificationService metadataService = mock(OnChainClarificationService.class);
        OnChainReceiptClarificationService receiptService = mock(OnChainReceiptClarificationService.class);
        when(metadataService.processNextBatch()).thenReturn(4, 0);

        OnChainClarificationJob job = new OnChainClarificationJob(properties, metadataService, receiptService);

        job.onOnChainNormalizationCompleted(new OnChainNormalizationCompletedEvent(5, "manual"));

        verify(metadataService, times(2)).processNextBatch();
        verify(receiptService, never()).processNextBatch();
    }
}
