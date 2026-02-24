package com.walletradar.ingestion.adapter;

import reactor.core.publisher.Mono;

/**
 * EVM JSON-RPC client abstraction for testing and endpoint rotation.
 * Single call with retries is handled by the adapter using RpcEndpointRotator.
 */
public interface EvmRpcClient {

    /**
     * Perform a single JSON-RPC call. Method and params are standard Ethereum JSON-RPC.
     *
     * @param endpointUrl RPC endpoint URL
     * @param method      e.g. "eth_getLogs"
     * @param params      method params (e.g. filter object)
     * @return response body as string (JSON); throws on HTTP or RPC error
     */
    Mono<String> call(String endpointUrl, String method, Object params);
}
