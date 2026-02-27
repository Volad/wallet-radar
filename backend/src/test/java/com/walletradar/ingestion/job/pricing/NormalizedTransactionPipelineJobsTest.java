package com.walletradar.ingestion.job.pricing;

import com.walletradar.domain.NetworkId;
import com.walletradar.domain.NormalizedLegRole;
import com.walletradar.domain.NormalizedTransaction;
import com.walletradar.domain.NormalizedTransactionRepository;
import com.walletradar.domain.NormalizedTransactionStatus;
import com.walletradar.domain.NormalizedTransactionType;
import com.walletradar.domain.PriceSource;
import com.walletradar.domain.RecalculateWalletRequestEvent;
import com.walletradar.pricing.HistoricalPriceResolverChain;
import com.walletradar.pricing.PriceResolutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NormalizedTransactionPipelineJobsTest {

    @Mock
    private NormalizedTransactionRepository normalizedTransactionRepository;
    @Mock
    private HistoricalPriceResolverChain historicalPriceResolverChain;
    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    private NormalizedTransactionPricingJob pricingJob;
    private NormalizedTransactionStatJob statJob;

    @BeforeEach
    void setUp() {
        pricingJob = new NormalizedTransactionPricingJob(normalizedTransactionRepository, historicalPriceResolverChain);
        ReflectionTestUtils.setField(pricingJob, "maxRetries", 2);
        statJob = new NormalizedTransactionStatJob(normalizedTransactionRepository, applicationEventPublisher);
    }

    @Test
    @DisplayName("happy path: PENDING_PRICE -> PENDING_STAT -> CONFIRMED and recalc event published")
    void happyPathTransitionsToConfirmed() {
        NormalizedTransaction tx = pendingPriceSwap();
        when(historicalPriceResolverChain.resolve(any()))
                .thenReturn(PriceResolutionResult.known(new BigDecimal("1"), PriceSource.STABLECOIN))
                .thenReturn(PriceResolutionResult.known(new BigDecimal("4000"), PriceSource.COINGECKO));

        pricingJob.priceOne(tx);
        assertThat(tx.getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_STAT);
        assertThat(tx.getLegs()).allSatisfy(leg -> {
            assertThat(leg.getUnitPriceUsd()).isNotNull();
            assertThat(leg.getValueUsd()).isNotNull();
        });

        when(normalizedTransactionRepository.findByStatusOrderByBlockTimestampAsc(NormalizedTransactionStatus.PENDING_STAT))
                .thenReturn(List.of(tx));
        statJob.runScheduled();

        assertThat(tx.getStatus()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(tx.getConfirmedAt()).isNotNull();
        ArgumentCaptor<RecalculateWalletRequestEvent> captor = ArgumentCaptor.forClass(RecalculateWalletRequestEvent.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().walletAddress()).isEqualTo("0xwallet");
    }

    @Test
    @DisplayName("unresolved price keeps record pending, then moves to NEEDS_REVIEW on retry limit")
    void unresolvedPricingPath() {
        NormalizedTransaction tx = pendingPriceSwap();
        when(historicalPriceResolverChain.resolve(any())).thenReturn(PriceResolutionResult.unknown());

        pricingJob.priceOne(tx);
        assertThat(tx.getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(tx.getMissingDataReasons()).isNotEmpty();

        tx.setPricingAttempts(1);
        pricingJob.priceOne(tx);
        assertThat(tx.getStatus()).isEqualTo(NormalizedTransactionStatus.NEEDS_REVIEW);
    }

    private static NormalizedTransaction pendingPriceSwap() {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setTxHash("0xswap");
        tx.setNetworkId(NetworkId.ARBITRUM);
        tx.setWalletAddress("0xwallet");
        tx.setBlockTimestamp(Instant.parse("2025-10-06T09:11:09Z"));
        tx.setType(NormalizedTransactionType.SWAP);
        tx.setStatus(NormalizedTransactionStatus.PENDING_PRICE);
        tx.setPricingAttempts(0);

        NormalizedTransaction.Leg sell = new NormalizedTransaction.Leg();
        sell.setRole(NormalizedLegRole.SELL);
        sell.setAssetContract("0xaf88");
        sell.setAssetSymbol("USDC");
        sell.setQuantityDelta(new BigDecimal("-16"));

        NormalizedTransaction.Leg buy = new NormalizedTransaction.Leg();
        buy.setRole(NormalizedLegRole.BUY);
        buy.setAssetContract("0x82af");
        buy.setAssetSymbol("WETH");
        buy.setQuantityDelta(new BigDecimal("0.004"));

        tx.setLegs(List.of(sell, buy));
        return tx;
    }
}
