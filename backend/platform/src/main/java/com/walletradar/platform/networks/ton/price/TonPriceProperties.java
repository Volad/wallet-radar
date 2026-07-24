package com.walletradar.platform.networks.ton.price;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * STON.fi (TON) free public price API configuration for jetton USD marks. Bound from
 * {@code walletradar.pricing.ton.*}.
 *
 * <p>Uses the public, un-authenticated {@code GET /v1/assets} endpoint which returns every DEX asset
 * with its {@code contract_address} and {@code dex_price_usd}. The full list is fetched once per
 * refresh cycle (mirroring the CEX bulk-ticker providers) and filtered to the held jettons.</p>
 */
@ConfigurationProperties(prefix = "walletradar.pricing.ton")
@NoArgsConstructor
@Getter
@Setter
public class TonPriceProperties {

    /** Master switch. When false, the TON jetton price provider no-ops. */
    private boolean enabled = true;

    /** STON.fi v1 base URL (no trailing slash required). */
    private String baseUrl = "https://api.ston.fi/v1";

    /** Per-request read timeout (ms). A hung read is retried as a transient failure. */
    private long timeoutMs = 10_000L;

    /**
     * Minimum interval (ms) between consecutive outbound STON.fi requests (client-side gate). The
     * feed is fetched at most once per refresh cycle so this only guards against accidental bursts.
     */
    private long minRequestIntervalMs = 250L;
}
