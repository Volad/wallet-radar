package com.walletradar.application.costbasis.application.replay.support;

/**
 * RC-9 D3 — thrown only when {@link CorridorBasisConservationGuard#SEVERITY} is promoted to
 * {@code HARD_FAIL} and an orphaned released covered carry survives to end of replay.
 */
public class CorridorBasisConservationException extends RuntimeException {

    private final CorridorBasisConservationResult result;

    public CorridorBasisConservationException(CorridorBasisConservationResult result) {
        super("Corridor basis conservation breach: " + result.breaches().size()
                + " orphaned released carry-out(s) totalling " + result.totalOrphanedBasisUsd() + " USD");
        this.result = result;
    }

    public CorridorBasisConservationResult getResult() {
        return result;
    }
}
