package com.walletradar.application.pricing.resolver.external.defillama;

import com.fasterxml.jackson.databind.JsonNode;
import com.walletradar.application.pricing.application.ExternalPricingEndpointProperties;
import com.walletradar.domain.common.NetworkId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

/**
 * Minimal client for the DefiLlama coins API (coins.llama.fi).
 * No API key is required; rate limits are generous for read-only access.
 */
@Component
@Slf4j
public class DefiLlamaClient {

    private final WebClient webClient;
    private final Duration requestTimeout;

    public DefiLlamaClient(WebClient.Builder builder, ExternalPricingEndpointProperties endpointProperties) {
        this.webClient = builder.baseUrl(endpointProperties.getDefiLlamaBaseUrl()).build();
        this.requestTimeout = endpointProperties.getRequestTimeout();
    }

    /**
     * Fetches the current USD price for a token identified by chain and contract address.
     *
     * @return empty if the token is unknown, the chain is unsupported, or the request fails
     */
    public Optional<BigDecimal> currentPrice(NetworkId networkId, String contractAddress) {
        String chainSlug = chainSlug(networkId).orElse(null);
        if (chainSlug == null || contractAddress == null || contractAddress.isBlank()) {
            return Optional.empty();
        }
        String coinKey = chainSlug + ":" + contractAddress.trim().toLowerCase(Locale.ROOT);
        try {
            JsonNode body = webClient.get()
                    .uri("/prices/current/{coin}?searchWidth=4h", coinKey)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(requestTimeout);
            return extractPrice(body, coinKey);
        } catch (RuntimeException ex) {
            log.debug("DefiLlama current price failed: coin={} error={}", coinKey, ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Fetches the historical USD price for a token at (approximately) the given timestamp.
     *
     * @return empty if the token is unknown, the chain is unsupported, or the request fails
     */
    public Optional<BigDecimal> historicalPrice(NetworkId networkId, String contractAddress, Instant at) {
        String chainSlug = chainSlug(networkId).orElse(null);
        if (chainSlug == null || contractAddress == null || contractAddress.isBlank() || at == null) {
            return Optional.empty();
        }
        String coinKey = chainSlug + ":" + contractAddress.trim().toLowerCase(Locale.ROOT);
        long epochSeconds = at.getEpochSecond();
        try {
            JsonNode body = webClient.get()
                    .uri("/prices/historical/{ts}/{coin}?searchWidth=4h", epochSeconds, coinKey)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(requestTimeout);
            return extractPrice(body, coinKey);
        } catch (RuntimeException ex) {
            log.debug("DefiLlama historical price failed: coin={} ts={} error={}", coinKey, epochSeconds, ex.getMessage());
            return Optional.empty();
        }
    }

    private Optional<BigDecimal> extractPrice(JsonNode body, String coinKey) {
        if (body == null || body.isMissingNode()) {
            return Optional.empty();
        }
        JsonNode coin = body.path("coins").path(coinKey);
        if (coin.isMissingNode() || coin.isNull()) {
            return Optional.empty();
        }
        JsonNode priceNode = coin.path("price");
        if (priceNode.isMissingNode() || priceNode.isNull()) {
            return Optional.empty();
        }
        BigDecimal price = new BigDecimal(priceNode.asText());
        if (price.signum() <= 0) {
            return Optional.empty();
        }
        return Optional.of(price);
    }

    /**
     * Maps WalletRadar {@link NetworkId} to the DefiLlama chain slug used in coin keys.
     */
    public static Optional<String> chainSlug(NetworkId networkId) {
        if (networkId == null) {
            return Optional.empty();
        }
        String slug = switch (networkId) {
            case ETHEREUM -> "ethereum";
            case ARBITRUM -> "arbitrum";
            case OPTIMISM -> "optimism";
            case POLYGON -> "polygon";
            case BASE -> "base";
            case BSC -> "bsc";
            case AVALANCHE -> "avax";
            case MANTLE -> "mantle";
            case LINEA -> "linea";
            case KATANA -> "katana";
            case UNICHAIN -> "unichain";
            case ZKSYNC -> "era";
            case PLASMA -> "plasma";
            default -> null;
        };
        return Optional.ofNullable(slug);
    }
}
