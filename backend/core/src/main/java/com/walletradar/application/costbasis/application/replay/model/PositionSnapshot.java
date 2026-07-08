package com.walletradar.application.costbasis.application.replay.model;

import java.math.BigDecimal;

public record PositionSnapshot(
        BigDecimal quantity,
        BigDecimal perWalletAvco,
        BigDecimal totalCostBasisUsd,
        BigDecimal perWalletNetAvco,
        BigDecimal netTotalCostBasisUsd,
        BigDecimal totalGasPaidUsd,
        BigDecimal totalRealisedPnlUsd,
        BigDecimal totalNetRealisedPnlUsd,
        BigDecimal quantityShortfall,
        BigDecimal uncoveredQuantity,
        boolean hasIncompleteHistory,
        boolean hasUnresolvedFlags,
        int unresolvedFlagCount
) {
    /** Test / legacy helper: net lane mirrors tax when net-specific values are not under test. */
    public static PositionSnapshot mirrorTax(
            BigDecimal quantity,
            BigDecimal perWalletAvco,
            BigDecimal totalCostBasisUsd,
            BigDecimal totalGasPaidUsd,
            BigDecimal totalRealisedPnlUsd,
            BigDecimal quantityShortfall,
            BigDecimal uncoveredQuantity,
            boolean hasIncompleteHistory,
            boolean hasUnresolvedFlags,
            int unresolvedFlagCount
    ) {
        return new PositionSnapshot(
                quantity,
                perWalletAvco,
                totalCostBasisUsd,
                perWalletAvco,
                totalCostBasisUsd,
                totalGasPaidUsd,
                totalRealisedPnlUsd,
                totalRealisedPnlUsd,
                quantityShortfall,
                uncoveredQuantity,
                hasIncompleteHistory,
                hasUnresolvedFlags,
                unresolvedFlagCount
        );
    }

    public boolean sameAs(PositionState state) {
        return sameDecimal(quantity, state.quantity())
                && sameDecimal(perWalletAvco, state.perWalletAvco())
                && sameDecimal(totalCostBasisUsd, state.totalCostBasisUsd())
                && sameDecimal(perWalletNetAvco, state.perWalletNetAvco())
                && sameDecimal(netTotalCostBasisUsd, state.netTotalCostBasisUsd())
                && sameDecimal(totalGasPaidUsd, state.totalGasPaidUsd())
                && sameDecimal(totalRealisedPnlUsd, state.totalRealisedPnlUsd())
                && sameDecimal(totalNetRealisedPnlUsd, state.totalNetRealisedPnlUsd())
                && sameDecimal(quantityShortfall, state.quantityShortfall())
                && sameDecimal(uncoveredQuantity, state.uncoveredQuantity())
                && hasIncompleteHistory == state.hasIncompleteHistory()
                && hasUnresolvedFlags == state.hasUnresolvedFlags()
                && unresolvedFlagCount == state.unresolvedFlagCount();
    }

    private static boolean sameDecimal(BigDecimal left, BigDecimal right) {
        BigDecimal a = left == null ? BigDecimal.ZERO : left;
        BigDecimal b = right == null ? BigDecimal.ZERO : right;
        return a.compareTo(b) == 0;
    }
}
