package com.walletradar.ingestion.config;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Classifier job config (ADR-021). Batch size and schedule for PENDING raw processing.
 */
@ConfigurationProperties(prefix = "walletradar.ingestion.classifier")
@NoArgsConstructor
@Getter
@Setter
public class ClassifierProperties {

    /** Max raw transactions to process per (wallet, network) per run. Default 1000. */
    private int batchSize = 1000;

    /** Schedule interval in ms (fixedDelay). Default 90s. */
    private long scheduleIntervalMs = 90_000;

    /** High-confidence threshold; values below are eligible for receipt enrichment. */
    private double receiptEnrichmentThreshold = 0.85;

    /** Low-confidence threshold; unresolved scores below this value are marked NEEDS_REVIEW. */
    private double needsReviewThreshold = 0.60;

    /** Max attempts for selective receipt enrichment retries. */
    private int receiptEnrichmentMaxAttempts = 3;

    /** Base delay (ms) for receipt enrichment retry backoff. */
    private long receiptEnrichmentBaseDelayMs = 1000;

    /** Jitter factor for receipt enrichment retry delays. */
    private double receiptEnrichmentJitterFactor = 0.2;
}
