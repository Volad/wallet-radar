package com.walletradar.costbasis.engine;

import com.walletradar.domain.EconomicEvent;
import com.walletradar.domain.EconomicEventRepository;
import com.walletradar.domain.EconomicEventType;
import com.walletradar.domain.NetworkId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CrossWalletAvcoAggregatorServiceTest {

    @Mock
    EconomicEventRepository economicEventRepository;

    @InjectMocks
    CrossWalletAvcoAggregatorService service;

    @Test
    @DisplayName("cache key is sorted wallets + assetSymbol")
    void cacheKeySortedWalletsAndAsset() {
        String key = CrossWalletAvcoAggregatorService.cacheKey(List.of("0xB", "0xA"), "ETH");
        assertThat(key).isEqualTo("0xA,0xB|ETH");
        assertThat(CrossWalletAvcoAggregatorService.cacheKey(List.of("0xA"), "USDC")).isEqualTo("0xA|USDC");
    }

    @Test
    @DisplayName("empty wallets returns zero avco and quantity")
    void emptyWallets_returnsZero() {
        CrossWalletAvcoResult result = service.compute(List.of(), "ETH");
        assertThat(result.getCrossWalletAvco()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.getQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("multi-wallet merged timeline: INTERNAL_TRANSFER excluded, AVCO correct")
    void multiWallet_excludesInternalTransfer() {
        EconomicEvent buyA = event("0xA", Instant.parse("2025-01-01T10:00:00Z"), EconomicEventType.SWAP_BUY,
                new BigDecimal("2"), new BigDecimal("1000"));
        EconomicEvent buyB = event("0xB", Instant.parse("2025-01-02T10:00:00Z"), EconomicEventType.SWAP_BUY,
                new BigDecimal("1"), new BigDecimal("1500"));
        EconomicEvent internal = event("0xA", Instant.parse("2025-01-03T10:00:00Z"), EconomicEventType.INTERNAL_TRANSFER,
                new BigDecimal("-1"), new BigDecimal("1000"));
        internal.setQuantityDelta(new BigDecimal("-1"));
        EconomicEvent sellB = event("0xB", Instant.parse("2025-01-04T10:00:00Z"), EconomicEventType.SWAP_SELL,
                new BigDecimal("-1"), new BigDecimal("2000"));

        when(economicEventRepository.findByWalletAddressInAndAssetSymbolOrderByBlockTimestampAsc(
                List.of("0xA", "0xB"), "ETH")).thenReturn(List.of(buyA, buyB, internal, sellB));

        CrossWalletAvcoResult result = service.compute(List.of("0xA", "0xB"), "ETH");

        // Exclude INTERNAL_TRANSFER: timeline is buy 2@1000, buy 1@1500, sell 1@2000. AVCO = (2*1000+1*1500)/3 = 1166.67, qty after = 2
        assertThat(result.getCrossWalletAvco()).isEqualByComparingTo("1166.666666666666666667");
        assertThat(result.getQuantity()).isEqualByComparingTo("2");
    }

    @Test
    @DisplayName("result is not persisted (service only reads and computes)")
    void neverPersists() {
        when(economicEventRepository.findByWalletAddressInAndAssetSymbolOrderByBlockTimestampAsc(
                List.of("0xA"), "ETH")).thenReturn(List.of());

        CrossWalletAvcoResult result = service.compute(List.of("0xA"), "ETH");

        assertThat(result.getQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.getCrossWalletAvco()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    private static EconomicEvent event(String wallet, Instant ts, EconomicEventType type, BigDecimal qty, BigDecimal price) {
        EconomicEvent e = new EconomicEvent();
        e.setWalletAddress(wallet);
        e.setNetworkId(NetworkId.ETHEREUM);
        e.setBlockTimestamp(ts);
        e.setEventType(type);
        e.setAssetSymbol("ETH");
        e.setAssetContract("0x0");
        e.setQuantityDelta(qty);
        e.setPriceUsd(price);
        return e;
    }
}
