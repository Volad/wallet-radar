package com.walletradar.application.normalization.job.clarification;

import com.walletradar.application.linking.pipeline.clarification.CounterpartyEnrichmentService;
import com.walletradar.application.linking.pipeline.clarification.ProtocolNameEnrichmentService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OnChainMetadataEnrichmentServiceTest {

    @Test
    void processNextBatchDelegatesToProtocolAndCounterpartyEnrichment() {
        ProtocolNameEnrichmentService protocolNameEnrichmentService = mock(ProtocolNameEnrichmentService.class);
        CounterpartyEnrichmentService counterpartyEnrichmentService = mock(CounterpartyEnrichmentService.class);
        when(protocolNameEnrichmentService.processNextBatch(25)).thenReturn(2);
        when(counterpartyEnrichmentService.processNextBatch(25)).thenReturn(3);

        OnChainMetadataEnrichmentService service = new OnChainMetadataEnrichmentService(
                protocolNameEnrichmentService,
                counterpartyEnrichmentService
        );

        int processed = service.processNextBatch(25);

        assertThat(processed).isEqualTo(5);
        verify(protocolNameEnrichmentService).processNextBatch(25);
        verify(counterpartyEnrichmentService).processNextBatch(25);
    }
}
