package com.walletradar.application.lending.application;

import com.walletradar.application.costbasis.domain.BorrowLiability;
import com.walletradar.application.costbasis.domain.BorrowLiabilityRepository;
import com.walletradar.application.lending.spi.LiveLendingAssetAmount;
import com.walletradar.application.lending.spi.LiveLendingPosition;
import com.walletradar.application.lending.spi.LivePositionRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WS-4 acceptance: the live borrow figure SETS/overrides the classification-derived {@code qtyOpen}
 * (never stacks — else {@code PortfolioConservationGate} over-subtracts), only for receipt-less
 * (non-EVM) networks. Debt = 233 USDT, counted once, marked at $1.
 */
class LendingLiabilityLiveTrueUpServiceTest {

    private static final String UNIVERSE = "universe-1";
    private static final String WALLET = "9Grpx4HKXTe51Ug9nAYuND9qf2bw326WvxFyEULt1DhG";

    private final BorrowLiabilityRepository repository = mock(BorrowLiabilityRepository.class);
    private final LendingLiabilityLiveTrueUpService service = new LendingLiabilityLiveTrueUpService(repository);

    private static LiveLendingPosition livePosition(BigDecimal debtQty) {
        LiveLendingAssetAmount debt = new LiveLendingAssetAmount(
                "USDT", "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB", 6, debtQty, debtQty);
        return new LiveLendingPosition(List.of(), List.of(debt),
                new BigDecimal("1.51"), new BigDecimal("0.85"), new BigDecimal("0.564"),
                "LIVE_PROTOCOL", null, "vault:2:nft:101");
    }

    private static BorrowLiability existing(BigDecimal qtyOpen) {
        BorrowLiability liability = new BorrowLiability();
        liability.setCompositeId(UNIVERSE + ":solana:jupiter-lend:usdt");
        liability.setUniverseId(UNIVERSE);
        liability.setAccountRef(WALLET);
        liability.setOrderId("solana:jupiter-lend:" + WALLET);
        liability.setAsset("USDT");
        liability.setQtyBorrowed(qtyOpen);
        liability.setQtyOpen(qtyOpen);
        liability.setStatus("OPEN");
        return liability;
    }

    @Test
    void setsQtyOpenToLiveDebtInsteadOfStacking() {
        BorrowLiability liability = existing(new BigDecimal("210.0"));
        when(repository.findByUniverseId(UNIVERSE)).thenReturn(List.of(liability));
        LivePositionRequest request = new LivePositionRequest("s", "Jupiter Lend", "SOLANA", WALLET);

        service.trueUp(UNIVERSE, request, livePosition(new BigDecimal("233.377961")));

        ArgumentCaptor<BorrowLiability> captor = ArgumentCaptor.forClass(BorrowLiability.class);
        verify(repository).save(captor.capture());
        BorrowLiability saved = captor.getValue();
        // SET, not stack: 233.377961 (NOT 210 + 233 = 443).
        assertThat(saved.getQtyOpen()).isEqualByComparingTo("233.377961");
        assertThat(saved.getStatus()).isEqualTo("OPEN");
    }

    @Test
    void skipsEvmNetworksBecauseDebtTokenBalanceIsAlreadyLive() {
        LivePositionRequest request = new LivePositionRequest("s", "Aave", "ARBITRUM", "0xabc");
        service.trueUp(UNIVERSE, request, livePosition(new BigDecimal("233")));
        verify(repository, never()).findByUniverseId(UNIVERSE);
        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void noOpWhenNoDebtLeg() {
        LivePositionRequest request = new LivePositionRequest("s", "Jupiter Lend", "SOLANA", WALLET);
        LiveLendingPosition noDebt = new LiveLendingPosition(List.of(), List.of(),
                null, new BigDecimal("0.85"), null, "LIVE_PROTOCOL", null, null);
        service.trueUp(UNIVERSE, request, noDebt);
        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
    }
}
