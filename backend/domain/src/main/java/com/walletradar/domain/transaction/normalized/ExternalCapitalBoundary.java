package com.walletradar.domain.transaction.normalized;

/**
 * Venue-neutral external-capital boundary direction stamped at normalization time.
 *
 * <p>Replaces per-venue regex / predicate branching in the portfolio conservation gate.
 * Post-normalization consumers read this field only — they never re-derive venue semantics.</p>
 *
 * <ul>
 *   <li>{@code INFLOW} — a qualifying capital injection (Bybit: stablecoin FUND deposit;
 *       Dzengi: any priced inbound flow).</li>
 *   <li>{@code OUTFLOW} — a qualifying capital withdrawal (symmetric to INFLOW).</li>
 *   <li>{@code null} — not a capital boundary event (on-chain rows, non-capital-gate
 *       sub-accounts, ineligible asset types).</li>
 * </ul>
 */
public enum ExternalCapitalBoundary {
    INFLOW,
    OUTFLOW
}
