package com.walletradar.pricing.resolver;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CoinGeckoHistoricalResolverParseTest {

    @Test
    @DisplayName("parseUsdPrice extracts usd from market_data.current_price")
    void parseUsdPrice() {
        String json = """
                {
                  "market_data": {
                    "current_price": {
                      "usd": 42000.5,
                      "eur": 38000.2
                    }
                  }
                }
                """;
        Optional<BigDecimal> price = CoinGeckoHistoricalResolver.parseUsdPrice(json);
        assertThat(price).isPresent();
        assertThat(price.get()).isEqualByComparingTo("42000.5");
    }

    @Test
    @DisplayName("parseUsdPrice returns empty when usd missing")
    void parseUsdPriceMissingUsd() {
        String json = "{\"market_data\": {\"current_price\": {}}}";
        assertThat(CoinGeckoHistoricalResolver.parseUsdPrice(json)).isEmpty();
    }

    @Test
    @DisplayName("parseUsdPrice returns empty when market_data missing")
    void parseUsdPriceMissingMarketData() {
        String json = "{}";
        assertThat(CoinGeckoHistoricalResolver.parseUsdPrice(json)).isEmpty();
    }
}
