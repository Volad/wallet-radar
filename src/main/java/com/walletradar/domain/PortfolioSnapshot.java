package com.walletradar.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Hourly point-in-time portfolio record. Per-wallet only (walletAddress always set).
 * networkId null = all-network rollup for this wallet. All monetary fields are BigDecimal (INV-06).
 */
@Document(collection = "portfolio_snapshots")
@CompoundIndex(name = "wallet_network_time", def = "{'walletAddress': 1, 'networkId': 1, 'snapshotTime': 1}", unique = true)
public class PortfolioSnapshot {

    @Id
    private String id;
    private Instant snapshotTime;
    private String walletAddress;
    private String networkId;
    private List<AssetSnapshot> assets = new ArrayList<>();
    private BigDecimal totalValueUsd;
    private int unresolvedCount;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Instant getSnapshotTime() {
        return snapshotTime;
    }

    public void setSnapshotTime(Instant snapshotTime) {
        this.snapshotTime = snapshotTime;
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

    public List<AssetSnapshot> getAssets() {
        return assets == null ? List.of() : Collections.unmodifiableList(assets);
    }

    public void setAssets(List<AssetSnapshot> assets) {
        this.assets = assets == null ? new ArrayList<>() : new ArrayList<>(assets);
    }

    public BigDecimal getTotalValueUsd() {
        return totalValueUsd;
    }

    public void setTotalValueUsd(BigDecimal totalValueUsd) {
        this.totalValueUsd = totalValueUsd;
    }

    public int getUnresolvedCount() {
        return unresolvedCount;
    }

    public void setUnresolvedCount(int unresolvedCount) {
        this.unresolvedCount = unresolvedCount;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PortfolioSnapshot that = (PortfolioSnapshot) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
