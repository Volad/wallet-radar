package com.walletradar.ingestion.store;

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
            existing.setUpdatedAt(now);
            return existing;
        }
        candidate.setId(existing.getId());
        candidate.setCreatedAt(existing.getCreatedAt() != null ? existing.getCreatedAt() : candidate.getCreatedAt());
        if (candidate.getConfirmedAt() == null) {
            candidate.setConfirmedAt(existing.getConfirmedAt());
        }
        candidate.setUpdatedAt(now);
        return candidate;
    }
}
