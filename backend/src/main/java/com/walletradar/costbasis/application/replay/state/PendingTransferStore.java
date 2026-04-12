package com.walletradar.costbasis.application.replay.state;

import com.walletradar.costbasis.application.replay.model.CarryTransfer;
import com.walletradar.costbasis.application.replay.model.PendingTransferKey;

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
}
