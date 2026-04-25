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
import static org.mockito.ArgumentMatchers.anyCollection;
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

    @Test
    void continuityTransferWithoutPrincipalMarketPriceStillPromotes() {
        NormalizedTransaction transaction = transaction(
                NormalizedTransactionType.BRIDGE_OUT,
                flow(NormalizedLegRole.TRANSFER, "USDC", "-1000", null, null),
                flow(NormalizedLegRole.FEE, "ETH", "-0.001", "2500", PriceSource.BINANCE)
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
        assertThat(captor.getValue().getMissingDataReasons()).doesNotContain(StatValidationService.FLOW_PRICE_MISSING_REASON);
    }

    @Test
    void feeOnlyReviewRowStillPromotesToConfirmed() {
        NormalizedTransaction transaction = transaction(
                NormalizedTransactionType.UNKNOWN,
                flow(NormalizedLegRole.FEE, "ETH", "-0.0001", "2500", PriceSource.BINANCE)
        );
        transaction.setMissingDataReasons(List.of("CLASSIFICATION_FAILED"));
        when(pendingStatQueryService.loadNextBatch(25, 60)).thenReturn(List.of(transaction));

        StatValidationService service = new StatValidationService(pendingStatQueryService, normalizedTransactionRepository);
        StatValidationOutcome outcome = service.processNextBatch(25, 60);

        assertThat(outcome.processed()).isEqualTo(1);
        assertThat(outcome.promotedToConfirmed()).isEqualTo(1);
        assertThat(outcome.demotedToNeedsReview()).isZero();

        ArgumentCaptor<NormalizedTransaction> captor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(normalizedTransactionRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(captor.getValue().getMissingDataReasons()).contains("CLASSIFICATION_FAILED");
        assertThat(captor.getValue().getMissingDataReasons()).doesNotContain(StatValidationService.NO_NON_FEE_FLOW_REASON);
    }

    @Test
    void replaySafeNeedsReviewRowsPromoteWhenTheyAreFeeOnlyOrEmpty() {
        NormalizedTransaction feeOnly = transaction(
                NormalizedTransactionType.UNKNOWN,
                flow(NormalizedLegRole.FEE, "ETH", "-0.0001", "2500", PriceSource.BINANCE)
        );
        feeOnly.setStatus(NormalizedTransactionStatus.NEEDS_REVIEW);
        feeOnly.setMissingDataReasons(List.of("CLASSIFICATION_FAILED"));

        NormalizedTransaction empty = transaction(NormalizedTransactionType.UNKNOWN);
        empty.setId("tx-2");
        empty.setStatus(NormalizedTransactionStatus.NEEDS_REVIEW);
        empty.setFlows(List.of());
        empty.setMissingDataReasons(List.of("ROUTER_METHOD_OVERLOAD_UNSUPPORTED"));

        NormalizedTransaction principal = transaction(
                NormalizedTransactionType.UNKNOWN,
                flow(NormalizedLegRole.TRANSFER, "USDC", "-10", "1", PriceSource.STABLECOIN),
                flow(NormalizedLegRole.FEE, "ETH", "-0.0001", "2500", PriceSource.BINANCE)
        );
        principal.setId("tx-3");
        principal.setStatus(NormalizedTransactionStatus.NEEDS_REVIEW);
        principal.setMissingDataReasons(List.of("CLASSIFICATION_FAILED"));

        when(normalizedTransactionRepository.findAllActiveAccountingByWalletAddressInAndStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                anyCollection(),
                org.mockito.ArgumentMatchers.eq(NormalizedTransactionStatus.NEEDS_REVIEW)
        )).thenReturn(List.of(feeOnly, empty, principal));

        StatValidationService service = new StatValidationService(pendingStatQueryService, normalizedTransactionRepository);
        int promoted = service.promoteReplaySafeNeedsReview(List.of("0xwallet"));

        assertThat(promoted).isEqualTo(2);

        ArgumentCaptor<NormalizedTransaction> captor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(normalizedTransactionRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(NormalizedTransaction::getId)
                .containsExactly("tx-1", "tx-2");
        assertThat(captor.getAllValues())
                .extracting(NormalizedTransaction::getStatus)
                .containsOnly(NormalizedTransactionStatus.CONFIRMED);
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
        flow.setUnitPriceUsd(unitPriceUsd == null ? null : new BigDecimal(unitPriceUsd));
        flow.setPriceSource(priceSource);
        return flow;
    }
}
