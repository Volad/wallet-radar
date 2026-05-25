package com.walletradar.costbasis.application.replay.state;

import com.walletradar.costbasis.application.replay.model.CarryTransfer;
import com.walletradar.costbasis.application.replay.model.PendingTransferKey;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
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
}
