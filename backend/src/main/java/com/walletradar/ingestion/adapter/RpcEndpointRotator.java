package com.walletradar.ingestion.adapter;

import com.walletradar.common.RetryPolicy;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Round-robin RPC endpoint selection with optional retry delay (exponential backoff ±20% jitter).
 * Used by EVM and Solana adapters to spread load and fail over.
 */
public class RpcEndpointRotator {

    private final List<String> endpoints;
    private final AtomicInteger index;
    private final RetryPolicy retryPolicy;

    public RpcEndpointRotator(List<String> endpoints, RetryPolicy retryPolicy) {
        if (endpoints == null || endpoints.isEmpty()) {
            throw new IllegalArgumentException("At least one endpoint required");
        }
        this.endpoints = List.copyOf(endpoints);
        this.index = new AtomicInteger(0);
        this.retryPolicy = retryPolicy != null ? retryPolicy : RetryPolicy.defaultPolicy();
    }

    /**
     * Next endpoint in round-robin order.
     */
    public String getNextEndpoint() {
        int i = index.getAndIncrement() % endpoints.size();
        if (i < 0) {
            i += endpoints.size();
        }
        return endpoints.get(i);
    }

    /**
     * Delay in ms before retrying after the given attempt (0-based).
     */
    public long retryDelayMs(int attempt) {
        return retryPolicy.delayMs(attempt);
    }

    public int getMaxAttempts() {
        return retryPolicy.getMaxAttempts();
    }

    public List<String> getEndpoints() {
        return endpoints;
    }
}
