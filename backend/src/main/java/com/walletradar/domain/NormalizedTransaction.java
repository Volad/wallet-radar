package com.walletradar.domain;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Canonical operation-level transaction object (ADR-025).
 */
@Document(collection = "normalized_transactions")
@CompoundIndexes({
    @CompoundIndex(name = "tx_network_wallet_uniq", def = "{'txHash': 1, 'networkId': 1, 'walletAddress': 1}", unique = true, sparse = true),
    @CompoundIndex(name = "wallet_network_status_block", def = "{'walletAddress': 1, 'networkId': 1, 'status': 1, 'blockTimestamp': 1}"),
    @CompoundIndex(name = "legs_asset_contract", def = "{'legs.assetContract': 1}")
})
@NoArgsConstructor
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class NormalizedTransaction {

    @Id
    @EqualsAndHashCode.Include
    private String id;
    private String txHash;
    private NetworkId networkId;
    private String walletAddress;
    private Instant blockTimestamp;
    private NormalizedTransactionType type;
    private NormalizedTransactionStatus status;
    private List<Leg> legs = new ArrayList<>();
    private List<String> missingDataReasons = new ArrayList<>();
    private ConfidenceLevel confidence;
    private Integer clarificationAttempts;
    private Integer pricingAttempts;
    private Integer statAttempts;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant confirmedAt;
    @Indexed(unique = true, sparse = true)
    private String clientId;

    @NoArgsConstructor
    @Getter
    @Setter
    public static class Leg {
        private NormalizedLegRole role;
        private String assetContract;
        private String assetSymbol;
        /** Positive = inbound, negative = outbound. */
        private BigDecimal quantityDelta;
        private BigDecimal unitPriceUsd;
        private BigDecimal valueUsd;
        private PriceSource priceSource;
        private boolean isInferred;
        private String inferenceReason;
        private ConfidenceLevel confidence;
        /** AVCO snapshot at moment of SELL confirmation/replay (audit). */
        private BigDecimal avcoAtTimeOfSale;
        /** Realised PnL for SELL legs. */
        private BigDecimal realisedPnlUsd;
        /** Deterministic ordering for legs inside same block timestamp. */
        private Integer logIndex;
    }
}
