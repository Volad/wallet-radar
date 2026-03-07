package com.walletradar.ingestion.job.pricing;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.ClassificationStatus;
import com.walletradar.domain.transaction.normalized.LpLifecycleBoundaryStatus;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.PricingStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.event.RecalculateWalletRequestEvent;
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
        assertThat(tx.getFlows()).allSatisfy(leg -> {
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
        tx.setClassificationStatus(ClassificationStatus.CONFIRMED);
        when(historicalPriceResolverChain.resolve(any())).thenReturn(PriceResolutionResult.unknown());

        pricingJob.priceOne(tx);
        assertThat(tx.getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(tx.getMissingDataReasons()).isNotEmpty();

        tx.setPricingAttempts(1);
        pricingJob.priceOne(tx);
        assertThat(tx.getStatus()).isEqualTo(NormalizedTransactionStatus.NEEDS_REVIEW);
        assertThat(tx.getClassificationStatus()).isEqualTo(ClassificationStatus.CONFIRMED);
        assertThat(tx.getPricingStatus()).isEqualTo(PricingStatus.UNRESOLVED);
    }

    @Test
    @DisplayName("LP_ADJUST does not require price resolution and can be confirmed")
    void lpAdjustSkipsPricing() {
        NormalizedTransaction tx = pendingPriceLpAdjust();

        pricingJob.priceOne(tx);

        assertThat(tx.getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_STAT);
        verify(historicalPriceResolverChain, never()).resolve(any());

        when(normalizedTransactionRepository.findByStatusOrderByBlockTimestampAsc(NormalizedTransactionStatus.PENDING_STAT))
                .thenReturn(List.of(tx));
        statJob.runScheduled();

        assertThat(tx.getStatus()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
    }

    @Test
    @DisplayName("LP_POSITION_STAKE does not require price resolution and can be confirmed")
    void lpPositionStakeSkipsPricing() {
        NormalizedTransaction tx = pendingPriceLpPositionStake();

        pricingJob.priceOne(tx);

        assertThat(tx.getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_STAT);
        verify(historicalPriceResolverChain, never()).resolve(any());
    }

    @Test
    @DisplayName("WRAP prices inbound leg and transitions to PENDING_STAT")
    void wrapPricesInboundLeg() {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setTxHash("0xwrap");
        tx.setNetworkId(NetworkId.BASE);
        tx.setWalletAddress("0xwallet");
        tx.setBlockTimestamp(Instant.parse("2025-10-06T09:11:09Z"));
        tx.setType(NormalizedTransactionType.WRAP);
        tx.setStatus(NormalizedTransactionStatus.PENDING_PRICE);
        tx.setPricingAttempts(0);
        tx.setClassificationStatus(ClassificationStatus.CONFIRMED);

        NormalizedTransaction.Flow nativeOut = flow(NormalizedLegRole.TRANSFER, "0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee", "ETH", "-1");
        NormalizedTransaction.Flow wrappedIn = flow(NormalizedLegRole.TRANSFER, "0x4200000000000000000000000000000000000006", "WETH", "1");
        tx.setFlows(List.of(nativeOut, wrappedIn));

        when(historicalPriceResolverChain.resolve(any()))
                .thenReturn(PriceResolutionResult.known(new BigDecimal("3025.42"), PriceSource.COINGECKO));

        pricingJob.priceOne(tx);

        assertThat(tx.getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_STAT);
        assertThat(tx.getPricingStatus()).isEqualTo(PricingStatus.RESOLVED);
        verify(historicalPriceResolverChain, times(1)).resolve(any());
    }

    @Test
    @DisplayName("grouped LP tx without open/close boundaries gets OPENING_MISSING and CLOSING_MISSING")
    void lpBoundaryStatusesAreRefreshedByGroup() {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setTxHash("0xclaim-only");
        tx.setNetworkId(NetworkId.BASE);
        tx.setWalletAddress("0xwallet");
        tx.setBlockTimestamp(Instant.parse("2026-02-06T07:15:13Z"));
        tx.setType(NormalizedTransactionType.LP_FEE_CLAIM);
        tx.setGroupId("LP_POSITION:BASE:0xwallet:938761");
        tx.setStatus(NormalizedTransactionStatus.PENDING_STAT);
        NormalizedTransaction.Flow pricedClaim = flow(NormalizedLegRole.BUY, "0x8335", "USDC", "1");
        pricedClaim.setUnitPriceUsd(BigDecimal.ONE);
        pricedClaim.setValueUsd(BigDecimal.ONE);
        tx.setFlows(List.of(pricedClaim));

        when(normalizedTransactionRepository.findByStatusOrderByBlockTimestampAsc(NormalizedTransactionStatus.PENDING_STAT))
                .thenReturn(List.of(tx));
        when(normalizedTransactionRepository.findByGroupIdOrderByBlockTimestampAsc(tx.getGroupId()))
                .thenReturn(List.of(tx));

        statJob.runScheduled();

        assertThat(tx.getStatus()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(tx.getBoundaryStatuses()).containsExactly(
                LpLifecycleBoundaryStatus.OPENING_MISSING,
                LpLifecycleBoundaryStatus.CLOSING_MISSING
        );
        verify(normalizedTransactionRepository).saveAll(anyList());
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

        NormalizedTransaction.Flow sell = new NormalizedTransaction.Flow();
        sell.setRole(NormalizedLegRole.SELL);
        sell.setAssetContract("0xaf88");
        sell.setAssetSymbol("USDC");
        sell.setQuantityDelta(new BigDecimal("-16"));

        NormalizedTransaction.Flow buy = new NormalizedTransaction.Flow();
        buy.setRole(NormalizedLegRole.BUY);
        buy.setAssetContract("0x82af");
        buy.setAssetSymbol("WETH");
        buy.setQuantityDelta(new BigDecimal("0.004"));

        tx.setFlows(List.of(sell, buy));
        return tx;
    }

    private static NormalizedTransaction pendingPriceLpAdjust() {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setTxHash("0xlpadjust");
        tx.setNetworkId(NetworkId.BASE);
        tx.setWalletAddress("0xwallet");
        tx.setBlockTimestamp(Instant.parse("2025-07-16T05:52:19Z"));
        tx.setType(NormalizedTransactionType.LP_ADJUST);
        tx.setStatus(NormalizedTransactionStatus.PENDING_PRICE);
        tx.setPricingAttempts(0);

        NormalizedTransaction.Flow transfer = new NormalizedTransaction.Flow();
        transfer.setRole(NormalizedLegRole.TRANSFER);
        transfer.setAssetContract("0x46a15b0b27311cedf172ab29e4f4766fbe7f4364");
        transfer.setAssetSymbol("PCS-V3-POS");
        transfer.setQuantityDelta(new BigDecimal("1"));

        tx.setFlows(List.of(transfer));
        return tx;
    }

    private static NormalizedTransaction pendingPriceLpPositionStake() {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setTxHash("0xlpstake");
        tx.setNetworkId(NetworkId.BASE);
        tx.setWalletAddress("0xwallet");
        tx.setBlockTimestamp(Instant.parse("2025-07-16T05:52:19Z"));
        tx.setType(NormalizedTransactionType.LP_POSITION_STAKE);
        tx.setStatus(NormalizedTransactionStatus.PENDING_PRICE);
        tx.setPricingAttempts(0);

        NormalizedTransaction.Flow transfer = new NormalizedTransaction.Flow();
        transfer.setRole(NormalizedLegRole.TRANSFER);
        transfer.setAssetContract("0x46a15b0b27311cedf172ab29e4f4766fbe7f4364");
        transfer.setAssetSymbol("PCS-V3-POS");
        transfer.setQuantityDelta(new BigDecimal("-1"));

        tx.setFlows(List.of(transfer));
        return tx;
    }

    private static NormalizedTransaction.Flow flow(
            NormalizedLegRole role, String contract, String symbol, String qty
    ) {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(role);
        flow.setAssetContract(contract);
        flow.setAssetSymbol(symbol);
        flow.setQuantityDelta(new BigDecimal(qty));
        return flow;
    }
}
