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

    @Getter
    @Setter
    public static class ContractMappingProperties {
        /** Enable dynamic lookup from coins/list; when false, only config overrides are used. */
        private boolean enabled = true;
        /** TTL in hours for coins/list cache. */
        private int coinsListCacheTtlHours = 24;
        /** Enable onchain API fallback (skip for MVP; set false per spec). */
        private boolean onchainFallbackEnabled = false;
    }
}
