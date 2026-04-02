package com.walletradar.pricing.resolver.external.bybit;

import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.pricing.domain.PriceRequest;
import com.walletradar.pricing.resolver.external.ExternalPriceMappingService;
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
}
