package com.walletradar.ingestion.job.clarification;

import com.walletradar.ingestion.pipeline.clarification.CounterpartyEnrichmentService;
import com.walletradar.ingestion.pipeline.clarification.ProtocolNameEnrichmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Job-layer facade for clarification-adjacent metadata enrichment batches.
 */
@Service
@RequiredArgsConstructor
public class OnChainMetadataEnrichmentService {

    private final ProtocolNameEnrichmentService protocolNameEnrichmentService;
    private final CounterpartyEnrichmentService counterpartyEnrichmentService;

    public int processNextBatch(int batchSize) {
        int boundedBatchSize = Math.max(1, batchSize);
        return protocolNameEnrichmentService.processNextBatch(boundedBatchSize)
                + counterpartyEnrichmentService.processNextBatch(boundedBatchSize);
    }
}
