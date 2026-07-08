package com.walletradar.application.costbasis.domain;

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
 * Immutable replay trace row for one applied accounting state transition.
 */
@Document(collection = "asset_ledger_points")
@CompoundIndexes({
        @CompoundIndex(
                name = "asset_ledger_universe_family_order_idx",
                def = "{'accountingUniverseId': 1, 'accountingFamilyIdentity': 1, 'blockTimestamp': 1, 'transactionIndex': 1, 'replaySequence': 1}"
        ),
        @CompoundIndex(
                name = "asset_ledger_universe_wallet_asset_order_idx",
                def = "{'accountingUniverseId': 1, 'walletAddress': 1, 'networkId': 1, 'accountingAssetIdentity': 1, 'blockTimestamp': 1, 'transactionIndex': 1, 'replaySequence': 1}"
        ),
        @CompoundIndex(
                name = "asset_ledger_universe_tx_idx",
                def = "{'accountingUniverseId': 1, 'normalizedTransactionId': 1, 'flowIndex': 1, 'replaySequence': 1}",
                unique = true
        ),
        @CompoundIndex(
                name = "asset_ledger_universe_lifecycle_idx",
                def = "{'accountingUniverseId': 1, 'lifecycleKind': 1}"
        )
})
@NoArgsConstructor
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class AssetLedgerPoint {

    @Id
    @EqualsAndHashCode.Include
    private String id;

    private String accountingUniverseId;
    private String walletAddress;
    private NetworkId networkId;
    private String accountingAssetIdentity;
    private String accountingFamilyIdentity;
    private String familyDisplaySymbol;
    private String assetSymbol;
    private String assetContract;

    private String normalizedTransactionId;
    private String txHash;
    private String correlationId;
    private String lifecycleChainId;
    private String matchedCounterparty;
    private Boolean continuityCandidate;

    private Instant blockTimestamp;
    private Integer transactionIndex;
    private Integer flowIndex;
    private Long replaySequence;

    private String normalizedType;
    private LifecycleKind lifecycleKind;
    private LifecycleStage lifecycleStage;
    private BasisEffect basisEffect;
    private String protocolName;

    private BigDecimal quantityDelta;
    private BigDecimal costBasisDeltaUsd;
    private BigDecimal realisedPnlDeltaUsd;
    private BigDecimal gasDeltaUsd;
    private BigDecimal quantityShortfallDelta;
    private BigDecimal uncoveredQuantityDelta;

    private BigDecimal quantityBefore;
    private BigDecimal quantityAfter;
    private BigDecimal totalCostBasisBeforeUsd;
    private BigDecimal totalCostBasisAfterUsd;
    private BigDecimal avcoBeforeUsd;
    private BigDecimal avcoAfterUsd;

    private BigDecimal netTotalCostBasisBeforeUsd;
    private BigDecimal netTotalCostBasisAfterUsd;
    private BigDecimal netAvcoBeforeUsd;
    private BigDecimal netAvcoAfterUsd;
    private BigDecimal netCostBasisDeltaUsd;
    private BigDecimal netRealisedPnlDeltaUsd;

    private BigDecimal basisBackedQuantityAfter;
    private BigDecimal quantityShortfallAfter;
    private BigDecimal uncoveredQuantityAfter;
    private Boolean hasIncompleteHistoryAfter;
    private Boolean hasUnresolvedFlagsAfter;
    private Integer unresolvedFlagCountAfter;
    private Instant createdAt;

    public enum LifecycleKind {
        SPOT,
        TRANSFER,
        BRIDGE,
        CUSTODY,
        LENDING,
        STAKING,
        VAULT,
        LP,
        ORDER,
        LOOP,
        WRAP,
        REWARD,
        DERIVATIVE,
        MANUAL,
        UNKNOWN
    }

    public enum LifecycleStage {
        SINGLE,
        REQUEST,
        SETTLEMENT,
        SOURCE,
        DESTINATION
    }

    public enum BasisEffect {
        ACQUIRE,
        DISPOSE,
        CARRY_OUT,
        CARRY_IN,
        REALLOCATE_OUT,
        REALLOCATE_IN,
        GAS_ONLY,
        UNKNOWN
    }
}
