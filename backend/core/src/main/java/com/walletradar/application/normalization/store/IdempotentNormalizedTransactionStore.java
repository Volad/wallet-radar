package com.walletradar.application.normalization.store;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Idempotent write path for canonical normalized transactions.
 */
@Service
@RequiredArgsConstructor
public class IdempotentNormalizedTransactionStore {

    private final NormalizedTransactionRepository repository;

    public NormalizedTransaction upsert(NormalizedTransaction candidate) {
        Instant now = Instant.now();
        return repository.findById(candidate.getId())
                .map(existing -> merge(existing, candidate, now))
                .map(repository::save)
                .orElseGet(() -> repository.save(candidate));
    }

    private NormalizedTransaction merge(
            NormalizedTransaction existing,
            NormalizedTransaction candidate,
            Instant now
    ) {
        if (existing.getStatus() == NormalizedTransactionStatus.CONFIRMED) {
            existing.setClarificationAttempts(normalizedCounter(candidate.getClarificationAttempts()));
            existing.setFullReceiptClarificationAttempts(normalizedCounter(candidate.getFullReceiptClarificationAttempts()));
            existing.setUpdatedAt(now);
            // Propagate per-flow buy-side fee signal (ADR-051): this field is newly computed at
            // normalization and must be refreshed even on already-CONFIRMED transactions so that
            // replay picks up the correct acquisitionFeeUsd after a rebuild. The field is additive
            // and does not affect pricing, stat-validation, or clarification counters.
            propagateAcquisitionFeeUsd(existing, candidate);
            return existing;
        }
        candidate.setId(existing.getId());
        candidate.setCreatedAt(existing.getCreatedAt() != null ? existing.getCreatedAt() : candidate.getCreatedAt());
        candidate.setClarificationAttempts(normalizedCounter(candidate.getClarificationAttempts()));
        candidate.setFullReceiptClarificationAttempts(normalizedCounter(candidate.getFullReceiptClarificationAttempts()));
        if (candidate.getConfirmedAt() == null) {
            candidate.setConfirmedAt(existing.getConfirmedAt());
        }
        candidate.setUpdatedAt(now);
        return candidate;
    }

    /**
     * ADR-051: merges {@code acquisitionFeeUsd} from candidate flows into existing flows by
     * matching on {@code role} + {@code assetSymbol}. Leaves existing flows unmodified when the
     * candidate does not supply a fee value for that leg.
     */
    private void propagateAcquisitionFeeUsd(
            NormalizedTransaction existing,
            NormalizedTransaction candidate
    ) {
        if (existing.getFlows() == null || candidate.getFlows() == null) {
            return;
        }
        for (NormalizedTransaction.Flow candidateFlow : candidate.getFlows()) {
            if (candidateFlow == null || candidateFlow.getAcquisitionFeeUsd() == null) {
                continue;
            }
            for (NormalizedTransaction.Flow existingFlow : existing.getFlows()) {
                if (existingFlow == null) {
                    continue;
                }
                if (existingFlow.getRole() == candidateFlow.getRole()
                        && java.util.Objects.equals(existingFlow.getAssetSymbol(), candidateFlow.getAssetSymbol())) {
                    existingFlow.setAcquisitionFeeUsd(candidateFlow.getAcquisitionFeeUsd());
                    break;
                }
            }
        }
    }

    private int normalizedCounter(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }
}
