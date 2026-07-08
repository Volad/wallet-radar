package com.walletradar.costbasis.application;

import com.walletradar.costbasis.domain.LpReceiptBasisPool;
import com.walletradar.costbasis.domain.LpReceiptBasisPoolKey;
import com.walletradar.costbasis.domain.LpReceiptBasisPoolRepository;
import com.walletradar.domain.common.NetworkId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class LpReceiptBasisPoolServiceTest {

    @Mock
    private LpReceiptBasisPoolRepository repository;

    private LpReceiptBasisPoolService service;

    @BeforeEach
    void setUp() {
        service = new LpReceiptBasisPoolService(repository);
    }

    @Test
    void depositAndWithdrawPreservesBasisProportionally() {
        var pools = new LinkedHashMap<LpReceiptBasisPoolKey, LpReceiptBasisPool>();
        var dirty = new HashSet<LpReceiptBasisPoolKey>();
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        LpReceiptBasisPool pool = service.lookupOrCreate(
                "universe-1",
                "lp-position:base:pancakeswap:1",
                "0xwallet",
                NetworkId.BASE,
                "FAMILY:ETH",
                "WETH",
                null,
                pools,
                dirty,
                now
        );
        service.deposit(pool, new BigDecimal("0.5"), new BigDecimal("1500"), BigDecimal.ZERO);

        var withdraw = service.withdraw(pool, new BigDecimal("0.2"));

        assertThat(withdraw.withdrawnQty()).isEqualByComparingTo("0.2");
        assertThat(withdraw.withdrawnBasisUsd()).isEqualByComparingTo("600");
        assertThat(pool.getQtyHeld()).isEqualByComparingTo("0.3");
        assertThat(pool.getBasisHeldUsd()).isEqualByComparingTo("900");
    }
}
