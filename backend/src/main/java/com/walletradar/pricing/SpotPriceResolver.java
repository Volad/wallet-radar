package com.walletradar.pricing;

import com.fasterxml.jackson.databind.JsonNode;
import com.walletradar.pricing.config.PricingProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * Resolves current (spot) USD price via CoinGecko /simple/price. Used by SnapshotBuilder. Cache TTL 5min per 02-architecture.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SpotPriceResolver {

    private static final int SCALE = 18;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private final PricingProperties pricingProperties;
    private final WebClient.Builder webClientBuilder;

    /**
     * Resolve current USD price for the given token contract. Uses spotPriceCache (5min TTL).
     * Returns empty when contract is not in contractToCoinGeckoId or API fails.
     */
    @Cacheable(cacheNames = "spotPriceCache", key = "#contractAddress")
    public Optional<BigDecimal> resolve(String contractAddress) {
        if (contractAddress == null || contractAddress.isBlank()) {
            return Optional.empty();
        }
        String coinId = pricingProperties.getContractToCoinGeckoId()
                .get(contractAddress.toLowerCase().strip());
        if (coinId == null || coinId.isBlank()) {
            log.debug("No CoinGecko id for spot contract {}", contractAddress);
            return Optional.empty();
        }
        String url = pricingProperties.getCoingeckoBaseUrl() + "/simple/price?ids=" + coinId + "&vs_currencies=usd";
        try {
            WebClient client = webClientBuilder.build();
            String response = client.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            return parseUsdPrice(response, coinId);
        } catch (WebClientResponseException e) {
            log.warn("CoinGecko spot price failed for {}: {}", coinId, e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("CoinGecko spot price error for {}", coinId, e);
            return Optional.empty();
        }
    }

    static Optional<BigDecimal> parseUsdPrice(String json, String coinId) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            JsonNode root = mapper.readTree(json);
            JsonNode coin = root.path(coinId);
            if (coin.isMissingNode()) {
                return Optional.empty();
            }
            JsonNode usd = coin.path("usd");
            if (usd.isMissingNode() || !usd.isNumber()) {
                return Optional.empty();
            }
            return Optional.of(usd.decimalValue().setScale(SCALE, ROUNDING));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
