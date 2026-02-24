package com.walletradar.pricing.resolver;

import com.fasterxml.jackson.databind.JsonNode;
import com.walletradar.common.RateLimiter;
import com.walletradar.domain.PriceSource;
import com.walletradar.pricing.HistoricalPriceRequest;
import com.walletradar.pricing.PriceResolutionResult;
import com.walletradar.pricing.config.PricingProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * Resolves historical USD price via CoinGecko /coins/{id}/history. 45 req/min throttle, cache key (contractAddress, date) TTL 24h.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CoinGeckoHistoricalResolver {

    private static final int SCALE = 18;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE; // YYYY-MM-DD

    private final PricingProperties pricingProperties;
    private final WebClient.Builder webClientBuilder;
    private final RateLimiter rateLimiter;

    @Cacheable(cacheNames = "historicalPriceCache", key = "#request.assetContract + '-' + #request.date")
    public PriceResolutionResult resolve(HistoricalPriceRequest request) {
        if (request == null || request.getAssetContract() == null || request.getDate() == null) {
            return PriceResolutionResult.unknown();
        }
        String coinId = pricingProperties.getContractToCoinGeckoId()
                .get(request.getAssetContract().toLowerCase().strip());
        if (coinId == null || coinId.isBlank()) {
            log.debug("No CoinGecko id for contract {}", request.getAssetContract());
            return PriceResolutionResult.unknown();
        }
        rateLimiter.acquire();
        String dateStr = request.getDate().format(DATE_FORMAT);
        String url = pricingProperties.getCoingeckoBaseUrl() + "/coins/" + coinId + "/history?date=" + dateStr + "&localization=false";
        try {
            WebClient client = webClientBuilder.build();
            String response = client.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            return parseUsdPrice(response).map(p -> PriceResolutionResult.known(p, PriceSource.COINGECKO))
                    .orElse(PriceResolutionResult.unknown());
        } catch (WebClientResponseException e) {
            log.warn("CoinGecko history failed for {} date {}: {}", coinId, dateStr, e.getMessage());
            return PriceResolutionResult.unknown();
        } catch (Exception e) {
            log.warn("CoinGecko history error for {} date {}", coinId, dateStr, e);
            return PriceResolutionResult.unknown();
        }
    }

    static Optional<BigDecimal> parseUsdPrice(String json) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            JsonNode root = mapper.readTree(json);
            JsonNode marketData = root.path("market_data");
            JsonNode currentPrice = marketData.path("current_price");
            JsonNode usd = currentPrice.path("usd");
            if (usd.isMissingNode() || !usd.isNumber()) {
                return Optional.empty();
            }
            return Optional.of(usd.decimalValue().setScale(SCALE, ROUNDING));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
