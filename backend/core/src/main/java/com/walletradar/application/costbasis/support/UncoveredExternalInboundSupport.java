package com.walletradar.application.costbasis.support;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;

import java.util.List;

/**
 * B2b — single source of truth for the "sourceless external inbound is basis-unknown / uncovered"
 * marker.
 *
 * <p>A {@code BRIDGE_IN} whose funds were bridged in from outside our transaction universe has no
 * correlatable source leg, so its cost basis is genuinely unknown. The classification/linking stage
 * reclassifies such a leg to {@code EXTERNAL_TRANSFER_IN} and stamps
 * {@link #SOURCELESS_EXTERNAL_INBOUND_BASIS_UNKNOWN} on it (see
 * {@code SourcelessBridgeInboundReclassificationService}). At replay, this marker forces the inbound
 * onto the uncovered / incomplete-history (PENDING) route — the same route a canonical inbound takes
 * when no quote resolves — instead of fabricating a market-at-arrival basis that would silently
 * dilute the pooled AVCO with a cost the wallet never actually paid.</p>
 *
 * <p>The marker is deterministic (a durable {@code missingDataReasons} entry, re-stamped every
 * renormalization by the reclassification pass), so the replay gate is byte-identical for every row
 * that does not carry it.</p>
 */
public final class UncoveredExternalInboundSupport {

    /**
     * Stamped on an {@code EXTERNAL_TRANSFER_IN} reclassified from a sourceless {@code BRIDGE_IN}.
     * Replay treats the flagged inbound as uncovered (basis-unknown) rather than market-priced.
     */
    public static final String SOURCELESS_EXTERNAL_INBOUND_BASIS_UNKNOWN =
            "SOURCELESS_EXTERNAL_INBOUND_BASIS_UNKNOWN";

    private UncoveredExternalInboundSupport() {
    }

    /**
     * {@code true} when the transaction carries the sourceless-inbound basis-unknown marker, so the
     * replay engine must leave its inbound leg uncovered instead of resolving a market basis.
     */
    public static boolean isBasisUnknownInbound(NormalizedTransaction transaction) {
        if (transaction == null) {
            return false;
        }
        List<String> reasons = transaction.getMissingDataReasons();
        return reasons != null && reasons.contains(SOURCELESS_EXTERNAL_INBOUND_BASIS_UNKNOWN);
    }
}
