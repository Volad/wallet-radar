package com.walletradar.ingestion.job.clarification;

import org.springframework.stereotype.Component;

import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * Shared batch-drain loop for clarification stages that process bounded batches until empty.
 */
@Component
public final class ClarificationBatchDrainer {

    public int drain(IntSupplier batchProcessor) {
        return drain(batchProcessor, ignored -> {
        });
    }

    public int drain(IntSupplier batchProcessor, IntConsumer afterBatch) {
        int processed = 0;
        while (true) {
            int batchProcessed = batchProcessor.getAsInt();
            processed += batchProcessed;
            afterBatch.accept(batchProcessed);
            if (batchProcessed == 0) {
                return processed;
            }
        }
    }
}
