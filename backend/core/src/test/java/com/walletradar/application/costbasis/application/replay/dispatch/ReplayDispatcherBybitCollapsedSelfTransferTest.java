package com.walletradar.application.costbasis.application.replay.dispatch;

import com.walletradar.application.costbasis.application.replay.handler.AsyncSpotOrderReplayHandler;
import com.walletradar.application.costbasis.application.replay.handler.BorrowReplayHandler;
import com.walletradar.application.costbasis.application.replay.handler.BybitVenueInternalReplayHandler;
import com.walletradar.application.costbasis.application.replay.handler.FamilyEquivalentCustodyReplayHandler;
import com.walletradar.application.costbasis.application.replay.handler.GenericAsyncLifecycleReplayHandler;
import com.walletradar.application.costbasis.application.replay.handler.GmxLpEntryReplayHandler;
import com.walletradar.application.costbasis.application.replay.handler.LiquidStakingReplayHandler;
import com.walletradar.application.costbasis.application.replay.handler.LpReceiptEntryReplayHandler;
import com.walletradar.application.costbasis.application.replay.handler.PositionScopedLpExitReplayHandler;
import com.walletradar.application.costbasis.application.replay.handler.RepayReplayHandler;
import com.walletradar.application.costbasis.application.replay.handler.TransferReplayHandler;
import com.walletradar.application.costbasis.application.replay.planning.ReplayRoutingDecision;
import com.walletradar.application.costbasis.application.replay.planning.ReplayTransactionRouter;
import com.walletradar.application.costbasis.application.replay.state.ReplayExecutionState;
import com.walletradar.application.costbasis.application.replay.support.CounterpartyBasisPoolReplayHook;
import com.walletradar.application.costbasis.application.replay.support.ReplayAssetSupport;
import com.walletradar.application.costbasis.application.replay.support.ReplayFlowSupport;
import com.walletradar.application.costbasis.application.replay.support.ReplayPendingTransferKeyFactory;
import com.walletradar.application.costbasis.support.AcquisitionFeeCapitalizationPolicy;
import com.walletradar.application.costbasis.application.replay.support.ReplayTransferClassifier;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@code bybit-collapsed-v1:} UTA↔FUND pairs are NOT skipped by
 * {@code isBybitSelfTransfer()} — both sides must proceed to replay so FUND receives the basis.
 */
@ExtendWith(MockitoExtension.class)
class ReplayDispatcherBybitCollapsedSelfTransferTest {

    private static final String UID = "33625378";
    private static final String CORR_ID = "bybit-collapsed-v1:abc1def2abc3def4";

    @Mock ReplayTransactionRouter replayTransactionRouter;
    @Mock ReplayAssetSupport assetSupport;
    @Mock ReplayFlowSupport flowSupport;
    @Mock ReplayTransferClassifier transferClassifier;
    @Mock ReplayPendingTransferKeyFactory pendingTransferKeyFactory;
    @Mock TransferReplayHandler transferReplayHandler;
    @Mock BybitVenueInternalReplayHandler bybitVenueInternalReplayHandler;
    @Mock LiquidStakingReplayHandler liquidStakingReplayHandler;
    @Mock FamilyEquivalentCustodyReplayHandler familyEquivalentCustodyReplayHandler;
    @Mock GenericAsyncLifecycleReplayHandler genericAsyncLifecycleReplayHandler;
    @Mock GmxLpEntryReplayHandler gmxLpEntryReplayHandler;
    @Mock LpReceiptEntryReplayHandler lpReceiptEntryReplayHandler;
    @Mock PositionScopedLpExitReplayHandler positionScopedLpExitReplayHandler;
    @Mock AsyncSpotOrderReplayHandler asyncSpotOrderReplayHandler;
    @Mock ReplayRouteHandlerRegistry replayRouteHandlerRegistry;
    @Mock CounterpartyBasisPoolReplayHook counterpartyBasisPoolReplayHook;
    @Mock com.walletradar.application.costbasis.application.replay.support.LeverageBorrowReplayHook leverageBorrowReplayHook;
    @Mock BorrowReplayHandler borrowReplayHandler;
    @Mock RepayReplayHandler repayReplayHandler;
    @Mock ReplayExecutionState replayState;

    private ReplayDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new ReplayDispatcher(
                replayTransactionRouter, assetSupport, flowSupport, transferClassifier,
                pendingTransferKeyFactory, replayRouteHandlerRegistry,
                mock(AcquisitionFeeCapitalizationPolicy.class),
                transferReplayHandler,
                bybitVenueInternalReplayHandler,
                liquidStakingReplayHandler, familyEquivalentCustodyReplayHandler,
                genericAsyncLifecycleReplayHandler, gmxLpEntryReplayHandler,
                lpReceiptEntryReplayHandler, positionScopedLpExitReplayHandler,
                asyncSpotOrderReplayHandler,
                counterpartyBasisPoolReplayHook, leverageBorrowReplayHook,
                borrowReplayHandler, repayReplayHandler,
                mock(com.walletradar.application.costbasis.application.replay.support.ReplayMarketAuthority.class)
        );
    }

    @Test
    void fundCarryIn_isNotSkipped_whenCorrIdIsBybitCollapsed() {
        NormalizedTransaction fundCarryIn = bybitCollapsedInternalTransfer(
                "BYBIT:" + UID + ":FUND",
                "BYBIT:" + UID + ":UTA",
                CORR_ID
        );
        when(replayTransactionRouter.route(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(ReplayRoutingDecision.generic());

        dispatcher.dispatch(fundCarryIn, replayState);

        verify(replayTransactionRouter).route(eq(fundCarryIn), any(), any(), any(), any(), any(), any());
        verify(replayRouteHandlerRegistry).dispatch(eq(fundCarryIn), any(), eq(replayState), any());
    }

    @Test
    void utaCarryOut_isNotSkipped_whenCorrIdIsBybitCollapsed() {
        NormalizedTransaction utaCarryOut = bybitCollapsedInternalTransfer(
                "BYBIT:" + UID + ":UTA",
                "BYBIT:" + UID + ":FUND",
                CORR_ID
        );
        when(replayTransactionRouter.route(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(ReplayRoutingDecision.generic());

        dispatcher.dispatch(utaCarryOut, replayState);

        verify(replayTransactionRouter).route(eq(utaCarryOut), any(), any(), any(), any(), any(), any());
        verify(replayRouteHandlerRegistry).dispatch(eq(utaCarryOut), any(), eq(replayState), any());
    }

    @Test
    void sameUidTransfer_withoutCollapsedPrefix_isSkippedAsSelfTransfer() {
        // Non-collapsed same-UID UTA↔FUND transfer must still be treated as a no-op.
        NormalizedTransaction regularTransfer = bybitCollapsedInternalTransfer(
                "BYBIT:" + UID + ":UTA",
                "BYBIT:" + UID + ":FUND",
                "bybit-econ-v1:somehash"
        );

        dispatcher.dispatch(regularTransfer, replayState);

        verifyNoInteractions(replayTransactionRouter);
    }

    private static NormalizedTransaction bybitCollapsedInternalTransfer(
            String walletAddress,
            String matchedCounterparty,
            String correlationId
    ) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setSource(NormalizedTransactionSource.BYBIT);
        tx.setType(NormalizedTransactionType.INTERNAL_TRANSFER);
        tx.setWalletAddress(walletAddress);
        tx.setMatchedCounterparty(matchedCounterparty);
        tx.setCorrelationId(correlationId);
        tx.setContinuityCandidate(true);
        tx.setFlows(new ArrayList<>());
        return tx;
    }
}
