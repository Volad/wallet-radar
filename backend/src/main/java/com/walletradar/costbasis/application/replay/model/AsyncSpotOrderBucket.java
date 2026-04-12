package com.walletradar.costbasis.application.replay.model;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public final class AsyncSpotOrderBucket {

    private final Deque<AsyncSpotOrderCarry> carries = new ArrayDeque<>();

    public void add(CarryTransfer carry, com.walletradar.domain.transaction.normalized.NormalizedTransaction.Flow requestFlow) {
        carries.addLast(new AsyncSpotOrderCarry(carry, requestFlow));
    }

    public BigDecimal totalCostBasisUsd() {
        BigDecimal total = BigDecimal.ZERO;
        for (AsyncSpotOrderCarry entry : carries) {
            total = total.add(entry.carry().costBasisUsd());
        }
        return total;
    }

    public BigDecimal totalQuantity() {
        BigDecimal total = BigDecimal.ZERO;
        for (AsyncSpotOrderCarry entry : carries) {
            total = total.add(entry.carry().quantity());
        }
        return total;
    }

    public List<AsyncSpotOrderCarry> drainAll() {
        List<AsyncSpotOrderCarry> drained = new ArrayList<>(carries);
        carries.clear();
        return drained;
    }

    public boolean isEmpty() {
        return carries.isEmpty();
    }
}
