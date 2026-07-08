package com.walletradar.costbasis.application.replay.dispatch;

import com.walletradar.costbasis.application.replay.planning.ReplayRoutingDecision;
import com.walletradar.costbasis.application.replay.state.ReplayExecutionState;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;

/**
 * Route-level replay handler selected by {@link ReplayRouteHandlerRegistry}.
 */
public interface ReplayRouteHandler {

    boolean supports(NormalizedTransaction transaction, ReplayRoutingDecision routingDecision);

    int getOrder();

    void apply(
            NormalizedTransaction transaction,
            ReplayRoutingDecision routingDecision,
            ReplayExecutionState replayState,
            ReplayDispatchCallbacks callbacks
    );
}
