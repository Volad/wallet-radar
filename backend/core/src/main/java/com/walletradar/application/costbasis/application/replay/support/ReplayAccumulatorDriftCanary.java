package com.walletradar.application.costbasis.application.replay.support;

import com.walletradar.application.costbasis.domain.BorrowLiability;
import com.walletradar.application.costbasis.domain.CounterpartyBasisPool;
import com.walletradar.application.costbasis.domain.CounterpartyBasisPoolKey;
import com.walletradar.application.costbasis.domain.LpReceiptBasisPool;
import com.walletradar.application.costbasis.domain.LpReceiptBasisPoolKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

/**
 * RC-12 / ADR-030 — compute-vs-persisted replay accumulator drift canary.
 *
 * <p>The AVCO replay rebuilds every persisted accumulator book ({@code borrow_liabilities},
 * {@code counterparty_basis_pools}, {@code lp_receipt_basis_pools}) from an <b>empty</b> map each
 * run and is the sole writer via replace-only persistence. This canary compares the freshly-computed
 * in-memory book totals against what was just persisted (reloaded from the store). Because the
 * replay is the sole writer of these projections, the two must agree bit-for-bit within a tiny
 * epsilon; any divergence indicates a persistence/serialization defect or an accidental
 * double-write/double-seed regression.
 *
 * <p>Severity is intentionally {@link #LOG WARN-only} (architect decision, ADR-030): the canary
 * never blocks the replay. The hard correctness check is the {@code rebuild == refresh == refresh×N}
 * idempotency unit test. A future incremental-window optimization must keep this canary green.
 */
@Component
@Slf4j
public class ReplayAccumulatorDriftCanary {

    /** Aggregate totals must agree within this band; below it is BigDecimal-scale dust. */
    static final BigDecimal DRIFT_EPSILON = new BigDecimal("0.000001");

    private static final String LOG = "WARN";

    /**
     * Compares the in-memory computed books against the persisted reload and WARN-logs any drift.
     * Never throws — the replay must not be blocked by the canary.
     *
     * @return {@code true} when no drift was detected (books agree within epsilon).
     */
    public boolean check(
            String universeId,
            Map<String, BorrowLiability> computedBorrow,
            Map<String, BorrowLiability> persistedBorrow,
            Map<CounterpartyBasisPoolKey, CounterpartyBasisPool> computedCounterparty,
            Map<CounterpartyBasisPoolKey, CounterpartyBasisPool> persistedCounterparty,
            Map<LpReceiptBasisPoolKey, LpReceiptBasisPool> computedLpReceipt,
            Map<LpReceiptBasisPoolKey, LpReceiptBasisPool> persistedLpReceipt
    ) {
        boolean clean = true;

        clean &= reportBook(
                universeId,
                "borrow_liabilities",
                sumBorrowOpen(computedBorrow),
                sumBorrowOpen(persistedBorrow),
                size(computedBorrow),
                size(persistedBorrow)
        );
        clean &= reportBook(
                universeId,
                "counterparty_basis_pools",
                sumCounterpartyQtyHeld(computedCounterparty),
                sumCounterpartyQtyHeld(persistedCounterparty),
                size(computedCounterparty),
                size(persistedCounterparty)
        );
        clean &= reportBook(
                universeId,
                "lp_receipt_basis_pools",
                sumLpReceiptQtyHeld(computedLpReceipt),
                sumLpReceiptQtyHeld(persistedLpReceipt),
                size(computedLpReceipt),
                size(persistedLpReceipt)
        );

        return clean;
    }

    private boolean reportBook(
            String universeId,
            String book,
            BigDecimal computedTotal,
            BigDecimal persistedTotal,
            int computedSize,
            int persistedSize
    ) {
        BigDecimal delta = computedTotal.subtract(persistedTotal).abs();
        if (delta.compareTo(DRIFT_EPSILON) > 0 || computedSize != persistedSize) {
            log.warn(
                    "REPLAY_ACCUMULATOR_DRIFT severity={} universeId={} book={} computedTotal={} "
                            + "persistedTotal={} delta={} computedEntries={} persistedEntries={}",
                    LOG,
                    universeId,
                    book,
                    computedTotal,
                    persistedTotal,
                    delta,
                    computedSize,
                    persistedSize
            );
            return false;
        }
        return true;
    }

    private static int size(Map<?, ?> map) {
        return map == null ? 0 : map.size();
    }

    private static BigDecimal sumBorrowOpen(Map<String, BorrowLiability> book) {
        if (book == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal total = BigDecimal.ZERO;
        for (BorrowLiability liability : book.values()) {
            total = total.add(zeroIfNull(liability == null ? null : liability.getQtyOpen()));
        }
        return total;
    }

    private static BigDecimal sumCounterpartyQtyHeld(Map<CounterpartyBasisPoolKey, CounterpartyBasisPool> book) {
        if (book == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal total = BigDecimal.ZERO;
        for (CounterpartyBasisPool pool : book.values()) {
            total = total.add(zeroIfNull(pool == null ? null : pool.getQtyHeld()));
        }
        return total;
    }

    private static BigDecimal sumLpReceiptQtyHeld(Map<LpReceiptBasisPoolKey, LpReceiptBasisPool> book) {
        if (book == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal total = BigDecimal.ZERO;
        for (LpReceiptBasisPool pool : book.values()) {
            total = total.add(zeroIfNull(pool == null ? null : pool.getQtyHeld()));
        }
        return total;
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
