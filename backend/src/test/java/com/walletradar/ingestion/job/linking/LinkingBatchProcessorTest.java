package com.walletradar.ingestion.job.linking;

import com.walletradar.ingestion.pipeline.clarification.AcrossBridgePairLinkService;
import com.walletradar.ingestion.pipeline.clarification.BybitTransferContinuityRepairService;
import com.walletradar.ingestion.pipeline.clarification.InternalTransferPairLinkService;
import com.walletradar.ingestion.pipeline.clarification.LiFiBridgePairLinkService;
import com.walletradar.ingestion.pipeline.clarification.MayanCctpBridgePairLinkService;
import com.walletradar.ingestion.pipeline.clarification.OnChainLifecycleLinkService;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LinkingBatchProcessorTest {

    @Test
    void heartbeatRunsAfterEveryLinkingSubstep() {
        BybitBridgeLinkService bybitBridgeLinkService = mock(BybitBridgeLinkService.class);
        OnChainLifecycleLinkService onChainLifecycleLinkService = mock(OnChainLifecycleLinkService.class);
        LiFiBridgePairLinkService liFiBridgePairLinkService = mock(LiFiBridgePairLinkService.class);
        MayanCctpBridgePairLinkService mayanCctpBridgePairLinkService = mock(MayanCctpBridgePairLinkService.class);
        AcrossBridgePairLinkService acrossBridgePairLinkService = mock(AcrossBridgePairLinkService.class);
        InternalTransferPairLinkService internalTransferPairLinkService = mock(InternalTransferPairLinkService.class);
        BybitTransferContinuityRepairService bybitTransferContinuityRepairService = mock(BybitTransferContinuityRepairService.class);

        when(bybitBridgeLinkService.reconcileOutstandingPairs(25)).thenReturn(2);
        when(onChainLifecycleLinkService.processNextBatch(25)).thenReturn(3);
        when(liFiBridgePairLinkService.reconcileOutstandingSources(25)).thenReturn(5);
        when(mayanCctpBridgePairLinkService.reconcileOutstandingSources(25)).thenReturn(7);
        when(acrossBridgePairLinkService.reconcileOutstandingSources(25)).thenReturn(11);
        when(internalTransferPairLinkService.reconcileOutstandingPairs(25)).thenReturn(13);
        when(bybitTransferContinuityRepairService.reconcileOutstandingPairs(25)).thenReturn(17);

        LinkingBatchProcessor processor = new LinkingBatchProcessor(
                bybitBridgeLinkService,
                onChainLifecycleLinkService,
                liFiBridgePairLinkService,
                mayanCctpBridgePairLinkService,
                acrossBridgePairLinkService,
                internalTransferPairLinkService,
                bybitTransferContinuityRepairService
        );

        AtomicInteger heartbeatCount = new AtomicInteger();
        int processed = processor.processNextBatch(25, heartbeatCount::incrementAndGet);

        assertThat(processed).isEqualTo(58);
        assertThat(heartbeatCount).hasValue(7);
    }
}
