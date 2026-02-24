package com.walletradar.ingestion.adapter.solana;

import com.walletradar.ingestion.adapter.RpcException;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.Map;

/**
 * Solana JSON-RPC client using WebClient. Same JSON-RPC 2.0 protocol as EVM.
 */
public class WebClientSolanaRpcClient implements SolanaRpcClient {

    private final WebClient webClient;

    public WebClientSolanaRpcClient(WebClient.Builder builder) {
        this.webClient = builder.build();
    }

    @Override
    public reactor.core.publisher.Mono<String> call(String endpointUrl, String method, Object params) {
        Map<String, Object> body = Map.of(
                "jsonrpc", "2.0",
                "id", 1,
                "method", method,
                "params", params != null ? params : new Object[]{}
        );
        return webClient.post()
                .uri(endpointUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .onErrorMap(WebClientResponseException.class, e -> new RpcException(e.getMessage(), e));
    }
}
