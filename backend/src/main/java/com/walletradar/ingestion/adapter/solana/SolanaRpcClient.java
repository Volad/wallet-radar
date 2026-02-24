package com.walletradar.ingestion.adapter.solana;

import reactor.core.publisher.Mono;

/**
 * Solana JSON-RPC client abstraction for testing and endpoint rotation.
 * Methods: getSignaturesForAddress, getTransaction. Retries handled by adapter via RpcEndpointRotator.
 */
public interface SolanaRpcClient {

    Mono<String> call(String endpointUrl, String method, Object params);
}
