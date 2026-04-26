package com.walletradar.costbasis.application;

import com.walletradar.domain.event.AccountingReplayCompletedEvent;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import com.walletradar.pricing.application.CurrentPriceQuoteRefreshService;
import com.walletradar.session.application.AccountingUniverseService;
import com.walletradar.session.application.SessionPipelineActivityService;
import com.walletradar.session.application.SessionPipelineStateService;
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
    private OnChainBalanceRefreshService onChainBalanceRefreshService;
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
        when(onChainBalanceRefreshService.refreshCurrentBalances(
                eq("session-1"),
                eq(scope.onChainWalletRefs()),
                any(),
                any(Runnable.class)
        )).thenReturn(210);
        when(currentPriceQuoteRefreshService.refreshForSessionBalances(eq("session-1"), any())).thenReturn(15);

        PortfolioSnapshotRefreshJob job = new PortfolioSnapshotRefreshJob(
                userSessionRepository,
                accountingUniverseService,
                onChainBalanceRefreshService,
                currentPriceQuoteRefreshService,
                sessionPipelineActivityService,
                sessionPipelineStateService
        );

        job.onAccountingReplayCompleted(new AccountingReplayCompletedEvent("session-1", 5724, "pricing-completed"));

        InOrder inOrder = inOrder(
                sessionPipelineStateService,
                onChainBalanceRefreshService,
                currentPriceQuoteRefreshService
        );
        inOrder.verify(sessionPipelineStateService).markStageRunning(
                "session-1",
                UserSession.PipelineStage.PORTFOLIO_SNAPSHOT_REFRESH,
                "Portfolio snapshot refresh running"
        );
        inOrder.verify(onChainBalanceRefreshService).refreshCurrentBalances(
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
