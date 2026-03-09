package com.walletradar.pricing.resolver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.pricing.config.NetworkIdToCoinGeckoPlatformMapper;
import com.walletradar.pricing.config.PricingProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClient.RequestHeadersSpec;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
    private static final int DEFAULT_WEBCLIENT_BUFFER_BYTES = 256 * 1024;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PricingProperties pricingProperties;
    private final WebClient.Builder webClientBuilder;
    private final com.github.benmanes.caffeine.cache.Cache<String, Map<String, String>> coinsListCache;
    private final ResourceLoader resourceLoader;
    private volatile WebClient coinsListClient;

    @PostConstruct
    void preloadBundledIndex() {
        if (!pricingProperties.getContractMapping().isEnabled()
                || !pricingProperties.getContractMapping().isPreloadBundledIndex()) {
            return;
        }
        Map<String, String> bundledIndex = loadBundledIndex();
        if (bundledIndex.isEmpty()) {
            log.info("No bundled CoinGecko contract index loaded from {}",
                    pricingProperties.getContractMapping().getBundledIndexResourcePath());
            return;
        }
        coinsListCache.put(CACHE_KEY, bundledIndex);
        log.info("Preloaded bundled CoinGecko contract index from {} with {} keys",
                pricingProperties.getContractMapping().getBundledIndexResourcePath(),
                bundledIndex.size());
    }

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
            String response = withApiKey(coinsListClient()
                    .get()
                    .uri(url))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            return parseAndBuildIndex(response);
        } catch (WebClientResponseException e) {
            log.warn("CoinGecko coins/list failed: {} {}", e.getStatusCode(), e.getMessage());
            throw new RuntimeException("Coins list fetch failed", e);
        } catch (Exception e) {
            log.warn("CoinGecko coins/list error (bufferBytes={}): {}",
                    effectiveCoinsListBufferBytes(),
                    e.getMessage());
            throw new RuntimeException("Coins list fetch failed", e);
        }
    }

    private Map<String, String> loadBundledIndex() {
        String resourcePath = pricingProperties.getContractMapping().getBundledIndexResourcePath();
        try {
            Resource resource = resourceLoader.getResource(resourcePath);
            if (!resource.exists()) {
                return Map.of();
            }
            try (InputStream inputStream = resource.getInputStream()) {
                String json = StreamUtils.copyToString(inputStream, StandardCharsets.UTF_8);
                return parseBundledIndex(json);
            }
        } catch (Exception e) {
            log.warn("Failed to load bundled CoinGecko contract index from {}: {}",
                    resourcePath, e.getMessage());
            return Map.of();
        }
    }

    private WebClient coinsListClient() {
        WebClient local = coinsListClient;
        if (local != null) {
            return local;
        }
        synchronized (this) {
            if (coinsListClient == null) {
                coinsListClient = webClientBuilder.clone()
                        .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(effectiveCoinsListBufferBytes()))
                        .build();
            }
            return coinsListClient;
        }
    }

    private int effectiveCoinsListBufferBytes() {
        return Math.max(
                DEFAULT_WEBCLIENT_BUFFER_BYTES,
                pricingProperties.getContractMapping().getCoinsListMaxBufferBytes()
        );
    }

    private RequestHeadersSpec<?> withApiKey(RequestHeadersSpec<?> request) {
        String apiKey = pricingProperties.getCoingeckoApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return request;
        }
        return request.header(pricingProperties.getCoingeckoApiKeyHeader(), apiKey.trim());
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

    static Map<String, String> parseBundledIndex(String json) {
        Map<String, String> index = new HashMap<>();
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode entriesNode = root;
            if (root.isObject() && root.has("entries")) {
                entriesNode = root.path("entries");
            }
            if (!entriesNode.isObject()) {
                return index;
            }
            Iterator<Map.Entry<String, JsonNode>> fields = entriesNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                if (!entry.getValue().isTextual()) {
                    continue;
                }
                String key = entry.getKey();
                String coinId = entry.getValue().asText();
                if (key == null || key.isBlank() || coinId == null || coinId.isBlank()) {
                    continue;
                }
                index.put(key, coinId);
                int separator = key.indexOf(':');
                if (separator >= 0 && separator + 1 < key.length()) {
                    String contractLower = key.substring(separator + 1).toLowerCase().strip();
                    if (!contractLower.isBlank()) {
                        index.putIfAbsent(":" + contractLower, coinId);
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse bundled contract index", e);
        }
        return index;
    }
}
