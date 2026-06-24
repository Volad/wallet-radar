package com.walletradar.costbasis.application.replay.support;

import java.math.BigDecimal;

/**
 * RC-9 D3 — thrown only when {@link CorridorBasisConservationGuard#SEVERITY} is promoted to
 * {@code HARD_FAIL} and an orphaned released covered carry survives to end of replay.
 */
public class CorridorBasisConservationException extends RuntimeException {

    public CorridorBasisConservationException(int breachCount, BigDecimal totalOrphanedBasisUsd) {
        super("Corridor basis conservation breach: " + breachCount
                + " orphaned released carry-out(s) totalling " + totalOrphanedBasisUsd + " USD");
    }
}
