package com.walletradar.platform.networks.ton.metadata;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * TON Center jetton-master metadata API configuration for live jetton {symbol, decimals} resolution
 * (WS-7). Bound from {@code walletradar.ingestion.ton.metadata.*}.
 *
 * <p>Uses the public TON Center v3 {@code GET /jetton/masters?address=...} endpoint. The free tier is
 * rate-limited (~1 rps), so a dedicated client-side throttle spaces outbound requests to avoid a ban
 * during the first full renormalization (mirrors the WS-6 TON price throttle).</p>
 */
@ConfigurationProperties(prefix = "walletradar.ingestion.ton.metadata")
@NoArgsConstructor
@Getter
@Setter
public class TonMetadataProperties {

    /** Master switch. When false, the live TON jetton metadata resolver no-ops. */
    private boolean enabled = true;

    /** TON Center v3 base URL (no trailing slash required). */
    private String baseUrl = "https://toncenter.com/api/v3";

    /** Optional API key; if blank, requests are sent without the X-API-Key header. */
    private String apiKey = "";

    /** Per-request read timeout (ms). A hung read is retried as a transient failure. */
    private long timeoutMs = 10_000L;

    /**
     * Minimum interval (ms) between consecutive outbound requests (client-side gate). Defaults to
     * ~1 rps to stay within the free-tier limit.
     */
    private long minRequestIntervalMs = 1_000L;

    /** @return true when an API key is configured. */
    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
