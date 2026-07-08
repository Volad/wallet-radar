package com.walletradar.application.normalization.job.clarification;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OnChainClarificationServiceTest {

    @Test
    @DisplayName("metadata clarification service delegates batch processing to workflow handler")
    void metadataClarificationServiceDelegatesBatchProcessingToWorkflowHandler() {
        MetadataClarificationWorkflowHandler workflowHandler = mock(MetadataClarificationWorkflowHandler.class);
        when(workflowHandler.processNextBatch()).thenReturn(7);

        OnChainClarificationService service = new OnChainClarificationService(workflowHandler);

        assertThat(service.processNextBatch()).isEqualTo(7);
        verify(workflowHandler).processNextBatch();
    }

    @Test
    @DisplayName("metadata clarification service delegates row clarification to workflow handler")
    void metadataClarificationServiceDelegatesRowClarificationToWorkflowHandler() {
        MetadataClarificationWorkflowHandler workflowHandler = mock(MetadataClarificationWorkflowHandler.class);
        NormalizedTransaction normalizedTransaction = new NormalizedTransaction();
        when(workflowHandler.clarify(normalizedTransaction)).thenReturn(true);

        OnChainClarificationService service = new OnChainClarificationService(workflowHandler);

        assertThat(service.clarify(normalizedTransaction)).isTrue();
        verify(workflowHandler).clarify(normalizedTransaction);
    }
}
