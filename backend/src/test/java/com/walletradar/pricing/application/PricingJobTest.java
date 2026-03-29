package com.walletradar.pricing.application;

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
class PricingJobTest {

    @Mock
    private PricingJobService pricingJobService;
    @Mock
    private PricingDataGateService pricingDataGateService;
    @Mock
    private PipelineTelemetrySnapshotService pipelineTelemetrySnapshotService;

    @Test
    void runPricingProcessesUntilEmptyAndEmitsDataGateSnapshot() {
        PricingProperties properties = new PricingProperties();
        properties.setEnabled(true);
        when(pricingJobService.processNextBatch()).thenReturn(2, 1, 0);
        when(pricingDataGateService.snapshot()).thenReturn(new PricingDataGateSnapshot(
                0L,
                0L,
                0L,
                3L,
                2L,
                true
        ));
        when(pipelineTelemetrySnapshotService.snapshot()).thenReturn(new PipelineTelemetrySnapshot(
                10L,
                2L,
                0L,
                1L,
                1L,
                3L,
                0L,
                2L
        ));

        PricingJob job = new PricingJob(properties, pricingJobService, pricingDataGateService, pipelineTelemetrySnapshotService);
        int processed = job.runPricing();

        assertThat(processed).isEqualTo(3);
        verify(pricingDataGateService).snapshot();
    }

    @Test
    void rerunWithNoPendingRowsIsIdempotent() {
        PricingProperties properties = new PricingProperties();
        properties.setEnabled(true);
        when(pricingJobService.processNextBatch()).thenReturn(0, 0);
        when(pricingDataGateService.snapshot()).thenReturn(new PricingDataGateSnapshot(
                0L,
                0L,
                0L,
                0L,
                0L,
                true
        ));
        when(pipelineTelemetrySnapshotService.snapshot()).thenReturn(new PipelineTelemetrySnapshot(
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L
        ));

        PricingJob job = new PricingJob(properties, pricingJobService, pricingDataGateService, pipelineTelemetrySnapshotService);

        int firstRun = job.runPricing();
        int secondRun = job.runPricing();

        assertThat(firstRun).isZero();
        assertThat(secondRun).isZero();
    }
}
