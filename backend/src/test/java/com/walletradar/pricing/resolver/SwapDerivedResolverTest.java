package com.walletradar.pricing.resolver;

import com.walletradar.domain.NetworkId;
import com.walletradar.domain.PriceSource;
import com.walletradar.pricing.HistoricalPriceRequest;
import com.walletradar.pricing.PriceResolutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SwapDerivedResolverTest {

    private CounterpartPriceResolver counterpartPriceResolver;
    private SwapDerivedResolver resolver;

    @BeforeEach
    void setUp() {
        counterpartPriceResolver = mock(CounterpartPriceResolver.class);
        resolver = new SwapDerivedResolver(counterpartPriceResolver);
    }

    @Test
    @DisplayName("when swap leg present and counterpart resolves, returns ratio-derived price")
    void swapLegPresentCounterpartResolved() {
        when(counterpartPriceResolver.resolve(any())).thenReturn(
                PriceResolutionResult.known(new BigDecimal("1.00"), PriceSource.STABLECOIN));

        HistoricalPriceRequest req = new HistoricalPriceRequest();
        req.setAssetContract("0xunknown");
        req.setNetworkId(NetworkId.ETHEREUM);
        req.setBlockTimestamp(Instant.now());
        req.setCounterpartContract("0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48");
        req.setCounterpartAmount(new BigDecimal("1000"));  // 1000 USDC
        req.setOurAmount(new BigDecimal("0.5"));          // 0.5 ETH

        PriceResolutionResult r = resolver.resolve(req);

        assertThat(r.isUnknown()).isFalse();
        assertThat(r.getPriceSource()).isEqualTo(PriceSource.SWAP_DERIVED);
        // 1000 * 1 / 0.5 = 2000
        assertThat(r.getPriceUsd()).hasValueSatisfying(p -> assertThat(p).isEqualByComparingTo(new BigDecimal("2000")));
    }

    @Test
    @DisplayName("when no swap leg returns UNKNOWN")
    void noSwapLegReturnsUnknown() {
        HistoricalPriceRequest req = new HistoricalPriceRequest();
        req.setAssetContract("0xunknown");
        req.setNetworkId(NetworkId.ETHEREUM);
        req.setBlockTimestamp(Instant.now());

        PriceResolutionResult r = resolver.resolve(req);

        assertThat(r.isUnknown()).isTrue();
    }

    @Test
    @DisplayName("when counterpart fails returns UNKNOWN")
    void counterpartFailsReturnsUnknown() {
        when(counterpartPriceResolver.resolve(any())).thenReturn(PriceResolutionResult.unknown());

        HistoricalPriceRequest req = new HistoricalPriceRequest();
        req.setAssetContract("0xunknown");
        req.setNetworkId(NetworkId.ETHEREUM);
        req.setBlockTimestamp(Instant.now());
        req.setCounterpartContract("0xother");
        req.setCounterpartAmount(new BigDecimal("100"));
        req.setOurAmount(new BigDecimal("1"));

        PriceResolutionResult r = resolver.resolve(req);

        assertThat(r.isUnknown()).isTrue();
    }
}
