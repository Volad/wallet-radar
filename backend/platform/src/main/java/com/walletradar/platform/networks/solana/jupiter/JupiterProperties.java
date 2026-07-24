package com.walletradar.platform.networks.solana.jupiter;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Jupiter (Solana) free public API configuration for SPL token metadata resolution and SPL USD
 * pricing. Bound from {@code walletradar.pricing.jupiter.*}.
 *
 * <p>Two endpoints are used:
 * <ul>
 *   <li>Token metadata (mint → symbol/decimals/name): {@code GET /tokens/v2/search?query={mint}}
 *       (returns a JSON array; the element whose {@code id} equals the mint is used)</li>
 *   <li>Price v3 (mint → USD price): {@code GET /price/v3?ids={comma-separated-mints}}</li>
 * </ul>
 *
 * <p>When {@link #apiKey} is set the keyed host {@code api.jup.ag} is used with an {@code x-api-key}
 * header; otherwise the no-key {@code lite-api.jup.ag} host is used. Explicit URL overrides win over
 * both defaults, mirroring {@code HeliusSolanaProperties} URL derivation.</p>
 */
@ConfigurationProperties(prefix = "walletradar.pricing.jupiter")
@NoArgsConstructor
@Getter
@Setter
public class JupiterProperties {

    private static final String KEYED_PRICE_URL = "https://api.jup.ag/price/v3";
    private static final String LITE_PRICE_URL = "https://lite-api.jup.ag/price/v3";
    private static final String KEYED_TOKEN_URL_TEMPLATE = "https://api.jup.ag/tokens/v2/search?query={mint}";
    private static final String LITE_TOKEN_URL_TEMPLATE = "https://lite-api.jup.ag/tokens/v2/search?query={mint}";

    /** Master switch. When false, the resolver and price provider no-op (fall back to mint). */
    private boolean enabled = true;

    /** Jupiter developer-portal API key. When blank, the no-key lite host is used. */
    private String apiKey = "";

    /** Explicit Price v3 URL override; when blank it is derived from {@link #apiKey} presence. */
    private String priceUrl = "";

    /**
     * Explicit token-metadata URL override; must contain the literal {@code {mint}} placeholder
     * (substituted into the {@code query} search parameter). When blank it is derived from
     * {@link #apiKey} presence.
     */
    private String tokenUrl = "";

    /** Per-request read timeout (ms). A hung read is retried as a transient failure. */
    private long timeoutMs = 5_000L;

    /** Minimum interval (ms) between consecutive outbound Jupiter requests (client-side gate). */
    private long minRequestIntervalMs = 250L;

    /** Max mint ids per Price v3 request. Jupiter v3 caps at 50; keep at or below that. */
    private int maxIdsPerRequest = 50;

    /** Caffeine token-metadata cache capacity (mints). */
    private long cacheMaxSize = 10_000L;

    /** Caffeine token-metadata cache time-to-live (hours). */
    private long cacheTtlHours = 24L;

    public boolean usesApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String resolvedPriceUrl() {
        if (priceUrl != null && !priceUrl.isBlank()) {
            return priceUrl.trim();
        }
        return usesApiKey() ? KEYED_PRICE_URL : LITE_PRICE_URL;
    }

    /** @return token-metadata URL for the mint (with {@code {mint}} substituted). */
    public String resolvedTokenUrl(String mint) {
        String template = tokenUrl != null && !tokenUrl.isBlank()
                ? tokenUrl.trim()
                : (usesApiKey() ? KEYED_TOKEN_URL_TEMPLATE : LITE_TOKEN_URL_TEMPLATE);
        return template.replace("{mint}", mint == null ? "" : mint.trim());
    }
}
