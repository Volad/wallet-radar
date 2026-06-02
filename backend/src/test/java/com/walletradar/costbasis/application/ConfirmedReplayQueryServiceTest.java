package com.walletradar.costbasis.application;

import com.walletradar.costbasis.application.replay.query.ConfirmedReplayQueryService;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
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
        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
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
        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(unordered);

        List<NormalizedTransaction> result = service().loadOrderedConfirmed();

        assertThat(result).extracting(NormalizedTransaction::getId).containsExactly("a", "b");
    }

    @Test
    void onChainBybitCorridorInternalTransferSortsOutboundBeforeInboundAtSameTimestamp() {
        // B-2: on-chain BYBIT-CORRIDOR: INTERNAL_TRANSFER corridors must sort CARRY_OUT before
        // CARRY_IN when both share the same blockTimestamp and transactionIndex. Without the fix,
        // lexicographic ID order puts "a" (inbound) before "b" (outbound). With the fix,
        // corridorContinuityFlowSign returns -1 for outbound and +1 for inbound.
        Instant sameTimestamp = Instant.parse("2025-09-10T00:00:00Z");
        Integer sameIndex = 0;
        String corrId = "BYBIT-CORRIDOR:MANTLE:0xd7c7736b";

        NormalizedTransaction inbound = corridorInternalTransfer("a", sameTimestamp, sameIndex, corrId, "1.0");
        NormalizedTransaction outbound = corridorInternalTransfer("b", sameTimestamp, sameIndex, corrId, "-1.0");

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(inbound, outbound));

        List<NormalizedTransaction> result = service().loadOrderedConfirmed();

        // Outbound (CARRY_OUT, sign=-1) must precede inbound (CARRY_IN, sign=+1)
        assertThat(result).extracting(NormalizedTransaction::getId).containsExactly("b", "a");
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

    private NormalizedTransaction corridorInternalTransfer(
            String id, Instant timestamp, Integer txIndex, String corrId, String quantityDelta
    ) {
        NormalizedTransaction tx = transaction(id, timestamp, txIndex);
        tx.setSource(NormalizedTransactionSource.ON_CHAIN);
        tx.setType(NormalizedTransactionType.INTERNAL_TRANSFER);
        tx.setCorrelationId(corrId);
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setQuantityDelta(new BigDecimal(quantityDelta));
        tx.setFlows(List.of(flow));
        return tx;
    }
}
