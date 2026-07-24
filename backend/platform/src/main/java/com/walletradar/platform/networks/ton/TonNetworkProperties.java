package com.walletradar.platform.networks.ton;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * TON Center v3 API configuration.
 * Bound from {@code walletradar.ingestion.ton.*}.
 */
@ConfigurationProperties(prefix = "walletradar.ingestion.ton")
@NoArgsConstructor
@Getter
@Setter
public class TonNetworkProperties {

    /** TON Center v3 base URL. */
    private String baseUrl = "https://toncenter.com/api/v3";

    /** Optional API key; if blank, requests are sent without the X-API-Key header. */
    private String apiKey = "";

    /** Max items per page for transactions and jetton-transfer queries. */
    private int pageSize = 100;

    /**
     * Max attempts for the flaky {@code /jetton/transfers} endpoint (free tier returns spurious
     * {@code count=0} / timeouts on consecutive identical calls). Retries cover timeout / 5xx /
     * empty responses before giving up.
     */
    private int jettonFetchMaxAttempts = 3;

    /** Base backoff (ms) between {@code /jetton/transfers} retry attempts (multiplied by attempt). */
    private long jettonFetchBackoffMillis = 300L;

    /** @return true when an API key is configured. */
    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
