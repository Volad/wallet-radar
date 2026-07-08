package com.walletradar.application.costbasis.application.replay.model;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class AsyncLifecycleBucket {

    private static final MathContext MC = MathContext.DECIMAL128;

    private final Map<String, Deque<CarryTransfer>> carriesByAsset = new LinkedHashMap<>();
    private final Map<String, Deque<CarryTransfer>> executionFeeReservesByAsset = new LinkedHashMap<>();

    public void add(String assetIdentity, CarryTransfer carry) {
        carriesByAsset.computeIfAbsent(assetIdentity, ignored -> new ArrayDeque<>()).addLast(carry);
    }

    public void addExecutionFeeReserve(String assetIdentity, CarryTransfer carry) {
        executionFeeReservesByAsset.computeIfAbsent(assetIdentity, ignored -> new ArrayDeque<>()).addLast(carry);
    }

    public Set<String> knownAssetIdentities() {
        return Set.copyOf(carriesByAsset.keySet());
    }

    public CarryTransfer takeSameAssetCarry(
            String assetIdentity,
            BigDecimal requestedQuantity,
            AssetKey restoreAssetKey
    ) {
        return takeCarry(carriesByAsset, assetIdentity, requestedQuantity, restoreAssetKey);
    }

    public CarryTransfer takeExecutionFeeReserve(
            String assetIdentity,
            BigDecimal requestedQuantity,
            AssetKey restoreAssetKey
    ) {
        return takeCarry(executionFeeReservesByAsset, assetIdentity, requestedQuantity, restoreAssetKey);
    }

    public BigDecimal remainingCostBasisUsd() {
        BigDecimal total = BigDecimal.ZERO;
        for (Deque<CarryTransfer> queue : carriesByAsset.values()) {
            for (CarryTransfer carry : queue) {
                total = total.add(carry.costBasisUsd());
            }
        }
        return total;
    }

    public BigDecimal remainingExecutionFeeReserveCostBasisUsd() {
        BigDecimal total = BigDecimal.ZERO;
        for (Deque<CarryTransfer> queue : executionFeeReservesByAsset.values()) {
            for (CarryTransfer carry : queue) {
                total = total.add(carry.costBasisUsd());
            }
        }
        return total;
    }

    public BigDecimal remainingUncoveredQuantity() {
        BigDecimal total = BigDecimal.ZERO;
        for (Deque<CarryTransfer> queue : carriesByAsset.values()) {
            for (CarryTransfer carry : queue) {
                total = total.add(carry.uncoveredQuantity(), MC);
            }
        }
        return total;
    }

    public void clearAll() {
        carriesByAsset.clear();
        executionFeeReservesByAsset.clear();
    }

    public boolean isEmpty() {
        return carriesByAsset.isEmpty() && executionFeeReservesByAsset.isEmpty();
    }

    private CarryTransfer takeCarry(
            Map<String, Deque<CarryTransfer>> carries,
            String assetIdentity,
            BigDecimal requestedQuantity,
            AssetKey restoreAssetKey
    ) {
        Deque<CarryTransfer> queue = carries.get(assetIdentity);
        if (queue == null || queue.isEmpty()) {
            return null;
        }
        BigDecimal remainingRequested = requestedQuantity;
        BigDecimal appliedQuantity = BigDecimal.ZERO;
        BigDecimal appliedCoveredQuantity = BigDecimal.ZERO;
        BigDecimal appliedUncoveredQuantity = BigDecimal.ZERO;
        BigDecimal appliedCost = BigDecimal.ZERO;
        // ADR-040 Change 2: track net cost lane through bucket slicing
        BigDecimal appliedNetCost = BigDecimal.ZERO;

        while (remainingRequested.signum() > 0 && queue != null && !queue.isEmpty()) {
            CarryTransfer carry = queue.removeFirst();
            BigDecimal carryQuantity = carry.quantity();
            BigDecimal usedQuantity = carryQuantity.min(remainingRequested);
            BigDecimal usedCoveredQuantity = usedQuantity.min(carry.coveredQuantity());
            BigDecimal usedUncoveredQuantity = nonNegative(usedQuantity.subtract(usedCoveredQuantity, MC));
            BigDecimal usedCost = carry.avco() == null
                    ? BigDecimal.ZERO
                    : usedCoveredQuantity.multiply(carry.avco(), MC);
            // ADR-040 Change 2: net cost uses netAvco (falls back to avco for legacy carries)
            BigDecimal effectiveNetAvco = carry.netAvco() != null ? carry.netAvco() : carry.avco();
            BigDecimal usedNetCost = effectiveNetAvco == null
                    ? BigDecimal.ZERO
                    : usedCoveredQuantity.multiply(effectiveNetAvco, MC);

            appliedQuantity = appliedQuantity.add(usedQuantity, MC);
            appliedCoveredQuantity = appliedCoveredQuantity.add(usedCoveredQuantity, MC);
            appliedUncoveredQuantity = appliedUncoveredQuantity.add(usedUncoveredQuantity, MC);
            appliedCost = appliedCost.add(usedCost, MC);
            appliedNetCost = appliedNetCost.add(usedNetCost, MC);

            if (carryQuantity.compareTo(usedQuantity) > 0) {
                BigDecimal remainingQuantity = carryQuantity.subtract(usedQuantity, MC);
                BigDecimal remainingCoveredQuantity =
                        nonNegative(carry.coveredQuantity().subtract(usedCoveredQuantity, MC));
                BigDecimal remainingUncoveredQuantity =
                        nonNegative(carry.uncoveredQuantity().subtract(usedUncoveredQuantity, MC));
                BigDecimal remainingCost = nonNegative(carry.costBasisUsd().subtract(usedCost, MC));
                BigDecimal srcNetCost = carry.netCostBasisUsd() != null ? carry.netCostBasisUsd() : carry.costBasisUsd();
                BigDecimal remainingNetCost = nonNegative(srcNetCost.subtract(usedNetCost, MC));
                BigDecimal remainingAvco = remainingCoveredQuantity.signum() <= 0
                        ? null
                        : safeDivide(remainingCost, remainingCoveredQuantity);
                BigDecimal remainingNetAvco = remainingCoveredQuantity.signum() <= 0
                        ? null
                        : safeDivide(remainingNetCost, remainingCoveredQuantity);
                queue.addFirst(new CarryTransfer(
                        remainingQuantity,
                        remainingCoveredQuantity,
                        remainingUncoveredQuantity,
                        remainingCost,
                        remainingAvco,
                        remainingNetCost,
                        remainingNetAvco,
                        carry.pendingInbound(),
                        carry.assetKey()
                ));
            }
            remainingRequested = remainingRequested.subtract(usedQuantity, MC);
            if (queue.isEmpty()) {
                carries.remove(assetIdentity);
            }
            queue = carries.get(assetIdentity);
        }
        if (appliedQuantity.signum() <= 0) {
            return null;
        }
        BigDecimal appliedAvco = appliedCoveredQuantity.signum() <= 0
                ? null
                : safeDivide(appliedCost, appliedCoveredQuantity);
        BigDecimal appliedNetAvco = appliedCoveredQuantity.signum() <= 0
                ? null
                : safeDivide(appliedNetCost, appliedCoveredQuantity);
        return new CarryTransfer(
                appliedQuantity,
                appliedCoveredQuantity,
                appliedUncoveredQuantity,
                appliedCost,
                appliedAvco,
                appliedNetCost,
                appliedNetAvco,
                false,
                restoreAssetKey
        );
    }

    private static BigDecimal safeDivide(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.signum() == 0) {
            return null;
        }
        return numerator.divide(denominator, MC);
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        return value.signum() < 0 ? BigDecimal.ZERO : value;
    }
}
