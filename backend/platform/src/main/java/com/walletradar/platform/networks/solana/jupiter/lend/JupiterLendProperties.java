package com.walletradar.platform.networks.solana.jupiter.lend;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Jupiter Lend Borrow API configuration. Bound from {@code walletradar.lending.jupiter.*}.
 *
 * <p>When {@link #apiKey} is set the keyed host {@code api.jup.ag} is used with an {@code x-api-key}
 * header; otherwise the no-key {@code lite-api.jup.ag} host is used. An explicit {@link #baseUrl}
 * override wins over both, mirroring {@code JupiterProperties} URL derivation.</p>
 */
@ConfigurationProperties(prefix = "walletradar.lending.jupiter")
@NoArgsConstructor
@Getter
@Setter
public class JupiterLendProperties {

    private static final String KEYED_BASE_URL = "https://api.jup.ag";
    private static final String LITE_BASE_URL = "https://lite-api.jup.ag";

    /** Master switch. When false, the reader no-ops (falls back to the guarded synthesized supply). */
    private boolean enabled = true;

    /** Jupiter developer-portal API key (shared with pricing). When blank, the no-key lite host is used. */
    private String apiKey = "";

    /** Explicit base URL override (no trailing slash); when blank it is derived from {@link #apiKey}. */
    private String baseUrl = "";

    /** Borrow market (`main` default; `ethena` also exists). */
    private String market = "main";

    /** Per-request read timeout (ms). A hung read is retried as a transient failure. */
    private long timeoutMs = 5_000L;

    /** Minimum interval (ms) between consecutive outbound Jupiter Lend requests (client-side gate). */
    private long minRequestIntervalMs = 250L;

    public boolean usesApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String resolvedBaseUrl() {
        if (baseUrl != null && !baseUrl.isBlank()) {
            String trimmed = baseUrl.trim();
            return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
        }
        return usesApiKey() ? KEYED_BASE_URL : LITE_BASE_URL;
    }

    public String resolvedMarket() {
        return market == null || market.isBlank() ? "main" : market.trim();
    }
}
