package com.walletradar.application.costbasis.application.replay.state;

import com.walletradar.application.costbasis.domain.CounterpartyBasisPool;
import com.walletradar.application.costbasis.domain.CounterpartyBasisPoolKey;

import java.util.Map;
import java.util.Set;

/**
 * In-memory counterparty pool state for one replay run (ADR-015 §D5).
 */
public record CounterpartyBasisPoolReplayContext(
        String universeId,
        Map<CounterpartyBasisPoolKey, CounterpartyBasisPool> pools,
        Set<CounterpartyBasisPoolKey> dirtyKeys
) {
}
