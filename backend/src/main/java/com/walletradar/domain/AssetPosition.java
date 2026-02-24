package com.walletradar.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Derived state per (wallet, network, asset). Recomputable from economic events.
 * crossWalletAvco is NOT stored — computed on-request. All monetary/quantity fields are BigDecimal (INV-06).
 */
@Document(collection = "asset_positions")
@CompoundIndex(name = "wallet_network_asset", def = "{'walletAddress': 1, 'networkId': 1, 'assetContract': 1}", unique = true)
public class AssetPosition {

    @Id
    private String id;
    private String walletAddress;
    private String networkId;
    private String assetSymbol;
    private String assetContract;
    private BigDecimal quantity;
    private BigDecimal perWalletAvco;
    private BigDecimal totalCostBasisUsd;
    private BigDecimal totalGasPaidUsd;
    private BigDecimal totalRealisedPnlUsd;
    private boolean hasIncompleteHistory;
    private boolean hasUnresolvedFlags;
    private int unresolvedFlagCount;
    private Instant lastEventTimestamp;
    private Instant lastCalculatedAt;
    private BigDecimal onChainQuantity;
    private Instant onChainCapturedAt;
    private String reconciliationStatus;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getWalletAddress() {
        return walletAddress;
    }

    public void setWalletAddress(String walletAddress) {
        this.walletAddress = walletAddress;
    }

    public String getNetworkId() {
        return networkId;
    }

    public void setNetworkId(String networkId) {
        this.networkId = networkId;
    }

    public String getAssetSymbol() {
        return assetSymbol;
    }

    public void setAssetSymbol(String assetSymbol) {
        this.assetSymbol = assetSymbol;
    }

    public String getAssetContract() {
        return assetContract;
    }

    public void setAssetContract(String assetContract) {
        this.assetContract = assetContract;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
    }

    public BigDecimal getPerWalletAvco() {
        return perWalletAvco;
    }

    public void setPerWalletAvco(BigDecimal perWalletAvco) {
        this.perWalletAvco = perWalletAvco;
    }

    public BigDecimal getTotalCostBasisUsd() {
        return totalCostBasisUsd;
    }

    public void setTotalCostBasisUsd(BigDecimal totalCostBasisUsd) {
        this.totalCostBasisUsd = totalCostBasisUsd;
    }

    public BigDecimal getTotalGasPaidUsd() {
        return totalGasPaidUsd;
    }

    public void setTotalGasPaidUsd(BigDecimal totalGasPaidUsd) {
        this.totalGasPaidUsd = totalGasPaidUsd;
    }

    public BigDecimal getTotalRealisedPnlUsd() {
        return totalRealisedPnlUsd;
    }

    public void setTotalRealisedPnlUsd(BigDecimal totalRealisedPnlUsd) {
        this.totalRealisedPnlUsd = totalRealisedPnlUsd;
    }

    public boolean isHasIncompleteHistory() {
        return hasIncompleteHistory;
    }

    public void setHasIncompleteHistory(boolean hasIncompleteHistory) {
        this.hasIncompleteHistory = hasIncompleteHistory;
    }

    public boolean isHasUnresolvedFlags() {
        return hasUnresolvedFlags;
    }

    public void setHasUnresolvedFlags(boolean hasUnresolvedFlags) {
        this.hasUnresolvedFlags = hasUnresolvedFlags;
    }

    public int getUnresolvedFlagCount() {
        return unresolvedFlagCount;
    }

    public void setUnresolvedFlagCount(int unresolvedFlagCount) {
        this.unresolvedFlagCount = unresolvedFlagCount;
    }

    public Instant getLastEventTimestamp() {
        return lastEventTimestamp;
    }

    public void setLastEventTimestamp(Instant lastEventTimestamp) {
        this.lastEventTimestamp = lastEventTimestamp;
    }

    public Instant getLastCalculatedAt() {
        return lastCalculatedAt;
    }

    public void setLastCalculatedAt(Instant lastCalculatedAt) {
        this.lastCalculatedAt = lastCalculatedAt;
    }

    public BigDecimal getOnChainQuantity() {
        return onChainQuantity;
    }

    public void setOnChainQuantity(BigDecimal onChainQuantity) {
        this.onChainQuantity = onChainQuantity;
    }

    public Instant getOnChainCapturedAt() {
        return onChainCapturedAt;
    }

    public void setOnChainCapturedAt(Instant onChainCapturedAt) {
        this.onChainCapturedAt = onChainCapturedAt;
    }

    public String getReconciliationStatus() {
        return reconciliationStatus;
    }

    public void setReconciliationStatus(String reconciliationStatus) {
        this.reconciliationStatus = reconciliationStatus;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AssetPosition that = (AssetPosition) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
