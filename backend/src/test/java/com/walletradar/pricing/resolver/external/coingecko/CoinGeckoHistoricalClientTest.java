package com.walletradar.pricing.resolver.external.coingecko;

import com.sun.net.httpserver.HttpServer;
import com.walletradar.pricing.application.PricingProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class CoinGeckoHistoricalClientTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void returnsEmptyOnUnauthorizedRateLimitResponses() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/coins/staked-ether/history", exchange -> {
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        server.start();

        PricingProperties properties = new PricingProperties();
        properties.getExternal().getCoinGecko().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        CoinGeckoHistoricalClient client = new CoinGeckoHistoricalClient(WebClient.builder(), properties);

        assertThat(client.fetchHistory("staked-ether", Instant.parse("2026-04-22T10:00:00Z"))).isEmpty();
    }

    @Test
    void returnsUsdPriceWhenResponseContainsHistoryPayload() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/coins/staked-ether/history", exchange -> {
            byte[] body = """
                    {"market_data":{"current_price":{"usd":1842.17}}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(body);
            }
        });
        server.start();

        PricingProperties properties = new PricingProperties();
        properties.getExternal().getCoinGecko().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        CoinGeckoHistoricalClient client = new CoinGeckoHistoricalClient(WebClient.builder(), properties);

        assertThat(client.fetchHistory("staked-ether", Instant.parse("2026-04-22T10:00:00Z")))
                .get()
                .extracting(CoinGeckoHistoricalClient.CoinGeckoHistory::priceUsd)
                .isEqualTo(new java.math.BigDecimal("1842.17"));
    }
}
