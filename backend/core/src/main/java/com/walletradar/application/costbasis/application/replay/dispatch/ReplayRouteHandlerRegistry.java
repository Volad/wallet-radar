package com.walletradar.application.costbasis.application.replay.dispatch;

import com.walletradar.application.costbasis.application.replay.planning.ReplayRoute;
import com.walletradar.application.costbasis.application.replay.planning.ReplayRoutingDecision;
import com.walletradar.application.costbasis.application.replay.state.ReplayExecutionState;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Ordered registry of route-level replay handlers (Track A / A5).
 */
public class ReplayRouteHandlerRegistry {

    private final List<ReplayRouteHandler> handlers;

    public ReplayRouteHandlerRegistry(List<ReplayRouteHandler> handlers) {
        this(handlers, true);
    }

    ReplayRouteHandlerRegistry(List<ReplayRouteHandler> handlers, boolean validateRouteCoverage) {
        List<ReplayRouteHandler> sorted = new ArrayList<>(handlers);
        sorted.sort(Comparator.comparingInt(ReplayRouteHandler::getOrder));
        if (validateRouteCoverage) {
            validateNoOverlappingSupport(sorted);
        }
        this.handlers = List.copyOf(sorted);
    }

    public void dispatch(
            NormalizedTransaction transaction,
            ReplayRoutingDecision routingDecision,
            ReplayExecutionState replayState,
            ReplayDispatchCallbacks callbacks
    ) {
        for (ReplayRouteHandler handler : handlers) {
            if (handler.supports(transaction, routingDecision)) {
                handler.apply(transaction, routingDecision, replayState, callbacks);
                return;
            }
        }
        throw new IllegalStateException("No replay route handler for route " + routingDecision.route());
    }

    List<ReplayRouteHandler> orderedHandlers() {
        return handlers;
    }

    private static void validateNoOverlappingSupport(List<ReplayRouteHandler> sortedHandlers) {
        Map<ReplayRoute, ReplayRouteHandler> ownerByRoute = new EnumMap<>(ReplayRoute.class);
        for (ReplayRoute route : ReplayRoute.values()) {
            ReplayRoutingDecision decision = decisionFor(route);
            List<ReplayRouteHandler> supporters = sortedHandlers.stream()
                    .filter(handler -> handler.supports(null, decision))
                    .toList();
            if (supporters.isEmpty()) {
                throw new IllegalStateException("No replay route handler supports route " + route);
            }
            if (supporters.size() > 1) {
                throw new IllegalStateException(
                        "Multiple replay route handlers support route " + route + ": " + supporters
                );
            }
            ownerByRoute.put(route, supporters.getFirst());
        }
    }

    static ReplayRoutingDecision decisionFor(ReplayRoute route) {
        return switch (route) {
            case EULER_LOOP -> ReplayRoutingDecision.of(ReplayRoute.EULER_LOOP);
            case GMX_LP_ENTRY_REQUEST -> ReplayRoutingDecision.of(ReplayRoute.GMX_LP_ENTRY_REQUEST);
            case GMX_LP_ENTRY_SETTLEMENT -> ReplayRoutingDecision.of(ReplayRoute.GMX_LP_ENTRY_SETTLEMENT);
            case LP_RECEIPT_ENTRY -> ReplayRoutingDecision.of(ReplayRoute.LP_RECEIPT_ENTRY);
            case ASYNC_LP_EXIT_SETTLEMENT -> ReplayRoutingDecision.of(ReplayRoute.ASYNC_LP_EXIT_SETTLEMENT);
            case POSITION_SCOPED_LP_EXIT -> ReplayRoutingDecision.of(ReplayRoute.POSITION_SCOPED_LP_EXIT);
            case CLUSTER_CARRY -> ReplayRoutingDecision.clusterCarry(
                    com.walletradar.application.costbasis.application.replay.model.LiquidStakingFlowSelection.empty()
            );
            case FAMILY_EQUIVALENT_CUSTODY -> ReplayRoutingDecision.familyEquivalentCustody(
                    com.walletradar.application.costbasis.application.replay.model.SimpleFamilyCustodySelection.empty()
            );
            case GENERIC -> ReplayRoutingDecision.generic();
        };
    }
}
