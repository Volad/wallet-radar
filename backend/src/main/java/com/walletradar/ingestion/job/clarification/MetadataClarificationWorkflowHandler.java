package com.walletradar.ingestion.job.clarification;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.ingestion.config.OnChainClarificationProperties;
import com.walletradar.ingestion.pipeline.classification.OnChainClassificationResult;
import com.walletradar.ingestion.pipeline.classification.OnChainClassifier;
import com.walletradar.ingestion.pipeline.classification.reason.ClassificationReasonCode;
import com.walletradar.ingestion.pipeline.clarification.ClarificationReceiptEnrichment;
import com.walletradar.ingestion.pipeline.clarification.PendingClarificationQueryService;
import com.walletradar.ingestion.pipeline.clarification.RawTransactionClarificationEnricher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Shared metadata clarification workflow for batch loading and per-row execution.
 */
@Component
final class MetadataClarificationWorkflowHandler {

    private static final Logger log = LoggerFactory.getLogger(MetadataClarificationWorkflowHandler.class);

    private final PendingClarificationQueryService pendingClarificationQueryService;
    private final OnChainClarificationProperties properties;
    private final RawTransactionClarificationEnricher rawTransactionClarificationEnricher;
    private final OnChainClassifier onChainClassifier;
    private final ClarificationFailureHandler clarificationFailureHandler;
    private final ClarificationReclassificationHandler clarificationReclassificationHandler;
    private final ClarificationPreparationHandler clarificationPreparationHandler;

    MetadataClarificationWorkflowHandler(
            PendingClarificationQueryService pendingClarificationQueryService,
            OnChainClarificationProperties properties,
            RawTransactionClarificationEnricher rawTransactionClarificationEnricher,
            OnChainClassifier onChainClassifier,
            ClarificationFailureHandler clarificationFailureHandler,
            ClarificationReclassificationHandler clarificationReclassificationHandler,
            ClarificationPreparationHandler clarificationPreparationHandler
    ) {
        this.pendingClarificationQueryService = pendingClarificationQueryService;
        this.properties = properties;
        this.rawTransactionClarificationEnricher = rawTransactionClarificationEnricher;
        this.onChainClassifier = onChainClassifier;
        this.clarificationFailureHandler = clarificationFailureHandler;
        this.clarificationReclassificationHandler = clarificationReclassificationHandler;
        this.clarificationPreparationHandler = clarificationPreparationHandler;
    }

    int processNextBatch() {
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

    boolean clarify(NormalizedTransaction normalizedTransaction) {
        Instant now = Instant.now();
        Optional<RawTransaction> rawTransactionOptional = clarificationPreparationHandler.loadRawOrMarkMetadataFailure(
                normalizedTransaction,
                now,
                properties.getMaxAttempts()
        );
        if (rawTransactionOptional.isEmpty()) {
            return false;
        }

        RawTransaction rawTransaction = rawTransactionOptional.get();
        try {
            OnChainClassificationResult currentClassification = onChainClassifier.classify(rawTransaction);
            if (currentClassification.status() != NormalizedTransactionStatus.PENDING_CLARIFICATION) {
                clarificationReclassificationHandler.persistReclassification(
                        normalizedTransaction,
                        rawTransaction,
                        currentClassification,
                        now
                );
                return true;
            }

            Optional<ClarificationReceiptEnrichment> enrichment = clarificationPreparationHandler.fetchMetadataReceiptOrMarkFailure(
                    normalizedTransaction,
                    rawTransaction,
                    now,
                    properties.getMaxAttempts()
            );
            if (enrichment.isEmpty()) {
                return false;
            }

            rawTransactionClarificationEnricher.merge(rawTransaction, enrichment.get());
            int currentAttempts = clarificationFailureHandler.recordMetadataAttemptSuccess(normalizedTransaction, rawTransaction);

            OnChainClassificationResult classificationResult = onChainClassifier.classify(rawTransaction);
            if (classificationResult.status() == NormalizedTransactionStatus.PENDING_CLARIFICATION) {
                clarificationFailureHandler.markMetadataFailure(
                        normalizedTransaction,
                        rawTransaction,
                        ClassificationReasonCode.CLARIFICATION_INSUFFICIENT_EVIDENCE.code(),
                        now,
                        currentAttempts,
                        false,
                        properties.getMaxAttempts()
                );
                return false;
            }
            clarificationReclassificationHandler.persistMetadataClarification(
                    normalizedTransaction,
                    rawTransaction,
                    classificationResult,
                    now
            );
            return true;
        } catch (RuntimeException ex) {
            log.warn("On-chain clarification failed for normalizedTxId={}: {}", normalizedTransaction.getId(), ex.getMessage());
            clarificationFailureHandler.markMetadataFailure(
                    normalizedTransaction,
                    rawTransaction,
                    ex.getClass().getSimpleName(),
                    now,
                    properties.getMaxAttempts()
            );
            return false;
        }
    }
}
