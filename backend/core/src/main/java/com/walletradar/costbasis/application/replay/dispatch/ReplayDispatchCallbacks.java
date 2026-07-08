package com.walletradar.costbasis.application.replay.dispatch;

import com.walletradar.costbasis.application.replay.state.ReplayExecutionState;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;

import java.util.Set;

/**
 * Callbacks from {@link ReplayDispatcher} into generic per-flow replay paths.
 */
public interface ReplayDispatchCallbacks {

    void replayGenericFlows(NormalizedTransaction transaction, ReplayExecutionState replayState);

    void replayGenericFlowsSkipping(
            NormalizedTransaction transaction,
            ReplayExecutionState replayState,
            Set<Integer> skippedIndexes
    );

    void applyLeverageBorrowIfNeeded(NormalizedTransaction transaction, ReplayExecutionState replayState);
}
