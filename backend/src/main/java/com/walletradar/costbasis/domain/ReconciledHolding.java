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
 * Bounded read model that combines current live quantity with replayed basis fields.
 */
@Document(collection = "reconciled_holdings")
@CompoundIndexes({
        @CompoundIndex(
                name = "reconciled_holding_wallet_network_asset_idx",
                def = "{'walletAddress': 1, 'networkId': 1, 'assetContract': 1}",
                unique = true
        ),
        @CompoundIndex(
                name = "reconciled_holding_current_wallet_idx",
                def = "{'currentHolding': 1, 'walletAddress': 1}"
        ),
        @CompoundIndex(
                name = "reconciled_holding_reconciliation_idx",
                def = "{'reconciliationStatus': 1}"
        )
})
@NoArgsConstructor
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ReconciledHolding {

    @Id
    @EqualsAndHashCode.Include
    private String id;

    private String walletAddress;
    private NetworkId networkId;
    private String assetSymbol;
    private String assetContract;
    private BigDecimal currentQuantity;
    private Boolean currentHolding;
    private BigDecimal derivedQuantity;
    private BigDecimal basisBackedDerivedQuantity;
    private BigDecimal currentCoveredQuantity;
    private BigDecimal currentUncoveredQuantity;
    private Boolean currentCostBasisProvable;
    private BigDecimal perWalletAvco;
    private BigDecimal totalCostBasisUsd;
    private BigDecimal totalGasPaidUsd;
    private BigDecimal totalRealisedPnlUsd;
    private BigDecimal quantityShortfall;
    private Boolean hasIncompleteHistory;
    private Boolean hasUnresolvedFlags;
    private Integer unresolvedFlagCount;
    private Instant lastEventTimestamp;
    private Instant onChainCapturedAt;
    private ReconciliationStatus reconciliationStatus;
    private Instant materializedAt;
}
