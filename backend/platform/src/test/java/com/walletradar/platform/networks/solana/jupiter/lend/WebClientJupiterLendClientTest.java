package com.walletradar.platform.networks.solana.jupiter.lend;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the Jupiter Lend rate decode: the Borrow API encodes supply/borrow rates in basis points
 * ({@code 458 → 4.58%}). Cross-checked against the Earn API where {@code rewardsRate + supplyRate ==
 * totalRate} ({@code 71 + 351 == 422}).
 */
class WebClientJupiterLendClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void basisPointRateDecodesToPercent() throws Exception {
        assertThat(WebClientJupiterLendClient.rateFraction(objectMapper.readTree("458")))
                .isEqualByComparingTo("4.58");
        assertThat(WebClientJupiterLendClient.rateFraction(objectMapper.readTree("446")))
                .isEqualByComparingTo("4.46");
        assertThat(WebClientJupiterLendClient.rateFraction(objectMapper.readTree("351")))
                .isEqualByComparingTo("3.51");
    }

    @Test
    void absentRateIsNull() {
        assertThat(WebClientJupiterLendClient.rateFraction(null)).isNull();
    }
}
