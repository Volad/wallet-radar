package com.walletradar.ingestion.job.clarification;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.ingestion.config.OnChainClarificationProperties;
import com.walletradar.ingestion.pipeline.classification.OnChainClassificationResult;
import com.walletradar.ingestion.pipeline.classification.OnChainClassifier;
import com.walletradar.ingestion.pipeline.clarification.ClarificationReceiptEnrichment;
import com.walletradar.ingestion.pipeline.clarification.PendingReceiptClarificationQueryService;
import com.walletradar.ingestion.pipeline.clarification.RawTransactionClarificationEnricher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Shared full-receipt clarification workflow for batch loading and per-row execution.
 */
@Component
final class ReceiptClarificationWorkflowHandler {

    private static final Logger log = LoggerFactory.getLogger(ReceiptClarificationWorkflowHandler.class);

    private final PendingReceiptClarificationQueryService pendingReceiptClarificationQueryService;
    private final OnChainClarificationProperties properties;
    private final RawTransactionClarificationEnricher rawTransactionClarificationEnricher;
    private final OnChainClassifier onChainClassifier;
    private final ClarificationFailureHandler clarificationFailureHandler;
    private final ClarificationReclassificationHandler clarificationReclassificationHandler;
    private final ClarificationPreparationHandler clarificationPreparationHandler;

    ReceiptClarificationWorkflowHandler(
            PendingReceiptClarificationQueryService pendingReceiptClarificationQueryService,
            OnChainClarificationProperties properties,
            RawTransactionClarificationEnricher rawTransactionClarificationEnricher,
            OnChainClassifier onChainClassifier,
            ClarificationFailureHandler clarificationFailureHandler,
            ClarificationReclassificationHandler clarificationReclassificationHandler,
            ClarificationPreparationHandler clarificationPreparationHandler
    ) {
        this.pendingReceiptClarificationQueryService = pendingReceiptClarificationQueryService;
        this.properties = properties;
        this.rawTransactionClarificationEnricher = rawTransactionClarificationEnricher;
        this.onChainClassifier = onChainClassifier;
        this.clarificationFailureHandler = clarificationFailureHandler;
        this.clarificationReclassificationHandler = clarificationReclassificationHandler;
        this.clarificationPreparationHandler = clarificationPreparationHandler;
    }

    int processNextBatch() {
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

    boolean clarify(NormalizedTransaction normalizedTransaction) {
        Instant now = Instant.now();
        Optional<RawTransaction> rawTransactionOptional = clarificationPreparationHandler.loadRawOrMarkReceiptFailure(
                normalizedTransaction,
                now
        );
        if (rawTransactionOptional.isEmpty()) {
            return false;
        }
        RawTransaction rawTransaction = rawTransactionOptional.get();
        if (!clarificationPreparationHandler.isReceiptClarificationEligible(normalizedTransaction, rawTransaction)) {
            return false;
        }

        try {
            Optional<ClarificationReceiptEnrichment> enrichment = clarificationPreparationHandler.fetchFullReceiptOrMarkFailure(
                    normalizedTransaction,
                    rawTransaction,
                    now
            );
            if (enrichment.isEmpty()) {
                return false;
            }

            rawTransactionClarificationEnricher.merge(rawTransaction, enrichment.get());
            clarificationFailureHandler.recordReceiptAttemptSuccess(normalizedTransaction, rawTransaction);

            OnChainClassificationResult classificationResult = onChainClassifier.classify(rawTransaction);
            clarificationReclassificationHandler.persistReceiptClarification(
                    normalizedTransaction,
                    rawTransaction,
                    classificationResult,
                    now
            );
            return true;
        } catch (RuntimeException ex) {
            log.warn("On-chain full-receipt clarification failed for normalizedTxId={}: {}", normalizedTransaction.getId(), ex.getMessage());
            clarificationFailureHandler.markReceiptFailure(
                    normalizedTransaction,
                    rawTransaction,
                    ex.getClass().getSimpleName(),
                    now
            );
            return false;
        }
    }
}
