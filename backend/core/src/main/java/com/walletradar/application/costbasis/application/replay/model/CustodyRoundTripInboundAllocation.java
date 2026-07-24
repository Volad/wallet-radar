package com.walletradar.application.costbasis.application.replay.model;

import java.math.BigDecimal;

/**
 * Finding 2 — the per-return-flow slice of a custody round-trip's pooled basis envelope.
 *
 * <p>The whole custody round-trip correlation is treated as ONE basis envelope: the total
 * carried-out basis (summed across every deposited asset family) is redistributed onto the
 * RETURNED assets by their market-value weights at return time, so
 * {@code Σ carried-in == Σ carried-out} exactly. Each returned flow receives:
 *
 * <ul>
 *   <li>{@code taxBasisUsd} / {@code netBasisUsd} — its value-weighted share of the pooled basis
 *       (never above the pooled total; {@code net ≤ tax}).</li>
 *   <li>{@code coveredQuantity} — the portion of the returned quantity backed at market avco. When
 *       the returned value exceeds the pooled basis, only the value-P worth of quantity is covered
 *       and the remainder ({@code uncoveredQuantity}) stays zero-basis surplus — basis is never
 *       minted above what was carried out.</li>
 * </ul>
 */
public record CustodyRoundTripInboundAllocation(
        BigDecimal quantity,
        BigDecimal coveredQuantity,
        BigDecimal uncoveredQuantity,
        BigDecimal taxBasisUsd,
        BigDecimal netBasisUsd,
        BigDecimal avco
) {
}
