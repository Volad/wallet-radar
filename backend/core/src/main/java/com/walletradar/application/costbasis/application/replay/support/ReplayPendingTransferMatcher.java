package com.walletradar.application.costbasis.application.replay.support;

import com.walletradar.application.costbasis.application.replay.model.CarryTransfer;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Deque;

@Component
public class ReplayPendingTransferMatcher {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal RELATIVE_TOLERANCE = new BigDecimal("0.000001");
    private static final BigDecimal ABSOLUTE_TOLERANCE = new BigDecimal("0.00000001");
    private static final BigDecimal MAX_TOLERANCE = new BigDecimal("0.0001");

    public int findUniqueCompatibleQueueIndex(
            Deque<CarryTransfer> queue,
            boolean pendingInbound,
            BigDecimal targetQuantity
    ) {
        if (queue == null || queue.isEmpty() || targetQuantity == null || targetQuantity.signum() <= 0) {
            return -1;
        }
        int matchedIndex = -1;
        int index = 0;
        for (CarryTransfer candidate : queue) {
            if (candidate == null || candidate.pendingInbound() != pendingInbound) {
                index++;
                continue;
            }
            if (!correlatedTransferQuantitiesCompatible(candidate.quantity(), targetQuantity)) {
                index++;
                continue;
            }
            if (matchedIndex >= 0) {
                return -1;
            }
            matchedIndex = index;
            index++;
        }
        return matchedIndex;
    }

    public int findUniqueBridgeQueueIndex(
            Deque<CarryTransfer> queue,
            boolean pendingInbound
    ) {
        if (queue == null || queue.isEmpty()) {
            return -1;
        }
        int matchedIndex = -1;
        int index = 0;
        for (CarryTransfer candidate : queue) {
            if (candidate == null || candidate.pendingInbound() != pendingInbound) {
                index++;
                continue;
            }
            if (matchedIndex >= 0) {
                return -1;
            }
            matchedIndex = index;
            index++;
        }
        return matchedIndex;
    }

    public CarryTransfer removeQueueElement(Deque<CarryTransfer> queue, int index) {
        if (queue == null || index < 0 || index >= queue.size()) {
            return null;
        }
        int currentIndex = 0;
        for (var iterator = queue.iterator(); iterator.hasNext(); ) {
            CarryTransfer candidate = iterator.next();
            if (currentIndex == index) {
                iterator.remove();
                return candidate;
            }
            currentIndex++;
        }
        return null;
    }

    private boolean correlatedTransferQuantitiesCompatible(
            BigDecimal leftQuantity,
            BigDecimal rightQuantity
    ) {
        if (leftQuantity == null
                || rightQuantity == null
                || leftQuantity.signum() <= 0
                || rightQuantity.signum() <= 0) {
            return false;
        }
        BigDecimal delta = leftQuantity.subtract(rightQuantity, MC).abs();
        BigDecimal baseline = leftQuantity.max(rightQuantity);
        BigDecimal tolerance = baseline.multiply(RELATIVE_TOLERANCE, MC)
                .max(ABSOLUTE_TOLERANCE)
                .min(MAX_TOLERANCE);
        return delta.compareTo(tolerance) <= 0;
    }
}
