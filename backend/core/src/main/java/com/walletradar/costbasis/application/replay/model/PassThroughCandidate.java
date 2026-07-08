package com.walletradar.costbasis.application.replay.model;

import java.math.BigDecimal;

/**
 * @param networkId the network on which the candidate inbound flow occurred, used by the
 *                  wallet-scoped fallback in {@code PassThroughCorridorPlanner} to reject
 *                  cross-network pairings (ADR-020, P0-b defensive guard).
 */
public record PassThroughCandidate(
        FlowRef flowRef,
        PassThroughScopeKey scopeKey,
        BigDecimal quantity,
        String networkId
) {
}
