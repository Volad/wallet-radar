package com.walletradar.ingestion.job.clarification;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Thin service facade for the metadata clarification workflow.
 */
@Service
public class OnChainClarificationService {

    private final MetadataClarificationWorkflowHandler workflowHandler;

    @Autowired
    public OnChainClarificationService(
            MetadataClarificationWorkflowHandler workflowHandler
    ) {
        this.workflowHandler = workflowHandler;
    }

    public int processNextBatch() {
        return workflowHandler.processNextBatch();
    }

    public int processConfirmedFluidReceiptBatch() {
        return workflowHandler.processConfirmedFluidReceiptBatch();
    }

    public boolean clarify(NormalizedTransaction normalizedTransaction) {
        return workflowHandler.clarify(normalizedTransaction);
    }
}
