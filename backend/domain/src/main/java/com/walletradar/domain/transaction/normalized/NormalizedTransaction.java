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
                name = "normalized_source_status_reclassification_idx",
                def = "{'source': 1, 'status': 1, 'updatedAt': 1, 'blockTimestamp': 1, 'transactionIndex': 1}"
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
        ),
        @CompoundIndex(
                name = "normalized_wallet_counterparty_source_idx",
                def = "{'walletAddress': 1, 'matchedCounterparty': 1, 'source': 1}"
        ),
        @CompoundIndex(
                name = "normalized_source_type_counterparty_block_idx",
                def = "{'source': 1, 'type': 1, 'matchedCounterparty': 1, 'blockTimestamp': 1, 'transactionIndex': 1}"
        ),
        @CompoundIndex(
                name = "normalized_source_protocol_status_block_idx",
                def = "{'source': 1, 'protocolName': 1, 'status': 1, 'blockTimestamp': 1, 'transactionIndex': 1}"
        ),
        @CompoundIndex(
                name = "normalized_source_status_type_resolution_idx",
                def = "{'source': 1, 'status': 1, 'type': 1, 'protocolResolutionState': 1, 'counterpartyResolutionState': 1}"
        ),
        @CompoundIndex(
                name = "normalized_source_wallet_type_block_idx",
                def = "{'source': 1, 'walletAddress': 1, 'type': 1, 'blockTimestamp': 1, 'transactionIndex': 1}"
        ),
        @CompoundIndex(
                name = "normalized_flow_counterparty_idx",
                def = "{'flows.counterpartyAddress': 1, 'flows.assetSymbol': 1, 'blockTimestamp': 1}",
                sparse = true
        ),
        @CompoundIndex(
                name = "normalized_fund_corridor_idx",
                def = "{'type': 1, 'walletAddress': 1}",
                sparse = true
        ),
        // RC-9 WS-5: deterministic corridor leg lookup. Drives the order-independent
        // findAllByTxHashAndNetworkIdAndSource used by BybitTransferContinuityRepairService so the
        // corridor projection is a pure function of the raw legs, not Mongo scan order.
        @CompoundIndex(
                name = "normalized_corridor_network_tx_source_idx",
                def = "{'networkId': 1, 'txHash': 1, 'source': 1}"
        ),
        // RC-9 WS-5: corridor-anchor recognition / idempotent re-stamp (source + correlationId).
        @CompoundIndex(
                name = "normalized_source_correlation_idx",
                def = "{'source': 1, 'correlationId': 1}",
                sparse = true
        ),
        // Linking performance: InternalTransferPairLinkService queries source+status+continuityCandidate+type
        // to find own-wallet transfer candidates that need pairing.  The existing
        // normalized_source_status_type_resolution_idx does not include continuityCandidate, causing
        // Mongo to do a post-filter scan over all confirmed/pending-price rows.
        @CompoundIndex(
                name = "normalized_linking_internal_candidates_idx",
                def = "{'source': 1, 'status': 1, 'continuityCandidate': 1, 'type': 1}"
        ),
        // Linking performance: convergent passes (bybitBridgeLink, crossNetworkBridgePairFallback, etc.)
        // frequently look up candidates by walletAddress + source + type within a status range.
        // This index accelerates per-wallet candidate retrieval and hasPendingLinking() gate checks.
        @CompoundIndex(
                name = "normalized_linking_wallet_source_type_status_idx",
                def = "{'walletAddress': 1, 'source': 1, 'type': 1, 'status': 1, 'blockTimestamp': 1}"
        ),
        @CompoundIndex(
                name = "normalized_wallet_status_clarification_lease_idx",
                def = "{'walletAddress': 1, 'status': 1, 'clarificationLeaseUntil': 1}"
        ),
        @CompoundIndex(
                name = "normalized_type_missing_reason_idx",
                def = "{'type': 1, 'missingDataReasons': 1}"
        ),
        @CompoundIndex(
                name = "normalized_source_type_excluded_idx",
                def = "{'source': 1, 'type': 1, 'excludedFromAccounting': 1}"
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
    private String eventSubtype;
    private NormalizedTransactionStatus status;
    private ClassificationSource classifiedBy;
    private List<Flow> flows = new ArrayList<>();
    private List<String> missingDataReasons = new ArrayList<>();
    private ConfidenceLevel confidence;
    @Indexed(name = "normalized_correlation_idx", sparse = true)
    private String correlationId;
    private Boolean continuityCandidate;
    private String matchedCounterparty;
    private String counterpartyAddress;
    private String counterpartyType;
    private String counterpartyResolutionState;
    private String counterpartyResolutionEvidence;
    private Boolean excludedFromAccounting;
    private String accountingExclusionReason;
    private String protocolName;
    private String protocolVersion;
    private String protocolResolutionState;
    private String protocolResolutionEvidence;
    private org.bson.Document metadata;
    private org.bson.Document clarificationEvidence;
    private Integer clarificationAttempts;
    private Integer fullReceiptClarificationAttempts;
    private Instant clarificationLeaseUntil;
    private String clarificationWorkerId;
    private Integer pricingAttempts;
    private Integer statAttempts;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant confirmedAt;
    private String clientId;

    /** CEX venue label when {@code source == BYBIT}; {@code null} for on-chain rows. */
    private String venue;
    /** When set, overrides replay self-transfer detection for Bybit INTERNAL_TRANSFER rows. */
    private Boolean selfTransferNoop;
    /** How this row participates in intra-venue basis carry during replay. */
    private VenueInternalCarryKind venueInternalCarry;
    /** Human-readable carry provenance (e.g. {@code collapsed}, {@code cross-uid}). */
    private String carrySourceHint;
    /** Bybit master UID extracted from {@code walletAddress} for venue-internal routing. */
    private String bybitUid;

    // ---- Boundary contract (venue-neutral, stamped at normalization time) ----

    /**
     * External-capital boundary direction for CEX capital-gate sub-accounts.
     * {@code null} for on-chain rows and non-capital-gate CEX sub-accounts.
     * Post-normalization consumers use this field instead of venue-specific predicates.
     *
     * @see ExternalCapitalBoundary
     */
    private ExternalCapitalBoundary externalCapitalBoundary;

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
        private String counterpartyAddress;
        private String counterpartyType;
        private String accountRef;
        /**
         * Buy-side venue commission in USD attributed to this BUY leg.
         * Set at normalization time for CEX venues (Dzengi, Bybit) where fee data is available.
         * Null / zero means no capitalization — on-chain flows always leave this null.
         * Consumed by the replay engine to raise Net AVCO without touching Market AVCO.
         */
        private BigDecimal acquisitionFeeUsd;
    }
}
