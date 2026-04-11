package com.walletradar.ingestion.job.linking;

import com.walletradar.ingestion.pipeline.clarification.AcrossBridgePairLinkService;
import com.walletradar.ingestion.pipeline.clarification.BybitTransferContinuityRepairService;
import com.walletradar.ingestion.pipeline.clarification.InternalTransferPairLinkService;
import com.walletradar.ingestion.pipeline.clarification.LiFiBridgePairLinkService;
import com.walletradar.ingestion.pipeline.clarification.MayanCctpBridgePairLinkService;
import com.walletradar.ingestion.pipeline.clarification.OnChainLifecycleLinkService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Dedicated driver for deterministic cross-row and cross-source linking passes.
 */
@Component
@RequiredArgsConstructor
class LinkingBatchProcessor {

    private final BybitBridgeLinkService bybitBridgeLinkService;
    private final OnChainLifecycleLinkService onChainLifecycleLinkService;
    private final LiFiBridgePairLinkService liFiBridgePairLinkService;
    private final MayanCctpBridgePairLinkService mayanCctpBridgePairLinkService;
    private final AcrossBridgePairLinkService acrossBridgePairLinkService;
    private final InternalTransferPairLinkService internalTransferPairLinkService;
    private final BybitTransferContinuityRepairService bybitTransferContinuityRepairService;

    int processNextBatch(int batchSize) {
        return processNextBatch(batchSize, () -> {
        });
    }

    int processNextBatch(int batchSize, Runnable progressHeartbeat) {
        int processed = 0;

        processed += bybitBridgeLinkService.reconcileOutstandingPairs(batchSize);
        progressHeartbeat.run();

        processed += onChainLifecycleLinkService.processNextBatch(batchSize);
        progressHeartbeat.run();

        processed += liFiBridgePairLinkService.reconcileOutstandingSources(batchSize);
        progressHeartbeat.run();

        processed += mayanCctpBridgePairLinkService.reconcileOutstandingSources(batchSize);
        progressHeartbeat.run();

        processed += acrossBridgePairLinkService.reconcileOutstandingSources(batchSize);
        progressHeartbeat.run();

        processed += internalTransferPairLinkService.reconcileOutstandingPairs(batchSize);
        progressHeartbeat.run();

        processed += bybitTransferContinuityRepairService.reconcileOutstandingPairs(batchSize);
        progressHeartbeat.run();

        return processed;
    }
}
