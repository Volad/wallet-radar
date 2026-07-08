package com.walletradar.application.costbasis.application.replay.support;

import com.walletradar.application.costbasis.domain.BorrowLiability;
import com.walletradar.application.costbasis.domain.CounterpartyBasisPool;
import com.walletradar.application.costbasis.domain.CounterpartyBasisPoolKey;
import com.walletradar.application.costbasis.domain.LpReceiptBasisPool;
import com.walletradar.application.costbasis.domain.LpReceiptBasisPoolKey;
import com.walletradar.domain.common.NetworkId;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReplayAccumulatorDriftCanaryTest {

    private final ReplayAccumulatorDriftCanary canary = new ReplayAccumulatorDriftCanary();

    @Test
    void reportsCleanWhenComputedMatchesPersisted() {
        Map<String, BorrowLiability> borrow = borrowBook("100");
        Map<CounterpartyBasisPoolKey, CounterpartyBasisPool> counterparty = counterpartyBook("5");
        Map<LpReceiptBasisPoolKey, LpReceiptBasisPool> lp = lpBook("7");

        boolean clean = canary.check(
                "GLOBAL",
                borrow, borrowBook("100"),
                counterparty, counterpartyBook("5"),
                lp, lpBook("7")
        );

        assertThat(clean).isTrue();
    }

    @Test
    void detectsBorrowDriftWhenPersistedTotalDiffers() {
        boolean clean = canary.check(
                "GLOBAL",
                borrowBook("100"), borrowBook("200"),
                Map.of(), Map.of(),
                Map.of(), Map.of()
        );

        assertThat(clean).isFalse();
    }

    @Test
    void detectsEntryCountDriftEvenWhenTotalsAgree() {
        boolean clean = canary.check(
                "GLOBAL",
                borrowBook("100"), new LinkedHashMap<>(),
                Map.of(), Map.of(),
                Map.of(), Map.of()
        );

        assertThat(clean).isFalse();
    }

    private static Map<String, BorrowLiability> borrowBook(String qtyOpen) {
        BorrowLiability liability = new BorrowLiability();
        liability.setCompositeId("GLOBAL:loan-1");
        liability.setUniverseId("GLOBAL");
        liability.setQtyOpen(new BigDecimal(qtyOpen));
        Map<String, BorrowLiability> book = new LinkedHashMap<>();
        book.put(liability.getCompositeId(), liability);
        return book;
    }

    private static Map<CounterpartyBasisPoolKey, CounterpartyBasisPool> counterpartyBook(String qtyHeld) {
        CounterpartyBasisPoolKey key =
                new CounterpartyBasisPoolKey("GLOBAL", "0xcp", NetworkId.BASE, "USDC");
        CounterpartyBasisPool pool = new CounterpartyBasisPool();
        pool.setId(key.documentId());
        pool.setUniverseId("GLOBAL");
        pool.setQtyHeld(new BigDecimal(qtyHeld));
        Map<CounterpartyBasisPoolKey, CounterpartyBasisPool> book = new LinkedHashMap<>();
        book.put(key, pool);
        return book;
    }

    private static Map<LpReceiptBasisPoolKey, LpReceiptBasisPool> lpBook(String qtyHeld) {
        LpReceiptBasisPoolKey key = new LpReceiptBasisPoolKey("GLOBAL", "lp-corr", "asset-identity");
        LpReceiptBasisPool pool = new LpReceiptBasisPool();
        pool.setId("GLOBAL:lp-corr:asset-identity");
        pool.setUniverseId("GLOBAL");
        pool.setQtyHeld(new BigDecimal(qtyHeld));
        Map<LpReceiptBasisPoolKey, LpReceiptBasisPool> book = new LinkedHashMap<>();
        book.put(key, pool);
        return book;
    }
}
