package com.walletradar.ingestion.store;

import com.walletradar.domain.NormalizedTransaction;
import com.walletradar.domain.NormalizedTransactionRepository;
import com.walletradar.domain.NormalizedTransactionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.EnumSet;

/**
 * Idempotent upsert for normalized transactions keyed by (txHash, networkId, walletAddress) or clientId.
 */
@Service
@RequiredArgsConstructor
public class IdempotentNormalizedTransactionStore {

    private static final EnumSet<NormalizedTransactionStatus> IMMUTABLE_STATUSES = EnumSet.of(
            NormalizedTransactionStatus.PENDING_STAT,
            NormalizedTransactionStatus.CONFIRMED,
            NormalizedTransactionStatus.NEEDS_REVIEW
    );

    private final NormalizedTransactionRepository repository;

    public NormalizedTransaction upsert(NormalizedTransaction tx) {
        if (tx.getTxHash() != null && tx.getNetworkId() != null && tx.getWalletAddress() != null) {
            return repository.findByTxHashAndNetworkIdAndWalletAddress(
                            tx.getTxHash(), tx.getNetworkId(), tx.getWalletAddress())
                    .map(existing -> mergeInto(existing, tx))
                    .map(repository::save)
                    .orElseGet(() -> repository.save(tx));
        }
        if (tx.getClientId() != null) {
            return repository.findByClientId(tx.getClientId())
                    .orElseGet(() -> repository.save(tx));
        }
        return repository.save(tx);
    }

    private static NormalizedTransaction mergeInto(NormalizedTransaction target, NormalizedTransaction source) {
        if (target.getStatus() != null && IMMUTABLE_STATUSES.contains(target.getStatus())) {
            target.setUpdatedAt(Instant.now());
            return target;
        }
        target.setBlockTimestamp(source.getBlockTimestamp());
        target.setType(source.getType());
        target.setLegs(source.getLegs());
        target.setMissingDataReasons(source.getMissingDataReasons());
        target.setConfidence(source.getConfidence());
        target.setClientId(source.getClientId());
        target.setClarificationAttempts(source.getClarificationAttempts());
        target.setPricingAttempts(source.getPricingAttempts());
        target.setStatAttempts(source.getStatAttempts());
        target.setStatus(maxStatus(target.getStatus(), source.getStatus()));
        target.setUpdatedAt(Instant.now());
        if (target.getCreatedAt() == null) {
            target.setCreatedAt(source.getCreatedAt() != null ? source.getCreatedAt() : Instant.now());
        }
        return target;
    }

    private static NormalizedTransactionStatus maxStatus(
            NormalizedTransactionStatus current, NormalizedTransactionStatus incoming) {
        if (current == null) return incoming;
        if (incoming == null) return current;
        if (rank(incoming) > rank(current)) return incoming;
        return current;
    }

    private static int rank(NormalizedTransactionStatus status) {
        return switch (status) {
            case PENDING_CLARIFICATION -> 1;
            case PENDING_PRICE -> 2;
            case PENDING_STAT -> 3;
            case CONFIRMED -> 4;
            case NEEDS_REVIEW -> 5;
        };
    }
}
