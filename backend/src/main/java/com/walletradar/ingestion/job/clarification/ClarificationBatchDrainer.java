package com.walletradar.ingestion.job.clarification;

import org.springframework.stereotype.Component;

import java.util.function.IntSupplier;

/**
 * Shared batch-drain loop for clarification stages that process bounded batches until empty.
 */
@Component
final class ClarificationBatchDrainer {

    int drain(IntSupplier batchProcessor) {
        int processed = 0;
        while (true) {
            int batchProcessed = batchProcessor.getAsInt();
            processed += batchProcessed;
            if (batchProcessed == 0) {
                return processed;
            }
        }
    }
}
