package com.walletradar.domain.transaction.normalized;

import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.common.PriceSource;
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
 * Canonical accounting document produced by normalization for on-chain and Bybit evidence.
 */
@Document(collection = "normalized_transactions")
@CompoundIndexes({
        @CompoundIndex(
                name = "normalized_onchain_identity_idx",
                def = "{'txHash': 1, 'networkId': 1, 'walletAddress': 1}",
                unique = true,
                sparse = true
        ),
        @CompoundIndex(
                name = "normalized_source_status_block_ts_idx",
                def = "{'source': 1, 'status': 1, 'blockTimestamp': 1}"
        ),
        @CompoundIndex(
                name = "normalized_wallet_status_block_tx_idx",
                def = "{'walletAddress': 1, 'status': 1, 'blockTimestamp': 1, 'transactionIndex': 1}"
        ),
        @CompoundIndex(
                name = "normalized_source_status_clarification_idx",
                def = "{'source': 1, 'status': 1, 'clarificationAttempts': 1, 'updatedAt': 1, 'blockTimestamp': 1, 'transactionIndex': 1}"
        ),
        @CompoundIndex(
                name = "normalized_source_status_full_receipt_clarification_idx",
                def = "{'source': 1, 'status': 1, 'fullReceiptClarificationAttempts': 1, 'updatedAt': 1, 'blockTimestamp': 1, 'transactionIndex': 1}"
        ),
        @CompoundIndex(
                name = "normalized_source_status_pricing_idx",
                def = "{'source': 1, 'status': 1, 'pricingAttempts': 1, 'updatedAt': 1, 'blockTimestamp': 1, 'transactionIndex': 1}"
        ),
        @CompoundIndex(
                name = "normalized_status_stat_idx",
                def = "{'status': 1, 'statAttempts': 1, 'updatedAt': 1, 'blockTimestamp': 1, 'transactionIndex': 1}"
        ),
        @CompoundIndex(
                name = "normalized_source_missing_reason_idx",
                def = "{'source': 1, 'missingDataReasons': 1}"
        ),
        @CompoundIndex(
                name = "normalized_status_missing_reason_idx",
                def = "{'status': 1, 'missingDataReasons': 1}"
        ),
        @CompoundIndex(
                name = "normalized_status_excluded_block_ts_idx",
                def = "{'status': 1, 'excludedFromAccounting': 1, 'blockTimestamp': 1, 'transactionIndex': 1}"
        ),
        @CompoundIndex(
                name = "normalized_flows_asset_contract_idx",
                def = "{'flows.assetContract': 1}"
        )
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
    private NormalizedTransactionSource source;
    private Instant blockTimestamp;
    private Integer transactionIndex;
    private NormalizedTransactionType type;
    private NormalizedTransactionStatus status;
    private ClassificationSource classifiedBy;
    private List<Flow> flows = new ArrayList<>();
    private List<String> missingDataReasons = new ArrayList<>();
    private ConfidenceLevel confidence;
    @Indexed(name = "normalized_correlation_idx", sparse = true)
    private String correlationId;
    private Boolean continuityCandidate;
    private String matchedCounterparty;
    private Boolean excludedFromAccounting;
    private String accountingExclusionReason;
    private String protocolName;
    private String protocolVersion;
    private Integer clarificationAttempts;
    private Integer fullReceiptClarificationAttempts;
    private Integer pricingAttempts;
    private Integer statAttempts;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant confirmedAt;
    private String clientId;

    @NoArgsConstructor
    @Getter
    @Setter
    public static class Flow {
        private NormalizedLegRole role;
        private String assetContract;
        private String assetSymbol;
        private BigDecimal quantityDelta;
        private BigDecimal unitPriceUsd;
        private BigDecimal valueUsd;
        private PriceSource priceSource;
        private Boolean isInferred;
        private String inferenceReason;
        private ConfidenceLevel confidence;
        private BigDecimal avcoAtTimeOfSale;
        private BigDecimal realisedPnlUsd;
        private Integer logIndex;
    }
}
