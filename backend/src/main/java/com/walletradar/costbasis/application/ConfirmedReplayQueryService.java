package com.walletradar.costbasis.application;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * Loads confirmed canonical rows in deterministic replay order.
 */
@Service
@RequiredArgsConstructor
public class ConfirmedReplayQueryService {

    private static final Comparator<NormalizedTransaction> REPLAY_ORDER = Comparator
            .comparing(NormalizedTransaction::getBlockTimestamp, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(NormalizedTransaction::getTransactionIndex, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(NormalizedTransaction::getId, Comparator.nullsLast(Comparator.naturalOrder()));

    private final NormalizedTransactionRepository normalizedTransactionRepository;

    public List<NormalizedTransaction> loadOrderedConfirmed() {
        return normalizedTransactionRepository.findAllByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        ).stream().sorted(REPLAY_ORDER).toList();
    }
}
