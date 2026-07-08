package com.walletradar.costbasis.application.replay.dispatch;

import com.walletradar.costbasis.application.replay.handler.EulerLoopReplayHandler;
import com.walletradar.costbasis.application.replay.handler.FamilyEquivalentCustodyReplayHandler;
import com.walletradar.costbasis.application.replay.handler.GenericAsyncLifecycleReplayHandler;
import com.walletradar.costbasis.application.replay.handler.GmxLpEntryReplayHandler;
import com.walletradar.costbasis.application.replay.handler.LiquidStakingReplayHandler;
import com.walletradar.costbasis.application.replay.handler.LpReceiptEntryReplayHandler;
import com.walletradar.costbasis.application.replay.handler.PositionScopedLpExitReplayHandler;

import java.util.List;

/**
 * Factory for constructing a production-parity {@link ReplayRouteHandlerRegistry}.
 */
public final class ReplayRouteHandlerRegistryFactory {

    private ReplayRouteHandlerRegistryFactory() {
    }

    public static ReplayRouteHandlerRegistry create(
            EulerLoopReplayHandler eulerLoopReplayHandler,
            GmxLpEntryReplayHandler gmxLpEntryReplayHandler,
            LpReceiptEntryReplayHandler lpReceiptEntryReplayHandler,
            GenericAsyncLifecycleReplayHandler genericAsyncLifecycleReplayHandler,
            PositionScopedLpExitReplayHandler positionScopedLpExitReplayHandler,
            LiquidStakingReplayHandler liquidStakingReplayHandler,
            FamilyEquivalentCustodyReplayHandler familyEquivalentCustodyReplayHandler
    ) {
        return new ReplayRouteHandlerRegistry(List.of(
                new ReplayRouteHandlerAdapters.EulerLoopRouteHandler(eulerLoopReplayHandler),
                new ReplayRouteHandlerAdapters.GmxLpEntryRequestRouteHandler(gmxLpEntryReplayHandler),
                new ReplayRouteHandlerAdapters.GmxLpEntrySettlementRouteHandler(gmxLpEntryReplayHandler),
                new ReplayRouteHandlerAdapters.LpReceiptEntryRouteHandler(lpReceiptEntryReplayHandler),
                new ReplayRouteHandlerAdapters.AsyncLpExitSettlementRouteHandler(genericAsyncLifecycleReplayHandler),
                new ReplayRouteHandlerAdapters.PositionScopedLpExitRouteHandler(positionScopedLpExitReplayHandler),
                new ReplayRouteHandlerAdapters.LiquidStakingRouteHandler(liquidStakingReplayHandler),
                new ReplayRouteHandlerAdapters.FamilyEquivalentCustodyRouteHandler(familyEquivalentCustodyReplayHandler),
                new ReplayRouteHandlerAdapters.GenericRouteHandler()
        ));
    }
}
