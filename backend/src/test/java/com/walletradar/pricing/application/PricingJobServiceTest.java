package com.walletradar.pricing.application;

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
class PricingJobServiceTest {

    @Mock
    private PendingPricingQueryService pendingPricingQueryService;
    @Mock
    private NormalizedTransactionRepository normalizedTransactionRepository;
    @Mock
    private PriceResolutionService priceResolutionService;

    @Test
    void processNextBatchSavesConfirmedPricedRows() {
        PricingProperties properties = new PricingProperties();
        properties.setBatchSize(25);
        properties.setRetryDelaySeconds(60);

        NormalizedTransaction pending = pendingTransaction();
        NormalizedTransaction confirmed = pendingTransaction();
        confirmed.setStatus(NormalizedTransactionStatus.CONFIRMED);
        confirmed.getFlows().get(0).setUnitPriceUsd(new BigDecimal("1"));
        confirmed.getFlows().get(0).setPriceSource(PriceSource.STABLECOIN);

        when(pendingPricingQueryService.loadNextBatch(25, 60)).thenReturn(List.of(pending));
        when(priceResolutionService.resolve(org.mockito.ArgumentMatchers.eq(pending), org.mockito.ArgumentMatchers.any()))
                .thenReturn(confirmed);

        PricingJobService service = new PricingJobService(
                pendingPricingQueryService,
                normalizedTransactionRepository,
                priceResolutionService,
                new PricingResultMapper(),
                properties
        );

        int processed = service.processNextBatch();

        assertThat(processed).isEqualTo(1);
        verify(normalizedTransactionRepository).save(confirmed);
    }

    @Test
    void failedPricingAttemptStaysPendingAndAddsOperationalReason() {
        PricingProperties properties = new PricingProperties();
        properties.setBatchSize(25);
        properties.setRetryDelaySeconds(60);

        NormalizedTransaction pending = pendingTransaction();
        when(pendingPricingQueryService.loadNextBatch(25, 60)).thenReturn(List.of(pending));
        when(priceResolutionService.resolve(org.mockito.ArgumentMatchers.eq(pending), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("boom"));

        PricingJobService service = new PricingJobService(
                pendingPricingQueryService,
                normalizedTransactionRepository,
                priceResolutionService,
                new PricingResultMapper(),
                properties
        );

        service.processNextBatch();

        ArgumentCaptor<NormalizedTransaction> captor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(normalizedTransactionRepository).save(captor.capture());
        NormalizedTransaction saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(saved.getMissingDataReasons()).contains(PriceableFlowPolicy.PRICING_EXECUTION_FAILED_REASON);
        assertThat(saved.getPricingAttempts()).isEqualTo(1);
    }

    private NormalizedTransaction pendingTransaction() {
        NormalizedTransaction transaction = new NormalizedTransaction();
        transaction.setId("tx-1");
        transaction.setTxHash("0xabc");
        transaction.setNetworkId(NetworkId.ETHEREUM);
        transaction.setWalletAddress("0xwallet");
        transaction.setSource(NormalizedTransactionSource.ON_CHAIN);
        transaction.setType(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        transaction.setStatus(NormalizedTransactionStatus.PENDING_PRICE);
        transaction.setBlockTimestamp(Instant.parse("2026-03-25T10:00:00Z"));
        transaction.setMissingDataReasons(List.of());
        transaction.setFlows(List.of(flow()));
        transaction.setPricingAttempts(0);
        return transaction;
    }

    private NormalizedTransaction.Flow flow() {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.BUY);
        flow.setAssetContract("0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48");
        flow.setAssetSymbol("USDC");
        flow.setQuantityDelta(new BigDecimal("1"));
        return flow;
    }
}
