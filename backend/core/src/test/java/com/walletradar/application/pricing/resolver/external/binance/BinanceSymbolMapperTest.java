package com.walletradar.application.pricing.resolver.external.binance;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.application.pricing.domain.PriceRequest;
import com.walletradar.application.pricing.resolver.external.ExternalPriceMappingService;
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
    void wrappedStakedEthMapsToItsOwnSpotPairsOnly() {
        // ADR-054 §6 / plan §7c: wstETH resolves its OWN market price; the STETH/ETH exchange
        // fallback was removed so only WSTETH spot pairs are produced.
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
                "WSTETHBUSD"
        );
    }
}
