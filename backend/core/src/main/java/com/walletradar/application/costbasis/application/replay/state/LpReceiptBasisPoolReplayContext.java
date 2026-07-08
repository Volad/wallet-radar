package com.walletradar.application.costbasis.application.replay.state;

import com.walletradar.application.costbasis.domain.LpReceiptBasisPool;
import com.walletradar.application.costbasis.domain.LpReceiptBasisPoolKey;

import java.util.Map;
import java.util.Set;

/**
 * In-memory LP receipt basis pool state for one replay run (Cycle/15 Z3).
 */
public record LpReceiptBasisPoolReplayContext(
        String universeId,
        Map<LpReceiptBasisPoolKey, LpReceiptBasisPool> pools,
        Set<LpReceiptBasisPoolKey> dirtyKeys
) {
}
