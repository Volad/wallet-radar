package com.walletradar.pricing.resolver;

import com.walletradar.domain.NetworkId;
import com.walletradar.pricing.HistoricalPriceRequest;
import com.walletradar.pricing.PriceResolutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class StablecoinResolverTest {

    private StablecoinResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new StablecoinResolver(new com.walletradar.common.StablecoinRegistry());
    }

    @Test
    @DisplayName("USDC returns $1.00 and STABLECOIN source")
    void usdcReturnsOneDollar() {
        HistoricalPriceRequest req = new HistoricalPriceRequest();
        req.setAssetContract("0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48");
        req.setNetworkId(NetworkId.ETHEREUM);
        req.setBlockTimestamp(Instant.now());

        PriceResolutionResult r = resolver.resolve(req);

        assertThat(r.isUnknown()).isFalse();
        assertThat(r.getPriceUsd()).contains(BigDecimal.ONE.setScale(18, java.math.RoundingMode.HALF_UP));
        assertThat(r.getPriceSource()).isEqualTo(com.walletradar.domain.PriceSource.STABLECOIN);
    }

    @Test
    @DisplayName("unknown contract returns UNKNOWN")
    void unknownContractReturnsUnknown() {
        HistoricalPriceRequest req = new HistoricalPriceRequest();
        req.setAssetContract("0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2");
        req.setNetworkId(NetworkId.ETHEREUM);
        req.setBlockTimestamp(Instant.now());

        PriceResolutionResult r = resolver.resolve(req);

        assertThat(r.isUnknown()).isTrue();
    }

    @Test
    @DisplayName("null request returns UNKNOWN")
    void nullRequestReturnsUnknown() {
        assertThat(resolver.resolve(null).isUnknown()).isTrue();
    }
}
