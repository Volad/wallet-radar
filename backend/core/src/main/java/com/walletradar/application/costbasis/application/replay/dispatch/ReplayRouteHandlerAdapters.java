package com.walletradar.application.costbasis.application.replay.dispatch;

import com.walletradar.application.costbasis.application.replay.handler.EulerLoopReplayHandler;
import com.walletradar.application.costbasis.application.replay.handler.FamilyEquivalentCustodyReplayHandler;
import com.walletradar.application.costbasis.application.replay.handler.GenericAsyncLifecycleReplayHandler;
import com.walletradar.application.costbasis.application.replay.handler.GmxLpEntryReplayHandler;
import com.walletradar.application.costbasis.application.replay.handler.LiquidStakingReplayHandler;
import com.walletradar.application.costbasis.application.replay.handler.LpReceiptEntryReplayHandler;
import com.walletradar.application.costbasis.application.replay.handler.PositionScopedLpExitReplayHandler;
import com.walletradar.application.costbasis.application.replay.planning.ReplayRoute;
import com.walletradar.application.costbasis.application.replay.planning.ReplayRoutingDecision;
import com.walletradar.application.costbasis.application.replay.state.ReplayExecutionState;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Spring-registered route-level replay handler adapters.
 */
public final class ReplayRouteHandlerAdapters {

    private ReplayRouteHandlerAdapters() {
    }

    @Component
    static class EulerLoopRouteHandler implements ReplayRouteHandler {

        private final EulerLoopReplayHandler delegate;

        EulerLoopRouteHandler(EulerLoopReplayHandler delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean supports(NormalizedTransaction transaction, ReplayRoutingDecision routingDecision) {
            return routingDecision.route() == ReplayRoute.EULER_LOOP;
        }

        @Override
        public int getOrder() {
            return 10;
        }

        @Override
        public void apply(
                NormalizedTransaction transaction,
                ReplayRoutingDecision routingDecision,
                ReplayExecutionState replayState,
                ReplayDispatchCallbacks callbacks
        ) {
            delegate.apply(transaction, replayState);
        }
    }

    @Component
    static class GmxLpEntryRequestRouteHandler implements ReplayRouteHandler {

        private final GmxLpEntryReplayHandler delegate;

        GmxLpEntryRequestRouteHandler(GmxLpEntryReplayHandler delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean supports(NormalizedTransaction transaction, ReplayRoutingDecision routingDecision) {
            return routingDecision.route() == ReplayRoute.GMX_LP_ENTRY_REQUEST;
        }

        @Override
        public int getOrder() {
            return 20;
        }

        @Override
        public void apply(
                NormalizedTransaction transaction,
                ReplayRoutingDecision routingDecision,
                ReplayExecutionState replayState,
                ReplayDispatchCallbacks callbacks
        ) {
            delegate.applyRequest(transaction, replayState);
        }
    }

    @Component
    static class GmxLpEntrySettlementRouteHandler implements ReplayRouteHandler {

        private final GmxLpEntryReplayHandler delegate;

        GmxLpEntrySettlementRouteHandler(GmxLpEntryReplayHandler delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean supports(NormalizedTransaction transaction, ReplayRoutingDecision routingDecision) {
            return routingDecision.route() == ReplayRoute.GMX_LP_ENTRY_SETTLEMENT;
        }

        @Override
        public int getOrder() {
            return 30;
        }

        @Override
        public void apply(
                NormalizedTransaction transaction,
                ReplayRoutingDecision routingDecision,
                ReplayExecutionState replayState,
                ReplayDispatchCallbacks callbacks
        ) {
            delegate.applySettlement(transaction, replayState);
        }
    }

    @Component
    static class LpReceiptEntryRouteHandler implements ReplayRouteHandler {

        private final LpReceiptEntryReplayHandler delegate;

        LpReceiptEntryRouteHandler(LpReceiptEntryReplayHandler delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean supports(NormalizedTransaction transaction, ReplayRoutingDecision routingDecision) {
            return routingDecision.route() == ReplayRoute.LP_RECEIPT_ENTRY;
        }

        @Override
        public int getOrder() {
            return 40;
        }

        @Override
        public void apply(
                NormalizedTransaction transaction,
                ReplayRoutingDecision routingDecision,
                ReplayExecutionState replayState,
                ReplayDispatchCallbacks callbacks
        ) {
            delegate.apply(transaction, replayState);
        }
    }

    @Component
    static class AsyncLpExitSettlementRouteHandler implements ReplayRouteHandler {

        private final GenericAsyncLifecycleReplayHandler delegate;

        AsyncLpExitSettlementRouteHandler(GenericAsyncLifecycleReplayHandler delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean supports(NormalizedTransaction transaction, ReplayRoutingDecision routingDecision) {
            return routingDecision.route() == ReplayRoute.ASYNC_LP_EXIT_SETTLEMENT;
        }

        @Override
        public int getOrder() {
            return 50;
        }

        @Override
        public void apply(
                NormalizedTransaction transaction,
                ReplayRoutingDecision routingDecision,
                ReplayExecutionState replayState,
                ReplayDispatchCallbacks callbacks
        ) {
            delegate.applyAsyncLpExitSettlement(transaction, replayState);
        }
    }

    @Component
    static class PositionScopedLpExitRouteHandler implements ReplayRouteHandler {

        private final PositionScopedLpExitReplayHandler delegate;

        PositionScopedLpExitRouteHandler(PositionScopedLpExitReplayHandler delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean supports(NormalizedTransaction transaction, ReplayRoutingDecision routingDecision) {
            return routingDecision.route() == ReplayRoute.POSITION_SCOPED_LP_EXIT;
        }

        @Override
        public int getOrder() {
            return 60;
        }

        @Override
        public void apply(
                NormalizedTransaction transaction,
                ReplayRoutingDecision routingDecision,
                ReplayExecutionState replayState,
                ReplayDispatchCallbacks callbacks
        ) {
            delegate.apply(transaction, replayState);
        }
    }

    /** ADR-083: adapter for the {@code CLUSTER_CARRY} route (formerly {@code LIQUID_STAKING}). */
    @Component
    static class ClusterCarryRouteHandler implements ReplayRouteHandler {

        private final LiquidStakingReplayHandler delegate;

        ClusterCarryRouteHandler(LiquidStakingReplayHandler delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean supports(NormalizedTransaction transaction, ReplayRoutingDecision routingDecision) {
            return routingDecision.route() == ReplayRoute.CLUSTER_CARRY;
        }

        @Override
        public int getOrder() {
            return 70;
        }

        @Override
        public void apply(
                NormalizedTransaction transaction,
                ReplayRoutingDecision routingDecision,
                ReplayExecutionState replayState,
                ReplayDispatchCallbacks callbacks
        ) {
            delegate.applySelected(transaction, routingDecision.liquidStakingSelection(), replayState);
            Set<Integer> selectedIndexes = new LinkedHashSet<>();
            routingDecision.liquidStakingSelection().outbound().forEach(flow -> selectedIndexes.add(flow.index()));
            routingDecision.liquidStakingSelection().inbound().forEach(flow -> selectedIndexes.add(flow.index()));
            callbacks.replayGenericFlowsSkipping(transaction, replayState, selectedIndexes);
        }
    }

    @Component
    static class FamilyEquivalentCustodyRouteHandler implements ReplayRouteHandler {

        private final FamilyEquivalentCustodyReplayHandler delegate;

        FamilyEquivalentCustodyRouteHandler(FamilyEquivalentCustodyReplayHandler delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean supports(NormalizedTransaction transaction, ReplayRoutingDecision routingDecision) {
            return routingDecision.route() == ReplayRoute.FAMILY_EQUIVALENT_CUSTODY;
        }

        @Override
        public int getOrder() {
            return 80;
        }

        @Override
        public void apply(
                NormalizedTransaction transaction,
                ReplayRoutingDecision routingDecision,
                ReplayExecutionState replayState,
                ReplayDispatchCallbacks callbacks
        ) {
            delegate.applySelected(transaction, routingDecision.familyCustodySelection(), replayState);
            callbacks.replayGenericFlowsSkipping(
                    transaction,
                    replayState,
                    routingDecision.familyCustodySelection().selectedByIndex().keySet()
            );
        }
    }

    @Component
    static class GenericRouteHandler implements ReplayRouteHandler {

        @Override
        public boolean supports(NormalizedTransaction transaction, ReplayRoutingDecision routingDecision) {
            return routingDecision.route() == ReplayRoute.GENERIC;
        }

        @Override
        public int getOrder() {
            return 100;
        }

        @Override
        public void apply(
                NormalizedTransaction transaction,
                ReplayRoutingDecision routingDecision,
                ReplayExecutionState replayState,
                ReplayDispatchCallbacks callbacks
        ) {
            callbacks.replayGenericFlows(transaction, replayState);
            callbacks.applyLeverageBorrowIfNeeded(transaction, replayState);
        }
    }
}
