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
        ),
        // B-ETH-02: LendingLoopOpenClosePairLinkService looks back for the still-open
        // LENDING_LOOP_OPEN of a given wallet whose type/timestamp precede a
        // LENDING_LOOP_DECREASE/CLOSE. The lookback is keyed by walletAddress + type with a
        // blockTimestamp range (protocolName / positionKey are low-selectivity residual filters),
        // so a leading {walletAddress, type, blockTimestamp} index keeps it out of a full scan.
        @CompoundIndex(
                name = "normalized_wallet_type_timestamp_idx",
                def = "{'walletAddress': 1, 'type': 1, 'blockTimestamp': 1}"
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

    /**
     * Venue-neutral semantic capability flag (WS-8): {@code true} when this row's counterparty is a
     * user-designated external custody destination — an off-chain / custodial venue we cannot read
     * into (e.g. Telegram Wallet Earn). Deposits stay {@code EXTERNAL_TRANSFER_OUT} and withdrawals
     * {@code EXTERNAL_TRANSFER_IN} (standard AVCO); the flag only lets read paths surface the
     * informational custody ledger without re-deriving venue semantics per network (ADR-072).
     * {@code null}/false for every other row.
     *
     * <p>Sparse-indexed: the field is absent on the overwhelming majority of rows, so the index only
     * holds the handful of custody rows and keeps the informational custody-ledger read off a scan.</p>
     */
    @Indexed(name = "normalized_custodial_offchain_idx", sparse = true)
    private Boolean custodialOffChain;

    /**
     * Venue- and network-neutral semantic capability flag (D1): {@code true} when this row is a
     * <b>cross-canonical staking/vault identity change</b> — one canonical asset is disposed and a
     * distinct canonical asset is acquired in the same operation (e.g. ETH → mETH staking on Bybit,
     * or a vault deposit that mints a share token of a different canonical identity). Same-family
     * carries (e.g. mETH → cmETH, both {@code FAMILY:METH}) are <b>not</b> flagged.
     *
     * <p>Stamped once at normalization time via the ADR-054 accounting identity registry
     * ({@code AccountingAssetClassificationSupport.isCrossCanonicalStakingVaultConversion}) — the
     * single source of truth for C1/C2 canonical identity — so no symbol/contract list is hardcoded.
     * The pricing layer reads this flag (instead of importing the accounting registry, which the
     * module boundary forbids) to force market pricing on the disposed and acquired principal
     * {@code TRANSFER} legs, ensuring replay books a real cost basis rather than silently admitting a
     * $0-basis acquisition that strips the underlying family's basis (ADR-054 §9). {@code null} for
     * every non-cross-canonical staking/vault row.</p>
     */
    private Boolean crossCanonicalStakingConversion;

    /**
     * Venue- and network-neutral semantic capability flag (WS-8): {@code true} when the lending
     * collateral on this row's network is represented by a fungible on-chain <b>receipt token</b>
     * (e.g. Aave aTokens / Compound cTokens on EVM), {@code false} for receipt-less lending
     * (e.g. Jupiter Lend on Solana, TON) where collateral surfaces no snapshot-able balance.
     *
     * <p>Stamped once at normalization time — the single place network specifics are allowed. Read
     * paths (e.g. {@code LendingCycleBuilder}) consume this instead of re-deriving
     * {@code NetworkAddressFormat.isEvm(networkId)}, keeping the consumption plane network-agnostic
     * (ADR-073, generalizing the ADR-052 "venue specificity ends at normalization" invariant to the
     * network axis). Meaningful only for lending-family rows; {@code null} elsewhere.</p>
     */
    private Boolean receiptBearingCollateral;

    /**
     * Venue- and network-neutral semantic capability flag (WS-8): {@code true} for a
     * concentrated-liquidity LP position whose full exit is recorded as a terminal {@code LP_EXIT}
     * (never {@code LP_EXIT_FINAL}) and whose closure is therefore governed by lifecycle events /
     * live snapshots rather than a residual {@code qtyHeld} signal — currently Solana DLMM (Meteora)
     * and CLMM (Raydium) positions. EVM CL-NFT positions emit {@code LP_EXIT_FINAL} and are left
     * unstamped.
     *
     * <p>Stamped once at normalization time. Read paths ({@code SessionLpQueryService},
     * {@code LpPositionRefreshService}) consume this instead of testing the
     * {@code "lp-position:solana:"} correlation-id prefix (ADR-073). {@code null}/false elsewhere.</p>
     */
    private Boolean lpConcentrated;

    /**
     * On-chain <b>pool address captured at normalization time</b> for a position-scoped LP row whose
     * pool identity cannot be recovered later from the (deallocated) position account. Currently the
     * Meteora DLMM <b>LbPair</b> pool address ({@code accounts[1]} of the largest DLMM liquidity
     * instruction): the LbPair pool account is shared and persists on-chain even after the user's
     * individual position PDA is closed and its rent reclaimed, so it is the only reliable pair
     * source for a CLOSED single-sided position (the position PDA is deallocated and cannot be
     * decoded to the LbPair anymore).
     *
     * <p>The correlation id is <b>unchanged</b> (still keyed on the position PDA so basis-pool
     * continuity holds); this is auxiliary enrichment metadata consumed by the LP position reader
     * to resolve the SOL/&lt;SPL&gt; pair for both open and closed positions without a read-path RPC
     * decode. {@code null} for every non-Meteora / non-LP row.</p>
     */
    private String lpPoolAddress;

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

        /**
         * ADR-080/ADR-081 (C1, durable identity/flag route): marks this leg as the <b>LP-receipt</b>
         * token of an LP correlation (e.g. the Meteora DAMM {@code MLP} fungible receipt whose symbol
         * is confusable across pools). Set at classification/normalization from LP-correlation
         * membership — not from the (confusable) symbol — so replay stamps the ledger point's
         * {@code accountingFamilyIdentity = FAMILY:LP_RECEIPT} and the dashboard/spot-family surfaces
         * exclude it by identity regardless of the symbol. Null/false for ordinary priced legs.
         */
        private Boolean lpReceipt;
    }
}
