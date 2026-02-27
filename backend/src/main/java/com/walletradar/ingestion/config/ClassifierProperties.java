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
}
