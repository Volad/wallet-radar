package com.walletradar.ingestion.job.clarification;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.domain.transaction.raw.RawTransactionRepository;
import com.walletradar.ingestion.pipeline.classification.reason.ClarificationDecision;
import com.walletradar.ingestion.pipeline.classification.reason.ClarificationPolicyService;
import com.walletradar.ingestion.pipeline.clarification.RawTransactionClarificationEnricher;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Shared failure/attempt handling for metadata and full-receipt clarification flows.
 */
@Component
final class ClarificationFailureHandler {

    private final RawTransactionClarificationEnricher rawTransactionClarificationEnricher;
    private final RawTransactionRepository rawTransactionRepository;
    private final NormalizedTransactionRepository normalizedTransactionRepository;
    private final ClarificationPolicyService clarificationPolicyService;

    ClarificationFailureHandler(
            RawTransactionClarificationEnricher rawTransactionClarificationEnricher,
            RawTransactionRepository rawTransactionRepository,
            NormalizedTransactionRepository normalizedTransactionRepository,
            ClarificationPolicyService clarificationPolicyService
    ) {
        this.rawTransactionClarificationEnricher = rawTransactionClarificationEnricher;
        this.rawTransactionRepository = rawTransactionRepository;
        this.normalizedTransactionRepository = normalizedTransactionRepository;
        this.clarificationPolicyService = clarificationPolicyService;
    }

    int recordMetadataAttemptSuccess(
            NormalizedTransaction normalizedTransaction,
            RawTransaction rawTransaction
    ) {
        int nextAttempts = rawTransactionClarificationEnricher.recordMetadataAttempt(
                rawTransaction,
                safeAttempts(normalizedTransaction.getClarificationAttempts()),
                null
        );
        rawTransactionRepository.save(rawTransaction);
        return nextAttempts;
    }

    int recordReceiptAttemptSuccess(
            NormalizedTransaction normalizedTransaction,
            RawTransaction rawTransaction
    ) {
        int nextAttempts = rawTransactionClarificationEnricher.recordFullReceiptAttempt(
                rawTransaction,
                safeAttempts(normalizedTransaction.getFullReceiptClarificationAttempts()),
                null
        );
        rawTransactionRepository.save(rawTransaction);
        return nextAttempts;
    }

    void markMetadataFailure(
            NormalizedTransaction normalizedTransaction,
            RawTransaction rawTransaction,
            String reason,
            Instant now,
            int maxAttempts
    ) {
        markMetadataFailure(normalizedTransaction, rawTransaction, reason, now, null, true, maxAttempts);
    }

    void markMetadataFailure(
            NormalizedTransaction normalizedTransaction,
            RawTransaction rawTransaction,
            String reason,
            Instant now,
            Integer existingAttempts,
            boolean incrementRawAttempt,
            int maxAttempts
    ) {
        int nextAttempts = existingAttempts == null
                ? safeAttempts(normalizedTransaction.getClarificationAttempts()) + 1
                : Math.max(0, existingAttempts);
        if (rawTransaction != null && incrementRawAttempt) {
            nextAttempts = rawTransactionClarificationEnricher.recordMetadataAttempt(
                    rawTransaction,
                    safeAttempts(normalizedTransaction.getClarificationAttempts()),
                    reason
            );
            rawTransactionRepository.save(rawTransaction);
        }
        normalizedTransaction.setClarificationAttempts(nextAttempts);
        normalizedTransaction.setUpdatedAt(now);
        ClarificationDecision decision = clarificationPolicyService.nextFailureDecision(
                normalizedTransaction,
                rawTransaction,
                reason,
                nextAttempts,
                maxAttempts
        );
        normalizedTransaction.setStatus(decision.status());
        normalizedTransaction.setMissingDataReasons(decision.missingDataReasons());
        normalizedTransaction.setClarificationLeaseUntil(null);
        normalizedTransaction.setClarificationWorkerId(null);
        normalizedTransactionRepository.save(normalizedTransaction);
    }

    void markReceiptFailure(
            NormalizedTransaction normalizedTransaction,
            RawTransaction rawTransaction,
            String reason,
            Instant now
    ) {
        markReceiptFailure(normalizedTransaction, rawTransaction, reason, now, 1);
    }

    void markReceiptFailure(
            NormalizedTransaction normalizedTransaction,
            RawTransaction rawTransaction,
            String reason,
            Instant now,
            int maxAttempts
    ) {
        int nextAttempts = safeAttempts(normalizedTransaction.getFullReceiptClarificationAttempts()) + 1;
        if (rawTransaction != null) {
            nextAttempts = rawTransactionClarificationEnricher.recordFullReceiptAttempt(
                    rawTransaction,
                    safeAttempts(normalizedTransaction.getFullReceiptClarificationAttempts()),
                    reason
            );
            rawTransactionRepository.save(rawTransaction);
        }
        normalizedTransaction.setFullReceiptClarificationAttempts(nextAttempts);
        normalizedTransaction.setUpdatedAt(now);
        ClarificationDecision decision = clarificationPolicyService.nextReceiptFailureDecision(
                normalizedTransaction,
                rawTransaction,
                reason,
                nextAttempts,
                maxAttempts
        );
        normalizedTransaction.setStatus(decision.status());
        normalizedTransaction.setMissingDataReasons(decision.missingDataReasons());
        normalizedTransaction.setClarificationLeaseUntil(null);
        normalizedTransaction.setClarificationWorkerId(null);
        normalizedTransactionRepository.save(normalizedTransaction);
    }

    private int safeAttempts(Integer attempts) {
        return attempts == null ? 0 : Math.max(0, attempts);
    }
}
