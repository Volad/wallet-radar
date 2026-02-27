package com.walletradar.ingestion.config;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * EVM RPC throttling and endpoint cool-down settings for ingestion.
 */
@ConfigurationProperties(prefix = "walletradar.ingestion.evm-rpc")
@NoArgsConstructor
@Getter
@Setter
public class IngestionEvmRpcProperties {

    /** Global EVM RPC budget (requests per second) for this service instance. */
    private int maxRequestsPerSecond = 1_200;

    /** Time to skip an endpoint after rate-limit errors (HTTP 429). */
    private long endpointCooldownMs = 60_000;

    /** Time to skip an endpoint after transient upstream errors (e.g. code 19 / temporary internal error). */
    private long transientErrorCooldownMs = 15_000;

    /** Time to disable JSON-RPC batch mode for an endpoint after non-transient batch errors. */
    private long batchUnsupportedCooldownMs = 300_000;

    /** Log local limiter waits longer than this threshold. */
    private long localLimiterLogThresholdMs = 100;

    /** How long local limiter may wait for a permit before failing the call. */
    private long localLimiterTimeoutMs = 2_000;
}
