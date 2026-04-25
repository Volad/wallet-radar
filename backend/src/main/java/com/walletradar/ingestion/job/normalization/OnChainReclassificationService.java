package com.walletradar.ingestion.job.normalization;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.domain.transaction.raw.RawTransactionRepository;
import com.walletradar.ingestion.config.OnChainNormalizationProperties;
import com.walletradar.ingestion.pipeline.classification.OnChainClassificationResult;
import com.walletradar.ingestion.pipeline.classification.OnChainClassifier;
import com.walletradar.ingestion.pipeline.classification.reason.ClassificationReasonCode;
import com.walletradar.ingestion.pipeline.clarification.CounterpartyEnrichmentService;
import com.walletradar.ingestion.pipeline.clarification.ProtocolNameEnrichmentService;
import com.walletradar.ingestion.pipeline.clarification.RelatedLifecycleDiscoveryService;
import com.walletradar.ingestion.pipeline.onchain.OnChainNormalizedTransactionBuilder;
import com.walletradar.ingestion.pipeline.onchain.PendingReclassificationQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the normal on-chain classifier over rows whose clarification evidence is now persisted.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OnChainReclassificationService {

    private final PendingReclassificationQueryService pendingReclassificationQueryService;
    private final RawTransactionRepository rawTransactionRepository;
    private final NormalizedTransactionRepository normalizedTransactionRepository;
    private final OnChainNormalizationProperties properties;
    private final OnChainClassifier onChainClassifier;
    private final OnChainNormalizedTransactionBuilder builder;
    private final ProtocolNameEnrichmentService protocolNameEnrichmentService;
    private final CounterpartyEnrichmentService counterpartyEnrichmentService;
    @Nullable
    private final RelatedLifecycleDiscoveryService relatedLifecycleDiscoveryService;

    public int processNextBatch() {
        List<NormalizedTransaction> batch = pendingReclassificationQueryService.loadNextBatch(properties.getBatchSize());
        int completed = 0;
        for (NormalizedTransaction normalizedTransaction : batch) {
            if (reclassify(normalizedTransaction)) {
                completed++;
            }
        }
        return completed;
    }

    public boolean reclassify(NormalizedTransaction normalizedTransaction) {
        if (normalizedTransaction == null || normalizedTransaction.getId() == null) {
            return false;
        }
        Instant now = Instant.now();
        return rawTransactionRepository.findById(normalizedTransaction.getId())
                .map(rawTransaction -> reclassify(normalizedTransaction, rawTransaction, now))
                .orElseGet(() -> markMissingRaw(normalizedTransaction, now));
    }

    private boolean reclassify(
            NormalizedTransaction existing,
            RawTransaction rawTransaction,
            Instant now
    ) {
        try {
            OnChainClassificationResult classificationResult = onChainClassifier.classify(rawTransaction);
            NormalizedTransaction reclassified = builder.rebuildAfterReclassification(
                    existing,
                    rawTransaction,
                    classificationResult,
                    now
            );
            enrichCanonicalMetadata(reclassified, rawTransaction, now);
            NormalizedTransaction saved = normalizedTransactionRepository.save(reclassified);
            if (relatedLifecycleDiscoveryService != null
                    && saved.getStatus() != NormalizedTransactionStatus.PENDING_CLARIFICATION) {
                relatedLifecycleDiscoveryService.discoverAndNormalize(rawTransaction, classificationResult);
            }
            log.debug(
                    "On-chain reclassification complete: normalizedTxId={}, status={}, type={}",
                    saved.getId(),
                    saved.getStatus(),
                    saved.getType()
            );
            return true;
        } catch (RuntimeException error) {
            log.warn("On-chain reclassification failed for normalizedTxId={}: {}", existing.getId(), error.getMessage());
            return false;
        }
    }

    private void enrichCanonicalMetadata(
            NormalizedTransaction normalizedTransaction,
            RawTransaction rawTransaction,
            Instant now
    ) {
        if (normalizedTransaction == null
                || normalizedTransaction.getStatus() == NormalizedTransactionStatus.PENDING_CLARIFICATION) {
            return;
        }
        protocolNameEnrichmentService.enrichInPlace(normalizedTransaction, rawTransaction, now);
        counterpartyEnrichmentService.enrichInPlace(normalizedTransaction, rawTransaction, now);
    }

    private boolean markMissingRaw(NormalizedTransaction normalizedTransaction, Instant now) {
        List<String> reasons = normalizedTransaction.getMissingDataReasons() == null
                ? new ArrayList<>()
                : new ArrayList<>(normalizedTransaction.getMissingDataReasons());
        if (!reasons.contains(ClassificationReasonCode.RAW_TRANSACTION_MISSING.code())) {
            reasons.add(ClassificationReasonCode.RAW_TRANSACTION_MISSING.code());
        }
        normalizedTransaction.setStatus(NormalizedTransactionStatus.NEEDS_REVIEW);
        normalizedTransaction.setMissingDataReasons(List.copyOf(reasons));
        normalizedTransaction.setUpdatedAt(now);
        normalizedTransactionRepository.save(normalizedTransaction);
        return true;
    }
}
