package com.walletradar.application.normalization.job.clarification;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OnChainReceiptClarificationServiceTest {

    @Test
    @DisplayName("receipt clarification service delegates batch processing to workflow handler")
    void receiptClarificationServiceDelegatesBatchProcessingToWorkflowHandler() {
        ReceiptClarificationWorkflowHandler workflowHandler = mock(ReceiptClarificationWorkflowHandler.class);
        when(workflowHandler.processNextBatch()).thenReturn(5);

        OnChainReceiptClarificationService service = new OnChainReceiptClarificationService(workflowHandler);

        assertThat(service.processNextBatch()).isEqualTo(5);
        verify(workflowHandler).processNextBatch();
    }

    @Test
    @DisplayName("receipt clarification service delegates row clarification to workflow handler")
    void receiptClarificationServiceDelegatesRowClarificationToWorkflowHandler() {
        ReceiptClarificationWorkflowHandler workflowHandler = mock(ReceiptClarificationWorkflowHandler.class);
        NormalizedTransaction normalizedTransaction = new NormalizedTransaction();
        when(workflowHandler.clarify(normalizedTransaction)).thenReturn(true);

        OnChainReceiptClarificationService service = new OnChainReceiptClarificationService(workflowHandler);

        assertThat(service.clarify(normalizedTransaction)).isTrue();
        verify(workflowHandler).clarify(normalizedTransaction);
    }
}
