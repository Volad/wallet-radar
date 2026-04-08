package com.walletradar.pricing.application;

import com.walletradar.domain.event.BybitNormalizationCompletedEvent;
import com.walletradar.domain.event.PricingCompletedEvent;
import com.walletradar.session.application.SessionPipelineStateService;
import com.walletradar.telemetry.PipelineTelemetrySnapshot;
import com.walletradar.telemetry.PipelineTelemetrySnapshotService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.List;

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
    private StalePriceUnresolvedRepairService stalePriceUnresolvedRepairService;
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

        when(stalePriceUnresolvedRepairService.repairNextBatch(properties.getBatchSize())).thenReturn(0);
        ApplicationEventPublisher publisher = org.mockito.Mockito.mock(ApplicationEventPublisher.class);
        SessionPipelineStateService pipelineStateService = org.mockito.Mockito.mock(SessionPipelineStateService.class);
        PricingJob job = new PricingJob(properties, pricingJobService, pricingDataGateService, stalePriceUnresolvedRepairService, pipelineTelemetrySnapshotService, publisher, pipelineStateService);
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

        when(stalePriceUnresolvedRepairService.repairNextBatch(properties.getBatchSize())).thenReturn(0, 0);
        ApplicationEventPublisher publisher = org.mockito.Mockito.mock(ApplicationEventPublisher.class);
        SessionPipelineStateService pipelineStateService = org.mockito.Mockito.mock(SessionPipelineStateService.class);
        PricingJob job = new PricingJob(properties, pricingJobService, pricingDataGateService, stalePriceUnresolvedRepairService, pipelineTelemetrySnapshotService, publisher, pipelineStateService);

        int firstRun = job.runPricing();
        int secondRun = job.runPricing();

        assertThat(firstRun).isZero();
        assertThat(secondRun).isZero();
    }

    @Test
    void bybitCompletionPublishesPricingCompletionEvenForEmptyDrain() {
        PricingProperties properties = new PricingProperties();
        properties.setEnabled(true);
        when(pricingJobService.processNextBatch()).thenReturn(0);
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

        List<Object> events = new ArrayList<>();
        ApplicationEventPublisher publisher = events::add;
        SessionPipelineStateService pipelineStateService = org.mockito.Mockito.mock(SessionPipelineStateService.class);
        when(stalePriceUnresolvedRepairService.repairNextBatch(properties.getBatchSize())).thenReturn(0);
        PricingJob job = new PricingJob(properties, pricingJobService, pricingDataGateService, stalePriceUnresolvedRepairService, pipelineTelemetrySnapshotService, publisher, pipelineStateService);

        job.onBybitNormalizationCompleted(new BybitNormalizationCompletedEvent("session-1", 0, "clarification-completed"));

        assertThat(events).singleElement().isInstanceOfSatisfying(PricingCompletedEvent.class, event -> {
            assertThat(event.sessionId()).isEqualTo("session-1");
            assertThat(event.processed()).isZero();
            assertThat(event.trigger()).isEqualTo("bybit-normalization-completed");
        });
    }
}
