package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.domain.transaction.raw.RawTransactionRepository;
import com.walletradar.application.normalization.pipeline.classification.reason.ClassificationReasonCode;
import com.walletradar.application.normalization.pipeline.classification.support.ClarificationEligibilitySupport;
import com.walletradar.platform.networks.descriptor.NetworkRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;

/**
 * Loads due full-receipt clarification candidates from the residual review tail.
 */
@Service
@RequiredArgsConstructor
public class PendingReceiptClarificationQueryService {

    private static final long GMX_EXECUTION_CORRELATION_WINDOW_SECONDS = 120L;
    private static final long GMX_POOL_EXIT_CORRELATION_WINDOW_SECONDS = 3_600L;
    private static final long COW_SETTLEMENT_CORRELATION_WINDOW_SECONDS = 86_400L;

    private final MongoOperations mongoOperations;
    private final RawTransactionRepository rawTransactionRepository;
    private final ReceiptClarificationGateway receiptClarificationGateway;
    private final NetworkRegistry networkRegistry;

    /**
     * Restricts every clarification selection to EVM-family networks. Full-receipt clarification
     * decodes EVM receipts (execution status, effective gas price, ERC-20/native transfer logs), so
     * Solana (Helius) and TON (toncenter) rows have no receipt to fetch. Without this guard a
     * SOLANA/TON {@code NEEDS_REVIEW}/{@code PENDING_CLARIFICATION} row is pulled into the EVM path,
     * accrues {@code CLARIFICATION_FULL_RECEIPT_UNAVAILABLE}, terminalizes to
     * {@code CLASSIFICATION_FAILED}, and stalls the resume scheduler. The EVM-family set is derived
     * from {@link NetworkRegistry} (config-driven {@code address-format: EVM}) rather than a
     * hardcoded enum list.
     */
    private Criteria evmFamilyCriteria() {
        return Criteria.where("networkId").in(networkRegistry.evmWalletSupportedNetworks());
    }

    public List<NormalizedTransaction> loadNextBatch(int batchSize, int maxAttempts, long retryDelaySeconds) {
        return loadNextBatch(batchSize, maxAttempts, retryDelaySeconds, null, 0L);
    }

    public List<NormalizedTransaction> claimNextBatch(
            int batchSize,
            int maxAttempts,
            long retryDelaySeconds,
            String workerId,
            long leaseSeconds
    ) {
        return loadNextBatch(batchSize, maxAttempts, retryDelaySeconds, workerId, leaseSeconds);
    }

    public List<NormalizedTransaction> claimActiveNeedsReviewBatch(
            int batchSize,
            int maxAttempts,
            long retryDelaySeconds,
            String workerId,
            long leaseSeconds
    ) {
        int boundedBatchSize = Math.max(1, batchSize);
        int boundedMaxAttempts = Math.max(1, maxAttempts);
        Instant now = Instant.now();
        Instant retryCutoff = now.minusSeconds(Math.max(0L, retryDelaySeconds));

        Criteria attemptsCriteria = new Criteria().orOperator(
                Criteria.where("fullReceiptClarificationAttempts").exists(false),
                Criteria.where("fullReceiptClarificationAttempts").lt(boundedMaxAttempts)
        );
        Criteria dueCriteria = new Criteria().orOperator(
                Criteria.where("fullReceiptClarificationAttempts").exists(false),
                Criteria.where("fullReceiptClarificationAttempts").lte(0),
                Criteria.where("updatedAt").lte(retryCutoff)
        );
        Criteria leaseCriteria = new Criteria().orOperator(
                Criteria.where("clarificationLeaseUntil").exists(false),
                Criteria.where("clarificationLeaseUntil").is(null),
                Criteria.where("clarificationLeaseUntil").lte(now)
        );
        Criteria activeAccountingCriteria = new Criteria().orOperator(
                Criteria.where("excludedFromAccounting").exists(false),
                Criteria.where("excludedFromAccounting").ne(true)
        );

        Query query = new Query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.ON_CHAIN),
                evmFamilyCriteria(),
                Criteria.where("status").is(NormalizedTransactionStatus.NEEDS_REVIEW),
                activeAccountingCriteria,
                attemptsCriteria,
                dueCriteria,
                leaseCriteria
        ));
        query.with(Sort.by(
                Sort.Order.asc("blockTimestamp"),
                Sort.Order.asc("transactionIndex"),
                Sort.Order.asc("_id")
        ));
        query.limit(boundedBatchSize);
        return claimIfRequested(
                mongoOperations.find(query, NormalizedTransaction.class),
                workerId,
                leaseSeconds,
                now
        );
    }

    /**
     * Claims EXTERNAL_TRANSFER_OUT transactions that used the multicall (0xac9650d8) method and are classified with
     * counterpartyAddress=MULTI, targeting cases where BlockScout failed to index ERC-20 token transfers due to
     * indexer lag. The RPC receipt fallback in the clarification gateway recovers the missing token transfers.
     *
     * <p>Uses {@code clarificationAttempts} as the gate because this path goes through the metadata clarification
     * workflow (non-NEEDS_REVIEW branch), which increments {@code clarificationAttempts} on both success and failure.
     */
    public List<NormalizedTransaction> claimMulticallMissingTransferBatch(
            int batchSize,
            int maxAttempts,
            long retryDelaySeconds,
            String workerId,
            long leaseSeconds
    ) {
        int boundedBatchSize = Math.max(1, batchSize);
        int boundedMaxAttempts = Math.max(1, maxAttempts);
        Instant now = Instant.now();
        Instant retryCutoff = now.minusSeconds(Math.max(0L, retryDelaySeconds));

        // Gate on clarificationAttempts: the metadata clarification path increments this field on both success and
        // failure, preventing re-selection of already-processed rows within the same pipeline run.
        Criteria attemptsCriteria = new Criteria().orOperator(
                Criteria.where("clarificationAttempts").exists(false),
                Criteria.where("clarificationAttempts").lt(boundedMaxAttempts)
        );
        Criteria dueCriteria = new Criteria().orOperator(
                Criteria.where("clarificationAttempts").exists(false),
                Criteria.where("clarificationAttempts").lte(0),
                Criteria.where("updatedAt").lte(retryCutoff)
        );
        Criteria leaseCriteria = new Criteria().orOperator(
                Criteria.where("clarificationLeaseUntil").exists(false),
                Criteria.where("clarificationLeaseUntil").is(null),
                Criteria.where("clarificationLeaseUntil").lte(now)
        );
        Criteria activeAccountingCriteria = new Criteria().orOperator(
                Criteria.where("excludedFromAccounting").exists(false),
                Criteria.where("excludedFromAccounting").ne(true)
        );

        Query query = new Query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.ON_CHAIN),
                evmFamilyCriteria(),
                Criteria.where("status").in(
                        NormalizedTransactionStatus.CONFIRMED,
                        NormalizedTransactionStatus.PENDING_PRICE
                ),
                Criteria.where("type").is(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT),
                Criteria.where("counterpartyAddress").is(FlowCounterpartySupport.MULTI_COUNTERPARTY),
                activeAccountingCriteria,
                attemptsCriteria,
                dueCriteria,
                leaseCriteria
        ));
        query.with(Sort.by(
                Sort.Order.asc("blockTimestamp"),
                Sort.Order.asc("transactionIndex"),
                Sort.Order.asc("_id")
        ));
        query.limit(boundedBatchSize);
        return claimIfRequested(
                mongoOperations.find(query, NormalizedTransaction.class),
                workerId,
                leaseSeconds,
                now
        );
    }

    public List<NormalizedTransaction> claimConfirmedFluidReceiptBatch(
            int batchSize,
            int maxAttempts,
            long retryDelaySeconds,
            String workerId,
            long leaseSeconds
    ) {
        int boundedBatchSize = Math.max(1, batchSize);
        int boundedMaxAttempts = Math.max(1, maxAttempts);
        Instant now = Instant.now();
        Instant retryCutoff = now.minusSeconds(Math.max(0L, retryDelaySeconds));

        Criteria attemptsCriteria = new Criteria().orOperator(
                Criteria.where("fullReceiptClarificationAttempts").exists(false),
                Criteria.where("fullReceiptClarificationAttempts").lt(boundedMaxAttempts)
        );
        Criteria dueCriteria = new Criteria().orOperator(
                Criteria.where("fullReceiptClarificationAttempts").exists(false),
                Criteria.where("fullReceiptClarificationAttempts").lte(0),
                Criteria.where("updatedAt").lte(retryCutoff)
        );
        Criteria leaseCriteria = new Criteria().orOperator(
                Criteria.where("clarificationLeaseUntil").exists(false),
                Criteria.where("clarificationLeaseUntil").is(null),
                Criteria.where("clarificationLeaseUntil").lte(now)
        );
        Criteria activeAccountingCriteria = new Criteria().orOperator(
                Criteria.where("excludedFromAccounting").exists(false),
                Criteria.where("excludedFromAccounting").ne(true)
        );
        Criteria missingFluidFullLogEvidenceCriteria = new Criteria().orOperator(
                Criteria.where("metadata.evidenceCompleteness").exists(false),
                Criteria.where("metadata.evidenceCompleteness").ne("FULL_LOGS_PRESENT")
        );

        Query query = new Query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.ON_CHAIN),
                evmFamilyCriteria(),
                Criteria.where("status").in(
                        NormalizedTransactionStatus.CONFIRMED,
                        NormalizedTransactionStatus.PENDING_PRICE,
                        NormalizedTransactionStatus.PENDING_STAT
                ),
                Criteria.where("protocolName").is("Fluid"),
                Criteria.where("type").in(
                        NormalizedTransactionType.LENDING_LOOP_OPEN,
                        NormalizedTransactionType.LENDING_LOOP_DECREASE,
                        NormalizedTransactionType.LENDING_LOOP_CLOSE,
                        NormalizedTransactionType.LENDING_WITHDRAW,
                        NormalizedTransactionType.REPAY,
                        NormalizedTransactionType.BORROW
                ),
                missingFluidFullLogEvidenceCriteria,
                activeAccountingCriteria,
                attemptsCriteria,
                dueCriteria,
                leaseCriteria
        ));
        query.with(Sort.by(
                Sort.Order.asc("blockTimestamp"),
                Sort.Order.asc("transactionIndex"),
                Sort.Order.asc("_id")
        ));
        query.limit(boundedBatchSize);
        List<NormalizedTransaction> selected = mongoOperations.find(query, NormalizedTransaction.class);
        return claimIfRequested(selected, workerId, leaseSeconds, now);
    }

    private List<NormalizedTransaction> loadNextBatch(
            int batchSize,
            int maxAttempts,
            long retryDelaySeconds,
            String workerId,
            long leaseSeconds
    ) {
        int boundedBatchSize = Math.max(1, batchSize);
        int boundedMaxAttempts = Math.max(1, maxAttempts);
        int selectionLimit = Math.max(boundedBatchSize, boundedBatchSize * 4);
        Instant now = Instant.now();
        Instant retryCutoff = now.minusSeconds(Math.max(0L, retryDelaySeconds));

        Criteria attemptsCriteria = new Criteria().orOperator(
                Criteria.where("fullReceiptClarificationAttempts").exists(false),
                Criteria.where("fullReceiptClarificationAttempts").lt(boundedMaxAttempts)
        );
        Criteria dueCriteria = new Criteria().orOperator(
                Criteria.where("fullReceiptClarificationAttempts").exists(false),
                Criteria.where("fullReceiptClarificationAttempts").lte(0),
                Criteria.where("updatedAt").lte(retryCutoff)
        );
        Criteria leaseCriteria = new Criteria().orOperator(
                Criteria.where("clarificationLeaseUntil").exists(false),
                Criteria.where("clarificationLeaseUntil").is(null),
                Criteria.where("clarificationLeaseUntil").lte(now)
        );
        Criteria activeAccountingCriteria = new Criteria().orOperator(
                Criteria.where("excludedFromAccounting").exists(false),
                Criteria.where("excludedFromAccounting").ne(true)
        );
        Criteria reviewTailCriteria = new Criteria().andOperator(
                Criteria.where("status").is(NormalizedTransactionStatus.NEEDS_REVIEW),
                activeAccountingCriteria
        );
        Criteria gmxPendingClarificationCriteria = new Criteria().andOperator(
                Criteria.where("status").is(NormalizedTransactionStatus.PENDING_CLARIFICATION),
                Criteria.where("protocolName").is("GMX"),
                Criteria.where("type").in(
                        NormalizedTransactionType.LP_ENTRY_REQUEST,
                        NormalizedTransactionType.LP_ENTRY_SETTLEMENT,
                        NormalizedTransactionType.LP_EXIT_REQUEST,
                        NormalizedTransactionType.LP_EXIT_SETTLEMENT,
                        NormalizedTransactionType.DERIVATIVE_ORDER_REQUEST,
                        NormalizedTransactionType.DERIVATIVE_ORDER_EXECUTION,
                        NormalizedTransactionType.DERIVATIVE_ORDER_CANCEL,
                        NormalizedTransactionType.DERIVATIVE_POSITION_INCREASE,
                        NormalizedTransactionType.DERIVATIVE_POSITION_DECREASE
                ),
                Criteria.where("missingDataReasons").in(
                        ClassificationReasonCode.GMX_DEPOSIT_REQUEST_CORRELATION_REQUIRED.code(),
                        ClassificationReasonCode.GMX_DEPOSIT_SETTLEMENT_CORRELATION_REQUIRED.code(),
                        ClassificationReasonCode.GMX_WITHDRAWAL_REQUEST_CORRELATION_REQUIRED.code(),
                        ClassificationReasonCode.GMX_WITHDRAWAL_SETTLEMENT_CORRELATION_REQUIRED.code(),
                        ClassificationReasonCode.GMX_DERIVATIVE_REQUEST_CORRELATION_REQUIRED.code(),
                        ClassificationReasonCode.GMX_DERIVATIVE_EXECUTION_EVIDENCE_REQUIRED.code()
                )
        );
        Criteria cowPendingClarificationCriteria = new Criteria().andOperator(
                Criteria.where("status").is(NormalizedTransactionStatus.PENDING_CLARIFICATION),
                Criteria.where("protocolName").is("CoW Swap"),
                Criteria.where("type").is(NormalizedTransactionType.DEX_ORDER_SETTLEMENT),
                Criteria.where("missingDataReasons").in(ClassificationReasonCode.COW_ORDER_SETTLEMENT_CORRELATION_REQUIRED.code())
        );
        Criteria bridgeEvidenceCriteria = new Criteria().andOperator(
                Criteria.where("status").is(NormalizedTransactionStatus.PENDING_PRICE),
                Criteria.where("type").is(NormalizedTransactionType.BRIDGE_OUT),
                Criteria.where("missingDataReasons").in(ClarificationEligibilitySupport.BRIDGE_PAIR_EVIDENCE_REQUIRED)
        );
        Criteria oneInchNativeSettlementCriteria = new Criteria().andOperator(
                Criteria.where("status").is(NormalizedTransactionStatus.PENDING_PRICE),
                Criteria.where("type").is(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT),
                Criteria.where("protocolName").is("1inch"),
                Criteria.where("missingDataReasons").in(ClassificationReasonCode.ROUTED_AGGREGATOR_OUTBOUND_ONLY.code())
        );
        Criteria eulerPendingClarificationCriteria = new Criteria().andOperator(
                Criteria.where("status").is(NormalizedTransactionStatus.PENDING_CLARIFICATION),
                Criteria.where("protocolName").is("Euler"),
                Criteria.where("missingDataReasons").in(ClassificationReasonCode.EULER_BATCH_DECODER_REQUIRED.code())
        );
        // ADR-044 D3: broadened beyond LP_EXIT/LP_FEE_CLAIM to SWAP/UNWRAP/LP_EXIT_PARTIAL/
        // LP_EXIT_FINAL. Safe: only rows carrying NATIVE_SETTLEMENT_TRANSFER_EVIDENCE_REQUIRED at
        // PENDING_CLARIFICATION match, which the (per-chain flag-gated) classification trigger
        // produces — with the flag off no SWAP/UNWRAP row carries this reason.
        Criteria nativeSettlementTransferRecoveryCriteria = new Criteria().andOperator(
                Criteria.where("status").is(NormalizedTransactionStatus.PENDING_CLARIFICATION),
                Criteria.where("type").in(
                        NormalizedTransactionType.LP_EXIT,
                        NormalizedTransactionType.LP_EXIT_PARTIAL,
                        NormalizedTransactionType.LP_EXIT_FINAL,
                        NormalizedTransactionType.LP_FEE_CLAIM,
                        NormalizedTransactionType.SWAP,
                        NormalizedTransactionType.UNWRAP
                ),
                Criteria.where("missingDataReasons")
                        .in(ClassificationReasonCode.NATIVE_SETTLEMENT_TRANSFER_EVIDENCE_REQUIRED.code())
        );
        Criteria lpPositionCorrelationRecoveryCriteria = new Criteria().andOperator(
                Criteria.where("status").is(NormalizedTransactionStatus.PENDING_CLARIFICATION),
                Criteria.where("type").in(
                        NormalizedTransactionType.LP_ENTRY,
                        NormalizedTransactionType.LP_EXIT,
                        NormalizedTransactionType.LP_EXIT_PARTIAL,
                        NormalizedTransactionType.LP_EXIT_FINAL
                ),
                Criteria.where("missingDataReasons")
                        .in(ClassificationReasonCode.LP_POSITION_CORRELATION_REQUIRED.code())
        );
        // R1 (LP exit fee split): a V3/Slipstream (or V4/Infinity) CL exit routed to
        // PENDING_CLARIFICATION by LpExitFeeClarificationTrigger so the full receipt is fetched and
        // the DecreaseLiquidity/Collect (or ModifyLiquidity) event logs are persisted. Without this
        // selector the row would stall at PENDING_CLARIFICATION whenever no sibling trigger (native
        // settlement / LP correlation) happened to fetch the full receipt first, leaving the
        // principal-vs-fee split unresolved and the exit mis-booked via the pool-residual fallback.
        Criteria lpFeeSplitEvidenceRecoveryCriteria = new Criteria().andOperator(
                Criteria.where("status").is(NormalizedTransactionStatus.PENDING_CLARIFICATION),
                Criteria.where("type").in(
                        NormalizedTransactionType.LP_EXIT,
                        NormalizedTransactionType.LP_EXIT_PARTIAL,
                        NormalizedTransactionType.LP_EXIT_FINAL
                ),
                Criteria.where("missingDataReasons")
                        .in(ClassificationReasonCode.LP_FEE_SPLIT_EVIDENCE_REQUIRED.code())
        );
        // LP_ENTRY mint() calls that were classified but never got a correlationId because the
        // NonfungiblePositionManager's ERC-721 mint event is absent from Blockscout's ERC-20
        // transfer API.  These transactions are CONFIRMED (not PENDING_CLARIFICATION) so the
        // lpPositionCorrelationRecoveryCriteria above never selects them.  Re-admit them for a
        // full-receipt attempt while there is still budget (< 3 attempts).
        Criteria lpEntryMissingCorrelationCriteria = new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.ON_CHAIN),
                Criteria.where("status").in(
                        NormalizedTransactionStatus.CONFIRMED,
                        NormalizedTransactionStatus.PENDING_PRICE
                ),
                Criteria.where("type").is(NormalizedTransactionType.LP_ENTRY),
                new Criteria().orOperator(
                        Criteria.where("correlationId").exists(false),
                        Criteria.where("correlationId").is(null)
                )
        );
        // Multicall (0xac9650d8) + ETH-value transactions classified as EXTERNAL_TRANSFER_OUT
        // when BlockScout hasn't indexed the sub-call token transfers yet.
        // The eligibility gate filters by methodId and rawValue via the raw_transaction view,
        // so the MongoDB pre-filter uses the broader counterpartyAddress=MULTI signal.
        Criteria multicallMissingTransferCriteria = new Criteria().andOperator(
                Criteria.where("status").in(
                        NormalizedTransactionStatus.CONFIRMED,
                        NormalizedTransactionStatus.PENDING_PRICE
                ),
                Criteria.where("type").is(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT),
                Criteria.where("counterpartyAddress").is("MULTI")
        );

        Query query = new Query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.ON_CHAIN),
                evmFamilyCriteria(),
                attemptsCriteria,
                dueCriteria,
                leaseCriteria,
                new Criteria().orOperator(
                        reviewTailCriteria,
                        gmxPendingClarificationCriteria,
                        cowPendingClarificationCriteria,
                        bridgeEvidenceCriteria,
                        oneInchNativeSettlementCriteria,
                        eulerPendingClarificationCriteria,
                        nativeSettlementTransferRecoveryCriteria,
                        lpPositionCorrelationRecoveryCriteria,
                        lpFeeSplitEvidenceRecoveryCriteria,
                        lpEntryMissingCorrelationCriteria,
                        multicallMissingTransferCriteria
                )
        ));
        query.with(Sort.by(
                Sort.Order.asc("blockTimestamp"),
                Sort.Order.asc("transactionIndex"),
                Sort.Order.asc("_id")
        ));
        query.limit(selectionLimit);
        List<NormalizedTransaction> selected = excludeRowsWithPersistedReceiptEvidence(
                mongoOperations.find(query, NormalizedTransaction.class)
        );
        if (selected.size() >= boundedBatchSize) {
            return claimIfRequested(List.copyOf(selected.subList(0, boundedBatchSize)), workerId, leaseSeconds, now);
        }

        Map<String, NormalizedTransaction> deduplicated = new LinkedHashMap<>();
        for (NormalizedTransaction transaction : selected) {
            deduplicated.put(transaction.getId(), transaction);
        }
        List<NormalizedTransaction> gmxCandidates = loadGmxDerivativeExecutionCandidates(
                boundedBatchSize - deduplicated.size(),
                boundedMaxAttempts,
                retryCutoff
        );
        for (NormalizedTransaction transaction : gmxCandidates) {
            deduplicated.putIfAbsent(transaction.getId(), transaction);
            if (deduplicated.size() >= boundedBatchSize) {
                break;
            }
        }
        List<NormalizedTransaction> gmxPoolExitCandidates = loadAsyncRequestSettlementCandidates(
                boundedBatchSize - deduplicated.size(),
                boundedMaxAttempts,
                retryCutoff,
                NormalizedTransactionType.LP_EXIT_REQUEST,
                "GMX",
                List.of(NormalizedTransactionType.EXTERNAL_TRANSFER_IN, NormalizedTransactionType.VAULT_WITHDRAW),
                GMX_POOL_EXIT_CORRELATION_WINDOW_SECONDS
        );
        for (NormalizedTransaction transaction : gmxPoolExitCandidates) {
            deduplicated.putIfAbsent(transaction.getId(), transaction);
            if (deduplicated.size() >= boundedBatchSize) {
                break;
            }
        }
        List<NormalizedTransaction> cowSettlementCandidates = loadAsyncRequestSettlementCandidates(
                boundedBatchSize - deduplicated.size(),
                boundedMaxAttempts,
                retryCutoff,
                NormalizedTransactionType.DEX_ORDER_REQUEST,
                "CoW Swap",
                List.of(NormalizedTransactionType.EXTERNAL_TRANSFER_IN),
                COW_SETTLEMENT_CORRELATION_WINDOW_SECONDS
        );
        for (NormalizedTransaction transaction : cowSettlementCandidates) {
            deduplicated.putIfAbsent(transaction.getId(), transaction);
            if (deduplicated.size() >= boundedBatchSize) {
                break;
            }
        }
        List<NormalizedTransaction> filtered = excludeRowsWithPersistedReceiptEvidence(deduplicated.values());
        List<NormalizedTransaction> limited = filtered.size() <= boundedBatchSize
                ? List.copyOf(filtered)
                : List.copyOf(filtered.subList(0, boundedBatchSize));
        return claimIfRequested(limited, workerId, leaseSeconds, now);
    }

    /**
     * Releases all active (future) clarification leases across all PENDING_CLARIFICATION transactions.
     * Intended to run once at startup so that items leased by a previously crashed process become
     * immediately claimable — without waiting for the full lease TTL (300 s by default).
     * Safe for single-instance backends; idempotent.
     */
    public int releaseAllStaleLeases() {
        Instant now = Instant.now();
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("status").is(NormalizedTransactionStatus.PENDING_CLARIFICATION),
                Criteria.where("clarificationLeaseUntil").gt(now)
        ));
        Update release = new Update().unset("clarificationLeaseUntil");
        com.mongodb.client.result.UpdateResult result = mongoOperations.updateMulti(query, release, NormalizedTransaction.class);
        return (int) result.getModifiedCount();
    }

    private List<NormalizedTransaction> claimIfRequested(
            List<NormalizedTransaction> selected,
            String workerId,
            long leaseSeconds,
            Instant now
    ) {
        if (workerId == null || workerId.isBlank() || selected.isEmpty()) {
            return selected;
        }
        List<String> ids = new ArrayList<>(selected.size());
        for (NormalizedTransaction transaction : selected) {
            ids.add(transaction.getId());
        }
        Instant leaseUntil = now.plusSeconds(Math.max(1L, leaseSeconds));
        Criteria leaseCriteria = new Criteria().orOperator(
                Criteria.where("clarificationLeaseUntil").exists(false),
                Criteria.where("clarificationLeaseUntil").is(null),
                Criteria.where("clarificationLeaseUntil").lte(now)
        );
        Query claimQuery = new Query(new Criteria().andOperator(
                Criteria.where("_id").in(ids),
                leaseCriteria
        ));
        Update claim = new Update()
                .set("clarificationWorkerId", workerId)
                .set("clarificationLeaseUntil", leaseUntil)
                .set("updatedAt", now);
        mongoOperations.updateMulti(claimQuery, claim, NormalizedTransaction.class);

        Query claimedQuery = new Query(new Criteria().andOperator(
                Criteria.where("_id").in(ids),
                Criteria.where("clarificationWorkerId").is(workerId),
                Criteria.where("clarificationLeaseUntil").is(leaseUntil)
        ));
        claimedQuery.with(Sort.by(
                Sort.Order.asc("blockTimestamp"),
                Sort.Order.asc("transactionIndex"),
                Sort.Order.asc("_id")
        ));
        return mongoOperations.find(claimedQuery, NormalizedTransaction.class);
    }

    private List<NormalizedTransaction> loadGmxDerivativeExecutionCandidates(
            int limit,
            int maxAttempts,
            Instant retryCutoff
    ) {
        if (limit <= 0) {
            return List.of();
        }

        Query requestsQuery = new Query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.ON_CHAIN),
                evmFamilyCriteria(),
                Criteria.where("type").is(NormalizedTransactionType.DERIVATIVE_ORDER_REQUEST),
                Criteria.where("protocolName").is("GMX")
        ));
        requestsQuery.with(Sort.by(
                Sort.Order.asc("blockTimestamp"),
                Sort.Order.asc("transactionIndex"),
                Sort.Order.asc("_id")
        ));

        List<NormalizedTransaction> requests = mongoOperations.find(requestsQuery, NormalizedTransaction.class);
        return loadCorrelatedSettlementCandidates(
                limit,
                maxAttempts,
                retryCutoff,
                requests,
                List.of(
                        NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                        NormalizedTransactionType.EXTERNAL_TRANSFER_OUT
                ),
                GMX_EXECUTION_CORRELATION_WINDOW_SECONDS
        );
    }

    private List<NormalizedTransaction> loadAsyncRequestSettlementCandidates(
            int limit,
            int maxAttempts,
            Instant retryCutoff,
            NormalizedTransactionType requestType,
            String protocolName,
            List<NormalizedTransactionType> candidateTypes,
            long forwardWindowSeconds
    ) {
        if (limit <= 0) {
            return List.of();
        }

        Query requestsQuery = new Query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.ON_CHAIN),
                evmFamilyCriteria(),
                Criteria.where("type").is(requestType),
                Criteria.where("protocolName").is(protocolName)
        ));
        requestsQuery.with(Sort.by(
                Sort.Order.asc("blockTimestamp"),
                Sort.Order.asc("transactionIndex"),
                Sort.Order.asc("_id")
        ));

        List<NormalizedTransaction> requests = mongoOperations.find(requestsQuery, NormalizedTransaction.class);
        return loadCorrelatedSettlementCandidates(
                limit,
                maxAttempts,
                retryCutoff,
                requests,
                candidateTypes,
                forwardWindowSeconds
        );
    }

    private List<NormalizedTransaction> loadCorrelatedSettlementCandidates(
            int limit,
            int maxAttempts,
            Instant retryCutoff,
            List<NormalizedTransaction> requests,
            List<NormalizedTransactionType> candidateTypes,
            long forwardWindowSeconds
    ) {
        if (limit <= 0 || requests.isEmpty()) {
            return List.of();
        }

        Set<String> wallets = new LinkedHashSet<>();
        Set<NetworkId> networks = new LinkedHashSet<>();
        Set<String> requestIds = new LinkedHashSet<>();
        Instant minTimestamp = null;
        Instant maxTimestamp = null;
        List<NormalizedTransaction> validRequests = new ArrayList<>();
        for (NormalizedTransaction request : requests) {
            if (request == null
                    || request.getId() == null
                    || request.getBlockTimestamp() == null
                    || request.getWalletAddress() == null
                    || request.getNetworkId() == null) {
                continue;
            }
            validRequests.add(request);
            wallets.add(request.getWalletAddress());
            networks.add(request.getNetworkId());
            requestIds.add(request.getId());
            Instant windowEnd = request.getBlockTimestamp().plusSeconds(Math.max(1L, forwardWindowSeconds));
            minTimestamp = minTimestamp == null || request.getBlockTimestamp().isBefore(minTimestamp)
                    ? request.getBlockTimestamp()
                    : minTimestamp;
            maxTimestamp = maxTimestamp == null || windowEnd.isAfter(maxTimestamp) ? windowEnd : maxTimestamp;
        }
        if (validRequests.isEmpty() || wallets.isEmpty() || networks.isEmpty()) {
            return List.of();
        }

        Criteria attemptsCriteria = fullReceiptAttemptsCriteria(maxAttempts, retryCutoff);
        Query candidateQuery = new Query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.ON_CHAIN),
                evmFamilyCriteria(),
                Criteria.where("walletAddress").in(wallets),
                Criteria.where("networkId").in(networks),
                Criteria.where("status").is(NormalizedTransactionStatus.PENDING_PRICE),
                Criteria.where("type").in(candidateTypes),
                Criteria.where("_id").nin(requestIds),
                Criteria.where("blockTimestamp").gte(minTimestamp),
                Criteria.where("blockTimestamp").lte(maxTimestamp),
                attemptsCriteria
        ));
        candidateQuery.with(Sort.by(
                Sort.Order.asc("blockTimestamp"),
                Sort.Order.asc("transactionIndex"),
                Sort.Order.asc("_id")
        ));

        List<NormalizedTransaction> candidatePool = mongoOperations.find(candidateQuery, NormalizedTransaction.class);
        LinkedHashMap<String, NormalizedTransaction> candidates = new LinkedHashMap<>();
        for (NormalizedTransaction request : validRequests) {
            Instant windowStart = request.getBlockTimestamp();
            Instant windowEnd = windowStart.plusSeconds(Math.max(1L, forwardWindowSeconds));
            for (NormalizedTransaction candidate : candidatePool) {
                if (!correlatesWithRequest(request, candidate, windowStart, windowEnd)) {
                    continue;
                }
                candidates.putIfAbsent(candidate.getId(), candidate);
                if (candidates.size() >= limit) {
                    return List.copyOf(candidates.values());
                }
            }
        }
        return List.copyOf(candidates.values());
    }

    private Criteria fullReceiptAttemptsCriteria(int maxAttempts, Instant retryCutoff) {
        Criteria attemptsCriteria = new Criteria().orOperator(
                Criteria.where("fullReceiptClarificationAttempts").exists(false),
                Criteria.where("fullReceiptClarificationAttempts").lt(Math.max(1, maxAttempts))
        );
        Criteria dueCriteria = new Criteria().orOperator(
                Criteria.where("fullReceiptClarificationAttempts").exists(false),
                Criteria.where("fullReceiptClarificationAttempts").lte(0),
                Criteria.where("updatedAt").lte(retryCutoff)
        );
        return new Criteria().andOperator(attemptsCriteria, dueCriteria);
    }

    private static boolean correlatesWithRequest(
            NormalizedTransaction request,
            NormalizedTransaction candidate,
            Instant windowStart,
            Instant windowEnd
    ) {
        if (candidate == null
                || candidate.getId() == null
                || candidate.getBlockTimestamp() == null
                || candidate.getWalletAddress() == null
                || candidate.getNetworkId() == null) {
            return false;
        }
        return request.getWalletAddress().equals(candidate.getWalletAddress())
                && request.getNetworkId() == candidate.getNetworkId()
                && !request.getId().equals(candidate.getId())
                && !candidate.getBlockTimestamp().isBefore(windowStart)
                && !candidate.getBlockTimestamp().isAfter(windowEnd);
    }

    private List<NormalizedTransaction> excludeRowsWithPersistedReceiptEvidence(
            Iterable<NormalizedTransaction> candidates
    ) {
        List<NormalizedTransaction> rows = new ArrayList<>();
        List<String> rawIds = new ArrayList<>();
        for (NormalizedTransaction candidate : candidates) {
            if (candidate == null || candidate.getId() == null || candidate.getId().isBlank()) {
                continue;
            }
            rows.add(candidate);
            rawIds.add(candidate.getId());
        }
        if (rows.isEmpty()) {
            return List.of();
        }

        Map<String, RawTransaction> rawsById = new LinkedHashMap<>();
        for (RawTransaction rawTransaction : rawTransactionRepository.findAllById(rawIds)) {
            if (rawTransaction != null && rawTransaction.getId() != null) {
                rawsById.put(rawTransaction.getId(), rawTransaction);
            }
        }

        List<NormalizedTransaction> filtered = new ArrayList<>(rows.size());
        for (NormalizedTransaction row : rows) {
            RawTransaction rawTransaction = rawsById.get(row.getId());
            if (rawTransaction != null
                    && receiptClarificationGateway.fromPersistedEvidence(rawTransaction, true).isPresent()) {
                continue;
            }
            filtered.add(row);
        }
        return filtered;
    }
}
