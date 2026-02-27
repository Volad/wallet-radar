package com.walletradar.ingestion.pipeline.enrichment;

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

    @Test
    @DisplayName("Multi-hop swap (>1 SWAP_SELL in same tx): entire group skipped")
    void multiHopSwap_multipleSwapSells_skipped() {
        EconomicEvent sell1 = swapEvent("0xTX_MH", EconomicEventType.SWAP_SELL, USDC_CONTRACT, new BigDecimal("-100"));
        EconomicEvent sell2 = swapEvent("0xTX_MH", EconomicEventType.SWAP_SELL, WBTC_CONTRACT, new BigDecimal("-0.001"));
        EconomicEvent buy = swapEvent("0xTX_MH", EconomicEventType.SWAP_BUY, "0xETH", new BigDecimal("0.05"));

        enricher.enrich(List.of(sell1, sell2, buy));

        assertThat(sell1.getPriceUsd()).isNull();
        assertThat(sell2.getPriceUsd()).isNull();
        assertThat(buy.getPriceUsd()).isNull();
    }

    @Test
    @DisplayName("Multi-hop swap (>1 SWAP_BUY in same tx): entire group skipped")
    void multiHopSwap_multipleSwapBuys_skipped() {
        EconomicEvent sell = swapEvent("0xTX_MH2", EconomicEventType.SWAP_SELL, USDC_CONTRACT, new BigDecimal("-200"));
        EconomicEvent buy1 = swapEvent("0xTX_MH2", EconomicEventType.SWAP_BUY, WBTC_CONTRACT, new BigDecimal("0.001"));
        EconomicEvent buy2 = swapEvent("0xTX_MH2", EconomicEventType.SWAP_BUY, "0xETH", new BigDecimal("0.05"));

        enricher.enrich(List.of(sell, buy1, buy2));

        assertThat(sell.getPriceUsd()).isNull();
        assertThat(buy1.getPriceUsd()).isNull();
        assertThat(buy2.getPriceUsd()).isNull();
    }

    @Test
    @DisplayName("Single leg only (SWAP_SELL, no SWAP_BUY): no-op")
    void singleLeg_noOp() {
        EconomicEvent sell = swapEvent("0xTX_SINGLE", EconomicEventType.SWAP_SELL, USDC_CONTRACT, new BigDecimal("-50"));
        EconomicEvent transfer = swapEvent("0xTX_SINGLE", EconomicEventType.EXTERNAL_INBOUND, WBTC_CONTRACT, new BigDecimal("1"));

        enricher.enrich(List.of(sell, transfer));

        assertThat(sell.getPriceUsd()).isNull();
    }

    @Test
    @DisplayName("Zero volatile quantity: price not set (division by zero avoided)")
    void zeroVolatileQuantity_skipped() {
        when(stablecoinRegistry.isStablecoin(USDC_CONTRACT)).thenReturn(true);
        when(stablecoinRegistry.isStablecoin(WBTC_CONTRACT)).thenReturn(false);

        EconomicEvent sell = swapEvent("0xTX_ZERO", EconomicEventType.SWAP_SELL, USDC_CONTRACT, new BigDecimal("-10"));
        EconomicEvent buy = swapEvent("0xTX_ZERO", EconomicEventType.SWAP_BUY, WBTC_CONTRACT, BigDecimal.ZERO);

        enricher.enrich(List.of(sell, buy));

        assertThat(sell.getPriceUsd()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(buy.getPriceUsd()).isNull();
    }

    @Test
    @DisplayName("Null txHash events are filtered out, no NPE")
    void nullTxHash_filtered() {
        EconomicEvent sell = swapEvent(null, EconomicEventType.SWAP_SELL, USDC_CONTRACT, new BigDecimal("-10"));
        EconomicEvent buy = swapEvent(null, EconomicEventType.SWAP_BUY, WBTC_CONTRACT, new BigDecimal("0.001"));

        enricher.enrich(List.of(sell, buy));

        assertThat(sell.getPriceUsd()).isNull();
        assertThat(buy.getPriceUsd()).isNull();
    }

    @Test
    @DisplayName("Idempotency: calling enrich twice on same list yields same result")
    void idempotency_sameResultOnDoubleCall() {
        when(stablecoinRegistry.isStablecoin(USDC_CONTRACT)).thenReturn(true);
        when(stablecoinRegistry.isStablecoin(WBTC_CONTRACT)).thenReturn(false);

        EconomicEvent sell = swapEvent("0xTX_IDEMP", EconomicEventType.SWAP_SELL, USDC_CONTRACT, new BigDecimal("-10"));
        EconomicEvent buy = swapEvent("0xTX_IDEMP", EconomicEventType.SWAP_BUY, WBTC_CONTRACT, new BigDecimal("0.00011"));

        List<EconomicEvent> events = List.of(sell, buy);
        enricher.enrich(events);

        BigDecimal priceAfterFirst = buy.getPriceUsd();
        BigDecimal totalAfterFirst = buy.getTotalValueUsd();

        enricher.enrich(events);

        assertThat(buy.getPriceUsd()).isEqualByComparingTo(priceAfterFirst);
        assertThat(buy.getTotalValueUsd()).isEqualByComparingTo(totalAfterFirst);
    }

    @Test
    @DisplayName("Null quantityDelta on one leg: group skipped gracefully")
    void nullQuantityDelta_skipped() {
        EconomicEvent sell = swapEvent("0xTX_NULL", EconomicEventType.SWAP_SELL, USDC_CONTRACT, null);
        EconomicEvent buy = swapEvent("0xTX_NULL", EconomicEventType.SWAP_BUY, WBTC_CONTRACT, new BigDecimal("0.001"));

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
