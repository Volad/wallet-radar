package com.walletradar.ingestion.pipeline.clarification;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.domain.transaction.raw.RawTransactionRepository;
import com.walletradar.ingestion.pipeline.classification.reason.ClassificationReasonCode;
import com.walletradar.ingestion.pipeline.classification.support.ClarificationEligibilitySupport;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
        Criteria reviewReasonsCriteria = Criteria.where("missingDataReasons").in(
                ClassificationReasonCode.ROUTER_METHOD_OVERLOAD_UNSUPPORTED.code(),
                ClassificationReasonCode.CLASSIFICATION_FAILED.code(),
                ClassificationReasonCode.INSUFFICIENT_MOVEMENT_EVIDENCE.code(),
                ClassificationReasonCode.GMX_DEPOSIT_REQUEST_CORRELATION_REQUIRED.code(),
                ClassificationReasonCode.GMX_DEPOSIT_SETTLEMENT_CORRELATION_REQUIRED.code(),
                ClassificationReasonCode.EULER_BATCH_DECODER_REQUIRED.code()
        );
        Criteria reviewTailCriteria = new Criteria().andOperator(
                Criteria.where("status").is(NormalizedTransactionStatus.NEEDS_REVIEW),
                reviewReasonsCriteria
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
        Criteria nativeSettlementTransferRecoveryCriteria = new Criteria().andOperator(
                Criteria.where("status").is(NormalizedTransactionStatus.PENDING_CLARIFICATION),
                Criteria.where("type").in(
                        NormalizedTransactionType.LP_EXIT,
                        NormalizedTransactionType.LP_FEE_CLAIM
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

        Query query = new Query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.ON_CHAIN),
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
                        lpPositionCorrelationRecoveryCriteria
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
                Criteria.where("type").is(NormalizedTransactionType.DERIVATIVE_ORDER_REQUEST),
                Criteria.where("protocolName").is("GMX")
        ));
        requestsQuery.with(Sort.by(
                Sort.Order.asc("blockTimestamp"),
                Sort.Order.asc("transactionIndex"),
                Sort.Order.asc("_id")
        ));

        List<NormalizedTransaction> requests = mongoOperations.find(requestsQuery, NormalizedTransaction.class);
        LinkedHashMap<String, NormalizedTransaction> candidates = new LinkedHashMap<>();
        for (NormalizedTransaction request : requests) {
            if (request == null
                    || request.getBlockTimestamp() == null
                    || request.getWalletAddress() == null
                    || request.getNetworkId() == null) {
                continue;
            }

            Criteria attemptsCriteria = new Criteria().orOperator(
                    Criteria.where("fullReceiptClarificationAttempts").exists(false),
                    Criteria.where("fullReceiptClarificationAttempts").lt(Math.max(1, maxAttempts))
            );
            Criteria dueCriteria = new Criteria().orOperator(
                    Criteria.where("fullReceiptClarificationAttempts").exists(false),
                    Criteria.where("fullReceiptClarificationAttempts").lte(0),
                    Criteria.where("updatedAt").lte(retryCutoff)
            );
            Query candidateQuery = new Query(new Criteria().andOperator(
                    Criteria.where("source").is(NormalizedTransactionSource.ON_CHAIN),
                    Criteria.where("walletAddress").is(request.getWalletAddress()),
                    Criteria.where("networkId").is(request.getNetworkId()),
                    Criteria.where("status").is(NormalizedTransactionStatus.PENDING_PRICE),
                    Criteria.where("type").in(
                            NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                            NormalizedTransactionType.EXTERNAL_TRANSFER_OUT
                    ),
                    Criteria.where("_id").ne(request.getId()),
                    Criteria.where("blockTimestamp").gte(request.getBlockTimestamp()),
                    Criteria.where("blockTimestamp").lte(request.getBlockTimestamp().plusSeconds(GMX_EXECUTION_CORRELATION_WINDOW_SECONDS)),
                    attemptsCriteria,
                    dueCriteria
            ));
            candidateQuery.with(Sort.by(
                    Sort.Order.asc("blockTimestamp"),
                    Sort.Order.asc("transactionIndex"),
                    Sort.Order.asc("_id")
            ));
            candidateQuery.limit(Math.max(limit, limit * 4));

            for (NormalizedTransaction candidate : mongoOperations.find(candidateQuery, NormalizedTransaction.class)) {
                candidates.putIfAbsent(candidate.getId(), candidate);
                if (candidates.size() >= limit) {
                    return List.copyOf(candidates.values());
                }
            }
        }
        return List.copyOf(candidates.values());
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
                Criteria.where("type").is(requestType),
                Criteria.where("protocolName").is(protocolName)
        ));
        requestsQuery.with(Sort.by(
                Sort.Order.asc("blockTimestamp"),
                Sort.Order.asc("transactionIndex"),
                Sort.Order.asc("_id")
        ));

        List<NormalizedTransaction> requests = mongoOperations.find(requestsQuery, NormalizedTransaction.class);
        LinkedHashMap<String, NormalizedTransaction> candidates = new LinkedHashMap<>();
        for (NormalizedTransaction request : requests) {
            if (request == null
                    || request.getBlockTimestamp() == null
                    || request.getWalletAddress() == null
                    || request.getNetworkId() == null) {
                continue;
            }

            Criteria attemptsCriteria = new Criteria().orOperator(
                    Criteria.where("fullReceiptClarificationAttempts").exists(false),
                    Criteria.where("fullReceiptClarificationAttempts").lt(Math.max(1, maxAttempts))
            );
            Criteria dueCriteria = new Criteria().orOperator(
                    Criteria.where("fullReceiptClarificationAttempts").exists(false),
                    Criteria.where("fullReceiptClarificationAttempts").lte(0),
                    Criteria.where("updatedAt").lte(retryCutoff)
            );
            Query candidateQuery = new Query(new Criteria().andOperator(
                    Criteria.where("source").is(NormalizedTransactionSource.ON_CHAIN),
                    Criteria.where("walletAddress").is(request.getWalletAddress()),
                    Criteria.where("networkId").is(request.getNetworkId()),
                    Criteria.where("status").is(NormalizedTransactionStatus.PENDING_PRICE),
                    Criteria.where("type").in(candidateTypes),
                    Criteria.where("_id").ne(request.getId()),
                    Criteria.where("blockTimestamp").gte(request.getBlockTimestamp()),
                    Criteria.where("blockTimestamp").lte(request.getBlockTimestamp().plusSeconds(Math.max(1L, forwardWindowSeconds))),
                    attemptsCriteria,
                    dueCriteria
            ));
            candidateQuery.with(Sort.by(
                    Sort.Order.asc("blockTimestamp"),
                    Sort.Order.asc("transactionIndex"),
                    Sort.Order.asc("_id")
            ));
            candidateQuery.limit(Math.max(limit, limit * 4));

            for (NormalizedTransaction candidate : mongoOperations.find(candidateQuery, NormalizedTransaction.class)) {
                candidates.putIfAbsent(candidate.getId(), candidate);
                if (candidates.size() >= limit) {
                    return List.copyOf(candidates.values());
                }
            }
        }
        return List.copyOf(candidates.values());
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
