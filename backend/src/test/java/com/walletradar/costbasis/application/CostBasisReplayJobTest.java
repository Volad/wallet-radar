package com.walletradar.costbasis.application;

import com.walletradar.costbasis.domain.AssetPositionRepository;
import com.walletradar.pricing.application.PricingDataGateService;
import com.walletradar.pricing.application.PricingDataGateSnapshot;
import com.walletradar.telemetry.PipelineTelemetrySnapshot;
import com.walletradar.telemetry.PipelineTelemetrySnapshotService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CostBasisReplayJobTest {

    @Mock
    private PricingDataGateService pricingDataGateService;
    @Mock
    private PendingStatQueryService pendingStatQueryService;
    @Mock
    private StatValidationService statValidationService;
    @Mock
    private AvcoReplayService avcoReplayService;
    @Mock
    private AssetPositionRepository assetPositionRepository;
    @Mock
    private PipelineTelemetrySnapshotService pipelineTelemetrySnapshotService;

    @Test
    void runReplayValidatesThenReplaysWhenGateIsGreen() {
        CostBasisProperties properties = properties();
        when(statValidationService.processNextBatch(25, 60)).thenReturn(
                new StatValidationOutcome(2, 2, 0),
                new StatValidationOutcome(0, 0, 0)
        );
        when(pricingDataGateService.snapshot()).thenReturn(new PricingDataGateSnapshot(0L, 0L, 0L, 1L, 2L, true));
        when(pendingStatQueryService.countPending()).thenReturn(0L);
        when(avcoReplayService.replayConfirmed()).thenReturn(7);
        when(pipelineTelemetrySnapshotService.snapshot()).thenReturn(snapshot());

        CostBasisReplayJob job = new CostBasisReplayJob(
                properties,
                pricingDataGateService,
                pendingStatQueryService,
                statValidationService,
                avcoReplayService,
                assetPositionRepository,
                pipelineTelemetrySnapshotService
        );

        int replayed = job.runReplay();

        assertThat(replayed).isEqualTo(7);
        verify(avcoReplayService).replayConfirmed();
    }

    @Test
    void runReplayStopsWhenPendingStatStillExists() {
        CostBasisProperties properties = properties();
        when(statValidationService.processNextBatch(25, 60)).thenReturn(new StatValidationOutcome(0, 0, 0));
        when(pricingDataGateService.snapshot()).thenReturn(new PricingDataGateSnapshot(0L, 0L, 0L, 0L, 2L, true));
        when(pendingStatQueryService.countPending()).thenReturn(3L);
        when(pipelineTelemetrySnapshotService.snapshot()).thenReturn(snapshot());

        CostBasisReplayJob job = new CostBasisReplayJob(
                properties,
                pricingDataGateService,
                pendingStatQueryService,
                statValidationService,
                avcoReplayService,
                assetPositionRepository,
                pipelineTelemetrySnapshotService
        );

        int replayed = job.runReplay();

        assertThat(replayed).isZero();
        verify(avcoReplayService, never()).replayConfirmed();
    }

    @Test
    void scheduledRunIsIdempotentWhenNothingNewNeedsReplay() {
        CostBasisProperties properties = properties();
        properties.setEnabled(true);
        when(statValidationService.processNextBatch(25, 60)).thenReturn(new StatValidationOutcome(0, 0, 0));
        when(pricingDataGateService.snapshot()).thenReturn(new PricingDataGateSnapshot(0L, 0L, 0L, 0L, 2L, true));
        when(pendingStatQueryService.countPending()).thenReturn(0L);
        when(assetPositionRepository.count()).thenReturn(2L);
        when(pipelineTelemetrySnapshotService.snapshot()).thenReturn(snapshot());

        CostBasisReplayJob job = new CostBasisReplayJob(
                properties,
                pricingDataGateService,
                pendingStatQueryService,
                statValidationService,
                avcoReplayService,
                assetPositionRepository,
                pipelineTelemetrySnapshotService
        );

        job.runScheduled();

        verify(avcoReplayService, never()).replayConfirmed();
    }

    private CostBasisProperties properties() {
        CostBasisProperties properties = new CostBasisProperties();
        properties.setValidationBatchSize(25);
        properties.setRetryDelaySeconds(60);
        return properties;
    }

    private PipelineTelemetrySnapshot snapshot() {
        return new PipelineTelemetrySnapshot(10L, 2L, 0L, 1L, 1L, 1L, 0L, 2L);
    }
}
