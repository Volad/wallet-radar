package com.walletradar.ingestion.job.clarification;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.domain.transaction.raw.RawTransactionRepository;
import com.walletradar.ingestion.pipeline.classification.reason.ClassificationReasonCode;
import com.walletradar.ingestion.pipeline.classification.reason.ClarificationPolicyService;
import com.walletradar.ingestion.pipeline.clarification.ClarificationReceiptEnrichment;
import com.walletradar.ingestion.pipeline.clarification.ReceiptClarificationGateway;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

/**
 * Shared raw lookup, eligibility, and receipt-fetch preparation for clarification flows.
 */
@Component
final class ClarificationPreparationHandler {

    private final ReceiptClarificationGateway clarificationGateway;
    private final RawTransactionRepository rawTransactionRepository;
    private final ClarificationFailureHandler clarificationFailureHandler;
    private final ClarificationPolicyService clarificationPolicyService;

    ClarificationPreparationHandler(
            ReceiptClarificationGateway clarificationGateway,
            RawTransactionRepository rawTransactionRepository,
            ClarificationFailureHandler clarificationFailureHandler,
            ClarificationPolicyService clarificationPolicyService
    ) {
        this.clarificationGateway = clarificationGateway;
        this.rawTransactionRepository = rawTransactionRepository;
        this.clarificationFailureHandler = clarificationFailureHandler;
        this.clarificationPolicyService = clarificationPolicyService;
    }

    Optional<RawTransaction> loadRawOrMarkMetadataFailure(
            NormalizedTransaction normalizedTransaction,
            Instant now,
            int maxAttempts
    ) {
        Optional<RawTransaction> rawTransactionOptional = rawTransactionRepository.findById(normalizedTransaction.getId());
        if (rawTransactionOptional.isEmpty()) {
            clarificationFailureHandler.markMetadataFailure(
                    normalizedTransaction,
                    null,
                    ClassificationReasonCode.RAW_TRANSACTION_MISSING.code(),
                    now,
                    maxAttempts
            );
        }
        return rawTransactionOptional;
    }

    Optional<RawTransaction> loadRawOrMarkReceiptFailure(
            NormalizedTransaction normalizedTransaction,
            Instant now
    ) {
        Optional<RawTransaction> rawTransactionOptional = rawTransactionRepository.findById(normalizedTransaction.getId());
        if (rawTransactionOptional.isEmpty()) {
            clarificationFailureHandler.markReceiptFailure(
                    normalizedTransaction,
                    null,
                    ClassificationReasonCode.RAW_TRANSACTION_MISSING.code(),
                    now
            );
        }
        return rawTransactionOptional;
    }

    boolean isReceiptClarificationEligible(
            NormalizedTransaction normalizedTransaction,
            RawTransaction rawTransaction
    ) {
        return clarificationPolicyService.isReceiptClarificationEligible(
                normalizedTransaction,
                OnChainRawTransactionView.wrap(rawTransaction)
        );
    }

    Optional<ClarificationReceiptEnrichment> fetchMetadataReceiptOrMarkFailure(
            NormalizedTransaction normalizedTransaction,
            RawTransaction rawTransaction,
            Instant now,
            int maxAttempts
    ) {
        Optional<ClarificationReceiptEnrichment> enrichment = clarificationGateway.fetchReceipt(rawTransaction);
        if (enrichment.isEmpty()) {
            clarificationFailureHandler.markMetadataFailure(
                    normalizedTransaction,
                    rawTransaction,
                    ClassificationReasonCode.CLARIFICATION_RECEIPT_UNAVAILABLE.code(),
                    now,
                    maxAttempts
            );
        }
        return enrichment;
    }

    Optional<ClarificationReceiptEnrichment> fetchFullReceiptOrMarkFailure(
            NormalizedTransaction normalizedTransaction,
            RawTransaction rawTransaction,
            Instant now
    ) {
        Optional<ClarificationReceiptEnrichment> enrichment = clarificationGateway.fetchReceiptWithTransferEvidence(rawTransaction);
        if (enrichment.isEmpty()) {
            clarificationFailureHandler.markReceiptFailure(
                    normalizedTransaction,
                    rawTransaction,
                    ClassificationReasonCode.CLARIFICATION_FULL_RECEIPT_UNAVAILABLE.code(),
                    now
            );
        }
        return enrichment;
    }
}
