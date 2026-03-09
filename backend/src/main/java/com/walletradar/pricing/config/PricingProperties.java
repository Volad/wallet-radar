package com.walletradar.pricing.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * Pricing module configuration. Documented in application.yml under walletradar.pricing.
 */
@ConfigurationProperties(prefix = "walletradar.pricing")
@Getter
@Setter
public class PricingProperties {

    /**
     * CoinGecko API base URL (free: https://api.coingecko.com/api/v3).
     */
    private String coingeckoBaseUrl = "https://api.coingecko.com/api/v3";

    /**
     * Optional CoinGecko API key. When present, sent as a request header.
     */
    private String coingeckoApiKey = "";

    /**
     * Header name for CoinGecko API key. Demo keys use x-cg-demo-api-key, Pro keys use x-cg-pro-api-key.
     */
    private String coingeckoApiKeyHeader = "x-cg-demo-api-key";

    /**
     * Token bucket: requests per minute for historical price (45 leaves headroom for spot).
     */
    private int coingeckoHistoricalRequestsPerMinute = 45;

    /**
     * Connect timeout in seconds for CoinGecko HTTP client.
     */
    private int connectTimeoutSeconds = 10;

    /**
     * Read timeout in seconds for CoinGecko HTTP client.
     */
    private int readTimeoutSeconds = 15;

    /**
     * Map: token contract address (lowercase) -> CoinGecko coin id (e.g. "ethereum", "weth").
     * Used for /coins/{id}/history. Add entries for tokens you need historical prices for.
     * Config overrides take precedence over dynamic lookup (ADR-022).
     */
    private Map<String, String> contractToCoinGeckoId = new HashMap<>();

    /**
     * Dynamic contract-to-CoinGecko-ID mapping (ADR-022).
     */
    private ContractMappingProperties contractMapping = new ContractMappingProperties();

    /**
     * Bundled historical pricing fallback for dates not supported by CoinGecko demo/public API.
     */
    private HistoricalFallbackProperties historicalFallback = new HistoricalFallbackProperties();

    @Getter
    @Setter
    public static class ContractMappingProperties {
        /** Enable dynamic lookup from coins/list; when false, only config overrides are used. */
        private boolean enabled = true;
        /** TTL in hours for coins/list cache. */
        private int coinsListCacheTtlHours = 24;
        /** Max bytes buffered for CoinGecko /coins/list payload. Endpoint is multi-megabyte. */
        private int coinsListMaxBufferBytes = 8 * 1024 * 1024;
        /** Preload a bundled contract index from resources on application startup. */
        private boolean preloadBundledIndex = true;
        /** Resource path to the bundled contract index JSON. */
        private String bundledIndexResourcePath = "classpath:/pricing/coingecko-platform-contract-index.json";
        /** Enable onchain API fallback (skip for MVP; set false per spec). */
        private boolean onchainFallbackEnabled = false;
    }

    @Getter
    @Setter
    public static class HistoricalFallbackProperties {
        /** Enable bundled historical price lookup for dates outside CoinGecko demo/public retention. */
        private boolean enabled = true;
        /** Demo/public CoinGecko history retention window in days. */
        private int maxDemoLookbackDays = 365;
        /** Resource path to the bundled historical price JSON snapshot. */
        private String resourcePath = "classpath:/pricing/missing-price-requests-older-300d-priced.json";
    }
}
