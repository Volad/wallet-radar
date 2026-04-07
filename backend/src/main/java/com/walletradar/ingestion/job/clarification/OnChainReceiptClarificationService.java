package com.walletradar.ingestion.job.clarification;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Thin service facade for the full-receipt clarification workflow.
 */
@Service
public class OnChainReceiptClarificationService {

    private final ReceiptClarificationWorkflowHandler workflowHandler;

    @Autowired
    public OnChainReceiptClarificationService(
            ReceiptClarificationWorkflowHandler workflowHandler
    ) {
        this.workflowHandler = workflowHandler;
    }

    public int processNextBatch() {
        return workflowHandler.processNextBatch();
    }

    public boolean clarify(NormalizedTransaction normalizedTransaction) {
        return workflowHandler.clarify(normalizedTransaction);
    }
}
