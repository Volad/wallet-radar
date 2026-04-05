package com.walletradar.costbasis.application;

import com.walletradar.costbasis.domain.AssetPositionRepository;
import com.walletradar.domain.event.PricingCompletedEvent;
import com.walletradar.domain.session.UserSession;
import com.walletradar.pricing.application.PricingDataGateService;
import com.walletradar.pricing.application.PricingDataGateSnapshot;
import com.walletradar.session.application.SessionPipelineStateService;
import com.walletradar.telemetry.PipelineTelemetrySnapshot;
import com.walletradar.telemetry.PipelineTelemetrySnapshotService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
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
    private OnChainBalanceRefreshService onChainBalanceRefreshService;
    @Mock
    private AssetPositionReconciliationService assetPositionReconciliationService;
    @Mock
    private ReconciledHoldingsMaterializationService reconciledHoldingsMaterializationService;
    @Mock
    private AssetPositionRepository assetPositionRepository;
    @Mock
    private PipelineTelemetrySnapshotService pipelineTelemetrySnapshotService;
    @Mock
    private SessionPipelineStateService sessionPipelineStateService;

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
                onChainBalanceRefreshService,
                assetPositionReconciliationService,
                reconciledHoldingsMaterializationService,
                assetPositionRepository,
                pipelineTelemetrySnapshotService,
                sessionPipelineStateService
        );

        int replayed = job.runReplay();

        assertThat(replayed).isEqualTo(7);
        verify(avcoReplayService).replayConfirmed();
        InOrder inOrder = org.mockito.Mockito.inOrder(
                avcoReplayService,
                onChainBalanceRefreshService,
                assetPositionReconciliationService,
                reconciledHoldingsMaterializationService
        );
        inOrder.verify(avcoReplayService).replayConfirmed();
        inOrder.verify(onChainBalanceRefreshService).refreshCurrentBalances(org.mockito.ArgumentMatchers.any());
        inOrder.verify(assetPositionReconciliationService).reconcile(org.mockito.ArgumentMatchers.any());
        inOrder.verify(reconciledHoldingsMaterializationService).materialize(org.mockito.ArgumentMatchers.any());
        verify(assetPositionReconciliationService).reconcile(org.mockito.ArgumentMatchers.any());
        verify(reconciledHoldingsMaterializationService).materialize(org.mockito.ArgumentMatchers.any());
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
                onChainBalanceRefreshService,
                assetPositionReconciliationService,
                reconciledHoldingsMaterializationService,
                assetPositionRepository,
                pipelineTelemetrySnapshotService,
                sessionPipelineStateService
        );

        int replayed = job.runReplay();

        assertThat(replayed).isZero();
        verify(avcoReplayService, never()).replayConfirmed();
        verify(onChainBalanceRefreshService, never()).refreshCurrentBalances(org.mockito.ArgumentMatchers.any());
        verify(assetPositionReconciliationService, never()).reconcile(org.mockito.ArgumentMatchers.any());
        verify(reconciledHoldingsMaterializationService, never()).materialize(org.mockito.ArgumentMatchers.any());
        verify(sessionPipelineStateService).markStageBlocked(
                eq(null),
                eq(UserSession.PipelineStage.ACCOUNTING_REPLAY),
                contains("Accounting replay blocked")
        );
    }

    @Test
    void manualReplayStillRunsWhenExplicitlyTriggeredAfterPricing() {
        CostBasisProperties properties = properties();
        properties.setEnabled(true);
        when(statValidationService.processNextBatch(25, 60)).thenReturn(new StatValidationOutcome(0, 0, 0));
        when(pricingDataGateService.snapshot()).thenReturn(new PricingDataGateSnapshot(0L, 0L, 0L, 0L, 2L, true));
        when(pendingStatQueryService.countPending()).thenReturn(0L);
        when(assetPositionRepository.count()).thenReturn(2L);
        when(avcoReplayService.replayConfirmed()).thenReturn(3);
        when(pipelineTelemetrySnapshotService.snapshot()).thenReturn(snapshot());

        CostBasisReplayJob job = new CostBasisReplayJob(
                properties,
                pricingDataGateService,
                pendingStatQueryService,
                statValidationService,
                avcoReplayService,
                onChainBalanceRefreshService,
                assetPositionReconciliationService,
                reconciledHoldingsMaterializationService,
                assetPositionRepository,
                pipelineTelemetrySnapshotService,
                sessionPipelineStateService
        );

        job.runReplay();

        verify(avcoReplayService).replayConfirmed();
        verify(onChainBalanceRefreshService).refreshCurrentBalances(org.mockito.ArgumentMatchers.any());
        verify(assetPositionReconciliationService).reconcile(org.mockito.ArgumentMatchers.any());
        verify(reconciledHoldingsMaterializationService).materialize(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void pricingCompletionForcesReplayCheckThroughEventPath() {
        CostBasisProperties properties = properties();
        properties.setEnabled(true);
        when(statValidationService.processNextBatch(25, 60)).thenReturn(new StatValidationOutcome(0, 0, 0));
        when(pricingDataGateService.snapshot()).thenReturn(new PricingDataGateSnapshot(0L, 0L, 0L, 0L, 0L, true));
        when(pendingStatQueryService.countPending()).thenReturn(0L);
        when(avcoReplayService.replayConfirmed()).thenReturn(4);
        when(pipelineTelemetrySnapshotService.snapshot()).thenReturn(snapshot());

        CostBasisReplayJob job = new CostBasisReplayJob(
                properties,
                pricingDataGateService,
                pendingStatQueryService,
                statValidationService,
                avcoReplayService,
                onChainBalanceRefreshService,
                assetPositionReconciliationService,
                reconciledHoldingsMaterializationService,
                assetPositionRepository,
                pipelineTelemetrySnapshotService,
                sessionPipelineStateService
        );

        job.onPricingCompleted(new PricingCompletedEvent("session-1", 0, "bybit-normalization-completed"));

        verify(avcoReplayService).replayConfirmed();
        verify(onChainBalanceRefreshService).refreshCurrentBalances(org.mockito.ArgumentMatchers.any());
        verify(assetPositionReconciliationService).reconcile(org.mockito.ArgumentMatchers.any());
        verify(reconciledHoldingsMaterializationService).materialize(org.mockito.ArgumentMatchers.any());
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
