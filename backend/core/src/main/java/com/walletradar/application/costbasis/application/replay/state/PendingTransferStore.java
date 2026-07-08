package com.walletradar.application.costbasis.application.replay.state;

import com.walletradar.application.costbasis.application.replay.model.CarryTransfer;
import com.walletradar.application.costbasis.application.replay.model.PendingTransferKey;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PendingTransferStore {

    private final Map<PendingTransferKey, Deque<CarryTransfer>> queues = new LinkedHashMap<>();

    public Deque<CarryTransfer> queue(PendingTransferKey key) {
        return queues.computeIfAbsent(key, ignored -> new ArrayDeque<>());
    }

    public Deque<CarryTransfer> find(PendingTransferKey key) {
        return queues.get(key);
    }

    public void remove(PendingTransferKey key) {
        queues.remove(key);
    }

    /**
     * Cycle/18 R9: after inbound shortfall spot fallback, bump provisional basis on queued
     * pending inbounds so late carry can replace the exact promoted amount.
     */
    public void addProvisionalBasisToPendingInbounds(PendingTransferKey key, BigDecimal additionalProvisionalBasisUsd) {
        if (key == null || additionalProvisionalBasisUsd == null || additionalProvisionalBasisUsd.signum() == 0) {
            return;
        }
        Deque<CarryTransfer> queue = queues.get(key);
        if (queue == null || queue.isEmpty()) {
            return;
        }
        Deque<CarryTransfer> updated = new ArrayDeque<>(queue.size());
        for (CarryTransfer carry : queue) {
            if (carry != null && carry.pendingInbound()) {
                updated.addLast(carry.withAdditionalProvisionalBasis(additionalProvisionalBasisUsd));
            } else {
                updated.addLast(carry);
            }
        }
        queues.put(key, updated);
    }

    /**
     * RC-9 D3 — read-only end-of-replay view of residual <b>released covered</b> carries: queued
     * {@code CARRY_OUT} entries (i.e. not {@code pendingInbound}) that still hold cost basis above
     * {@code epsilon} after the last event. A non-empty result means a counterpart credit never
     * inherited the released basis (an orphan). Returns immutable references to the stored carries
     * and never mutates the store.
     */
    public List<ResidualCoveredCarry> residualCoveredCarries(BigDecimal epsilon) {
        BigDecimal threshold = epsilon == null ? BigDecimal.ZERO : epsilon.abs();
        List<ResidualCoveredCarry> residuals = new ArrayList<>();
        for (Map.Entry<PendingTransferKey, Deque<CarryTransfer>> entry : queues.entrySet()) {
            String queueKey = entry.getKey().value();
            for (CarryTransfer carry : entry.getValue()) {
                if (carry == null || carry.pendingInbound()) {
                    continue;
                }
                BigDecimal basis = carry.costBasisUsd() == null ? BigDecimal.ZERO : carry.costBasisUsd();
                if (basis.abs().compareTo(threshold) > 0) {
                    residuals.add(new ResidualCoveredCarry(queueKey, carry));
                }
            }
        }
        return residuals;
    }

    /**
     * RC-9 D3 / G-1 (WS-E) — queue keys of every non-empty pending queue at end of replay. Used by
     * the conservation guard to consult a corridor's <b>counterpart leg</b>: a leftover CARRY_OUT
     * whose corridor also holds a leg of a different asset/family is a legitimate cross-asset swap,
     * not an orphan. Read-only; never mutates the store.
     */
    public List<String> nonEmptyQueueKeys() {
        List<String> keys = new ArrayList<>();
        for (Map.Entry<PendingTransferKey, Deque<CarryTransfer>> entry : queues.entrySet()) {
            if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                keys.add(entry.getKey().value());
            }
        }
        return keys;
    }

    /** A released covered carry-out left orphaned in a pending-transfer queue at end of replay. */
    public record ResidualCoveredCarry(String queueKey, CarryTransfer carry) {
    }
}
