package com.walletradar.testsupport;

import com.walletradar.costbasis.application.replay.handler.TransferReplayHandler;
import com.walletradar.costbasis.application.replay.support.ContinuityCarryService;
import com.walletradar.costbasis.application.replay.support.LinkedBridgeTransferReplaySupport;
import com.walletradar.costbasis.application.replay.support.ReplayFlowSupport;
import com.walletradar.costbasis.application.replay.support.ReplayMarketAuthority;
import com.walletradar.costbasis.application.replay.support.ReplayPendingTransferKeyFactory;
import com.walletradar.costbasis.application.replay.support.ReplayPendingTransferMatcher;
import com.walletradar.costbasis.application.replay.support.ReplayTransferClassifier;

/**
 * Test factory for replay handlers (Track A / A5 extract-class wiring).
 */
public final class TransferReplayHandlerFixtures {

    private TransferReplayHandlerFixtures() {
    }

    public static TransferReplayHandler handler(
            ReplayFlowSupport flowSupport,
            ContinuityCarryService carryService,
            ReplayPendingTransferKeyFactory keyFactory,
            ReplayTransferClassifier classifier,
            ReplayPendingTransferMatcher matcher,
            ReplayMarketAuthority marketAuthority
    ) {
        return new TransferReplayHandler(
                flowSupport,
                carryService,
                keyFactory,
                classifier,
                matcher,
                marketAuthority,
                linkedBridgeSupport(flowSupport, carryService, keyFactory, matcher)
        );
    }

    public static LinkedBridgeTransferReplaySupport linkedBridgeSupport(
            ReplayFlowSupport flowSupport,
            ContinuityCarryService carryService,
            ReplayPendingTransferKeyFactory keyFactory,
            ReplayPendingTransferMatcher matcher
    ) {
        return new LinkedBridgeTransferReplaySupport(flowSupport, carryService, keyFactory, matcher);
    }
}
