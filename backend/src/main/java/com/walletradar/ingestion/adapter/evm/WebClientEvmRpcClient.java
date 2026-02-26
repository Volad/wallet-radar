package com.walletradar.ingestion.adapter.evm;

import com.walletradar.ingestion.adapter.RpcException;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * EVM JSON-RPC client using WebClient. Used by EvmNetworkAdapter.
 */
public class WebClientEvmRpcClient implements EvmRpcClient {

    private final WebClient webClient;

    public WebClientEvmRpcClient(WebClient.Builder builder) {
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

    @Override
    public reactor.core.publisher.Mono<String> batchCall(String endpointUrl, List<RpcRequest> requests) {
        List<Map<String, Object>> batch = new ArrayList<>();
        for (int i = 0; i < requests.size(); i++) {
            RpcRequest req = requests.get(i);
            batch.add(Map.of(
                    "jsonrpc", "2.0",
                    "id", i + 1,
                    "method", req.method(),
                    "params", req.params() != null ? req.params() : new Object[]{}
            ));
        }
        return webClient.post()
                .uri(endpointUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(batch)
                .retrieve()
                .bodyToMono(String.class)
                .onErrorMap(WebClientResponseException.class, e -> new RpcException(e.getMessage(), e));
    }
}
