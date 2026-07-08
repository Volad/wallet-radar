package com.walletradar.application.costbasis.application.replay.model;

import java.math.BigDecimal;
import java.time.Instant;

public final class PositionState {

    private final AssetKey assetKey;
    private BigDecimal quantity = BigDecimal.ZERO;
    private BigDecimal perWalletAvco;
    private BigDecimal perWalletNetAvco;
    private BigDecimal totalCostBasisUsd = BigDecimal.ZERO;
    private BigDecimal netTotalCostBasisUsd = BigDecimal.ZERO;
    private BigDecimal totalGasPaidUsd = BigDecimal.ZERO;
    private BigDecimal totalRealisedPnlUsd = BigDecimal.ZERO;
    private BigDecimal totalNetRealisedPnlUsd = BigDecimal.ZERO;
    private BigDecimal quantityShortfall = BigDecimal.ZERO;
    private BigDecimal uncoveredQuantity = BigDecimal.ZERO;
    private boolean hasIncompleteHistory;
    private boolean hasUnresolvedFlags;
    private int unresolvedFlagCount;
    private Instant lastEventTimestamp;

    public PositionState(AssetKey assetKey) {
        this.assetKey = assetKey;
    }

    public AssetKey assetKey() {
        return assetKey;
    }

    public BigDecimal quantity() {
        return quantity;
    }

    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
    }

    public BigDecimal perWalletAvco() {
        return perWalletAvco;
    }

    public void setPerWalletAvco(BigDecimal perWalletAvco) {
        this.perWalletAvco = perWalletAvco;
    }

    public BigDecimal totalCostBasisUsd() {
        return totalCostBasisUsd;
    }

    public void setTotalCostBasisUsd(BigDecimal totalCostBasisUsd) {
        this.totalCostBasisUsd = totalCostBasisUsd;
    }

    public BigDecimal netTotalCostBasisUsd() {
        return netTotalCostBasisUsd;
    }

    public void setNetTotalCostBasisUsd(BigDecimal netTotalCostBasisUsd) {
        this.netTotalCostBasisUsd = netTotalCostBasisUsd;
    }

    public BigDecimal perWalletNetAvco() {
        return perWalletNetAvco;
    }

    public void setPerWalletNetAvco(BigDecimal perWalletNetAvco) {
        this.perWalletNetAvco = perWalletNetAvco;
    }

    public BigDecimal totalNetRealisedPnlUsd() {
        return totalNetRealisedPnlUsd;
    }

    public void setTotalNetRealisedPnlUsd(BigDecimal totalNetRealisedPnlUsd) {
        this.totalNetRealisedPnlUsd = totalNetRealisedPnlUsd;
    }

    public BigDecimal totalGasPaidUsd() {
        return totalGasPaidUsd;
    }

    public void setTotalGasPaidUsd(BigDecimal totalGasPaidUsd) {
        this.totalGasPaidUsd = totalGasPaidUsd;
    }

    public BigDecimal totalRealisedPnlUsd() {
        return totalRealisedPnlUsd;
    }

    public void setTotalRealisedPnlUsd(BigDecimal totalRealisedPnlUsd) {
        this.totalRealisedPnlUsd = totalRealisedPnlUsd;
    }

    public BigDecimal quantityShortfall() {
        return quantityShortfall;
    }

    public void setQuantityShortfall(BigDecimal quantityShortfall) {
        this.quantityShortfall = quantityShortfall;
    }

    public BigDecimal uncoveredQuantity() {
        return uncoveredQuantity;
    }

    public void setUncoveredQuantity(BigDecimal uncoveredQuantity) {
        this.uncoveredQuantity = uncoveredQuantity;
    }

    public boolean hasIncompleteHistory() {
        return hasIncompleteHistory;
    }

    public void setHasIncompleteHistory(boolean hasIncompleteHistory) {
        this.hasIncompleteHistory = hasIncompleteHistory;
    }

    public boolean hasUnresolvedFlags() {
        return hasUnresolvedFlags;
    }

    public void setHasUnresolvedFlags(boolean hasUnresolvedFlags) {
        this.hasUnresolvedFlags = hasUnresolvedFlags;
    }

    public int unresolvedFlagCount() {
        return unresolvedFlagCount;
    }

    public void setUnresolvedFlagCount(int unresolvedFlagCount) {
        this.unresolvedFlagCount = unresolvedFlagCount;
    }

    public Instant lastEventTimestamp() {
        return lastEventTimestamp;
    }

    public void setLastEventTimestamp(Instant lastEventTimestamp) {
        this.lastEventTimestamp = lastEventTimestamp;
    }
}
