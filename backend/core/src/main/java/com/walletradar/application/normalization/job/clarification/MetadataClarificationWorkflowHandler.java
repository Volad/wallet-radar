package com.walletradar.application.normalization.job.clarification;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.application.normalization.config.OnChainClarificationProperties;
import com.walletradar.application.linking.pipeline.clarification.ClarificationReceiptEnrichment;
import com.walletradar.application.linking.pipeline.clarification.PendingClarificationQueryService;
import com.walletradar.application.linking.pipeline.clarification.PendingReceiptClarificationQueryService;
import com.walletradar.application.linking.pipeline.clarification.RawTransactionClarificationEnricher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Shared metadata clarification workflow for batch loading and per-row execution.
 */
@Component
final class MetadataClarificationWorkflowHandler {

    private static final Logger log = LoggerFactory.getLogger(MetadataClarificationWorkflowHandler.class);

    private final PendingClarificationQueryService pendingClarificationQueryService;
    private final PendingReceiptClarificationQueryService pendingReceiptClarificationQueryService;
    private final OnChainClarificationProperties properties;
    private final RawTransactionClarificationEnricher rawTransactionClarificationEnricher;
    private final ClarificationFailureHandler clarificationFailureHandler;
    private final ClarificationReclassificationMarker clarificationReclassificationMarker;
    private final ClarificationPreparationHandler clarificationPreparationHandler;

    MetadataClarificationWorkflowHandler(
            PendingClarificationQueryService pendingClarificationQueryService,
            PendingReceiptClarificationQueryService pendingReceiptClarificationQueryService,
            OnChainClarificationProperties properties,
            RawTransactionClarificationEnricher rawTransactionClarificationEnricher,
            ClarificationFailureHandler clarificationFailureHandler,
            ClarificationReclassificationMarker clarificationReclassificationMarker,
            ClarificationPreparationHandler clarificationPreparationHandler
    ) {
        this.pendingClarificationQueryService = pendingClarificationQueryService;
        this.pendingReceiptClarificationQueryService = pendingReceiptClarificationQueryService;
        this.properties = properties;
        this.rawTransactionClarificationEnricher = rawTransactionClarificationEnricher;
        this.clarificationFailureHandler = clarificationFailureHandler;
        this.clarificationReclassificationMarker = clarificationReclassificationMarker;
        this.clarificationPreparationHandler = clarificationPreparationHandler;
    }

    int processNextBatch() {
        List<NormalizedTransaction> batch = new ArrayList<>(pendingClarificationQueryService.claimNextBatch(
                properties.getBatchSize(),
                properties.getMaxAttempts(),
                properties.getRetryDelaySeconds(),
                "metadata-" + java.util.UUID.randomUUID(),
                properties.getLeaseSeconds()
        ));
        if (properties.getFullReceipt().isEnabled()) {
            batch.addAll(pendingReceiptClarificationQueryService.claimActiveNeedsReviewBatch(
                    properties.getFullReceipt().getBatchSize(),
                    properties.getFullReceipt().getMaxAttempts(),
                    properties.getFullReceipt().getRetryDelaySeconds(),
                    "receipt-" + java.util.UUID.randomUUID(),
                    properties.getLeaseSeconds()
            ));
            batch.addAll(pendingReceiptClarificationQueryService.claimConfirmedFluidReceiptBatch(
                    properties.getFullReceipt().getBatchSize(),
                    properties.getFullReceipt().getMaxAttempts(),
                    properties.getFullReceipt().getRetryDelaySeconds(),
                    "receipt-" + java.util.UUID.randomUUID(),
                    properties.getLeaseSeconds()
            ));
            batch.addAll(pendingReceiptClarificationQueryService.claimMulticallMissingTransferBatch(
                    properties.getFullReceipt().getBatchSize(),
                    properties.getFullReceipt().getMaxAttempts(),
                    properties.getFullReceipt().getRetryDelaySeconds(),
                    "receipt-" + java.util.UUID.randomUUID(),
                    properties.getLeaseSeconds()
            ));
        }

        return clarifyBatch(batch);
    }

    int processConfirmedFluidReceiptBatch() {
        List<NormalizedTransaction> batch = pendingReceiptClarificationQueryService.claimConfirmedFluidReceiptBatch(
                properties.getFullReceipt().getBatchSize(),
                properties.getFullReceipt().getMaxAttempts(),
                properties.getFullReceipt().getRetryDelaySeconds(),
                "receipt-" + java.util.UUID.randomUUID(),
                properties.getLeaseSeconds()
        );
        return clarifyBatch(batch);
    }

    private int clarifyBatch(List<NormalizedTransaction> batch) {
        if (batch.isEmpty()) {
            return 0;
        }
        int lanes = Math.max(1, properties.getThreads());
        if (lanes == 1 || batch.size() == 1) {
            int completed = 0;
            for (NormalizedTransaction normalizedTransaction : batch) {
                if (clarify(normalizedTransaction)) {
                    completed++;
                }
            }
            return completed;
        }

        ExecutorService executor = Executors.newFixedThreadPool(Math.min(lanes, batch.size()));
        try {
            List<Callable<Boolean>> tasks = new ArrayList<>(batch.size());
            for (NormalizedTransaction normalizedTransaction : batch) {
                tasks.add(() -> clarify(normalizedTransaction));
            }

            int completed = 0;
            for (Future<Boolean> future : executor.invokeAll(tasks)) {
                if (Boolean.TRUE.equals(future.get())) {
                    completed++;
                }
            }
            return completed;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Clarification worker interrupted", error);
        } catch (ExecutionException error) {
            throw new IllegalStateException("Clarification worker failed", error.getCause());
        } finally {
            executor.shutdownNow();
        }
    }

    boolean clarify(NormalizedTransaction normalizedTransaction) {
        Instant now = Instant.now();
        boolean reviewTail = normalizedTransaction.getStatus() == NormalizedTransactionStatus.NEEDS_REVIEW;
        Optional<RawTransaction> rawTransactionOptional = reviewTail
                ? clarificationPreparationHandler.loadRawOrMarkReceiptFailure(normalizedTransaction, now)
                : clarificationPreparationHandler.loadRawOrMarkMetadataFailure(
                        normalizedTransaction,
                        now,
                        properties.getMaxAttempts()
                );
        if (rawTransactionOptional.isEmpty()) {
            return false;
        }

        RawTransaction rawTransaction = rawTransactionOptional.get();
        try {
            Optional<ClarificationReceiptEnrichment> enrichment = reviewTail
                    ? clarificationPreparationHandler.fetchFullReceiptForReviewOrMarkFailure(
                            normalizedTransaction,
                            rawTransaction,
                            now,
                            properties.getFullReceipt().getMaxAttempts()
                    )
                    : clarificationPreparationHandler.fetchFullReceiptForClarificationOrMarkFailure(
                    normalizedTransaction,
                    rawTransaction,
                    now,
                    properties.getMaxAttempts()
            );
            if (enrichment.isEmpty()) {
                return false;
            }

            rawTransactionClarificationEnricher.merge(rawTransaction, enrichment.get());
            if (reviewTail) {
                clarificationFailureHandler.recordReceiptAttemptSuccess(normalizedTransaction, rawTransaction);
            } else {
                clarificationFailureHandler.recordMetadataAttemptSuccess(normalizedTransaction, rawTransaction);
            }
            clarificationReclassificationMarker.markPendingReclassification(
                    normalizedTransaction,
                    rawTransaction,
                    now
            );
            return true;
        } catch (RuntimeException ex) {
            log.warn("On-chain clarification failed for normalizedTxId={}: {}", normalizedTransaction.getId(), ex.getMessage());
            if (reviewTail) {
                clarificationFailureHandler.markReceiptFailure(
                        normalizedTransaction,
                        rawTransaction,
                        ex.getClass().getSimpleName(),
                        now,
                        properties.getFullReceipt().getMaxAttempts()
                );
            } else {
                clarificationFailureHandler.markMetadataFailure(
                        normalizedTransaction,
                        rawTransaction,
                        ex.getClass().getSimpleName(),
                        now,
                        properties.getMaxAttempts()
                );
            }
            return false;
        }
    }
}
