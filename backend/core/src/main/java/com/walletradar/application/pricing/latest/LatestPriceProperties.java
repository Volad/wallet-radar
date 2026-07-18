package com.walletradar.application.pricing.latest;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Runtime settings for the independent latest-price refresh subsystem.
 */
@ConfigurationProperties(prefix = "walletradar.pricing.latest")
@NoArgsConstructor
@Getter
@Setter
public class LatestPriceProperties {

    /**
     * How often the latest-price refresh job fires (milliseconds).
     * Default: 30 minutes.
     */
    private long refreshIntervalMs = 1_800_000L;

    /**
     * Maximum relative price divergence between two providers before a WARN is emitted.
     * Computed as |a - b| / min(a, b). Default: 5%.
     */
    private double divergenceTolerancePct = 0.05;

    /**
     * A quote older than this (milliseconds) is considered stale.
     * Stale quotes are excluded from divergence comparison but are still served with {@code stale=true}.
     * Default: 90 minutes (3× the refresh interval).
     */
    private long staleAfterMs = 5_400_000L;

    /**
     * Number of days after which a tracked asset entry that has not been refreshed is pruned from the registry.
     * Default: 7 days.
     */
    private int registryPruneTtlDays = 7;
}
