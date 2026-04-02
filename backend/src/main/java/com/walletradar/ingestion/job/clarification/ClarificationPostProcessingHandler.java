package com.walletradar.ingestion.job.clarification;

import com.walletradar.ingestion.pipeline.clarification.AcrossBridgePairLinkService;
import com.walletradar.ingestion.pipeline.clarification.LiFiBridgePairLinkService;
import com.walletradar.ingestion.pipeline.clarification.MayanCctpBridgePairLinkService;
import org.springframework.stereotype.Component;

/**
 * Job-layer post-processing wrapper for clarification-adjacent reconciliation passes.
 */
@Component
final class ClarificationPostProcessingHandler {

    private final LiFiBridgePairLinkService liFiBridgePairLinkService;
    private final MayanCctpBridgePairLinkService mayanCctpBridgePairLinkService;
    private final AcrossBridgePairLinkService acrossBridgePairLinkService;

    ClarificationPostProcessingHandler(
            LiFiBridgePairLinkService liFiBridgePairLinkService,
            MayanCctpBridgePairLinkService mayanCctpBridgePairLinkService,
            AcrossBridgePairLinkService acrossBridgePairLinkService
    ) {
        this.liFiBridgePairLinkService = liFiBridgePairLinkService;
        this.mayanCctpBridgePairLinkService = mayanCctpBridgePairLinkService;
        this.acrossBridgePairLinkService = acrossBridgePairLinkService;
    }

    int reconcileBridgePairs(int batchSize) {
        return liFiBridgePairLinkService.reconcileOutstandingSources(batchSize)
                + mayanCctpBridgePairLinkService.reconcileOutstandingSources(batchSize)
                + acrossBridgePairLinkService.reconcileOutstandingSources(batchSize);
    }
}
