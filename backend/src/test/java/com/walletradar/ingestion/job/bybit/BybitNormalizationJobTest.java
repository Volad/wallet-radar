package com.walletradar.ingestion.job.bybit;

import com.walletradar.ingestion.config.BybitNormalizationProperties;
import com.walletradar.telemetry.PipelineTelemetrySnapshot;
import com.walletradar.telemetry.PipelineTelemetrySnapshotService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BybitNormalizationJobTest {

    @Mock
    private BybitNormalizationService bybitNormalizationService;
    @Mock
    private PipelineTelemetrySnapshotService pipelineTelemetrySnapshotService;

    @Test
    void runNormalizationProcessesUntilQueueIsEmpty() {
        BybitNormalizationProperties properties = new BybitNormalizationProperties();
        properties.setEnabled(true);
        properties.setBatchSize(25);
        when(bybitNormalizationService.processNextBatch(25)).thenReturn(2, 1, 0);
        when(pipelineTelemetrySnapshotService.snapshot()).thenReturn(new PipelineTelemetrySnapshot(
                10L,
                3L,
                0L,
                1L,
                1L,
                2L,
                0L,
                2L
        ));

        BybitNormalizationJob job = new BybitNormalizationJob(properties, bybitNormalizationService, pipelineTelemetrySnapshotService);
        int processed = job.runNormalization();

        assertThat(processed).isEqualTo(3);
        verify(pipelineTelemetrySnapshotService).snapshot();
    }
}
