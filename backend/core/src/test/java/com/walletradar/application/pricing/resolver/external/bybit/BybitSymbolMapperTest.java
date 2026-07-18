package com.walletradar.application.pricing.resolver.external.bybit;

import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.application.pricing.domain.PriceRequest;
import com.walletradar.application.pricing.resolver.external.ExternalPriceMappingService;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class BybitSymbolMapperTest {

    @Test
    void canonicalMantleMapsToBybitSpotCandidates() {
        BybitSymbolMapper mapper = new BybitSymbolMapper(new ExternalPriceMappingService());

        assertThat(mapper.candidateSymbols(new PriceRequest(
                "BYBIT:tx-1",
                NormalizedTransactionSource.BYBIT,
                null,
                null,
                "WMNT",
                Instant.parse("2025-01-14T12:43:33Z")
        ))).containsExactly("MNTUSDT", "MNTUSDC");
    }

    @Test
    void wrappedStakedEthMapsToItsOwnSpotPairsOnly() {
        // ADR-054 §6 / plan §7c: wstETH resolves its OWN market price; the STETH/ETH exchange
        // fallback was removed so only WSTETH spot pairs are produced.
        BybitSymbolMapper mapper = new BybitSymbolMapper(new ExternalPriceMappingService());

        assertThat(mapper.candidateSymbols(new PriceRequest(
                "tx-2",
                NormalizedTransactionSource.ON_CHAIN,
                null,
                null,
                "wstETH",
                Instant.parse("2026-04-22T10:00:00Z")
        ))).containsExactly(
                "WSTETHUSDT",
                "WSTETHUSDC"
        );
    }

    @Test
    void auditedAaveAvaxReceiptMapsToAvaxSpotCandidates() {
        BybitSymbolMapper mapper = new BybitSymbolMapper(new ExternalPriceMappingService());

        assertThat(mapper.candidateSymbols(new PriceRequest(
                "tx-3",
                NormalizedTransactionSource.ON_CHAIN,
                null,
                null,
                "aAvaSAVAX",
                Instant.parse("2025-11-27T16:24:20Z")
        ))).containsExactly("AVAXUSDT", "AVAXUSDC");
    }
}
