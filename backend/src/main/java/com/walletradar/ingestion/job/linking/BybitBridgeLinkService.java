package com.walletradar.ingestion.job.linking;

import com.walletradar.domain.transaction.bybit.BybitExtractedEvent;
import com.walletradar.domain.transaction.bybit.BybitExtractedEventRepository;
import com.walletradar.domain.transaction.externalledger.ExternalLedgerRaw;
import com.walletradar.domain.transaction.externalledger.ExternalLedgerRawRepository;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.domain.transaction.raw.RawTransactionRepository;
import com.walletradar.ingestion.pipeline.bybit.BybitCanonicalTransactionBuilder;
import com.walletradar.ingestion.wallet.query.TrackedWalletLookupService;
import com.walletradar.integration.bybit.BybitExtractedEventMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Resolves Bybit bridge correlation after source-local normalization has finished.
 */
@Service
@RequiredArgsConstructor
public class BybitBridgeLinkService {

    static final String BRIDGE_MISSING_REASON = "BRIDGE_ON_CHAIN_LEG_NOT_FOUND";
    static final String EXTERNAL_CUSTODY_EXCLUSION_REASON = "EXTERNAL_CUSTODY_UNTRACKED_VENUE";

    private final MongoOperations mongoOperations;
    private final BybitExtractedEventRepository bybitExtractedEventRepository;
    private final ExternalLedgerRawRepository externalLedgerRawRepository;
    private final BybitExtractedEventMapper bybitExtractedEventMapper;
    private final BybitCanonicalTransactionBuilder bybitCanonicalTransactionBuilder;
    private final NormalizedTransactionRepository normalizedTransactionRepository;
    private final RawTransactionRepository rawTransactionRepository;
    private final TrackedWalletLookupService trackedWalletLookupService;

    public int reconcileOutstandingPairs(int batchSize) {
        int changed = 0;
        List<BybitExtractedEvent> extractedBatch = loadExtractedBatch(batchSize);
        for (BybitExtractedEvent candidate : extractedBatch) {
            if (repair(candidate, Instant.now())) {
                changed++;
            }
        }
        if (changed > 0 || !extractedBatch.isEmpty()) {
            return changed;
        }

        for (ExternalLedgerRaw candidate : loadLegacyBatch(batchSize)) {
            if (repair(candidate, Instant.now())) {
                changed++;
            }
        }
        return changed;
    }

    boolean repair(BybitExtractedEvent row, Instant now) {
        if (row == null) {
            return false;
        }
        return repair(row, bybitExtractedEventMapper.toLegacyRaw(row), now);
    }

    boolean repair(ExternalLedgerRaw row, Instant now) {
        if (row == null) {
            return false;
        }
        return repair(row, row, now);
    }

    private boolean repair(Object sourceRow, ExternalLedgerRaw mappedRow, Instant now) {
        if (!isBridgeCandidate(mappedRow)) {
            return false;
        }
        NormalizedTransaction bybitTransaction = normalizedTransactionRepository.findById(
                bybitCanonicalTransactionBuilder.canonicalId(mappedRow)
        ).orElse(null);
        if (bybitTransaction == null || bybitTransaction.getSource() != NormalizedTransactionSource.BYBIT) {
            return false;
        }

        List<RawTransaction> rawMatches = rawTransactionRepository.findAllByTxHashAndNetworkId(
                mappedRow.getTxHash(),
                mappedRow.getNetworkId().name()
        );
        List<NormalizedTransaction> normalizedMatches = normalizedTransactionRepository.findAllByTxHashAndNetworkIdAndSource(
                mappedRow.getTxHash(),
                mappedRow.getNetworkId(),
                NormalizedTransactionSource.ON_CHAIN
        );

        boolean changed;
        if (!rawMatches.isEmpty() && !normalizedMatches.isEmpty()) {
            changed = applyMatched(sourceRow, bybitTransaction, rawMatches.getFirst().getId(), now);
        } else if (isExternalCustodyCandidate(mappedRow)) {
            changed = applyExternalCustody(sourceRow, bybitTransaction, now);
        } else {
            changed = applyUnmatchedBridgeGap(sourceRow, bybitTransaction, now);
        }

        return changed;
    }

    private List<BybitExtractedEvent> loadExtractedBatch(int batchSize) {
        Query query = baseCandidateQuery(batchSize);
        return mongoOperations.find(query, BybitExtractedEvent.class);
    }

    private List<ExternalLedgerRaw> loadLegacyBatch(int batchSize) {
        Query query = baseCandidateQuery(batchSize);
        return mongoOperations.find(query, ExternalLedgerRaw.class);
    }

    private Query baseCandidateQuery(int batchSize) {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("status").is("CONFIRMED"),
                Criteria.where("sourceFileType").is("withdraw_deposit"),
                Criteria.where("txHash").exists(true).ne(""),
                Criteria.where("networkId").exists(true).ne(null),
                new Criteria().orOperator(
                        Criteria.where("onChainCorrelation.status").exists(false),
                        Criteria.where("onChainCorrelation.status").is(null),
                        Criteria.where("onChainCorrelation.status").is(""),
                        Criteria.where("onChainCorrelation.status").is("UNMATCHED"),
                        Criteria.where("onChainCorrelation.status").is("EXTERNAL_CUSTODY")
                )
        ));
        query.with(Sort.by(
                Sort.Order.asc("timeUtc"),
                Sort.Order.asc("_id")
        ));
        query.limit(Math.max(1, batchSize));
        return query;
    }

    private boolean applyMatched(
            Object sourceRow,
            NormalizedTransaction bybitTransaction,
            String matchedDocId,
            Instant now
    ) {
        boolean changed = applySourceCorrelation(sourceRow, "MATCHED", matchedDocId);
        changed |= clearBridgeAnnotations(bybitTransaction, now);
        if (changed) {
            saveSource(sourceRow);
            normalizedTransactionRepository.save(bybitTransaction);
        }
        return changed;
    }

    private boolean applyExternalCustody(
            Object sourceRow,
            NormalizedTransaction bybitTransaction,
            Instant now
    ) {
        boolean changed = applySourceCorrelation(sourceRow, "EXTERNAL_CUSTODY", null);
        NormalizedTransaction before = snapshot(bybitTransaction);
        bybitCanonicalTransactionBuilder.markExternalCustodyExcluded(
                bybitTransaction,
                now,
                EXTERNAL_CUSTODY_EXCLUSION_REASON
        );
        changed |= !sameBridgeState(before, bybitTransaction);
        if (changed) {
            saveSource(sourceRow);
            normalizedTransactionRepository.save(bybitTransaction);
        }
        return changed;
    }

    private boolean applyUnmatchedBridgeGap(
            Object sourceRow,
            NormalizedTransaction bybitTransaction,
            Instant now
    ) {
        boolean changed = applySourceCorrelation(sourceRow, "UNMATCHED", null);
        changed |= markBridgeGap(bybitTransaction, now);
        if (changed) {
            saveSource(sourceRow);
            normalizedTransactionRepository.save(bybitTransaction);
        }
        return changed;
    }

    private boolean applySourceCorrelation(Object sourceRow, String status, String matchedDocId) {
        if (sourceRow instanceof BybitExtractedEvent row) {
            BybitExtractedEvent.OnChainCorrelation correlation = row.getOnChainCorrelation();
            if (correlation == null) {
                correlation = new BybitExtractedEvent.OnChainCorrelation();
                row.setOnChainCorrelation(correlation);
            }
            boolean changed = false;
            if (!sameText(correlation.getStatus(), status)) {
                correlation.setStatus(status);
                changed = true;
            }
            if (!sameText(correlation.getMatchedDocId(), matchedDocId)) {
                correlation.setMatchedDocId(matchedDocId);
                changed = true;
            }
            if (correlation.getCorrelationId() != null) {
                correlation.setCorrelationId(null);
                changed = true;
            }
            return changed;
        }
        if (sourceRow instanceof ExternalLedgerRaw row) {
            ExternalLedgerRaw.OnChainCorrelation correlation = row.getOnChainCorrelation();
            if (correlation == null) {
                correlation = new ExternalLedgerRaw.OnChainCorrelation();
                row.setOnChainCorrelation(correlation);
            }
            boolean changed = false;
            if (!sameText(correlation.getStatus(), status)) {
                correlation.setStatus(status);
                changed = true;
            }
            if (!sameText(correlation.getMatchedDocId(), matchedDocId)) {
                correlation.setMatchedDocId(matchedDocId);
                changed = true;
            }
            if (correlation.getCorrelationId() != null) {
                correlation.setCorrelationId(null);
                changed = true;
            }
            return changed;
        }
        return false;
    }

    private boolean clearBridgeAnnotations(NormalizedTransaction transaction, Instant now) {
        boolean changed = false;
        changed |= removeMissingReason(transaction, BRIDGE_MISSING_REASON);
        changed |= removeMissingReason(transaction, EXTERNAL_CUSTODY_EXCLUSION_REASON);
        if (Boolean.TRUE.equals(transaction.getExcludedFromAccounting())) {
            transaction.setExcludedFromAccounting(false);
            changed = true;
        }
        if (transaction.getAccountingExclusionReason() != null) {
            transaction.setAccountingExclusionReason(null);
            changed = true;
        }
        if (transaction.getCorrelationId() != null) {
            transaction.setCorrelationId(null);
            changed = true;
        }
        if (transaction.getMatchedCounterparty() != null) {
            transaction.setMatchedCounterparty(null);
            changed = true;
        }
        if (Boolean.TRUE.equals(transaction.getContinuityCandidate())) {
            transaction.setContinuityCandidate(false);
            changed = true;
        }
        changed |= restoreBaselineStatus(transaction, now);
        if (changed) {
            transaction.setUpdatedAt(now);
        }
        return changed;
    }

    private boolean markBridgeGap(NormalizedTransaction transaction, Instant now) {
        boolean changed = false;
        changed |= removeMissingReason(transaction, EXTERNAL_CUSTODY_EXCLUSION_REASON);
        if (Boolean.TRUE.equals(transaction.getExcludedFromAccounting())) {
            transaction.setExcludedFromAccounting(false);
            changed = true;
        }
        if (transaction.getAccountingExclusionReason() != null) {
            transaction.setAccountingExclusionReason(null);
            changed = true;
        }
        if (transaction.getCorrelationId() != null) {
            transaction.setCorrelationId(null);
            changed = true;
        }
        if (transaction.getMatchedCounterparty() != null) {
            transaction.setMatchedCounterparty(null);
            changed = true;
        }
        if (Boolean.TRUE.equals(transaction.getContinuityCandidate())) {
            transaction.setContinuityCandidate(false);
            changed = true;
        }
        changed |= restoreBaselineStatus(transaction, now);
        if (!transaction.getMissingDataReasons().contains(BRIDGE_MISSING_REASON)) {
            transaction.getMissingDataReasons().add(BRIDGE_MISSING_REASON);
            changed = true;
        }
        if (changed) {
            transaction.setUpdatedAt(now);
        }
        return changed;
    }

    private boolean restoreBaselineStatus(NormalizedTransaction transaction, Instant now) {
        NormalizedTransactionStatus targetStatus = requiresPricing(transaction)
                ? NormalizedTransactionStatus.PENDING_PRICE
                : NormalizedTransactionStatus.CONFIRMED;
        boolean changed = false;
        if (transaction.getStatus() != targetStatus) {
            transaction.setStatus(targetStatus);
            changed = true;
        }
        if (targetStatus == NormalizedTransactionStatus.CONFIRMED) {
            if (transaction.getConfirmedAt() == null) {
                transaction.setConfirmedAt(now);
                changed = true;
            }
        } else if (transaction.getConfirmedAt() != null) {
            transaction.setConfirmedAt(null);
            changed = true;
        }
        return changed;
    }

    private boolean requiresPricing(NormalizedTransaction transaction) {
        return transaction.getFlows() != null && transaction.getFlows().stream()
                .anyMatch(flow -> flow != null
                        && (flow.getRole() == NormalizedLegRole.BUY || flow.getRole() == NormalizedLegRole.SELL));
    }

    private boolean removeMissingReason(NormalizedTransaction transaction, String reason) {
        if (transaction.getMissingDataReasons() == null) {
            transaction.setMissingDataReasons(new ArrayList<>());
            return false;
        }
        return transaction.getMissingDataReasons().removeIf(reason::equals);
    }

    private boolean isBridgeCandidate(ExternalLedgerRaw row) {
        if (row == null || row.getNetworkId() == null || blank(row.getTxHash())) {
            return false;
        }
        String sourceFileType = normalize(row.getSourceFileType());
        if (!"withdraw_deposit".equals(sourceFileType)) {
            return false;
        }
        String canonicalType = normalize(row.getCanonicalType());
        return "external_transfer_in".equals(canonicalType) || "external_inbound".equals(canonicalType)
                || "external_transfer_out".equals(canonicalType);
    }

    private boolean isExternalCustodyCandidate(ExternalLedgerRaw row) {
        return !blank(row.getReceivedAddress()) && !trackedWalletLookupService.contains(row.getReceivedAddress());
    }

    private void saveSource(Object sourceRow) {
        if (sourceRow instanceof BybitExtractedEvent row) {
            bybitExtractedEventRepository.save(row);
        } else if (sourceRow instanceof ExternalLedgerRaw row) {
            externalLedgerRawRepository.save(row);
        }
    }

    private NormalizedTransaction snapshot(NormalizedTransaction transaction) {
        NormalizedTransaction copy = new NormalizedTransaction();
        copy.setStatus(transaction.getStatus());
        copy.setConfirmedAt(transaction.getConfirmedAt());
        copy.setExcludedFromAccounting(transaction.getExcludedFromAccounting());
        copy.setAccountingExclusionReason(transaction.getAccountingExclusionReason());
        copy.setCorrelationId(transaction.getCorrelationId());
        copy.setMatchedCounterparty(transaction.getMatchedCounterparty());
        copy.setContinuityCandidate(transaction.getContinuityCandidate());
        copy.setUpdatedAt(transaction.getUpdatedAt());
        copy.setMissingDataReasons(transaction.getMissingDataReasons() == null
                ? new ArrayList<>()
                : new ArrayList<>(transaction.getMissingDataReasons()));
        return copy;
    }

    private boolean sameBridgeState(NormalizedTransaction left, NormalizedTransaction right) {
        return left.getStatus() == right.getStatus()
                && sameText(left.getAccountingExclusionReason(), right.getAccountingExclusionReason())
                && sameText(left.getCorrelationId(), right.getCorrelationId())
                && sameText(left.getMatchedCounterparty(), right.getMatchedCounterparty())
                && java.util.Objects.equals(left.getContinuityCandidate(), right.getContinuityCandidate())
                && java.util.Objects.equals(left.getExcludedFromAccounting(), right.getExcludedFromAccounting())
                && java.util.Objects.equals(left.getMissingDataReasons(), right.getMissingDataReasons());
    }

    private boolean sameText(String left, String right) {
        return normalize(left).equals(normalize(right));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
