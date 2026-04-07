package com.walletradar.ingestion.adapter.evm.rpc;

import com.walletradar.ingestion.adapter.RpcException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * EVM JSON-RPC client using WebClient. Used by EvmNetworkAdapter.
 */
@Slf4j
public class WebClientEvmRpcClient implements EvmRpcClient {

    private static final int ERROR_BODY_LOG_LIMIT = 1000;

    private final WebClient webClient;

    public WebClientEvmRpcClient(WebClient.Builder builder, int maxResponseBytes) {
        int maxBytes = Math.max(256 * 1024, maxResponseBytes);
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(cfg -> cfg.defaultCodecs().maxInMemorySize(maxBytes))
                .build();
        this.webClient = builder.clone()
                .exchangeStrategies(strategies)
                .build();
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
                .onErrorMap(WebClientResponseException.class, e -> {
                    logHttp400(endpointUrl, method, e);
                    return toRpcException(endpointUrl, method, e);
                })
                .doOnError(e -> logRpcFailure(endpointUrl, method, e));
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
                .onErrorMap(WebClientResponseException.class, e -> {
                    String operation = requests.isEmpty() ? "batch" : requests.get(0).method() + "(batch)";
                    logHttp400(endpointUrl, operation, e);
                    return toRpcException(endpointUrl, operation, e);
                })
                .doOnError(e -> {
                    String operation = requests.isEmpty() ? "batch" : requests.get(0).method() + "(batch)";
                    logRpcFailure(endpointUrl, operation, e);
                });
    }

    private void logHttp400(String endpointUrl, String operation, WebClientResponseException e) {
        if (e.getRawStatusCode() != 400) {
            return;
        }
        String body = trimForLog(e.getResponseBodyAsString());
//        log.warn("RPC HTTP 400 for operation={} endpointHost={} responseBody={}",
//                operation, endpointHost(endpointUrl), body);
    }

    private static String endpointHost(String endpointUrl) {
        try {
            URI uri = URI.create(endpointUrl);
            String host = uri.getHost();
            return host != null ? host : endpointUrl;
        } catch (Exception ex) {
            return endpointUrl;
        }
    }

    private static String trimForLog(String value) {
        if (value == null || value.isBlank()) {
            return "<empty>";
        }
        if (value.length() <= ERROR_BODY_LOG_LIMIT) {
            return value;
        }
        return value.substring(0, ERROR_BODY_LOG_LIMIT) + "...";
    }

    private RpcException toRpcException(String endpointUrl, String operation, WebClientResponseException e) {
        if (e.getRawStatusCode() == 400) {
            String body = trimForLog(e.getResponseBodyAsString());
            String msg = "RPC HTTP 400 for operation=" + operation
                    + " endpointHost=" + endpointHost(endpointUrl)
                    + " body=" + body;
            return new RpcException(msg, e);
        }
        return new RpcException(e.getMessage(), e);
    }

    private void logRpcFailure(String endpointUrl, String operation, Throwable error) {
        Throwable root = rootCause(error);
        log.warn(
                "RPC transport failure endpointHost={} operation={} errorClass={} message={} rootCauseClass={} rootCauseMessage={}",
                endpointHost(endpointUrl),
                operation,
                error == null ? "unknown" : error.getClass().getName(),
                trimForLog(error == null ? null : error.getMessage()),
                root == null ? "unknown" : root.getClass().getName(),
                trimForLog(root == null ? null : root.getMessage()),
                error
        );
    }

    private Throwable rootCause(Throwable error) {
        if (error == null) {
            return null;
        }
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}
