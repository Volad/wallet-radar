package com.walletradar.ingestion.job.clarification;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Marks clarified rows for the dedicated classifier pass.
 */
@Component
@RequiredArgsConstructor
final class ClarificationReclassificationMarker {

    private final NormalizedTransactionRepository normalizedTransactionRepository;

    NormalizedTransaction markPendingReclassification(
            NormalizedTransaction normalizedTransaction,
            RawTransaction rawTransaction,
            Instant now
    ) {
        OnChainRawTransactionView view = rawTransaction == null ? null : OnChainRawTransactionView.wrap(rawTransaction);
        if (view != null) {
            int clarificationAttempts = view.clarificationAttemptCount();
            if (clarificationAttempts > 0 || normalizedTransaction.getClarificationAttempts() == null) {
                normalizedTransaction.setClarificationAttempts(Math.max(
                        clarificationAttempts,
                        safeAttempts(normalizedTransaction.getClarificationAttempts())
                ));
            }
            int fullReceiptClarificationAttempts = view.fullReceiptClarificationAttemptCount();
            if (fullReceiptClarificationAttempts > 0 || normalizedTransaction.getFullReceiptClarificationAttempts() == null) {
                normalizedTransaction.setFullReceiptClarificationAttempts(Math.max(
                        fullReceiptClarificationAttempts,
                        safeAttempts(normalizedTransaction.getFullReceiptClarificationAttempts())
                ));
            }
        }
        normalizedTransaction.setStatus(NormalizedTransactionStatus.PENDING_RECLASSIFICATION);
        normalizedTransaction.setClarificationLeaseUntil(null);
        normalizedTransaction.setClarificationWorkerId(null);
        normalizedTransaction.setUpdatedAt(now);
        return normalizedTransactionRepository.save(normalizedTransaction);
    }

    private int safeAttempts(Integer attempts) {
        return attempts == null ? 0 : Math.max(0, attempts);
    }
}
