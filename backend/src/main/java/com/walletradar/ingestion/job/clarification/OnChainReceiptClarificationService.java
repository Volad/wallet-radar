package com.walletradar.ingestion.job.clarification;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.domain.transaction.raw.RawTransactionRepository;
import com.walletradar.ingestion.config.OnChainClarificationProperties;
import com.walletradar.ingestion.pipeline.classification.OnChainClassificationResult;
import com.walletradar.ingestion.pipeline.classification.OnChainClassifier;
import com.walletradar.ingestion.pipeline.clarification.ClarificationMode;
import com.walletradar.ingestion.pipeline.clarification.ClarificationReceiptEnrichment;
import com.walletradar.ingestion.pipeline.clarification.PendingReceiptClarificationQueryService;
import com.walletradar.ingestion.pipeline.clarification.ReceiptClarificationEligibilitySupport;
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
 * Allowlisted full-receipt clarification for residual review families that require receipt evidence.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OnChainReceiptClarificationService {

    private final PendingReceiptClarificationQueryService pendingReceiptClarificationQueryService;
    private final OnChainClarificationProperties properties;
    private final ReceiptClarificationGateway clarificationGateway;
    private final RawTransactionClarificationEnricher rawTransactionClarificationEnricher;
    private final RawTransactionRepository rawTransactionRepository;
    private final OnChainClassifier onChainClassifier;
    private final OnChainNormalizedTransactionBuilder builder;
    private final NormalizedTransactionRepository normalizedTransactionRepository;

    public int processNextBatch() {
        List<NormalizedTransaction> batch = pendingReceiptClarificationQueryService.loadNextBatch(
                properties.getFullReceipt().getBatchSize(),
                properties.getFullReceipt().getMaxAttempts(),
                properties.getFullReceipt().getRetryDelaySeconds()
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
        OnChainRawTransactionView view = OnChainRawTransactionView.wrap(rawTransaction);
        if (!ReceiptClarificationEligibilitySupport.isEligible(normalizedTransaction, view)) {
            return false;
        }

        try {
            Optional<ClarificationReceiptEnrichment> enrichment = clarificationGateway.fetch(
                    rawTransaction,
                    ClarificationMode.FULL_RECEIPT
            );
            if (enrichment.isEmpty()) {
                markFailure(normalizedTransaction, rawTransaction, "CLARIFICATION_FULL_RECEIPT_UNAVAILABLE", now);
                return false;
            }

            rawTransactionClarificationEnricher.merge(rawTransaction, enrichment.get());
            rawTransactionClarificationEnricher.recordAttempt(
                    rawTransaction,
                    ClarificationMode.FULL_RECEIPT,
                    safeAttempts(normalizedTransaction.getFullReceiptClarificationAttempts()),
                    null
            );
            rawTransactionRepository.save(rawTransaction);

            OnChainClassificationResult classificationResult = onChainClassifier.classify(rawTransaction);
            NormalizedTransaction reclassified = builder.rebuildAfterReclassification(
                    normalizedTransaction,
                    rawTransaction,
                    classificationResult,
                    now
            );
            normalizedTransactionRepository.save(reclassified);
            return true;
        } catch (RuntimeException ex) {
            log.warn("On-chain full-receipt clarification failed for normalizedTxId={}: {}", normalizedTransaction.getId(), ex.getMessage());
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
        int nextAttempts = safeAttempts(normalizedTransaction.getFullReceiptClarificationAttempts()) + 1;
        if (rawTransaction != null) {
            nextAttempts = rawTransactionClarificationEnricher.recordAttempt(
                    rawTransaction,
                    ClarificationMode.FULL_RECEIPT,
                    safeAttempts(normalizedTransaction.getFullReceiptClarificationAttempts()),
                    reason
            );
            rawTransactionRepository.save(rawTransaction);
        }
        normalizedTransaction.setFullReceiptClarificationAttempts(nextAttempts);
        normalizedTransaction.setUpdatedAt(now);
        List<String> reasons = new ArrayList<>(normalizedTransaction.getMissingDataReasons() == null
                ? List.of()
                : normalizedTransaction.getMissingDataReasons());
        if (reason != null && !reason.isBlank() && !reasons.contains(reason)) {
            reasons.add(reason);
        }
        normalizedTransaction.setMissingDataReasons(reasons);
        normalizedTransactionRepository.save(normalizedTransaction);
    }

    private int safeAttempts(Integer attempts) {
        return attempts == null ? 0 : Math.max(0, attempts);
    }
}
