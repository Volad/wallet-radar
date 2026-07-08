package com.walletradar.application.costbasis.application.replay.state;

import com.walletradar.application.costbasis.domain.BorrowLiability;

import java.util.Map;
import java.util.Set;

/**
 * In-memory borrow-liability book for one replay run (ADR-012 §D5).
 */
public record BorrowLiabilityReplayContext(
        String universeId,
        Map<String, BorrowLiability> liabilitiesByCompositeId,
        Set<String> dirtyCompositeIds
) {
}
