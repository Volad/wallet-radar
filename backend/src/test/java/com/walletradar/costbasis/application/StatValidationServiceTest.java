package com.walletradar.costbasis.application;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StatValidationServiceTest {

    @Mock
    private PendingStatQueryService pendingStatQueryService;
    @Mock
    private NormalizedTransactionRepository normalizedTransactionRepository;

    @Test
    void validPendingStatPromotesToConfirmed() {
        NormalizedTransaction transaction = transaction(
                NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                flow(NormalizedLegRole.BUY, "ETH", "1", "2500", PriceSource.BINANCE)
        );
        when(pendingStatQueryService.loadNextBatch(25, 60)).thenReturn(List.of(transaction));

        StatValidationService service = new StatValidationService(pendingStatQueryService, normalizedTransactionRepository);
        StatValidationOutcome outcome = service.processNextBatch(25, 60);

        assertThat(outcome.processed()).isEqualTo(1);
        assertThat(outcome.promotedToConfirmed()).isEqualTo(1);
        assertThat(outcome.demotedToNeedsReview()).isZero();

        ArgumentCaptor<NormalizedTransaction> captor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(normalizedTransactionRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(captor.getValue().getStatAttempts()).isEqualTo(1);
    }

    @Test
    void invalidSwapWithoutBuyLegFallsIntoNeedsReview() {
        NormalizedTransaction transaction = transaction(
                NormalizedTransactionType.SWAP,
                flow(NormalizedLegRole.SELL, "USDC", "-1000", "1", PriceSource.STABLECOIN)
        );
        when(pendingStatQueryService.loadNextBatch(10, 120)).thenReturn(List.of(transaction));

        StatValidationService service = new StatValidationService(pendingStatQueryService, normalizedTransactionRepository);
        StatValidationOutcome outcome = service.processNextBatch(10, 120);

        assertThat(outcome.processed()).isEqualTo(1);
        assertThat(outcome.promotedToConfirmed()).isZero();
        assertThat(outcome.demotedToNeedsReview()).isEqualTo(1);

        ArgumentCaptor<NormalizedTransaction> captor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(normalizedTransactionRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(NormalizedTransactionStatus.NEEDS_REVIEW);
        assertThat(captor.getValue().getMissingDataReasons()).contains(StatValidationService.SWAP_MISSING_BUY_LEG_REASON);
    }

    private NormalizedTransaction transaction(
            NormalizedTransactionType type,
            NormalizedTransaction.Flow... flows
    ) {
        NormalizedTransaction transaction = new NormalizedTransaction();
        transaction.setId("tx-1");
        transaction.setTxHash("0xabc");
        transaction.setNetworkId(NetworkId.BASE);
        transaction.setWalletAddress("0xwallet");
        transaction.setSource(NormalizedTransactionSource.ON_CHAIN);
        transaction.setType(type);
        transaction.setStatus(NormalizedTransactionStatus.PENDING_STAT);
        transaction.setBlockTimestamp(Instant.parse("2026-03-25T10:00:00Z"));
        transaction.setMissingDataReasons(List.of());
        transaction.setFlows(List.of(flows));
        return transaction;
    }

    private NormalizedTransaction.Flow flow(
            NormalizedLegRole role,
            String assetSymbol,
            String quantity,
            String unitPriceUsd,
            PriceSource priceSource
    ) {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(role);
        flow.setAssetSymbol(assetSymbol);
        flow.setQuantityDelta(new BigDecimal(quantity));
        flow.setUnitPriceUsd(new BigDecimal(unitPriceUsd));
        flow.setPriceSource(priceSource);
        return flow;
    }
}
