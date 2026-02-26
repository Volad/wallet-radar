package com.walletradar.ingestion.job.pricing;

import com.walletradar.domain.EconomicEvent;
import com.walletradar.domain.EconomicEventRepository;
import com.walletradar.domain.FlagCode;
import com.walletradar.domain.NetworkId;
import com.walletradar.domain.PriceSource;
import com.walletradar.domain.RecalculateWalletRequestEvent;
import com.walletradar.pricing.HistoricalPriceRequest;
import com.walletradar.pricing.HistoricalPriceResolverChain;
import com.walletradar.pricing.PriceResolutionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeferredPriceResolutionJobTest {

    @Mock
    private EconomicEventRepository economicEventRepository;
    @Mock
    private HistoricalPriceResolverChain historicalPriceResolverChain;
    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @Spy
    @InjectMocks
    private DeferredPriceResolutionJob deferredPriceResolutionJob;

    @Test
    @DisplayName("Events with known prices get resolved and flagCode cleared")
    void resolvesKnownPrices() {
        EconomicEvent event = pendingEvent("0xAAA", "0xToken1", Instant.parse("2025-06-15T12:00:00Z"));
        when(economicEventRepository.findByWalletAddressAndFlagCode("0xAAA", FlagCode.PRICE_PENDING))
                .thenReturn(List.of(event));
        when(historicalPriceResolverChain.resolve(any(HistoricalPriceRequest.class)))
                .thenReturn(PriceResolutionResult.known(new BigDecimal("1850.50"), PriceSource.COINGECKO));

        deferredPriceResolutionJob.resolveForWallet("0xAAA");

        assertThat(event.getPriceUsd()).isEqualByComparingTo("1850.50");
        assertThat(event.getPriceSource()).isEqualTo(PriceSource.COINGECKO);
        assertThat(event.getFlagCode()).isNull();
        assertThat(event.isFlagResolved()).isTrue();
        verify(economicEventRepository).save(event);
    }

    @Test
    @DisplayName("Events with unknown prices get flagCode=PRICE_UNKNOWN")
    void marksUnknownPrices() {
        EconomicEvent event = pendingEvent("0xAAA", "0xObscure", Instant.parse("2025-06-15T12:00:00Z"));
        when(economicEventRepository.findByWalletAddressAndFlagCode("0xAAA", FlagCode.PRICE_PENDING))
                .thenReturn(List.of(event));
        when(historicalPriceResolverChain.resolve(any(HistoricalPriceRequest.class)))
                .thenReturn(PriceResolutionResult.unknown());

        deferredPriceResolutionJob.resolveForWallet("0xAAA");

        assertThat(event.getFlagCode()).isEqualTo(FlagCode.PRICE_UNKNOWN);
        assertThat(event.isFlagResolved()).isFalse();
        verify(economicEventRepository).save(event);
    }

    @Test
    @DisplayName("Same (contract, date) only triggers one price lookup")
    void deduplicatesByCacheKey() {
        Instant ts1 = Instant.parse("2025-06-15T10:00:00Z");
        Instant ts2 = Instant.parse("2025-06-15T18:00:00Z");
        EconomicEvent event1 = pendingEvent("0xAAA", "0xToken1", ts1);
        EconomicEvent event2 = pendingEvent("0xAAA", "0xToken1", ts2);

        when(economicEventRepository.findByWalletAddressAndFlagCode("0xAAA", FlagCode.PRICE_PENDING))
                .thenReturn(List.of(event1, event2));
        when(historicalPriceResolverChain.resolve(any(HistoricalPriceRequest.class)))
                .thenReturn(PriceResolutionResult.known(new BigDecimal("3000"), PriceSource.COINGECKO));

        deferredPriceResolutionJob.resolveForWallet("0xAAA");

        verify(historicalPriceResolverChain, times(1)).resolve(any(HistoricalPriceRequest.class));
        assertThat(event1.getPriceUsd()).isEqualByComparingTo("3000");
        assertThat(event2.getPriceUsd()).isEqualByComparingTo("3000");
        verify(economicEventRepository, times(2)).save(any(EconomicEvent.class));
    }

    @Test
    @DisplayName("No-op when no PRICE_PENDING events exist")
    void noopWhenNoPendingEvents() {
        when(economicEventRepository.findByWalletAddressAndFlagCode("0xAAA", FlagCode.PRICE_PENDING))
                .thenReturn(Collections.emptyList());

        deferredPriceResolutionJob.resolveForWallet("0xAAA");

        verify(historicalPriceResolverChain, never()).resolve(any());
        verify(economicEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("Different contracts on same date trigger separate lookups")
    void differentContractsSeparateLookups() {
        Instant ts = Instant.parse("2025-06-15T12:00:00Z");
        EconomicEvent event1 = pendingEvent("0xAAA", "0xToken1", ts);
        EconomicEvent event2 = pendingEvent("0xAAA", "0xToken2", ts);

        when(economicEventRepository.findByWalletAddressAndFlagCode("0xAAA", FlagCode.PRICE_PENDING))
                .thenReturn(List.of(event1, event2));
        when(historicalPriceResolverChain.resolve(any(HistoricalPriceRequest.class)))
                .thenReturn(PriceResolutionResult.known(new BigDecimal("100"), PriceSource.COINGECKO));

        deferredPriceResolutionJob.resolveForWallet("0xAAA");

        verify(historicalPriceResolverChain, times(2)).resolve(any(HistoricalPriceRequest.class));
    }

    @Test
    @DisplayName("Events already resolved inline (SWAP_DERIVED) are skipped — no price lookup")
    void skipsInlineResolvedEvents() {
        EconomicEvent inlineResolved = pendingEvent("0xAAA", "0xToken1", Instant.parse("2025-06-15T12:00:00Z"));
        inlineResolved.setPriceUsd(new BigDecimal("90909.09"));
        inlineResolved.setPriceSource(PriceSource.SWAP_DERIVED);

        when(economicEventRepository.findByWalletAddressAndFlagCode("0xAAA", FlagCode.PRICE_PENDING))
                .thenReturn(List.of(inlineResolved));

        deferredPriceResolutionJob.resolveForWallet("0xAAA");

        assertThat(inlineResolved.getPriceUsd()).isEqualByComparingTo("90909.09");
        assertThat(inlineResolved.getPriceSource()).isEqualTo(PriceSource.SWAP_DERIVED);
        verify(historicalPriceResolverChain, never()).resolve(any(HistoricalPriceRequest.class));
        verify(economicEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("Events already resolved inline (STABLECOIN) are skipped — no price lookup")
    void skipsStablecoinResolvedEvents() {
        EconomicEvent stableResolved = pendingEvent("0xAAA", "0xUSDC", Instant.parse("2025-06-15T12:00:00Z"));
        stableResolved.setPriceUsd(BigDecimal.ONE);
        stableResolved.setPriceSource(PriceSource.STABLECOIN);

        when(economicEventRepository.findByWalletAddressAndFlagCode("0xAAA", FlagCode.PRICE_PENDING))
                .thenReturn(List.of(stableResolved));

        deferredPriceResolutionJob.resolveForWallet("0xAAA");

        assertThat(stableResolved.getPriceUsd()).isEqualByComparingTo("1");
        assertThat(stableResolved.getPriceSource()).isEqualTo(PriceSource.STABLECOIN);
        verify(historicalPriceResolverChain, never()).resolve(any(HistoricalPriceRequest.class));
        verify(economicEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("runScheduled finds distinct wallets with PRICE_PENDING, resolves and publishes RecalculateWalletRequestEvent for each")
    void runScheduled_resolvesAndPublishesForEachWallet() {
        when(economicEventRepository.findDistinctWalletAddressesByFlagCode(FlagCode.PRICE_PENDING))
                .thenReturn(List.of("0xA", "0xB"));

        deferredPriceResolutionJob.runScheduled();

        verify(deferredPriceResolutionJob).resolveForWallet(eq("0xA"));
        verify(deferredPriceResolutionJob).resolveForWallet(eq("0xB"));

        ArgumentCaptor<RecalculateWalletRequestEvent> captor = ArgumentCaptor.forClass(RecalculateWalletRequestEvent.class);
        verify(applicationEventPublisher, times(2)).publishEvent(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(RecalculateWalletRequestEvent::walletAddress)
                .containsExactly("0xA", "0xB");
    }

    @Test
    @DisplayName("runScheduled does nothing when no wallets have PRICE_PENDING")
    void runScheduled_emptyList_noResolveNoEvents() {
        when(economicEventRepository.findDistinctWalletAddressesByFlagCode(FlagCode.PRICE_PENDING))
                .thenReturn(Collections.emptyList());

        deferredPriceResolutionJob.runScheduled();

        verify(economicEventRepository, never()).findByWalletAddressAndFlagCode(anyString(), any());
        verify(applicationEventPublisher, never()).publishEvent(any());
    }

    private static EconomicEvent pendingEvent(String wallet, String contract, Instant blockTimestamp) {
        EconomicEvent e = new EconomicEvent();
        e.setWalletAddress(wallet);
        e.setAssetContract(contract);
        e.setNetworkId(NetworkId.ETHEREUM);
        e.setBlockTimestamp(blockTimestamp);
        e.setFlagCode(FlagCode.PRICE_PENDING);
        e.setFlagResolved(false);
        return e;
    }
}
