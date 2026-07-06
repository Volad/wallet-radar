package com.walletradar.pricing.resolver.external.binance;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
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
                NormalizedTransactionSource.ON_CHAIN,
                NetworkId.BASE,
                "0x4200000000000000000000000000000000000006",
                "WETH",
                java.time.Instant.parse("2026-03-25T10:00:00Z")
        ))).containsExactly("ETHUSDT", "ETHFDUSD", "ETHUSDC", "ETHBUSD");
    }

    @Test
    void wrappedStakedEthFallsBackToStEthAndEthMarkets() {
        BinanceSymbolMapper mapper = new BinanceSymbolMapper(new ExternalPriceMappingService());

        assertThat(mapper.candidateSymbols(new PriceRequest(
                "tx-2",
                NormalizedTransactionSource.ON_CHAIN,
                NetworkId.UNICHAIN,
                "0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2",
                "wstETH",
                java.time.Instant.parse("2026-04-22T10:00:00Z")
        ))).containsExactly(
                "WSTETHUSDT",
                "WSTETHFDUSD",
                "WSTETHUSDC",
                "WSTETHBUSD",
                "STETHUSDT",
                "STETHFDUSD",
                "STETHUSDC",
                "STETHBUSD",
                "ETHUSDT",
                "ETHFDUSD",
                "ETHUSDC",
                "ETHBUSD"
        );
    }
}
