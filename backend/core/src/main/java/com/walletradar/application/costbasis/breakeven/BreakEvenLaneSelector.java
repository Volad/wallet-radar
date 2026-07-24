package com.walletradar.application.costbasis.breakeven;

import java.math.BigDecimal;

/**
 * ADR-062 (2026-07-24 amendment) shared lane-pick helper. Centralizes the single rule
 * "under {@link OffsetLane#NET} the effective-cost numerator consumes the <b>Net</b> (real-cash)
 * lane; under {@link OffsetLane#MARKET} it consumes the <b>Market</b> lane" so every consumer
 * (break-even header via {@link BreakEvenCalculator}, and the move-basis series via
 * {@code AssetLedgerChartService}) selects the numerator lane in exactly one place and cannot drift.
 *
 * <p>Held zero-cost income (staking rewards / airdrops / LP-fee / lending interest received and
 * still held, never sold) lowers the Net-lane held basis but generates no realized P&amp;L, so under
 * NET the numerator must be the Net-lane held basis/AVCO for that income to be credited as free
 * (sAVAX exemplar: $11.96 Market AVCO → $0.53 Net AVCO). Pure and null-safe; the caller applies any
 * {@code zeroIfNull}/dust handling.</p>
 */
public final class BreakEvenLaneSelector {

    private BreakEvenLaneSelector() {
    }

    /**
     * Selects the held-basis numerator for the configured lane: {@code netBasis} under
     * {@link OffsetLane#NET}, {@code marketBasis} otherwise (MARKET or {@code null} lane). Null-safe:
     * the chosen value is returned verbatim (may be {@code null}).
     */
    public static BigDecimal chooseLaneBasis(OffsetLane lane, BigDecimal marketBasis, BigDecimal netBasis) {
        return lane == OffsetLane.NET ? netBasis : marketBasis;
    }

    /**
     * Selects the per-unit AVCO numerator for the configured lane: {@code netAvco} under
     * {@link OffsetLane#NET}, {@code marketAvco} otherwise (MARKET or {@code null} lane). Null-safe:
     * the chosen value is returned verbatim (may be {@code null}).
     */
    public static BigDecimal chooseLaneAvco(OffsetLane lane, BigDecimal marketAvco, BigDecimal netAvco) {
        return lane == OffsetLane.NET ? netAvco : marketAvco;
    }
}
