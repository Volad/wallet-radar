package com.walletradar.platform.networks.solana.helius;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Helius Enhanced Transactions REST API configuration.
 * Bound from {@code walletradar.ingestion.solana.helius.*}.
 *
 * <p>URLs can be specified explicitly via {@code parse-transactions-url} /
 * {@code parse-transactions-history-url}, <em>or</em> derived automatically when
 * only {@code api-key} is provided (uses standard Helius mainnet endpoints).</p>
 *
 * <p>The history URL contains a literal <code>{address}</code> placeholder that is replaced
 * at runtime with the wallet address.</p>
 */
@ConfigurationProperties(prefix = "walletradar.ingestion.solana.helius")
@NoArgsConstructor
@Getter
@Setter
public class HeliusSolanaProperties {

    private static final String HISTORY_URL_TEMPLATE =
            "https://api-mainnet.helius-rpc.com/v0/addresses/{address}/transactions?api-key=";
    private static final String PARSE_URL_TEMPLATE =
            "https://api-mainnet.helius-rpc.com/v0/transactions?api-key=";

    /** Standalone API key — used to derive URLs when explicit URL props are not set. */
    private String apiKey = "";

    /**
     * Minimum interval (ms) between consecutive outbound Helius requests (Enhanced REST + RPC ATA
     * path), enforced client-side by {@link HeliusRequestThrottle}. Default 250 ms ⇒ ≤ 4 req/s,
     * comfortably under Helius limits, so a single-segment 2-year backfill burst does not trigger
     * HTTP 429. Set ≤ 0 to disable throttling.
     */
    private long minRequestIntervalMillis = 250L;

    /**
     * Helius batch parse endpoint: {@code POST https://api-mainnet.helius-rpc.com/v0/transactions?api-key=…}.
     * Accepts a JSON body of {@code {"transactions": [sig1, sig2, …]}}.
     * When blank, derived from {@code api-key}.
     */
    private String parseTransactionsUrl = "";

    /**
     * Helius enhanced transaction history endpoint template.
     * Contains {@code {address}} placeholder, e.g.
     * {@code https://api-mainnet.helius-rpc.com/v0/addresses/{address}/transactions?api-key=…}.
     * When blank, derived from {@code api-key}.
     */
    private String parseTransactionsHistoryUrl = "";

    public String resolvedParseTransactionsHistoryUrl() {
        if (parseTransactionsHistoryUrl != null && !parseTransactionsHistoryUrl.isBlank()) {
            return parseTransactionsHistoryUrl;
        }
        if (apiKey != null && !apiKey.isBlank()) {
            return HISTORY_URL_TEMPLATE + apiKey;
        }
        return "";
    }

    public String resolvedParseTransactionsUrl() {
        if (parseTransactionsUrl != null && !parseTransactionsUrl.isBlank()) {
            return parseTransactionsUrl;
        }
        if (apiKey != null && !apiKey.isBlank()) {
            return PARSE_URL_TEMPLATE + apiKey;
        }
        return "";
    }

    /** @return true when Helius REST endpoints are configured (non-blank). */
    public boolean isConfigured() {
        return !resolvedParseTransactionsHistoryUrl().isBlank();
    }
}
