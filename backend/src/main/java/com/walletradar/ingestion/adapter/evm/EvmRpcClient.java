package com.walletradar.ingestion.adapter.evm;

import reactor.core.publisher.Mono;

import java.util.List;

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

    /**
     * JSON-RPC batch call: send multiple requests in one HTTP request.
     * Returns the raw JSON array response body.
     *
     * @param endpointUrl RPC endpoint URL
     * @param requests    list of individual RPC requests to batch
     * @return response body as string (JSON array); throws on HTTP error
     */
    Mono<String> batchCall(String endpointUrl, List<RpcRequest> requests);
}
