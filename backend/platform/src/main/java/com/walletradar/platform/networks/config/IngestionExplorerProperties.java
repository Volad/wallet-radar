package com.walletradar.platform.networks.config;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Shared explorer HTTP settings.
 * Per-network explorer sources are configured in
 * {@code walletradar.ingestion.network.<NETWORK>.explorer.*}.
 */
@ConfigurationProperties(prefix = "walletradar.ingestion.explorer")
@NoArgsConstructor
@Getter
@Setter
public class IngestionExplorerProperties {

    /** Maximum response bytes buffered by explorer WebClient. */
    private int maxResponseBytes = 4 * 1024 * 1024;

    /** Maximum pages to scan for a single window call, safety guard. */
    private int maxPagesPerWindow = 10_000;

    /** Retry settings for explorer HTTP calls. */
    private int maxAttempts = 5;
    private long baseDelayMs = 1000;
    private double jitterFactor = 0.2;

    /** HTTP timeout for a single explorer call. Prevents hanging getReceipt/getTransactions calls. */
    private long requestTimeoutMs = 15_000;
}
