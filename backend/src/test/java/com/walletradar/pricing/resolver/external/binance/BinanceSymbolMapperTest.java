package com.walletradar.pricing.resolver.external.binance;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.pricing.domain.PriceRequest;
import com.walletradar.pricing.resolver.external.ExternalPriceMappingService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BinanceSymbolMapperTest {

    @Test
    void wrappedAliasMapsToNativeMajorCandidates() {
        BinanceSymbolMapper mapper = new BinanceSymbolMapper(new ExternalPriceMappingService());

        assertThat(mapper.candidateSymbols(new PriceRequest(
                "tx-1",
                NetworkId.BASE,
                "0x4200000000000000000000000000000000000006",
                "WETH",
                java.time.Instant.parse("2026-03-25T10:00:00Z")
        ))).containsExactly("ETHUSDT", "ETHFDUSD", "ETHUSDC", "ETHBUSD");
    }
}
