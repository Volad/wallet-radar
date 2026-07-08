package com.walletradar.testsupport;

import com.walletradar.application.costbasis.application.replay.handler.BridgeTransferReplaySupport;
import com.walletradar.application.costbasis.application.replay.handler.CarryTransferReplaySupport;
import com.walletradar.application.costbasis.application.replay.handler.EarnBundleTransferReplaySupport;
import com.walletradar.application.costbasis.application.replay.handler.TransferReplayHandler;
import com.walletradar.application.costbasis.application.replay.support.ContinuityCarryService;
import com.walletradar.application.costbasis.application.replay.support.LinkedBridgeTransferReplaySupport;
import com.walletradar.application.costbasis.application.replay.support.ReplayFlowSupport;
import com.walletradar.application.costbasis.application.replay.support.ReplayMarketAuthority;
import com.walletradar.application.costbasis.application.replay.support.ReplayPendingTransferKeyFactory;
import com.walletradar.application.costbasis.application.replay.support.ReplayPendingTransferMatcher;
import com.walletradar.application.costbasis.application.replay.support.ReplayTransferClassifier;

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
        BridgeTransferReplaySupport bridgeSupport = new BridgeTransferReplaySupport(
                flowSupport,
                keyFactory,
                classifier,
                carryService,
                matcher,
                marketAuthority
        );
        EarnBundleTransferReplaySupport earnSupport = new EarnBundleTransferReplaySupport(
                keyFactory,
                matcher,
                flowSupport,
                carryService,
                classifier,
                marketAuthority,
                bridgeSupport
        );
        CarryTransferReplaySupport carryTransferSupport = new CarryTransferReplaySupport(
                flowSupport,
                carryService
        );
        return new TransferReplayHandler(
                flowSupport,
                carryService,
                keyFactory,
                classifier,
                linkedBridgeSupport(flowSupport, carryService, keyFactory, matcher),
                bridgeSupport,
                earnSupport,
                carryTransferSupport
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
