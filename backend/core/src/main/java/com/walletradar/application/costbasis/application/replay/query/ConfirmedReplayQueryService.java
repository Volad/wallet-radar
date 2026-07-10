package com.walletradar.application.costbasis.application.replay.query;

import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ConfirmedReplayQueryService {

    /**
     * For INTERNAL_TRANSFER transactions only: outflows (negative qty, i.e. CARRY_OUT) must
     * process before inflows (positive qty, i.e. CARRY_IN) at the same timestamp so that the
     * pending-transfer queue is populated before it is consumed. Without this tiebreaker, Bybit
     * EARN deposits where both legs share the same {@code blockTimestamp} read stale queue entries.
     * Scoped to Bybit continuity rows (INTERNAL_TRANSFER and Earn product LENDING_*) to avoid
     * reordering unrelated buys/sells at the same timestamp.
     */
    private static final Comparator<NormalizedTransaction> REPLAY_ORDER = Comparator
            .comparing(NormalizedTransaction::getBlockTimestamp, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(NormalizedTransaction::getTransactionIndex, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparingInt(ConfirmedReplayQueryService::bybitSameDayTransactionClassOrder)
            .thenComparingInt(ConfirmedReplayQueryService::corridorContinuityFlowSign)
            .thenComparing(NormalizedTransaction::getId, Comparator.nullsLast(Comparator.naturalOrder()));

    /**
     * Same-day Bybit earn redeem must run after spot disposals that shrink umbrella inventory,
     * otherwise earn outbound drains an empty {@code :EARN} slice while umbrella basis remains.
     */
    private static int bybitSameDayTransactionClassOrder(NormalizedTransaction tx) {
        if (tx == null || tx.getSource() != NormalizedTransactionSource.BYBIT) {
            return 0;
        }
        // Genuine external inbound deposits bring new inventory onto the umbrella. They must settle
        // before any same-timestamp internal drain (e.g. a bybit-collapsed-v1 FUND->UTA outbound
        // ordered outbound-first by corridorContinuityFlowSign). Otherwise the drain clamps against
        // an unfunded umbrella and the matching inbound credits it back, manufacturing a phantom
        // equal to the deposit quantity (root cause of the MNT/USDT umbrella inflation).
        if (tx.getType() == NormalizedTransactionType.EXTERNAL_TRANSFER_IN) {
            return -3;
        }
        if (isBybitSpotDisposition(tx)) {
            return -2;
        }
        return switch (tx.getType()) {
            case LENDING_WITHDRAW, EARN_FLEXIBLE_SAVING -> -1;
            default -> 0;
        };
    }

    private static boolean isBybitSpotDisposition(NormalizedTransaction tx) {
        if (tx.getId() != null && tx.getId().contains(":EXECUTION_SPOT:")) {
            return true;
        }
        return tx.getType() == NormalizedTransactionType.EXTERNAL_TRANSFER_OUT
                || tx.getType() == NormalizedTransactionType.FIAT_EXIT
                || tx.getType() == NormalizedTransactionType.DEX_ORDER_SETTLEMENT;
    }

    // B-2 / B-SPIKE-03: order bybit-collapsed-v1 and corridor rows outbound-first so FUND
    // CARRY_OUT lands before umbrella CARRY_IN at the same timestamp.
    private static int corridorContinuityFlowSign(NormalizedTransaction tx) {
        if (tx == null || tx.getFlows() == null) {
            return 0;
        }
        boolean isBybit = tx.getSource() == NormalizedTransactionSource.BYBIT;
        String correlationId = tx.getCorrelationId();
        boolean isBybitCollapsed = isBybit
                && correlationId != null
                && correlationId.startsWith("bybit-collapsed-v1:");
        boolean isOnChainCorridor = !isBybit
                && tx.getType() == NormalizedTransactionType.INTERNAL_TRANSFER
                && correlationId != null
                && correlationId.startsWith("BYBIT-CORRIDOR:");
        if (!isBybit && !isOnChainCorridor && !isBybitCollapsed) {
            return 0;
        }
        if (isBybitCollapsed) {
            return bybitPrincipalFlowSign(tx);
        }
        return switch (tx.getType()) {
            case INTERNAL_TRANSFER, LENDING_DEPOSIT, LENDING_WITHDRAW, EARN_FLEXIBLE_SAVING -> bybitPrincipalFlowSign(tx);
            default -> 0;
        };
    }

    private static int bybitPrincipalFlowSign(NormalizedTransaction tx) {
        if (tx == null || tx.getFlows() == null) {
            return 0;
        }
        for (NormalizedTransaction.Flow flow : tx.getFlows()) {
            if (flow != null
                    && flow.getQuantityDelta() != null
                    && flow.getQuantityDelta().signum() != 0
                    && flow.getRole() != NormalizedLegRole.FEE) {
                return flow.getQuantityDelta().signum();
            }
        }
        return 0;
    }

    private final NormalizedTransactionRepository normalizedTransactionRepository;

    public List<NormalizedTransaction> loadOrderedConfirmed() {
        return ensureReplayOrder(normalizedTransactionRepository.findAllActiveAccountingByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                NormalizedTransactionStatus.CONFIRMED
        ));
    }

    public List<NormalizedTransaction> loadOrderedConfirmed(Collection<String> walletAddresses) {
        if (walletAddresses == null || walletAddresses.isEmpty()) {
            return List.of();
        }
        return ensureReplayOrder(normalizedTransactionRepository.findAllActiveAccountingByWalletAddressInAndStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
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
