package com.walletradar.application.costbasis.application.replay.dispatch;

import com.walletradar.application.costbasis.application.replay.planning.ReplayRoute;
import com.walletradar.application.costbasis.application.replay.planning.ReplayRoutingDecision;
import com.walletradar.application.costbasis.application.replay.state.ReplayExecutionState;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ReplayRouteHandlerRegistryTest {

    @Test
    @DisplayName("Exactly one handler supports each ReplayRoute")
    void noTwoHandlersSupportSameRoute() {
        ReplayRouteHandlerRegistry registry = new ReplayRouteHandlerRegistry(sampleHandlers());
        for (ReplayRoute route : ReplayRoute.values()) {
            ReplayRoutingDecision decision = ReplayRouteHandlerRegistry.decisionFor(route);
            long supporters = registry.orderedHandlers().stream()
                    .filter(handler -> handler.supports(null, decision))
                    .count();
            assertThat(supporters)
                    .as("route %s", route)
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("Handler order matches legacy ReplayDispatcher switch precedence")
    void orderParityWithLegacySwitch() {
        List<ReplayRouteHandler> handlers = new ReplayRouteHandlerRegistry(sampleHandlers()).orderedHandlers();
        assertThat(handlers).extracting(ReplayRouteHandler::getOrder)
                .containsExactly(10, 20, 30, 40, 50, 60, 70, 80, 100);
        assertThat(handlers.get(0).supports(null, ReplayRouteHandlerRegistry.decisionFor(ReplayRoute.EULER_LOOP))).isTrue();
        assertThat(handlers.get(handlers.size() - 1).supports(null, ReplayRouteHandlerRegistry.decisionFor(ReplayRoute.GENERIC))).isTrue();
    }

    @Test
    @DisplayName("Registry dispatches to first matching handler by order")
    void dispatchesFirstMatchingHandler() {
        RecordingHandler euler = new RecordingHandler(10, ReplayRoute.EULER_LOOP);
        RecordingHandler generic = new RecordingHandler(100, ReplayRoute.GENERIC);
        ReplayRouteHandlerRegistry registry = new ReplayRouteHandlerRegistry(List.of(generic, euler), false);

        registry.dispatch(
                mock(NormalizedTransaction.class),
                ReplayRouteHandlerRegistry.decisionFor(ReplayRoute.EULER_LOOP),
                mock(ReplayExecutionState.class),
                mock(ReplayDispatchCallbacks.class)
        );

        assertThat(euler.invoked).isTrue();
        assertThat(generic.invoked).isFalse();
    }

    private static List<ReplayRouteHandler> sampleHandlers() {
        return List.of(
                routeHandler(10, ReplayRoute.EULER_LOOP),
                routeHandler(20, ReplayRoute.GMX_LP_ENTRY_REQUEST),
                routeHandler(30, ReplayRoute.GMX_LP_ENTRY_SETTLEMENT),
                routeHandler(40, ReplayRoute.LP_RECEIPT_ENTRY),
                routeHandler(50, ReplayRoute.ASYNC_LP_EXIT_SETTLEMENT),
                routeHandler(60, ReplayRoute.POSITION_SCOPED_LP_EXIT),
                routeHandler(70, ReplayRoute.LIQUID_STAKING),
                routeHandler(80, ReplayRoute.FAMILY_EQUIVALENT_CUSTODY),
                routeHandler(100, ReplayRoute.GENERIC)
        );
    }

    private static ReplayRouteHandler routeHandler(int order, ReplayRoute route) {
        return new ReplayRouteHandler() {
            @Override
            public boolean supports(NormalizedTransaction transaction, ReplayRoutingDecision routingDecision) {
                return routingDecision.route() == route;
            }

            @Override
            public int getOrder() {
                return order;
            }

            @Override
            public void apply(
                    NormalizedTransaction transaction,
                    ReplayRoutingDecision routingDecision,
                    ReplayExecutionState replayState,
                    ReplayDispatchCallbacks callbacks
            ) {
                // no-op
            }
        };
    }

    private static final class RecordingHandler implements ReplayRouteHandler {
        private final int order;
        private final ReplayRoute route;
        private boolean invoked;

        private RecordingHandler(int order, ReplayRoute route) {
            this.order = order;
            this.route = route;
        }

        @Override
        public boolean supports(NormalizedTransaction transaction, ReplayRoutingDecision routingDecision) {
            return routingDecision.route() == route;
        }

        @Override
        public int getOrder() {
            return order;
        }

        @Override
        public void apply(
                NormalizedTransaction transaction,
                ReplayRoutingDecision routingDecision,
                ReplayExecutionState replayState,
                ReplayDispatchCallbacks callbacks
        ) {
            invoked = true;
        }
    }
}
