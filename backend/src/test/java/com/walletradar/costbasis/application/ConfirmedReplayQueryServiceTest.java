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

    @Test
    void bybitCollapsedInternalTransferSortsFundOutboundBeforeUmbrellaInboundAtSameTimestamp() {
        Instant sameTimestamp = Instant.parse("2026-03-12T00:00:00Z");
        Integer sameIndex = 0;
        String corrId = "bybit-collapsed-v1:abc123";

        NormalizedTransaction fundOutbound = bybitCollapsedTransfer("fund-out", sameTimestamp, sameIndex, corrId, "-1.0", "BYBIT:33625378:FUND");
        NormalizedTransaction umbrellaInbound = bybitCollapsedTransfer("umbrella-in", sameTimestamp, sameIndex, corrId, "1.0", "BYBIT:33625378");

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(umbrellaInbound, fundOutbound));

        List<NormalizedTransaction> result = service().loadOrderedConfirmed();

        assertThat(result).extracting(NormalizedTransaction::getId).containsExactly("fund-out", "umbrella-in");
    }

    @Test
    void bybitExternalDepositSortsBeforeSameTimestampCollapsedOutboundDrain() {
        // MNT/USDT umbrella phantom: a genuine deposit (EXTERNAL_TRANSFER_IN) and a
        // bybit-collapsed-v1 FUND->UTA transfer share the same timestamp+index. Without the fix,
        // corridorContinuityFlowSign sorts the collapsed outbound (sign=-1) before the deposit
        // (sign=0), so the drain clamps an unfunded umbrella and the inbound credits it back,
        // manufacturing a phantom equal to the deposit. The deposit must settle first.
        Instant sameTimestamp = Instant.parse("2025-04-11T16:30:00Z");
        Integer sameIndex = 0;
        String corrId = "bybit-collapsed-v1:8e38ede";

        NormalizedTransaction deposit = bybitExternalTransferIn("deposit", sameTimestamp, sameIndex, "1074.68");
        NormalizedTransaction collapsedOut =
                bybitCollapsedTransfer("collapsed-out", sameTimestamp, sameIndex, corrId, "-1074.68", "BYBIT:33625378:FUND");
        NormalizedTransaction collapsedIn =
                bybitCollapsedTransfer("collapsed-in", sameTimestamp, sameIndex, corrId, "1074.68", "BYBIT:33625378");

        when(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        )).thenReturn(List.of(collapsedIn, collapsedOut, deposit));

        List<NormalizedTransaction> result = service().loadOrderedConfirmed();

        assertThat(result).extracting(NormalizedTransaction::getId)
                .containsExactly("deposit", "collapsed-out", "collapsed-in");
    }

    private ConfirmedReplayQueryService service() {
        return new ConfirmedReplayQueryService(normalizedTransactionRepository);
    }

    private NormalizedTransaction bybitExternalTransferIn(
            String id, Instant timestamp, Integer txIndex, String quantityDelta
    ) {
        NormalizedTransaction tx = transaction(id, timestamp, txIndex);
        tx.setSource(NormalizedTransactionSource.BYBIT);
        tx.setType(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        tx.setWalletAddress("BYBIT:33625378:FUND");
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setQuantityDelta(new BigDecimal(quantityDelta));
        tx.setFlows(List.of(flow));
        return tx;
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

    private NormalizedTransaction bybitCollapsedTransfer(
            String id,
            Instant timestamp,
            Integer txIndex,
            String corrId,
            String quantityDelta,
            String walletAddress
    ) {
        NormalizedTransaction tx = transaction(id, timestamp, txIndex);
        tx.setSource(NormalizedTransactionSource.BYBIT);
        tx.setType(NormalizedTransactionType.INTERNAL_TRANSFER);
        tx.setCorrelationId(corrId);
        tx.setWalletAddress(walletAddress);
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setQuantityDelta(new BigDecimal(quantityDelta));
        tx.setFlows(List.of(flow));
        return tx;
    }
}
