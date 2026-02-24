package com.walletradar.domain;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Hourly point-in-time portfolio record. Per-wallet only (walletAddress always set).
 * networkId null = all-network rollup for this wallet. All monetary fields are BigDecimal (INV-06).
 */
@Document(collection = "portfolio_snapshots")
@CompoundIndex(name = "wallet_network_time", def = "{'walletAddress': 1, 'networkId': 1, 'snapshotTime': 1}", unique = true)
@NoArgsConstructor
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class PortfolioSnapshot {

    @Id
    @EqualsAndHashCode.Include
    private String id;
    private Instant snapshotTime;
    private String walletAddress;
    private String networkId;
    private List<AssetSnapshot> assets = new ArrayList<>();
    private BigDecimal totalValueUsd;
    private int unresolvedCount;

    public List<AssetSnapshot> getAssets() {
        return assets == null ? List.of() : Collections.unmodifiableList(assets);
    }

    public PortfolioSnapshot setAssets(List<AssetSnapshot> assets) {
        this.assets = assets == null ? new ArrayList<>() : new ArrayList<>(assets);
        return this;
    }
}
