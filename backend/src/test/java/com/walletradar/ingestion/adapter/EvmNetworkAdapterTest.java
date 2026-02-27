package com.walletradar.ingestion.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.domain.NetworkId;
import com.walletradar.domain.RawTransaction;
import com.walletradar.common.RetryPolicy;
import com.walletradar.ingestion.config.IngestionNetworkProperties;
import com.walletradar.ingestion.config.IngestionEvmRpcProperties;
import com.walletradar.ingestion.adapter.evm.EvmBatchBlockSizeResolver;
import com.walletradar.ingestion.adapter.evm.EvmNetworkAdapter;
import com.walletradar.ingestion.adapter.evm.EvmRpcClient;
import com.walletradar.ingestion.adapter.evm.RpcRequest;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvmNetworkAdapterTest {

    private EvmNetworkAdapter adapter;
    private MockEvmRpcClient mockRpc;
    private RpcEndpointRotator rotator;
    private IngestionEvmRpcProperties evmRpcProperties;

    @BeforeEach
    void setUp() {
        mockRpc = new MockEvmRpcClient();
        rotator = new RpcEndpointRotator(List.of("https://test.rpc"), RetryPolicy.defaultPolicy());
        IngestionNetworkProperties properties = new IngestionNetworkProperties();
        EvmBatchBlockSizeResolver resolver = new EvmBatchBlockSizeResolver(properties);
        Map<String, RpcEndpointRotator> rotatorsByNetwork = Map.of("ETHEREUM", rotator, "ARBITRUM", rotator);
        evmRpcProperties = evmRpcProps();
        adapter = new EvmNetworkAdapter(mockRpc, rotatorsByNetwork, rotator, fastLimiter(), evmRpcProperties, new ObjectMapper(), resolver);
    }

    @Test
    void supports_ethereum_returnsTrue() {
        assertThat(adapter.supports(NetworkId.ETHEREUM)).isTrue();
        assertThat(adapter.supports(NetworkId.ARBITRUM)).isTrue();
    }

    @Test
    void supports_solana_returnsFalse() {
        assertThat(adapter.supports(NetworkId.SOLANA)).isFalse();
    }

    @Test
    void getMaxBlockBatchSize_returns2000() {
        assertThat(adapter.getMaxBlockBatchSize()).isEqualTo(2000);
    }

    @Test
    void fetchTransactions_emptyRange_returnsEmpty() {
        List<RawTransaction> result = adapter.fetchTransactions("0x1234", NetworkId.ETHEREUM, 100L, 50L);
        assertThat(result).isEmpty();
    }

    @Test
    void fetchTransactions_mockReturnsLogs_groupsByTxHash() {
        String walletTopic = "0x0000000000000000000000000000000000000000000000000000000000001234";
        String logsJson = """
                {"jsonrpc":"2.0","id":1,"result":[
                  {"transactionHash":"0xabc","blockNumber":"0x64","address":"0xtoken","topics":["0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef","%s","0x0000000000000000000000000000000000000000000000000000000000005678"],"data":"0x1","logIndex":"0x0"}
                ]}
                """.formatted(walletTopic);
        String receiptJson = """
                {"jsonrpc":"2.0","id":1,"result":{"blockNumber":"0x64","blockHash":"0xhash","logs":[
                  {"transactionHash":"0xabc","blockNumber":"0x64","address":"0xtoken","topics":["0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef","%s","0x0000000000000000000000000000000000000000000000000000000000005678"],"data":"0x1","logIndex":"0x0"}
                ],"gasUsed":"0x5208","transactionHash":"0xabc"}}
                """.formatted(walletTopic);
        mockRpc.setResponse(logsJson);
        mockRpc.setReceiptResponse(receiptJson);

        List<RawTransaction> result = adapter.fetchTransactions("0x1234", NetworkId.ETHEREUM, 1L, 1L);

        assertThat(result).hasSize(1);
        RawTransaction tx = result.get(0);
        assertThat(tx.getTxHash()).isEqualTo("0xabc");
        assertThat(tx.getNetworkId()).isEqualTo("ETHEREUM");
        assertThat(tx.getWalletAddress()).isEqualTo("0x1234");
        assertThat(tx.getBlockNumber()).isEqualTo(100L);
        assertThat(tx.getRawData()).isNotNull();
        assertThat(tx.getRawData().get("blockNumber")).isNotNull();
        assertThat(tx.getRawData().getList("logs", org.bson.Document.class)).hasSizeGreaterThanOrEqualTo(1);
    }

    @Test
    void fetchTransactions_rpcError_throwsAfterRetries() {
        mockRpc.setResponse("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32005,\"message\":\"rate limit\"}}");

        assertThatThrownBy(() -> adapter.fetchTransactions("0x1234", NetworkId.ETHEREUM, 1L, 1L))
                .isInstanceOf(RpcException.class)
                .hasMessageContaining("RPC failed after");
    }

    @Test
    void fetchTransactions_unknownNetworkId_usesDefaultBatchSizeWithoutFailure() {
        IngestionNetworkProperties properties = new IngestionNetworkProperties();
        properties.setNetwork(Map.of("ARBITRUM", entry(500)));
        EvmBatchBlockSizeResolver resolver = new EvmBatchBlockSizeResolver(properties);
        RpcEndpointRotator r = new RpcEndpointRotator(List.of("https://test.rpc"), RetryPolicy.defaultPolicy());
        EvmNetworkAdapter adapterWithResolver = new EvmNetworkAdapter(mockRpc, Map.of("ARBITRUM", r), r, fastLimiter(), evmRpcProps(), new ObjectMapper(), resolver);

        List<RawTransaction> result = adapterWithResolver.fetchTransactions("0x1234", NetworkId.POLYGON, 1L, 10L);
        assertThat(result).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "eth_getLogs error: {\"code\":-32701,\"message\":\"Please specify an address\"}",
            "query returned more than 10000 results",
            "block range is too wide",
            "exceed maximum block range",
            "log response size exceeded",
            "too many results in block range"
    })
    void isRangeTooWideError_recognizesKnownPatterns(String message) {
        assertThat(EvmNetworkAdapter.isRangeTooWideError(new RuntimeException(message))).isTrue();
    }

    @Test
    void isRangeTooWideError_returnsFalseForUnrelatedError() {
        assertThat(EvmNetworkAdapter.isRangeTooWideError(new RuntimeException("connection timeout"))).isFalse();
        assertThat(EvmNetworkAdapter.isRangeTooWideError(null)).isFalse();
        assertThat(EvmNetworkAdapter.isRangeTooWideError(new RuntimeException())).isFalse();
    }

    @Test
    void fetchTransactions_rangeTooWideError_splitsAndRetries() {
        AtomicInteger callCount = new AtomicInteger(0);
        String emptyResult = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":[]}";
        String rangeTooWideError = "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32701,\"message\":\"Please specify an address in your request\"}}";

        EvmRpcClient splittingRpc = new EvmRpcClient() {
            @Override
            public Mono<String> call(String endpointUrl, String method, Object params) {
                if (!"eth_getLogs".equals(method)) return Mono.just(emptyResult);
                int call = callCount.incrementAndGet();
                if (call <= 2) return Mono.just(rangeTooWideError);
                return Mono.just(emptyResult);
            }
            @Override
            public Mono<String> batchCall(String endpointUrl, List<RpcRequest> requests) {
                // Simulate batch returning the range-too-wide error for the first batch call
                int call = callCount.incrementAndGet();
                if (call <= 2) {
                    return Mono.just("[" +
                            "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32701,\"message\":\"Please specify an address in your request\"}}," +
                            "{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":{\"code\":-32701,\"message\":\"Please specify an address in your request\"}}" +
                            "]");
                }
                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < requests.size(); i++) {
                    if (i > 0) sb.append(",");
                    sb.append("{\"jsonrpc\":\"2.0\",\"id\":").append(i + 1).append(",\"result\":[]}");
                }
                sb.append("]");
                return Mono.just(sb.toString());
            }
        };

        RetryPolicy policy = new RetryPolicy(0, 0.0, 3);
        RpcEndpointRotator r = new RpcEndpointRotator(List.of("https://test.rpc"), policy);
        EvmBatchBlockSizeResolver resolver = new EvmBatchBlockSizeResolver(new IngestionNetworkProperties());
        EvmNetworkAdapter splittingAdapter = new EvmNetworkAdapter(splittingRpc, Map.of("ETHEREUM", r), r, fastLimiter(), evmRpcProps(), new ObjectMapper(), resolver);

        // Range of 200 blocks — large enough to split (> MIN_CHUNK_SIZE=50)
        List<RawTransaction> result = splittingAdapter.fetchTransactions("0x1234", NetworkId.ETHEREUM, 1L, 200L);
        assertThat(result).isNotNull();
        // The adapter must have made more calls than the initial 2 (it split and retried)
        assertThat(callCount.get()).isGreaterThan(2);
    }

    @Test
    void fetchTransactions_rangeTooWideError_tooSmallToSplit_propagatesError() {
        String rangeTooWideError = "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32701,\"message\":\"Please specify an address in your request\"}}";

        MockEvmRpcClient errorRpc = new MockEvmRpcClient();
        errorRpc.setResponse(rangeTooWideError);

        RetryPolicy policy = new RetryPolicy(0, 0.0, 3);
        RpcEndpointRotator r = new RpcEndpointRotator(List.of("https://test.rpc"), policy);
        EvmBatchBlockSizeResolver resolver = new EvmBatchBlockSizeResolver(new IngestionNetworkProperties());
        EvmNetworkAdapter smallRangeAdapter = new EvmNetworkAdapter(errorRpc, Map.of("ETHEREUM", r), r, fastLimiter(), evmRpcProps(), new ObjectMapper(), resolver);

        // Range of 10 blocks — too small to split (< MIN_CHUNK_SIZE=50), should propagate error
        assertThatThrownBy(() -> smallRangeAdapter.fetchTransactions("0x1234", NetworkId.ETHEREUM, 1L, 10L))
                .isInstanceOf(RpcException.class)
                .hasMessageContaining("RPC failed after");
    }

    @Test
    void fetchTransactions_batchGetLogs_usedByDefault() {
        AtomicInteger batchCallCount = new AtomicInteger(0);
        AtomicInteger singleCallCount = new AtomicInteger(0);
        String emptyResult = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":[]}";
        String batchLogsEmpty = "[{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":[]},{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":[]}]";

        EvmRpcClient trackingRpc = new EvmRpcClient() {
            @Override
            public Mono<String> call(String endpointUrl, String method, Object params) {
                singleCallCount.incrementAndGet();
                return Mono.just(emptyResult);
            }
            @Override
            public Mono<String> batchCall(String endpointUrl, List<RpcRequest> requests) {
                batchCallCount.incrementAndGet();
                return Mono.just(batchLogsEmpty);
            }
        };

        RpcEndpointRotator r = new RpcEndpointRotator(List.of("https://test.rpc"), RetryPolicy.defaultPolicy());
        EvmBatchBlockSizeResolver resolver = new EvmBatchBlockSizeResolver(new IngestionNetworkProperties());
        EvmNetworkAdapter batchAdapter = new EvmNetworkAdapter(trackingRpc, Map.of("ETHEREUM", r), r, fastLimiter(), evmRpcProps(), new ObjectMapper(), resolver);

        batchAdapter.fetchTransactions("0x1234", NetworkId.ETHEREUM, 1L, 1L);

        assertThat(batchCallCount.get()).as("batchCall should be used for eth_getLogs").isGreaterThanOrEqualTo(1);
        assertThat(singleCallCount.get()).as("single call should not be used when batch succeeds").isEqualTo(0);
    }

    @Test
    void fetchTransactions_batchFails_fallsBackToSequential() {
        AtomicInteger singleCallCount = new AtomicInteger(0);
        String emptyResult = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":[]}";

        EvmRpcClient failBatchRpc = new EvmRpcClient() {
            @Override
            public Mono<String> call(String endpointUrl, String method, Object params) {
                singleCallCount.incrementAndGet();
                return Mono.just(emptyResult);
            }
            @Override
            public Mono<String> batchCall(String endpointUrl, List<RpcRequest> requests) {
                return Mono.error(new RpcException("Batch not supported by this RPC"));
            }
        };

        RpcEndpointRotator r = new RpcEndpointRotator(List.of("https://test.rpc"), RetryPolicy.defaultPolicy());
        EvmBatchBlockSizeResolver resolver = new EvmBatchBlockSizeResolver(new IngestionNetworkProperties());
        EvmNetworkAdapter fallbackAdapter = new EvmNetworkAdapter(failBatchRpc, Map.of("ETHEREUM", r), r, fastLimiter(), evmRpcProps(), new ObjectMapper(), resolver);

        List<RawTransaction> result = fallbackAdapter.fetchTransactions("0x1234", NetworkId.ETHEREUM, 1L, 1L);

        assertThat(result).isEmpty();
        assertThat(singleCallCount.get()).as("sequential calls used as fallback").isGreaterThanOrEqualTo(2);
    }

    @Test
    void fetchTransactions_batchReceipts_mapsLogsCorrectly() {
        String walletTopic = "0x0000000000000000000000000000000000000000000000000000000000001234";
        String transferTopic = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";

        String batchLogsResponse = """
                [
                  {"jsonrpc":"2.0","id":1,"result":[
                    {"transactionHash":"0xaaa","blockNumber":"0x1","address":"0xtoken","topics":["%s","%s","0x0000000000000000000000000000000000000000000000000000000000005678"],"data":"0x1","logIndex":"0x0"}
                  ]},
                  {"jsonrpc":"2.0","id":2,"result":[]}
                ]
                """.formatted(transferTopic, walletTopic);

        String batchReceiptResponse = """
                [
                  {"jsonrpc":"2.0","id":1,"result":{"logs":[
                    {"transactionHash":"0xaaa","blockNumber":"0x1","address":"0xtoken","topics":["%s","%s","0x0000000000000000000000000000000000000000000000000000000000005678"],"data":"0x1","logIndex":"0x0"},
                    {"transactionHash":"0xaaa","blockNumber":"0x1","address":"0xrouter","topics":["0xswaptopic"],"data":"0xswapdata","logIndex":"0x1"}
                  ]}}
                ]
                """.formatted(transferTopic, walletTopic);

        AtomicReference<String> lastBatchMethod = new AtomicReference<>();
        AtomicInteger batchCallCount = new AtomicInteger(0);

        EvmRpcClient batchRpc = new EvmRpcClient() {
            @Override
            public Mono<String> call(String endpointUrl, String method, Object params) {
                return Mono.just("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":[]}");
            }
            @Override
            public Mono<String> batchCall(String endpointUrl, List<RpcRequest> requests) {
                int callNum = batchCallCount.incrementAndGet();
                if (!requests.isEmpty()) lastBatchMethod.set(requests.get(0).method());
                if (callNum == 1) return Mono.just(batchLogsResponse);
                return Mono.just(batchReceiptResponse);
            }
        };

        RpcEndpointRotator r = new RpcEndpointRotator(List.of("https://test.rpc"), RetryPolicy.defaultPolicy());
        EvmBatchBlockSizeResolver resolver = new EvmBatchBlockSizeResolver(new IngestionNetworkProperties());
        EvmNetworkAdapter batchAdapter = new EvmNetworkAdapter(batchRpc, Map.of("ETHEREUM", r), r, fastLimiter(), evmRpcProps(), new ObjectMapper(), resolver);

        List<RawTransaction> result = batchAdapter.fetchTransactions("0x1234", NetworkId.ETHEREUM, 1L, 1L);

        assertThat(result).hasSize(1);
        RawTransaction tx = result.get(0);
        assertThat(tx.getTxHash()).isEqualTo("0xaaa");
        assertThat(tx.getRawData().getList("logs", org.bson.Document.class)).hasSize(2);
        assertThat(batchCallCount.get()).isEqualTo(2);
    }

    @Test
    void fetchTransactions_batchReceiptFails_fallsBackToSequentialReceipts() {
        String walletTopic = "0x0000000000000000000000000000000000000000000000000000000000001234";
        String transferTopic = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";

        String batchLogsResponse = """
                [
                  {"jsonrpc":"2.0","id":1,"result":[
                    {"transactionHash":"0xaaa","blockNumber":"0x1","address":"0xtoken","topics":["%s","%s","0x0000000000000000000000000000000000000000000000000000000000005678"],"data":"0x1","logIndex":"0x0"}
                  ]},
                  {"jsonrpc":"2.0","id":2,"result":[]}
                ]
                """.formatted(transferTopic, walletTopic);

        String singleReceiptResponse = """
                {"jsonrpc":"2.0","id":1,"result":{"logs":[
                  {"transactionHash":"0xaaa","blockNumber":"0x1","address":"0xtoken","topics":["%s","%s","0x0000000000000000000000000000000000000000000000000000000000005678"],"data":"0x1","logIndex":"0x0"}
                ]}}
                """.formatted(transferTopic, walletTopic);

        AtomicInteger batchCallCount = new AtomicInteger(0);
        AtomicInteger singleCallCount = new AtomicInteger(0);

        EvmRpcClient mixedRpc = new EvmRpcClient() {
            @Override
            public Mono<String> call(String endpointUrl, String method, Object params) {
                singleCallCount.incrementAndGet();
                return Mono.just(singleReceiptResponse);
            }
            @Override
            public Mono<String> batchCall(String endpointUrl, List<RpcRequest> requests) {
                int call = batchCallCount.incrementAndGet();
                if (call == 1) return Mono.just(batchLogsResponse);
                return Mono.error(new RpcException("Receipt batch not supported"));
            }
        };

        RpcEndpointRotator r = new RpcEndpointRotator(List.of("https://test.rpc"), RetryPolicy.defaultPolicy());
        EvmBatchBlockSizeResolver resolver = new EvmBatchBlockSizeResolver(new IngestionNetworkProperties());
        EvmNetworkAdapter mixedAdapter = new EvmNetworkAdapter(mixedRpc, Map.of("ETHEREUM", r), r, fastLimiter(), evmRpcProps(), new ObjectMapper(), resolver);

        List<RawTransaction> result = mixedAdapter.fetchTransactions("0x1234", NetworkId.ETHEREUM, 1L, 1L);

        assertThat(result).hasSize(1);
        assertThat(singleCallCount.get()).as("sequential receipt calls used as fallback").isGreaterThanOrEqualTo(1);
    }

    @Test
    void fetchTransactions_rateLimitedEndpoint_rotatesToHealthyEndpoint() {
        RetryPolicy policy = new RetryPolicy(0, 0.0, 3);
        RpcEndpointRotator rotator = new RpcEndpointRotator(List.of("https://rate-limited.rpc", "https://healthy.rpc"), policy);
        Map<String, Integer> endpointCalls = new ConcurrentHashMap<>();

        EvmRpcClient rateLimitedRpc = new EvmRpcClient() {
            @Override
            public Mono<String> call(String endpointUrl, String method, Object params) {
                endpointCalls.merge(endpointUrl, 1, Integer::sum);
                return Mono.just("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":[]}");
            }

            @Override
            public Mono<String> batchCall(String endpointUrl, List<RpcRequest> requests) {
                endpointCalls.merge(endpointUrl, 1, Integer::sum);
                if ("https://rate-limited.rpc".equals(endpointUrl)) {
                    return Mono.just("[" +
                            "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":429,\"message\":\"Too Many Requests\"}}," +
                            "{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":{\"code\":429,\"message\":\"Too Many Requests\"}}" +
                            "]");
                }
                return Mono.just("[{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":[]},{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":[]}]");
            }
        };

        EvmBatchBlockSizeResolver resolver = new EvmBatchBlockSizeResolver(new IngestionNetworkProperties());
        EvmNetworkAdapter adapter = new EvmNetworkAdapter(
                rateLimitedRpc,
                Map.of("ETHEREUM", rotator),
                rotator,
                fastLimiter(),
                evmRpcProps(),
                new ObjectMapper(),
                resolver
        );

        List<RawTransaction> result = adapter.fetchTransactions("0x1234", NetworkId.ETHEREUM, 1L, 1L);
        assertThat(result).isEmpty();
        assertThat(endpointCalls.getOrDefault("https://rate-limited.rpc", 0)).isGreaterThan(0);
        assertThat(endpointCalls.getOrDefault("https://healthy.rpc", 0)).isGreaterThan(0);
    }

    private static IngestionNetworkProperties.NetworkIngestionEntry entry(int batchBlockSize) {
        IngestionNetworkProperties.NetworkIngestionEntry e = new IngestionNetworkProperties.NetworkIngestionEntry();
        e.setUrls(List.of("https://example.com"));
        e.setBatchBlockSize(batchBlockSize);
        return e;
    }

    private static RateLimiter fastLimiter() {
        RateLimiterConfig config = RateLimiterConfig.custom()
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .limitForPeriod(1_000_000)
                .timeoutDuration(Duration.ofMillis(1))
                .build();
        return RateLimiter.of("test-evm-fast-limiter", config);
    }

    private static IngestionEvmRpcProperties evmRpcProps() {
        IngestionEvmRpcProperties props = new IngestionEvmRpcProperties();
        props.setMaxRequestsPerSecond(100_000);
        props.setEndpointCooldownMs(1_000);
        return props;
    }

    private static class MockEvmRpcClient implements EvmRpcClient {
        private String response = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":[]}";
        private String receiptResponse = null;
        private String batchResponse = null;
        private boolean batchShouldFail = false;
        private static final ObjectMapper MAPPER = new ObjectMapper();

        void setResponse(String response) {
            this.response = response;
        }

        void setReceiptResponse(String receiptResponse) {
            this.receiptResponse = receiptResponse;
        }

        void setBatchResponse(String batchResponse) {
            this.batchResponse = batchResponse;
        }

        void setBatchShouldFail(boolean fail) {
            this.batchShouldFail = fail;
        }

        @Override
        public Mono<String> call(String endpointUrl, String method, Object params) {
            if ("eth_getTransactionReceipt".equals(method) && receiptResponse != null) {
                return Mono.just(receiptResponse);
            }
            return Mono.just(response);
        }

        @Override
        public Mono<String> batchCall(String endpointUrl, List<RpcRequest> requests) {
            if (batchShouldFail) {
                return Mono.error(new RpcException("Batch not supported"));
            }
            if (batchResponse != null) {
                return Mono.just(batchResponse);
            }
            String source = response;
            if (!requests.isEmpty() && "eth_getTransactionReceipt".equals(requests.get(0).method()) && receiptResponse != null) {
                source = receiptResponse;
            }
            try {
                com.fasterxml.jackson.databind.JsonNode parsed = MAPPER.readTree(source);
                boolean isError = !parsed.path("error").isMissingNode();
                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < requests.size(); i++) {
                    if (i > 0) sb.append(",");
                    if (isError) {
                        sb.append("{\"jsonrpc\":\"2.0\",\"id\":").append(i + 1)
                          .append(",\"error\":").append(parsed.get("error").toString()).append("}");
                    } else {
                        sb.append("{\"jsonrpc\":\"2.0\",\"id\":").append(i + 1)
                          .append(",\"result\":").append(parsed.get("result").toString()).append("}");
                    }
                }
                sb.append("]");
                return Mono.just(sb.toString());
            } catch (Exception e) {
                return Mono.error(e);
            }
        }
    }
}
