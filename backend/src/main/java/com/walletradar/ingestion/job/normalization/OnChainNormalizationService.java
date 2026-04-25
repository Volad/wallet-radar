package com.walletradar.ingestion.job.normalization;

import com.walletradar.domain.transaction.raw.NormalizationStatus;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.domain.transaction.raw.RawTransactionRepository;
import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.transaction.normalized.ClassificationSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.ingestion.config.OnChainNormalizationProperties;
import com.walletradar.ingestion.pipeline.classification.OnChainClassifier;
import com.walletradar.ingestion.pipeline.classification.OnChainClassificationResult;
import com.walletradar.ingestion.pipeline.clarification.CounterpartyEnrichmentService;
import com.walletradar.ingestion.pipeline.clarification.ProtocolNameEnrichmentService;
import com.walletradar.ingestion.pipeline.onchain.OnChainNormalizedTransactionBuilder;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;
import com.walletradar.ingestion.pipeline.onchain.PendingRawTransactionQueryService;
import com.walletradar.ingestion.pipeline.onchain.repair.ExplorerRawOrderingRepairGateway;
import com.walletradar.ingestion.pipeline.onchain.repair.InternalTransferRawPeerRepairService;
import com.walletradar.ingestion.pipeline.onchain.support.RawOrderingMetadataResolver;
import com.walletradar.ingestion.pipeline.onchain.support.ResolvedRawOrderingMetadata;
import com.walletradar.ingestion.store.IdempotentNormalizedTransactionStore;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Deterministic shell processor for pending on-chain raw evidence.
 */
@Service
@RequiredArgsConstructor
public class OnChainNormalizationService {

    private static final Logger log = LoggerFactory.getLogger(OnChainNormalizationService.class);

    private static final Comparator<RawTransaction> RAW_ORDER = Comparator
            .comparing(
                    (RawTransaction rawTransaction) -> OnChainRawTransactionView.wrap(rawTransaction).blockTimestamp(),
                    Comparator.nullsLast(Instant::compareTo)
            )
            .thenComparing(
                    rawTransaction -> OnChainRawTransactionView.wrap(rawTransaction).transactionIndex(),
                    Comparator.nullsLast(Integer::compareTo)
            )
            .thenComparing(RawTransaction::getTxHash, Comparator.nullsLast(String::compareTo));

    private final PendingRawTransactionQueryService pendingRawTransactionQueryService;
    private final OnChainNormalizationProperties properties;
    private final OnChainClassifier onChainClassifier;
    private final OnChainNormalizedTransactionBuilder builder;
    private final IdempotentNormalizedTransactionStore normalizedTransactionStore;
    private final RawTransactionRepository rawTransactionRepository;
    private final ExplorerRawOrderingRepairGateway explorerRawOrderingRepairGateway;
    private final InternalTransferRawPeerRepairService internalTransferRawPeerRepairService;
    private final ProtocolNameEnrichmentService protocolNameEnrichmentService;
    private final CounterpartyEnrichmentService counterpartyEnrichmentService;

    public int processNextBatch() {
        List<RawTransaction> batch = new ArrayList<>(
                pendingRawTransactionQueryService.loadNextBatch(properties.getBatchSize())
        );
        int repairedPeers = internalTransferRawPeerRepairService.repairMissingPeers(batch);
        if (repairedPeers > 0) {
            log.info("On-chain internal transfer raw peer repair complete: repaired={}", repairedPeers);
        }
        for (RawTransaction rawTransaction : batch) {
            prepareOrdering(rawTransaction);
        }
        batch.sort(RAW_ORDER);

        int completed = 0;
        for (RawTransaction rawTransaction : batch) {
            if (normalize(rawTransaction, true)) {
                completed++;
            }
        }
        return completed;
    }

    public boolean normalize(RawTransaction rawTransaction) {
        return normalize(rawTransaction, false);
    }

    private boolean normalize(RawTransaction rawTransaction, boolean orderingPrepared) {
        Instant now = Instant.now();
        if (!orderingPrepared) {
            prepareOrdering(rawTransaction);
        }
        OnChainRawTransactionView view = OnChainRawTransactionView.wrap(rawTransaction);
        List<String> validationErrors = view.validationErrors();
        if (!validationErrors.isEmpty()) {
            log.warn("On-chain normalization validation failed for rawTxId={}, errors={}", rawTransaction.getId(), validationErrors);
            normalizedTransactionStore.upsert(builder.build(rawTransaction, validationFailureResult(validationErrors), now));
            markComplete(rawTransaction);
            return true;
        }

        try {
            OnChainClassificationResult classificationResult = onChainClassifier.classify(rawTransaction);
            com.walletradar.domain.transaction.normalized.NormalizedTransaction normalized =
                    builder.build(rawTransaction, classificationResult, now);
            enrichCanonicalMetadata(normalized, rawTransaction, now);
            normalizedTransactionStore.upsert(normalized);
            markComplete(rawTransaction);
            return true;
        } catch (RuntimeException ex) {
            log.warn("On-chain normalization shell failed for rawTxId={}: {}", rawTransaction.getId(), ex.getMessage());
            markRetry(rawTransaction, ex.getMessage(), now);
            return false;
        }
    }

    private void enrichCanonicalMetadata(
            com.walletradar.domain.transaction.normalized.NormalizedTransaction normalizedTransaction,
            RawTransaction rawTransaction,
            Instant now
    ) {
        if (normalizedTransaction == null
                || normalizedTransaction.getStatus() == NormalizedTransactionStatus.PENDING_CLARIFICATION
                || normalizedTransaction.getType() == NormalizedTransactionType.UNKNOWN) {
            return;
        }
        protocolNameEnrichmentService.enrichInPlace(normalizedTransaction, rawTransaction, now);
        counterpartyEnrichmentService.enrichInPlace(normalizedTransaction, rawTransaction, now);
    }

    private void prepareOrdering(RawTransaction rawTransaction) {
        RawOrderingMetadataResolver.canonicalizeTopLevel(rawTransaction);
        OnChainRawTransactionView view = OnChainRawTransactionView.wrap(rawTransaction);
        if (view.transactionIndex() != null && view.blockTimestamp() != null) {
            return;
        }

        explorerRawOrderingRepairGateway.fetch(view.txHash(), view.networkId())
                .ifPresent(repaired -> applyRepairedOrdering(rawTransaction, repaired));
    }

    private void applyRepairedOrdering(
            RawTransaction rawTransaction,
            ResolvedRawOrderingMetadata repaired
    ) {
        ResolvedRawOrderingMetadata existing = RawOrderingMetadataResolver.resolve(rawTransaction);
        ResolvedRawOrderingMetadata merged = new ResolvedRawOrderingMetadata(
                existing.epochSeconds() != null ? existing.epochSeconds() : repaired.epochSeconds(),
                existing.transactionIndex() != null ? existing.transactionIndex() : repaired.transactionIndex()
        );
        RawOrderingMetadataResolver.canonicalizeTopLevel(rawTransaction, merged);
    }

    private void markComplete(RawTransaction rawTransaction) {
        rawTransaction.setNormalizationStatus(NormalizationStatus.COMPLETE);
        rawTransaction.setRetryCount(0);
        rawTransaction.setLastError(null);
        rawTransaction.setNextRetryAt(null);
        rawTransactionRepository.save(rawTransaction);
    }

    private void markRetry(RawTransaction rawTransaction, String reason, Instant now) {
        rawTransaction.setNormalizationStatus(NormalizationStatus.PENDING);
        rawTransaction.setRetryCount((rawTransaction.getRetryCount() == null ? 0 : rawTransaction.getRetryCount()) + 1);
        rawTransaction.setLastError(reason == null || reason.isBlank() ? "Normalization failed" : reason);
        rawTransaction.setNextRetryAt(now.plusSeconds(Math.max(1L, properties.getRetryDelaySeconds())));
        rawTransactionRepository.save(rawTransaction);
    }

    private OnChainClassificationResult validationFailureResult(List<String> validationErrors) {
        Set<String> reasons = new LinkedHashSet<>();
        for (String error : validationErrors) {
            switch (error) {
                case "Missing rawData.transactionIndex" -> reasons.add("MISSING_TRANSACTION_INDEX");
                case "Missing rawData.timeStamp" -> reasons.add("MISSING_BLOCK_TIMESTAMP");
                default -> reasons.add(error);
            }
        }
        return new OnChainClassificationResult(
                NormalizedTransactionType.UNKNOWN,
                NormalizedTransactionStatus.NEEDS_REVIEW,
                ClassificationSource.HEURISTIC,
                ConfidenceLevel.LOW,
                List.of(),
                List.copyOf(reasons),
                null,
                false,
                null,
                false,
                null,
                null,
                null
        );
    }
}
