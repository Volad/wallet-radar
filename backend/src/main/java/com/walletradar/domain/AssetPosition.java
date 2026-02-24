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

/**
 * Derived state per (wallet, network, asset). Recomputable from economic events.
 * crossWalletAvco is NOT stored — computed on-request. All monetary/quantity fields are BigDecimal (INV-06).
 */
@Document(collection = "asset_positions")
@CompoundIndex(name = "wallet_network_asset", def = "{'walletAddress': 1, 'networkId': 1, 'assetContract': 1}", unique = true)
@NoArgsConstructor
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class AssetPosition {

    @Id
    @EqualsAndHashCode.Include
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
}
