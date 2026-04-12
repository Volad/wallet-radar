package com.walletradar.costbasis.application;

import com.walletradar.costbasis.application.replay.query.ConfirmedReplayQueryService;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConfirmedReplayQueryServiceTest {

    @Mock
    private NormalizedTransactionRepository normalizedTransactionRepository;

    @Test
    void keepsRepositoryOrderWhenRowsAreAlreadyDeterministic() {
        List<NormalizedTransaction> ordered = List.of(
                transaction("a", Instant.parse("2026-01-01T00:00:00Z"), 0),
                transaction("b", Instant.parse("2026-01-01T00:00:01Z"), 1)
        );
        when(normalizedTransactionRepository.findAllByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(ordered);

        List<NormalizedTransaction> result = service().loadOrderedConfirmed();

        assertThat(result).isSameAs(ordered);
    }

    @Test
    void sortsOnlyWhenRepositoryResultDriftsOutOfReplayOrder() {
        List<NormalizedTransaction> unordered = List.of(
                transaction("b", Instant.parse("2026-01-01T00:00:00Z"), 0),
                transaction("a", Instant.parse("2026-01-01T00:00:00Z"), 0)
        );
        when(normalizedTransactionRepository.findAllByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(unordered);

        List<NormalizedTransaction> result = service().loadOrderedConfirmed();

        assertThat(result).extracting(NormalizedTransaction::getId).containsExactly("a", "b");
    }

    private ConfirmedReplayQueryService service() {
        return new ConfirmedReplayQueryService(normalizedTransactionRepository);
    }

    private NormalizedTransaction transaction(String id, Instant timestamp, Integer transactionIndex) {
        NormalizedTransaction transaction = new NormalizedTransaction();
        transaction.setId(id);
        transaction.setBlockTimestamp(timestamp);
        transaction.setTransactionIndex(transactionIndex);
        return transaction;
    }
}
