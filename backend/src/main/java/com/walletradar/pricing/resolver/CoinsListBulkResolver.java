package com.walletradar.pricing.resolver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.domain.NetworkId;
import com.walletradar.pricing.config.NetworkIdToCoinGeckoPlatformMapper;
import com.walletradar.pricing.config.PricingProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves contract-to-CoinGecko-ID via GET /coins/list?include_platform=true.
 * Builds (platformId, contractLowercase)→coinId map, cached 24h (configurable).
 * Second in chain after ConfigOverride (ADR-022).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CoinsListBulkResolver implements ContractToCoinGeckoIdResolver {

    private static final String CACHE_KEY = "coins-list";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PricingProperties pricingProperties;
    private final WebClient.Builder webClientBuilder;
    private final com.github.benmanes.caffeine.cache.Cache<String, Map<String, String>> coinsListCache;

    @Override
    public Optional<String> resolve(String contractAddress, NetworkId networkId) {
        if (contractAddress == null || contractAddress.isBlank()) {
            return Optional.empty();
        }
        if (!pricingProperties.getContractMapping().isEnabled()) {
            return Optional.empty();
        }
        String contractLower = contractAddress.toLowerCase().strip();
        Map<String, String> index = getOrFetchIndex();
        if (index == null) {
            return Optional.empty();
        }
        Optional<String> platformId = NetworkIdToCoinGeckoPlatformMapper.toPlatformId(networkId);
        if (platformId.isPresent()) {
            String key = platformId.get() + ":" + contractLower;
            String coinId = index.get(key);
            if (coinId != null) {
                return Optional.of(coinId);
            }
        }
        String contractOnlyKey = ":" + contractLower;
        String coinId = index.get(contractOnlyKey);
        return coinId != null ? Optional.of(coinId) : Optional.empty();
    }

    private Map<String, String> getOrFetchIndex() {
        try {
            return coinsListCache.get(CACHE_KEY, k -> fetchAndBuildIndex());
        } catch (Exception e) {
            log.warn("Coins list cache load failed: {}", e.getMessage());
            return null;
        }
    }

    private Map<String, String> fetchAndBuildIndex() {
        String url = pricingProperties.getCoingeckoBaseUrl() + "/coins/list?include_platform=true";
        try {
            String response = webClientBuilder.build()
                    .get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            return parseAndBuildIndex(response);
        } catch (WebClientResponseException e) {
            log.warn("CoinGecko coins/list failed: {} {}", e.getStatusCode(), e.getMessage());
            throw new RuntimeException("Coins list fetch failed", e);
        } catch (Exception e) {
            log.warn("CoinGecko coins/list error: {}", e.getMessage());
            throw new RuntimeException("Coins list fetch failed", e);
        }
    }

    static Map<String, String> parseAndBuildIndex(String json) {
        Map<String, String> index = new HashMap<>();
        try {
            JsonNode root = MAPPER.readTree(json);
            if (!root.isArray()) {
                return index;
            }
            for (JsonNode coin : root) {
                JsonNode idNode = coin.path("id");
                if (idNode.isMissingNode() || !idNode.isTextual()) {
                    continue;
                }
                String coinId = idNode.asText();
                if (coinId.isBlank()) {
                    continue;
                }
                JsonNode platforms = coin.path("platforms");
                if (platforms.isMissingNode() || !platforms.isObject()) {
                    continue;
                }
                Iterator<String> platformIds = platforms.fieldNames();
                while (platformIds.hasNext()) {
                    String platformId = platformIds.next();
                    JsonNode addrNode = platforms.path(platformId);
                    if (addrNode.isMissingNode() || !addrNode.isTextual()) {
                        continue;
                    }
                    String addr = addrNode.asText();
                    if (addr == null || addr.isBlank()) {
                        continue;
                    }
                    String addrLower = addr.toLowerCase().strip();
                    index.put(platformId + ":" + addrLower, coinId);
                    index.putIfAbsent(":" + addrLower, coinId);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse coins list", e);
        }
        return index;
    }
}
