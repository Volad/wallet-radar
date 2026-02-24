package com.walletradar.ingestion.config;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Backfill job config (T-009). Window in blocks; ~2 years on Ethereum (12s block) ≈ 5.26M blocks.
 * Worker count aligns with ADR-014 (queue + worker loops).
 */
@ConfigurationProperties(prefix = "walletradar.ingestion.backfill")
@NoArgsConstructor
@Getter
@Setter
public class BackfillProperties {

    /**
     * Number of blocks to backfill from current block (per network). Default ~2 years Ethereum.
     */
    private long windowBlocks = 5_256_000;

    /**
     * Number of worker loops that drain the backfill queue. Default 4 (match backfill-executor core size).
     */
    private int workerThreads = 4;
}
