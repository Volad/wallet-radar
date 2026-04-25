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
            normalizedTransaction.setClarificationAttempts(view.clarificationAttemptCount());
            normalizedTransaction.setFullReceiptClarificationAttempts(view.fullReceiptClarificationAttemptCount());
        }
        normalizedTransaction.setStatus(NormalizedTransactionStatus.PENDING_RECLASSIFICATION);
        normalizedTransaction.setClarificationLeaseUntil(null);
        normalizedTransaction.setClarificationWorkerId(null);
        normalizedTransaction.setUpdatedAt(now);
        return normalizedTransactionRepository.save(normalizedTransaction);
    }
}
