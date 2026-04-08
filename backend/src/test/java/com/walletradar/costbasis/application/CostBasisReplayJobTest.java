package com.walletradar.costbasis.application;

import com.walletradar.costbasis.domain.AssetLedgerPointRepository;
import com.walletradar.domain.event.PricingCompletedEvent;
import com.walletradar.domain.session.UserSession;
import com.walletradar.pricing.application.PricingDataGateService;
import com.walletradar.pricing.application.PricingDataGateSnapshot;
import com.walletradar.domain.session.UserSessionRepository;
import com.walletradar.session.application.AccountingUniverseService;
import com.walletradar.session.application.SessionPipelineStateService;
import com.walletradar.telemetry.PipelineTelemetrySnapshot;
import com.walletradar.telemetry.PipelineTelemetrySnapshotService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

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
    private UserSessionRepository userSessionRepository;
    @Mock
    private AccountingUniverseService accountingUniverseService;
    @Mock
    private PendingStatQueryService pendingStatQueryService;
    @Mock
    private StatValidationService statValidationService;
    @Mock
    private AvcoReplayService avcoReplayService;
    @Mock
    private OnChainBalanceRefreshService onChainBalanceRefreshService;
    @Mock
    private AssetLedgerPointRepository assetLedgerPointRepository;
    @Mock
    private PipelineTelemetrySnapshotService pipelineTelemetrySnapshotService;
    @Mock
    private SessionPipelineStateService sessionPipelineStateService;

    @Test
    void runReplayValidatesThenReplaysWhenGateIsGreen() {
        CostBasisProperties properties = properties();
        UserSession session = session("session-1");
        AccountingUniverseService.AccountingUniverseScope scope = scope("session-1", "wallet-a");
        when(userSessionRepository.findAll()).thenReturn(List.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(scope);
        when(statValidationService.processNextBatch(25, 60, scope.memberRefs())).thenReturn(
                new StatValidationOutcome(2, 2, 0),
                new StatValidationOutcome(0, 0, 0)
        );
        when(pricingDataGateService.snapshot(scope.memberRefs())).thenReturn(new PricingDataGateSnapshot(0L, 0L, 0L, 1L, 2L, true));
        when(pendingStatQueryService.countPending(scope.memberRefs())).thenReturn(0L);
        when(avcoReplayService.replayConfirmed("ACCOUNTING_UNIVERSE:session-1", scope.memberRefs())).thenReturn(7);
        when(pipelineTelemetrySnapshotService.snapshot()).thenReturn(snapshot());

        CostBasisReplayJob job = new CostBasisReplayJob(
                properties,
                userSessionRepository,
                accountingUniverseService,
                pricingDataGateService,
                pendingStatQueryService,
                statValidationService,
                avcoReplayService,
                onChainBalanceRefreshService,
                assetLedgerPointRepository,
                pipelineTelemetrySnapshotService,
                sessionPipelineStateService
        );

        int replayed = job.runReplay();

        assertThat(replayed).isEqualTo(7);
        verify(avcoReplayService).replayConfirmed("ACCOUNTING_UNIVERSE:session-1", scope.memberRefs());
        InOrder inOrder = org.mockito.Mockito.inOrder(avcoReplayService, onChainBalanceRefreshService);
        inOrder.verify(avcoReplayService).replayConfirmed("ACCOUNTING_UNIVERSE:session-1", scope.memberRefs());
        inOrder.verify(onChainBalanceRefreshService).refreshCurrentBalances(eq("session-1"), eq(scope.onChainWalletRefs()), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void runReplayStopsWhenPendingStatStillExists() {
        CostBasisProperties properties = properties();
        UserSession session = session("session-1");
        AccountingUniverseService.AccountingUniverseScope scope = scope("session-1", "wallet-a");
        when(userSessionRepository.findAll()).thenReturn(List.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(scope);
        when(statValidationService.processNextBatch(25, 60, scope.memberRefs())).thenReturn(new StatValidationOutcome(0, 0, 0));
        when(pricingDataGateService.snapshot(scope.memberRefs())).thenReturn(new PricingDataGateSnapshot(0L, 0L, 0L, 0L, 2L, true));
        when(pendingStatQueryService.countPending(scope.memberRefs())).thenReturn(3L);
        when(pipelineTelemetrySnapshotService.snapshot()).thenReturn(snapshot());

        CostBasisReplayJob job = new CostBasisReplayJob(
                properties,
                userSessionRepository,
                accountingUniverseService,
                pricingDataGateService,
                pendingStatQueryService,
                statValidationService,
                avcoReplayService,
                onChainBalanceRefreshService,
                assetLedgerPointRepository,
                pipelineTelemetrySnapshotService,
                sessionPipelineStateService
        );

        int replayed = job.runReplay();

        assertThat(replayed).isZero();
        verify(avcoReplayService, never()).replayConfirmed(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyCollection());
        verify(onChainBalanceRefreshService, never()).refreshCurrentBalances(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyCollection(), org.mockito.ArgumentMatchers.any());
        verify(sessionPipelineStateService).markStageBlocked(
                eq("session-1"),
                eq(UserSession.PipelineStage.ACCOUNTING_REPLAY),
                contains("Accounting replay blocked")
        );
    }

    @Test
    void manualReplayStillRunsWhenExplicitlyTriggeredAfterPricing() {
        CostBasisProperties properties = properties();
        properties.setEnabled(true);
        UserSession session = session("session-1");
        AccountingUniverseService.AccountingUniverseScope scope = scope("session-1", "wallet-a");
        when(userSessionRepository.findAll()).thenReturn(List.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(scope);
        when(statValidationService.processNextBatch(25, 60, scope.memberRefs())).thenReturn(new StatValidationOutcome(0, 0, 0));
        when(pricingDataGateService.snapshot(scope.memberRefs())).thenReturn(new PricingDataGateSnapshot(0L, 0L, 0L, 0L, 2L, true));
        when(pendingStatQueryService.countPending(scope.memberRefs())).thenReturn(0L);
        when(avcoReplayService.replayConfirmed("ACCOUNTING_UNIVERSE:session-1", scope.memberRefs())).thenReturn(3);
        when(pipelineTelemetrySnapshotService.snapshot()).thenReturn(snapshot());

        CostBasisReplayJob job = new CostBasisReplayJob(
                properties,
                userSessionRepository,
                accountingUniverseService,
                pricingDataGateService,
                pendingStatQueryService,
                statValidationService,
                avcoReplayService,
                onChainBalanceRefreshService,
                assetLedgerPointRepository,
                pipelineTelemetrySnapshotService,
                sessionPipelineStateService
        );

        job.runReplay();

        verify(avcoReplayService).replayConfirmed("ACCOUNTING_UNIVERSE:session-1", scope.memberRefs());
        verify(onChainBalanceRefreshService).refreshCurrentBalances(eq("session-1"), eq(scope.onChainWalletRefs()), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void pricingCompletionForcesReplayCheckThroughEventPath() {
        CostBasisProperties properties = properties();
        properties.setEnabled(true);
        UserSession session = session("session-1");
        AccountingUniverseService.AccountingUniverseScope scope = scope("session-1", "wallet-a");
        when(userSessionRepository.findById("session-1")).thenReturn(java.util.Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(scope);
        when(statValidationService.processNextBatch(25, 60, scope.memberRefs())).thenReturn(new StatValidationOutcome(0, 0, 0));
        when(pricingDataGateService.snapshot(scope.memberRefs())).thenReturn(new PricingDataGateSnapshot(0L, 0L, 0L, 0L, 0L, true));
        when(pendingStatQueryService.countPending(scope.memberRefs())).thenReturn(0L);
        when(avcoReplayService.replayConfirmed("ACCOUNTING_UNIVERSE:session-1", scope.memberRefs())).thenReturn(4);
        when(pipelineTelemetrySnapshotService.snapshot()).thenReturn(snapshot());

        CostBasisReplayJob job = new CostBasisReplayJob(
                properties,
                userSessionRepository,
                accountingUniverseService,
                pricingDataGateService,
                pendingStatQueryService,
                statValidationService,
                avcoReplayService,
                onChainBalanceRefreshService,
                assetLedgerPointRepository,
                pipelineTelemetrySnapshotService,
                sessionPipelineStateService
        );

        job.onPricingCompleted(new PricingCompletedEvent("session-1", 0, "bybit-normalization-completed"));

        verify(avcoReplayService).replayConfirmed("ACCOUNTING_UNIVERSE:session-1", scope.memberRefs());
        verify(onChainBalanceRefreshService).refreshCurrentBalances(eq("session-1"), eq(scope.onChainWalletRefs()), org.mockito.ArgumentMatchers.any());
    }

    private UserSession session(String sessionId) {
        UserSession session = new UserSession();
        session.setId(sessionId);
        return session;
    }

    private AccountingUniverseService.AccountingUniverseScope scope(String sessionId, String walletAddress) {
        return new AccountingUniverseService.AccountingUniverseScope(
                "ACCOUNTING_UNIVERSE:" + sessionId,
                List.of(walletAddress),
                List.of(walletAddress)
        );
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
