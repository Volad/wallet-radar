package com.walletradar.ingestion.job.clarification;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.domain.transaction.raw.RawTransactionRepository;
import com.walletradar.ingestion.config.OnChainClarificationProperties;
import com.walletradar.ingestion.pipeline.classification.OnChainClassificationResult;
import com.walletradar.ingestion.pipeline.classification.OnChainClassifier;
import com.walletradar.ingestion.pipeline.classification.support.ClarificationEligibilitySupport;
import com.walletradar.ingestion.pipeline.clarification.ClarificationMode;
import com.walletradar.ingestion.pipeline.clarification.ClarificationReceiptEnrichment;
import com.walletradar.ingestion.pipeline.clarification.PendingClarificationQueryService;
import com.walletradar.ingestion.pipeline.clarification.RawTransactionClarificationEnricher;
import com.walletradar.ingestion.pipeline.clarification.ReceiptClarificationGateway;
import com.walletradar.ingestion.pipeline.onchain.OnChainNormalizedTransactionBuilder;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Bounded clarification stage for low-confidence on-chain transactions.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OnChainClarificationService {

    private final PendingClarificationQueryService pendingClarificationQueryService;
    private final OnChainClarificationProperties properties;
    private final ReceiptClarificationGateway clarificationGateway;
    private final RawTransactionClarificationEnricher rawTransactionClarificationEnricher;
    private final RawTransactionRepository rawTransactionRepository;
    private final OnChainClassifier onChainClassifier;
    private final OnChainNormalizedTransactionBuilder builder;
    private final NormalizedTransactionRepository normalizedTransactionRepository;

    public int processNextBatch() {
        List<NormalizedTransaction> batch = pendingClarificationQueryService.loadNextBatch(
                properties.getBatchSize(),
                properties.getMaxAttempts(),
                properties.getRetryDelaySeconds()
        );

        int completed = 0;
        for (NormalizedTransaction normalizedTransaction : batch) {
            if (clarify(normalizedTransaction)) {
                completed++;
            }
        }
         return completed;
    }

    public boolean clarify(NormalizedTransaction normalizedTransaction) {
        Instant now = Instant.now();
        Optional<RawTransaction> rawTransactionOptional = rawTransactionRepository.findById(normalizedTransaction.getId());
        if (rawTransactionOptional.isEmpty()) {
            markFailure(normalizedTransaction, null, "RAW_TRANSACTION_MISSING", now);
            return false;
        }

        RawTransaction rawTransaction = rawTransactionOptional.get();
        try {
            OnChainClassificationResult currentClassification = onChainClassifier.classify(rawTransaction);
            if (currentClassification.status() != NormalizedTransactionStatus.PENDING_CLARIFICATION) {
                NormalizedTransaction reclassified = builder.rebuildAfterReclassification(
                        normalizedTransaction,
                        rawTransaction,
                        currentClassification,
                        now
                );
                normalizedTransactionRepository.save(reclassified);
                return true;
            }

            Optional<ClarificationReceiptEnrichment> enrichment = clarificationGateway.fetch(
                    rawTransaction,
                    ClarificationMode.METADATA_ONLY
            );
            if (enrichment.isEmpty()) {
                markFailure(normalizedTransaction, rawTransaction, "CLARIFICATION_RECEIPT_UNAVAILABLE", now);
                return false;
            }

            rawTransactionClarificationEnricher.merge(rawTransaction, enrichment.get());
            rawTransactionRepository.save(rawTransaction);

            OnChainClassificationResult classificationResult = onChainClassifier.classify(rawTransaction);
            if (classificationResult.status() == NormalizedTransactionStatus.PENDING_CLARIFICATION) {
                markFailure(normalizedTransaction, rawTransaction, "CLARIFICATION_INSUFFICIENT_EVIDENCE", now);
                return false;
            }
            NormalizedTransaction clarified = builder.rebuildAfterClarification(
                    normalizedTransaction,
                    rawTransaction,
                    classificationResult,
                    now
            );
            normalizedTransactionRepository.save(clarified);
            return true;
        } catch (RuntimeException ex) {
            log.warn("On-chain clarification failed for normalizedTxId={}: {}", normalizedTransaction.getId(), ex.getMessage());
            markFailure(normalizedTransaction, rawTransaction, ex.getClass().getSimpleName(), now);
            return false;
        }
    }

    private void markFailure(
            NormalizedTransaction normalizedTransaction,
            RawTransaction rawTransaction,
            String reason,
            Instant now
    ) {
        int nextAttempts = safeAttempts(normalizedTransaction.getClarificationAttempts()) + 1;
        normalizedTransaction.setClarificationAttempts(nextAttempts);
        normalizedTransaction.setUpdatedAt(now);

        List<String> reasons = new ArrayList<>(initialReasons(normalizedTransaction, rawTransaction));
        addReason(reasons, reason);
        if (nextAttempts >= Math.max(1, properties.getMaxAttempts())) {
            normalizedTransaction.setStatus(NormalizedTransactionStatus.NEEDS_REVIEW);
            addReason(reasons, "CLARIFICATION_ATTEMPTS_EXHAUSTED");
        } else {
            normalizedTransaction.setStatus(NormalizedTransactionStatus.PENDING_CLARIFICATION);
        }
        normalizedTransaction.setMissingDataReasons(reasons);
        normalizedTransactionRepository.save(normalizedTransaction);
    }

    private List<String> initialReasons(
            NormalizedTransaction normalizedTransaction,
            RawTransaction rawTransaction
    ) {
        List<String> existingReasons = normalizedTransaction.getMissingDataReasons() == null
                ? List.of()
                : normalizedTransaction.getMissingDataReasons();
        if (rawTransaction == null) {
            return existingReasons;
        }
        return ClarificationEligibilitySupport.mergeClarificationReasons(
                OnChainRawTransactionView.wrap(rawTransaction),
                normalizedTransaction.getType(),
                existingReasons
        );
    }

    private int safeAttempts(Integer attempts) {
        return attempts == null ? 0 : Math.max(0, attempts);
    }

    private void addReason(List<String> reasons, String reason) {
        if (reason != null && !reason.isBlank() && !reasons.contains(reason)) {
            reasons.add(reason);
        }
    }
}
