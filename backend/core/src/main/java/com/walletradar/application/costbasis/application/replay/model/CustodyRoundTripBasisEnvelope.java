package com.walletradar.application.costbasis.application.replay.model;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.HashMap;
import java.util.Map;

/**
 * Finding 2 — a single pooled basis envelope for one {@code bridge:custody-roundtrip:} correlation.
 *
 * <p>Per-family independent carry breaks conservation whenever a custody vault rebalances its asset
 * composition inside the round-trip (deposit A+B, withdraw less A + more B): restoring each family's
 * carried-out basis independently over a different returned quantity both inflates the shrunken
 * family's avco and re-prices the grown family's surplus at market. This envelope removes that
 * per-family coupling: every deposited family's carried-out basis is pooled here, then redistributed
 * onto the RETURNED assets by market-value weight at return time so
 * {@code Σ carried-in == Σ carried-out} exactly, with {@code rPnL = 0}.
 *
 * <p>The envelope is keyed by correlation id in {@code ReplayExecutionState}, so it survives the
 * (potentially multi-day) gap between the OUT and IN transactions. It is populated by the OUT legs
 * (one accumulation per deposited principal) and consumed once by the IN transaction; the inbound
 * allocation is memoised per inbound transaction id so multi-leg returns are allocated together.
 */
public final class CustodyRoundTripBasisEnvelope {

    private static final MathContext MC = MathContext.DECIMAL128;

    private BigDecimal taxBasisUsd = BigDecimal.ZERO;
    private BigDecimal netBasisUsd = BigDecimal.ZERO;

    /** Memoised per-inbound-transaction allocation: transactionId → (flowIndex → allocation). */
    private final Map<String, Map<Integer, CustodyRoundTripInboundAllocation>> inboundAllocations = new HashMap<>();

    /** Adds a deposited principal's carried-out basis to the pool ({@code net ≤ tax} preserved). */
    public void addCarriedOut(BigDecimal taxBasis, BigDecimal netBasis) {
        if (taxBasis != null && taxBasis.signum() > 0) {
            taxBasisUsd = taxBasisUsd.add(taxBasis, MC);
        }
        BigDecimal net = netBasis == null ? BigDecimal.ZERO : netBasis;
        if (net.signum() > 0) {
            netBasisUsd = netBasisUsd.add(net, MC);
        }
        if (netBasisUsd.compareTo(taxBasisUsd) > 0) {
            netBasisUsd = taxBasisUsd;
        }
    }

    public BigDecimal taxBasisUsd() {
        return taxBasisUsd;
    }

    public BigDecimal netBasisUsd() {
        return netBasisUsd;
    }

    public boolean hasInboundAllocation(String transactionId) {
        return transactionId != null && inboundAllocations.containsKey(transactionId);
    }

    public Map<Integer, CustodyRoundTripInboundAllocation> inboundAllocation(String transactionId) {
        return inboundAllocations.get(transactionId);
    }

    public void putInboundAllocation(String transactionId, Map<Integer, CustodyRoundTripInboundAllocation> allocation) {
        if (transactionId != null) {
            inboundAllocations.put(transactionId, allocation);
        }
    }

    /** Drains the pooled basis once its return allocation has been computed. */
    public void consumeBasis() {
        taxBasisUsd = BigDecimal.ZERO;
        netBasisUsd = BigDecimal.ZERO;
    }
}
