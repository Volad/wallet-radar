package com.walletradar.platform.networks;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.platform.common.RetryPolicy;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.platform.networks.evm.rpc.EvmBatchBlockSizeResolver;
import com.walletradar.platform.networks.evm.rpc.EvmNetworkAdapter;
import com.walletradar.platform.networks.evm.rpc.EvmRpcClient;
import com.walletradar.platform.networks.evm.rpc.RpcRequest;
import com.walletradar.platform.networks.evm.rpc.nativerpc.NativeRpcTransactionRepairGateway;
import com.walletradar.platform.networks.evm.rpc.provider.AnkrTransactionsByAddressProvider;
import com.walletradar.platform.networks.evm.rpc.provider.BscProviderFirstRpcNetworkAdapter;
import com.walletradar.platform.networks.evm.rpc.provider.ProviderBackedRawTransactionMapper;
import com.walletradar.platform.networks.evm.rpc.support.RpcTokenTransferResolver;
import com.walletradar.platform.networks.config.IngestionEvmRpcProperties;
import com.walletradar.platform.networks.config.IngestionNetworkProperties;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BscProviderFirstRpcNetworkAdapterTest {

    private static final String PROVIDER_ENDPOINT = "https://provider.ankr.test";
    private static final String NATIVE_ENDPOINT = "https://bsc.native.rpc";

    @Test
    @DisplayName("provider-first BSC payload maps into canonical raw with logs and derived token transfers")
    void providerFirstBscPayloadMapsIntoCanonicalRawWithLogsAndDerivedTokenTransfers() {
        AtomicInteger nativeRepairCalls = new AtomicInteger(0);
        EvmRpcClient rpcClient = new EvmRpcClient() {
            @Override
            public Mono<String> call(String endpointUrl, String method, Object params) {
                if ("ankr_getTransactionsByAddress".equals(method)) {
                    return Mono.just(providerResponse(completeProviderTransaction()));
                }
                if ("eth_call".equals(method)) {
                    @SuppressWarnings("unchecked")
                    String data = ((Map<String, String>) ((List<?>) params).getFirst()).get("data");
                    return Mono.just(switch (data) {
                        case "0x313ce567" -> jsonResult("0x0000000000000000000000000000000000000000000000000000000000000006");
                        case "0x95d89b41" -> jsonResult("0x5553445400000000000000000000000000000000000000000000000000000000");
                        case "0x06fdde03" -> jsonResult("0x5465746865722055534400000000000000000000000000000000000000000000");
                        default -> jsonResult("0x");
                    });
                }
                nativeRepairCalls.incrementAndGet();
                return Mono.just("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":null}");
            }

            @Override
            public Mono<String> batchCall(String endpointUrl, List<RpcRequest> requests) {
                return Mono.just("[]");
            }
        };

        BscProviderFirstRpcNetworkAdapter adapter = adapter(rpcClient, true);

        List<RawTransaction> transactions = adapter.fetchTransactions(
                "0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f",
                NetworkId.BSC,
                56_000_000L,
                57_000_000L
        );

        assertThat(transactions).hasSize(1);
        RawTransaction tx = transactions.getFirst();
        assertThat(tx.getTxHash()).isEqualTo("0xac23f81f7c6b0b774201c1c1417d52cb6497947af864549fcdbe470e819eaf7f");
        assertThat(tx.getBlockNumber()).isEqualTo(85780169L);
        assertThat(tx.getRawData().getString("methodId")).isEqualTo("0xac9650d8");
        assertThat(tx.getRawData().getString("timeStamp")).isEqualTo(Long.toString(1_773_147_254L));
        assertThat(tx.getRawData().getString("transactionIndex")).isEqualTo("49");
        assertThat(tx.getRawData().getString("txreceipt_status")).isEqualTo("1");
        assertThat(tx.getRawData().getList("logs", Document.class)).hasSize(1);
        Document explorer = tx.getRawData().get("explorer", Document.class);
        assertThat(explorer).isNotNull();
        assertThat(explorer.getList("tokenTransfers", Document.class)).hasSize(1);
        Document transfer = explorer.getList("tokenTransfers", Document.class).getFirst();
        assertThat(transfer.getString("contractAddress")).isEqualTo("0x55d398326f99059ff775485246999027b3197955");
        assertThat(transfer.getString("tokenSymbol")).isEqualTo("USDT");
        assertThat(transfer.getString("tokenDecimal")).isEqualTo("6");
        assertThat(nativeRepairCalls.get()).isZero();
    }

    @Test
    @DisplayName("provider-first BSC fetch uses one advanced API request without pagination params")
    void providerFirstBscFetchUsesOneAdvancedApiRequestWithoutPaginationParams() {
        AtomicInteger providerCalls = new AtomicInteger(0);
        AtomicReference<Document> providerParams = new AtomicReference<>();
        EvmRpcClient rpcClient = new EvmRpcClient() {
            @Override
            public Mono<String> call(String endpointUrl, String method, Object params) {
                if ("ankr_getTransactionsByAddress".equals(method)) {
                    providerCalls.incrementAndGet();
                    providerParams.set(new Document((Document) params));
                    return Mono.just(providerResponse(completeProviderTransaction()));
                }
                if ("eth_call".equals(method)) {
                    return Mono.just(jsonResult("0x"));
                }
                return Mono.just("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":null}");
            }

            @Override
            public Mono<String> batchCall(String endpointUrl, List<RpcRequest> requests) {
                return Mono.just("[]");
            }
        };

        BscProviderFirstRpcNetworkAdapter adapter = adapter(rpcClient, true);

        List<RawTransaction> transactions = adapter.fetchTransactions(
                "0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f",
                NetworkId.BSC,
                56_000_000L,
                57_000_000L
        );

        assertThat(transactions).hasSize(1);
        assertThat(providerCalls.get()).isEqualTo(1);
        assertThat(providerParams.get()).isNotNull();
        assertThat(providerParams.get()).doesNotContainKeys("pageSize", "pageToken");
    }

    @Test
    @DisplayName("missing provider transactionIndex is repaired through native RPC")
    void missingProviderTransactionIndexIsRepairedThroughNativeRpc() {
        AtomicInteger receiptCalls = new AtomicInteger(0);
        EvmRpcClient rpcClient = new EvmRpcClient() {
            @Override
            public Mono<String> call(String endpointUrl, String method, Object params) {
                if ("ankr_getTransactionsByAddress".equals(method)) {
                    Document tx = completeProviderTransaction();
                    tx.remove("transactionIndex");
                    return Mono.just(providerResponse(tx));
                }
                if ("eth_call".equals(method)) {
                    return Mono.just(jsonResult("0x"));
                }
                if ("eth_getTransactionReceipt".equals(method)) {
                    receiptCalls.incrementAndGet();
                    return Mono.just("""
                            {"jsonrpc":"2.0","id":1,"result":{
                              "transactionIndex":"0x31",
                              "gasUsed":"0x61e68",
                              "status":"0x1",
                              "logs":[]
                            }}
                            """);
                }
                return Mono.just("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":null}");
            }

            @Override
            public Mono<String> batchCall(String endpointUrl, List<RpcRequest> requests) {
                return Mono.just("[]");
            }
        };

        BscProviderFirstRpcNetworkAdapter adapter = adapter(rpcClient, true);

        RawTransaction tx = adapter.fetchTransactions(
                "0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f",
                NetworkId.BSC,
                56_000_000L,
                57_000_000L
        ).getFirst();

        assertThat(tx.getRawData().getString("transactionIndex")).isEqualTo("49");
        assertThat(tx.getRawData().get("ingestBlockers")).isNull();
        assertThat(receiptCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("provider gaps that native repair cannot fill are recorded as explicit ingest blockers")
    void providerGapsThatNativeRepairCannotFillAreRecordedAsExplicitIngestBlockers() {
        EvmRpcClient rpcClient = new EvmRpcClient() {
            @Override
            public Mono<String> call(String endpointUrl, String method, Object params) {
                if ("ankr_getTransactionsByAddress".equals(method)) {
                    Document tx = new Document()
                            .append("hash", "0x149342ddd1d445297b57b89b8e44e6bef79263e668f91c48f6109df61baddd50")
                            .append("blockchain", "bsc")
                            .append("blockNumber", "0x361f36b")
                            .append("from", "0x8c826f795466e39acbff1bb4eeeb759609377ba1")
                            .append("to", "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f")
                            .append("input", "0x")
                            .append("value", "0xd3acc160d426");
                    return Mono.just(providerResponse(tx));
                }
                return Mono.just("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":null}");
            }

            @Override
            public Mono<String> batchCall(String endpointUrl, List<RpcRequest> requests) {
                return Mono.just("[]");
            }
        };

        BscProviderFirstRpcNetworkAdapter adapter = adapter(rpcClient, true);

        RawTransaction tx = adapter.fetchTransactions(
                "0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f",
                NetworkId.BSC,
                56_000_000L,
                57_000_000L
        ).getFirst();

        assertThat(tx.getRawData().getList("ingestBlockers", String.class))
                .contains("MISSING_TRANSACTION_INDEX", "MISSING_BLOCK_TIMESTAMP", "MISSING_RECEIPT_STATUS", "MISSING_GAS_USED");
    }

    @Test
    @DisplayName("provider-first BSC fetch fails fast when Ankr returns paginated segment response")
    void providerFirstBscFetchFailsFastWhenAnkrReturnsPaginatedSegmentResponse() {
        EvmRpcClient rpcClient = new EvmRpcClient() {
            @Override
            public Mono<String> call(String endpointUrl, String method, Object params) {
                if ("ankr_getTransactionsByAddress".equals(method)) {
                    return Mono.just("""
                            {"jsonrpc":"2.0","id":1,"result":{"transactions":[%s],"nextPageToken":"next-page"}}
                            """.formatted(completeProviderTransaction().toJson()));
                }
                return Mono.just("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":null}");
            }

            @Override
            public Mono<String> batchCall(String endpointUrl, List<RpcRequest> requests) {
                return Mono.just("[]");
            }
        };

        BscProviderFirstRpcNetworkAdapter adapter = adapter(rpcClient, true);

        assertThatThrownBy(() -> adapter.fetchTransactions(
                "0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f",
                NetworkId.BSC,
                56_000_000L,
                57_000_000L
        )).hasMessageContaining("nextPageToken");
    }

    @Test
    @DisplayName("ordered adapter resolution chooses provider-first for BSC and native RPC for other EVM networks")
    void orderedAdapterResolutionChoosesProviderFirstForBscAndNativeRpcForOtherEvmNetworks() {
        EvmRpcClient rpcClient = new EvmRpcClient() {
            @Override
            public Mono<String> call(String endpointUrl, String method, Object params) {
                return Mono.just("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":[]}");
            }

            @Override
            public Mono<String> batchCall(String endpointUrl, List<RpcRequest> requests) {
                return Mono.just("[]");
            }
        };

        BscProviderFirstRpcNetworkAdapter bscAdapter = adapter(rpcClient, true);
        EvmNetworkAdapter nativeAdapter = new EvmNetworkAdapter(
                rpcClient,
                Map.of("BSC", new RpcEndpointRotator(List.of(NATIVE_ENDPOINT), RetryPolicy.defaultPolicy())),
                new RpcEndpointRotator(List.of(NATIVE_ENDPOINT), RetryPolicy.defaultPolicy()),
                fastLimiter(),
                evmRpcProps(),
                new ObjectMapper(),
                new EvmBatchBlockSizeResolver(networkProperties(true))
        );

        List<NetworkAdapter> adapters = new ArrayList<>(List.of(nativeAdapter, bscAdapter));
        AnnotationAwareOrderComparator.sort(adapters);

        NetworkAdapter selectedBsc = adapters.stream().filter(adapter -> adapter.supports(NetworkId.BSC)).findFirst().orElseThrow();
        NetworkAdapter selectedEthereum = adapters.stream().filter(adapter -> adapter.supports(NetworkId.ETHEREUM)).findFirst().orElseThrow();

        assertThat(selectedBsc).isInstanceOf(BscProviderFirstRpcNetworkAdapter.class);
        assertThat(selectedEthereum).isInstanceOf(EvmNetworkAdapter.class);
    }

    private static BscProviderFirstRpcNetworkAdapter adapter(EvmRpcClient rpcClient, boolean providerEnabled) {
        ObjectMapper objectMapper = new ObjectMapper();
        IngestionNetworkProperties networkProperties = networkProperties(providerEnabled);
        RpcTokenTransferResolver tokenTransferResolver = new RpcTokenTransferResolver(
                rpcClient,
                fastLimiter(),
                evmRpcProps(),
                objectMapper
        );
        return new BscProviderFirstRpcNetworkAdapter(
                new AnkrTransactionsByAddressProvider(rpcClient, objectMapper, networkProperties),
                new ProviderBackedRawTransactionMapper(tokenTransferResolver),
                new NativeRpcTransactionRepairGateway(rpcClient, objectMapper),
                networkProperties,
                Map.of("BSC", new RpcEndpointRotator(List.of(NATIVE_ENDPOINT), RetryPolicy.defaultPolicy()))
        );
    }

    private static IngestionNetworkProperties networkProperties(boolean providerEnabled) {
        IngestionNetworkProperties properties = new IngestionNetworkProperties();
        IngestionNetworkProperties.NetworkIngestionEntry entry = new IngestionNetworkProperties.NetworkIngestionEntry();
        entry.setSyncMethod(IngestionNetworkProperties.NetworkIngestionEntry.SyncMethod.RPC);
        entry.setUrls(List.of(NATIVE_ENDPOINT));
        entry.setBatchBlockSize(250_000);
        IngestionNetworkProperties.NetworkIngestionEntry.Provider provider = new IngestionNetworkProperties.NetworkIngestionEntry.Provider();
        provider.setEnabled(providerEnabled);
        provider.setBaseUrl(PROVIDER_ENDPOINT);
        provider.setPageSize(100);
        entry.setProvider(provider);
        properties.setNetwork(Map.of("BSC", entry));
        return properties;
    }

    private static IngestionEvmRpcProperties evmRpcProps() {
        IngestionEvmRpcProperties properties = new IngestionEvmRpcProperties();
        properties.setLocalLimiterTimeoutMs(100);
        properties.setLocalLimiterLogThresholdMs(1000);
        properties.setMaxResponseBytes(16 * 1024 * 1024);
        return properties;
    }

    private static RateLimiter fastLimiter() {
        return RateLimiter.of("test-evm-rpc", RateLimiterConfig.custom()
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .limitForPeriod(1_000)
                .timeoutDuration(Duration.ofMillis(100))
                .build());
    }

    private static String providerResponse(Document tx) {
        return """
                {"jsonrpc":"2.0","id":1,"result":{"transactions":[%s]}}
                """.formatted(tx.toJson());
    }

    private static String jsonResult(String result) {
        return """
                {"jsonrpc":"2.0","id":1,"result":"%s"}
                """.formatted(result);
    }

    private static Document completeProviderTransaction() {
        return new Document("hash", "0xac23f81f7c6b0b774201c1c1417d52cb6497947af864549fcdbe470e819eaf7f")
                .append("blockchain", "bsc")
                .append("blockNumber", "0x51ce6c9")
                .append("from", "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f")
                .append("to", "0x55f4c8aba71a1e923edc303eb4feff14608cc226")
                .append("gas", "0x98619")
                .append("gasPrice", "0x2faf080")
                .append("input", "0xac9650d80000000000000000000000000000000000000000000000000000000000000020")
                .append("transactionIndex", "0x31")
                .append("blockHash", "0x01abec754be944779bef81540fe98c75f68f558501001a5c47a1d6851a7f1204")
                .append("value", "0x0")
                .append("type", "0x0")
                .append("cumulativeGasUsed", "0x8de6e4")
                .append("gasUsed", "0x61e68")
                .append("status", "0x1")
                .append("timestamp", "0x69b01476")
                .append("logs", List.of(new Document("address", "0x55d398326f99059ff775485246999027b3197955")
                        .append("topics", List.of(
                                "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef",
                                "0x0000000000000000000000001a87f12ac07e9746e9b053b8d7ef1d45270d693f",
                                "0x0000000000000000000000009e9035aafecb30cfd5355a10f93a270e33bc4293"
                        ))
                        .append("data", "0x00000000000000000000000000000000000000000000108ce31d42443e9f8389")
                        .append("blockNumber", "0x51ce6c9")
                        .append("transactionHash", "0xac23f81f7c6b0b774201c1c1417d52cb6497947af864549fcdbe470e819eaf7f")
                        .append("transactionIndex", "0x31")
                        .append("blockHash", "0x01abec754be944779bef81540fe98c75f68f558501001a5c47a1d6851a7f1204")
                        .append("logIndex", "0x11b")
                        .append("removed", false)));
    }
}
