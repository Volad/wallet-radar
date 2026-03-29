package com.walletradar.costbasis.domain;

import com.walletradar.domain.common.NetworkId;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Materialized per-wallet asset state derived from confirmed replay.
 */
@Document(collection = "asset_positions")
@CompoundIndexes({
        @CompoundIndex(
                name = "asset_position_wallet_network_asset_idx",
                def = "{'walletAddress': 1, 'networkId': 1, 'assetContract': 1}",
                unique = true
        ),
        @CompoundIndex(
                name = "asset_position_wallet_symbol_idx",
                def = "{'walletAddress': 1, 'assetSymbol': 1}"
        ),
        @CompoundIndex(
                name = "asset_position_reconciliation_idx",
                def = "{'reconciliationStatus': 1}"
        )
})
@NoArgsConstructor
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class AssetPosition {

    @Id
    @EqualsAndHashCode.Include
    private String id;

    private String walletAddress;
    private NetworkId networkId;
    private String assetSymbol;
    private String assetContract;
    private BigDecimal quantity;
    private BigDecimal perWalletAvco;
    private BigDecimal totalCostBasisUsd;
    private BigDecimal totalGasPaidUsd;
    private BigDecimal totalRealisedPnlUsd;
    private Boolean hasIncompleteHistory;
    private Boolean hasUnresolvedFlags;
    private Integer unresolvedFlagCount;
    private Instant lastEventTimestamp;
    private Instant lastCalculatedAt;
    private BigDecimal onChainQuantity;
    private Instant onChainCapturedAt;
    private ReconciliationStatus reconciliationStatus;
}
