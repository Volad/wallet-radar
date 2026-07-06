package com.walletradar.costbasis.application.replay.support;

import java.math.BigDecimal;
import java.util.List;

/**
 * RC-9 D3 — structured outcome of the end-of-replay corridor/bridge conservation sweep.
 * {@code conserved()} is true when no orphaned released covered carry remains.
 */
public record CorridorBasisConservationResult(
        List<Breach> breaches,
        BigDecimal totalOrphanedBasisUsd
) {

    public static CorridorBasisConservationResult empty() {
        return new CorridorBasisConservationResult(List.of(), BigDecimal.ZERO);
    }

    public boolean conserved() {
        return breaches == null || breaches.isEmpty();
    }

    /** One orphaned released covered carry-out left in a guarded queue. */
    public record Breach(
            String queueKey,
            String assetSymbol,
            BigDecimal orphanedQuantity,
            BigDecimal orphanedBasisUsd
    ) {
    }
}
