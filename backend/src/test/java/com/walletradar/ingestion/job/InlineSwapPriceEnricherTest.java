package com.walletradar.ingestion.job;

import com.walletradar.common.StablecoinRegistry;
import com.walletradar.domain.EconomicEvent;
import com.walletradar.domain.EconomicEventType;
import com.walletradar.domain.NetworkId;
import com.walletradar.domain.PriceSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InlineSwapPriceEnricherTest {

    private static final String USDC_CONTRACT = "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48";
    private static final String USDT_CONTRACT = "0xdac17f958d2ee523a2206206994597c13d831ec7";
    private static final String WBTC_CONTRACT = "0x2260fac5e5542a773aa44fbcfedf7c193bc2c599";

    @Mock
    private StablecoinRegistry stablecoinRegistry;

    @InjectMocks
    private InlineSwapPriceEnricher enricher;

    @Test
    @DisplayName("One stablecoin leg: stablecoin gets $1 STABLECOIN, other gets SWAP_DERIVED price")
    void oneStablecoinLeg_derivesPriceFromSwapRatio() {
        when(stablecoinRegistry.isStablecoin(USDC_CONTRACT)).thenReturn(true);
        when(stablecoinRegistry.isStablecoin(WBTC_CONTRACT)).thenReturn(false);

        EconomicEvent sell = swapEvent("0xTX1", EconomicEventType.SWAP_SELL, USDC_CONTRACT, new BigDecimal("-10"));
        EconomicEvent buy = swapEvent("0xTX1", EconomicEventType.SWAP_BUY, WBTC_CONTRACT, new BigDecimal("0.00011"));

        enricher.enrich(List.of(sell, buy));

        assertThat(sell.getPriceUsd()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(sell.getPriceSource()).isEqualTo(PriceSource.STABLECOIN);
        assertThat(sell.getTotalValueUsd()).isEqualByComparingTo("10");

        assertThat(buy.getPriceUsd()).isEqualByComparingTo(new BigDecimal("10").divide(new BigDecimal("0.00011"), java.math.MathContext.DECIMAL128));
        assertThat(buy.getPriceSource()).isEqualTo(PriceSource.SWAP_DERIVED);
        assertThat(buy.getTotalValueUsd()).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("Neither leg is stablecoin: both events left unchanged")
    void neitherStablecoin_leftUnchanged() {
        when(stablecoinRegistry.isStablecoin(WBTC_CONTRACT)).thenReturn(false);
        String otherContract = "0x1111111111111111111111111111111111111111";
        when(stablecoinRegistry.isStablecoin(otherContract)).thenReturn(false);

        EconomicEvent sell = swapEvent("0xTX2", EconomicEventType.SWAP_SELL, WBTC_CONTRACT, new BigDecimal("-1"));
        EconomicEvent buy = swapEvent("0xTX2", EconomicEventType.SWAP_BUY, otherContract, new BigDecimal("100"));

        enricher.enrich(List.of(sell, buy));

        assertThat(sell.getPriceUsd()).isNull();
        assertThat(sell.getPriceSource()).isNull();
        assertThat(buy.getPriceUsd()).isNull();
        assertThat(buy.getPriceSource()).isNull();
    }

    @Test
    @DisplayName("Both legs are stablecoins: both get priceUsd=1, STABLECOIN")
    void bothStablecoins_bothGetOneDollar() {
        when(stablecoinRegistry.isStablecoin(USDC_CONTRACT)).thenReturn(true);
        when(stablecoinRegistry.isStablecoin(USDT_CONTRACT)).thenReturn(true);

        EconomicEvent sell = swapEvent("0xTX3", EconomicEventType.SWAP_SELL, USDC_CONTRACT, new BigDecimal("-500"));
        EconomicEvent buy = swapEvent("0xTX3", EconomicEventType.SWAP_BUY, USDT_CONTRACT, new BigDecimal("499.8"));

        enricher.enrich(List.of(sell, buy));

        assertThat(sell.getPriceUsd()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(sell.getPriceSource()).isEqualTo(PriceSource.STABLECOIN);
        assertThat(sell.getTotalValueUsd()).isEqualByComparingTo("500");

        assertThat(buy.getPriceUsd()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(buy.getPriceSource()).isEqualTo(PriceSource.STABLECOIN);
        assertThat(buy.getTotalValueUsd()).isEqualByComparingTo("499.8");
    }

    @Test
    @DisplayName("Events from different txHashes are not paired")
    void differentTxHashes_notPaired() {
        EconomicEvent sell = swapEvent("0xTX_A", EconomicEventType.SWAP_SELL, USDC_CONTRACT, new BigDecimal("-10"));
        EconomicEvent buy = swapEvent("0xTX_B", EconomicEventType.SWAP_BUY, WBTC_CONTRACT, new BigDecimal("0.00011"));

        enricher.enrich(List.of(sell, buy));

        assertThat(sell.getPriceUsd()).isNull();
        assertThat(buy.getPriceUsd()).isNull();
    }

    private static EconomicEvent swapEvent(String txHash, EconomicEventType eventType,
                                           String assetContract, BigDecimal quantityDelta) {
        EconomicEvent e = new EconomicEvent();
        e.setTxHash(txHash);
        e.setNetworkId(NetworkId.ETHEREUM);
        e.setWalletAddress("0xWALLET");
        e.setEventType(eventType);
        e.setAssetContract(assetContract);
        e.setQuantityDelta(quantityDelta);
        return e;
    }
}
