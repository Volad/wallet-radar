package com.walletradar.application.costbasis.application;

import com.walletradar.application.costbasis.application.port.OnChainBalanceRefresher;
import com.walletradar.domain.event.AccountingReplayCompletedEvent;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import com.walletradar.application.pricing.application.CurrentPriceQuoteRefreshService;
import com.walletradar.application.session.application.AccountingUniverseService;
import com.walletradar.application.session.application.SessionPipelineActivityService;
import com.walletradar.application.session.application.SessionPipelineStateService;
import com.walletradar.application.pipeline.config.JobHeartbeatProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PortfolioSnapshotRefreshJobTest {

    @Mock
    private UserSessionRepository userSessionRepository;
    @Mock
    private AccountingUniverseService accountingUniverseService;
    @Mock
    private OnChainBalanceRefresher onChainBalanceRefresher;
    @Mock
    private CurrentPriceQuoteRefreshService currentPriceQuoteRefreshService;
    @Mock
    private SessionPipelineActivityService sessionPipelineActivityService;
    @Mock
    private SessionPipelineStateService sessionPipelineStateService;

    @Test
    void accountingReplayCompletionRefreshesPortfolioSnapshotInSeparateStage() {
        UserSession session = new UserSession();
        session.setId("session-1");
        AccountingUniverseService.AccountingUniverseScope scope = new AccountingUniverseService.AccountingUniverseScope(
                "ACCOUNTING_UNIVERSE:session-1",
                List.of("wallet-a"),
                List.of("wallet-a")
        );
        when(userSessionRepository.findById("session-1")).thenReturn(Optional.of(session));
        when(accountingUniverseService.resolveScope(session)).thenReturn(scope);
        when(onChainBalanceRefresher.refreshCurrentBalances(
                eq("session-1"),
                eq(scope.onChainWalletRefs()),
                any(),
                any(Runnable.class)
        )).thenReturn(210);
        when(currentPriceQuoteRefreshService.refreshForSessionBalances(eq("session-1"), any())).thenReturn(15);

        PortfolioSnapshotRefreshJob job = new PortfolioSnapshotRefreshJob(
                new JobHeartbeatProperties(),
                userSessionRepository,
                accountingUniverseService,
                onChainBalanceRefresher,
                currentPriceQuoteRefreshService,
                sessionPipelineActivityService,
                sessionPipelineStateService
        );

        job.onAccountingReplayCompleted(new AccountingReplayCompletedEvent("session-1", 5724, "pricing-completed"));

        InOrder inOrder = inOrder(
                sessionPipelineStateService,
                onChainBalanceRefresher,
                currentPriceQuoteRefreshService
        );
        inOrder.verify(sessionPipelineStateService).markStageRunning(
                "session-1",
                UserSession.PipelineStage.PORTFOLIO_SNAPSHOT_REFRESH,
                "Portfolio snapshot refresh running"
        );
        inOrder.verify(onChainBalanceRefresher).refreshCurrentBalances(
                eq("session-1"),
                eq(scope.onChainWalletRefs()),
                any(),
                any(Runnable.class)
        );
        inOrder.verify(currentPriceQuoteRefreshService).refreshForSessionBalances(eq("session-1"), any());
        inOrder.verify(sessionPipelineStateService).markStageComplete(
                "session-1",
                UserSession.PipelineStage.PORTFOLIO_SNAPSHOT_REFRESH,
                "Portfolio snapshot refresh complete"
        );
    }
}
