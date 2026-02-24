package com.walletradar.ingestion.adapter;

import reactor.core.publisher.Mono;

/**
 * Solana JSON-RPC client abstraction for testing and endpoint rotation.
 * Methods: getSignaturesForAddress, getTransaction. Retries handled by adapter via RpcEndpointRotator.
 */
public interface SolanaRpcClient {

    /**
     * Perform a single Solana JSON-RPC call.
     *
     * @param endpointUrl RPC endpoint URL
     * @param method      e.g. "getSignaturesForAddress", "getTransaction"
     * @param params      method params (array or list)
     * @return response body as string (JSON); throws on HTTP or RPC error
     */
    Mono<String> call(String endpointUrl, String method, Object params);
}
