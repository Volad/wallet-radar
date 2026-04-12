package com.walletradar.costbasis.application.replay.query;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ConfirmedReplayQueryService {

    private static final Comparator<NormalizedTransaction> REPLAY_ORDER = Comparator
            .comparing(NormalizedTransaction::getBlockTimestamp, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(NormalizedTransaction::getTransactionIndex, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(NormalizedTransaction::getId, Comparator.nullsLast(Comparator.naturalOrder()));

    private final NormalizedTransactionRepository normalizedTransactionRepository;

    public List<NormalizedTransaction> loadOrderedConfirmed() {
        return ensureReplayOrder(normalizedTransactionRepository.findAllByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        ));
    }

    public List<NormalizedTransaction> loadOrderedConfirmed(Collection<String> walletAddresses) {
        if (walletAddresses == null || walletAddresses.isEmpty()) {
            return List.of();
        }
        return ensureReplayOrder(normalizedTransactionRepository.findAllByWalletAddressInAndStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                walletAddresses,
                NormalizedTransactionStatus.CONFIRMED
        ));
    }

    private List<NormalizedTransaction> ensureReplayOrder(List<NormalizedTransaction> transactions) {
        if (transactions == null || transactions.size() < 2) {
            return transactions == null ? List.of() : transactions;
        }
        for (int index = 1; index < transactions.size(); index++) {
            if (REPLAY_ORDER.compare(transactions.get(index - 1), transactions.get(index)) > 0) {
                return transactions.stream().sorted(REPLAY_ORDER).toList();
            }
        }
        return transactions;
    }
}
