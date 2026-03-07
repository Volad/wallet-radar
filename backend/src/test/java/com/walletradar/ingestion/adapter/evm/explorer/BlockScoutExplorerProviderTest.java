package com.walletradar.ingestion.adapter.evm.explorer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.ingestion.adapter.evm.explorer.model.ExplorerReceipt;
import com.walletradar.ingestion.adapter.evm.explorer.model.ExplorerTransactionDetails;
import com.walletradar.ingestion.adapter.evm.explorer.model.ExplorerTransaction;
import com.walletradar.ingestion.config.IngestionExplorerProperties;
import com.walletradar.ingestion.config.IngestionNetworkProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class BlockScoutExplorerProviderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void getTransactionsBuildsBlockscoutApiUrlWithoutChainId() {
        TestConfig config = baseProperties();
        AtomicReference<URI> lastUrl = new AtomicReference<>();
        WebClient.Builder webClientBuilder = WebClient.builder()
                .exchangeFunction(request -> {
                    lastUrl.set(request.url());
                    return Mono.just(jsonResponse("{\"status\":\"1\",\"message\":\"OK\",\"result\":[]}"));
                });
        BlockScoutExplorerProvider provider = new BlockScoutExplorerProvider(
                webClientBuilder, objectMapper, config.explorerProperties(), config.networkProperties());

        List<ExplorerTransaction> result = provider.getTransactions("0xabc", NetworkId.ARBITRUM, 10L, 20L, 3);

        assertThat(result).isEmpty();
        assertThat(lastUrl.get()).isNotNull();
        assertThat(lastUrl.get().getPath()).isEqualTo("/api");
        MultiValueMap<String, String> query = UriComponentsBuilder.fromUri(lastUrl.get()).build().getQueryParams();
        assertThat(query.getFirst("module")).isEqualTo("account");
        assertThat(query.getFirst("action")).isEqualTo("txlist");
        assertThat(query.getFirst("address")).isEqualTo("0xabc");
        assertThat(query.getFirst("startblock")).isEqualTo("10");
        assertThat(query.getFirst("endblock")).isEqualTo("20");
        assertThat(query.getFirst("page")).isEqualTo("3");
        assertThat(query.getFirst("offset")).isEqualTo("500");
        assertThat(query.getFirst("apikey")).isEqualTo("arb-blockscout-key");
        assertThat(query.getFirst("chainid")).isNull();
    }

    @Test
    void getTokenTransfersUsesSmallerOffsetToAvoidHugeResponses() {
        TestConfig config = baseProperties();
        AtomicReference<URI> lastUrl = new AtomicReference<>();
        WebClient.Builder webClientBuilder = WebClient.builder()
                .exchangeFunction(request -> {
                    lastUrl.set(request.url());
                    return Mono.just(jsonResponse("{\"status\":\"1\",\"message\":\"OK\",\"result\":[]}"));
                });
        BlockScoutExplorerProvider provider = new BlockScoutExplorerProvider(
                webClientBuilder, objectMapper, config.explorerProperties(), config.networkProperties());

        provider.getTokenTransfers("0xabc", NetworkId.ARBITRUM, 10L, 20L, 1);

        assertThat(lastUrl.get()).isNotNull();
        MultiValueMap<String, String> query = UriComponentsBuilder.fromUri(lastUrl.get()).build().getQueryParams();
        assertThat(query.getFirst("action")).isEqualTo("tokentx");
        assertThat(query.getFirst("offset")).isEqualTo("500");
    }

    @Test
    void getTransactionsReturnsEmptyForNoTransactionsFound() {
        TestConfig config = baseProperties();
        WebClient.Builder webClientBuilder = WebClient.builder()
                .exchangeFunction(request -> Mono.just(
                        jsonResponse("{\"status\":\"0\",\"message\":\"No transactions found\",\"result\":[]}")
                ));
        BlockScoutExplorerProvider provider = new BlockScoutExplorerProvider(
                webClientBuilder, objectMapper, config.explorerProperties(), config.networkProperties());

        List<ExplorerTransaction> result = provider.getTransactions("0xabc", NetworkId.ARBITRUM, 1L, 2L, 1);

        assertThat(result).isEmpty();
    }

    @Test
    void getTransactionsReturnsEmptyForStatusZeroAndEmptyResult() {
        TestConfig config = baseProperties();
        WebClient.Builder webClientBuilder = WebClient.builder()
                .exchangeFunction(request -> Mono.just(
                        jsonResponse("{\"status\":\"0\",\"message\":\"\",\"result\":[]}")
                ));
        BlockScoutExplorerProvider provider = new BlockScoutExplorerProvider(
                webClientBuilder, objectMapper, config.explorerProperties(), config.networkProperties());

        List<ExplorerTransaction> result = provider.getTransactions("0xabc", NetworkId.ARBITRUM, 1L, 2L, 1);

        assertThat(result).isEmpty();
    }

    @Test
    void getReceiptReturnsReceiptResultNode() {
        TestConfig config = baseProperties();
        AtomicReference<URI> lastUrl = new AtomicReference<>();
        AtomicReference<HttpMethod> lastMethod = new AtomicReference<>();
        WebClient.Builder webClientBuilder = WebClient.builder()
                .exchangeFunction(request -> {
                    lastUrl.set(request.url());
                    lastMethod.set(request.method());
                    return Mono.just(jsonResponse("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"blockNumber\":\"0x10\"}}"));
                });
        BlockScoutExplorerProvider provider = new BlockScoutExplorerProvider(
                webClientBuilder, objectMapper, config.explorerProperties(), config.networkProperties());

        ExplorerReceipt receipt = provider.getReceipt("0xhash", NetworkId.ARBITRUM);

        assertThat(receipt).isNotNull();
        assertThat(receipt.blockNumber()).isEqualTo("0x10");
        assertThat(lastMethod.get()).isEqualTo(HttpMethod.POST);
        assertThat(lastUrl.get()).isNotNull();
        assertThat(lastUrl.get().getPath()).isEqualTo("/api/eth-rpc");
    }

    @Test
    void getCurrentBlockNumberUsesEthRpcEndpoint() {
        TestConfig config = baseProperties();
        AtomicReference<URI> lastUrl = new AtomicReference<>();
        AtomicReference<HttpMethod> lastMethod = new AtomicReference<>();
        WebClient.Builder webClientBuilder = WebClient.builder()
                .exchangeFunction(request -> {
                    lastUrl.set(request.url());
                    lastMethod.set(request.method());
                    return Mono.just(jsonResponse("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"0x65\"}"));
                });
        BlockScoutExplorerProvider provider = new BlockScoutExplorerProvider(
                webClientBuilder, objectMapper, config.explorerProperties(), config.networkProperties());

        Long block = provider.getCurrentBlockNumber(NetworkId.ARBITRUM);

        assertThat(block).isEqualTo(101L);
        assertThat(lastMethod.get()).isEqualTo(HttpMethod.POST);
        assertThat(lastUrl.get()).isNotNull();
        assertThat(lastUrl.get().getPath()).isEqualTo("/api/eth-rpc");
    }

    @Test
    void getReceiptReturnsNullWhenBlockscoutRpcEndpointReturnsBadRequest() {
        TestConfig config = baseProperties();
        WebClient.Builder webClientBuilder = WebClient.builder()
                .exchangeFunction(request -> Mono.just(
                        ClientResponse.create(HttpStatus.BAD_REQUEST)
                                .header("Content-Type", "application/json")
                                .body("{\"message\":\"bad request\"}")
                                .build()
                ));
        BlockScoutExplorerProvider provider = new BlockScoutExplorerProvider(
                webClientBuilder, objectMapper, config.explorerProperties(), config.networkProperties());

        ExplorerReceipt receipt = provider.getReceipt("0xhash", NetworkId.ARBITRUM);

        assertThat(receipt).isNull();
    }

    @Test
    void getTransactionReturnsTransactionResultNode() {
        TestConfig config = baseProperties();
        WebClient.Builder webClientBuilder = WebClient.builder()
                .exchangeFunction(request -> Mono.just(
                        jsonResponse("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"hash\":\"0xhash\",\"blockNumber\":\"0x11\"}}")
                ));
        BlockScoutExplorerProvider provider = new BlockScoutExplorerProvider(
                webClientBuilder, objectMapper, config.explorerProperties(), config.networkProperties());

        ExplorerTransaction tx = provider.getTransaction("0xhash", NetworkId.ARBITRUM);

        assertThat(tx).isNotNull();
        assertThat(tx.hash()).isEqualTo("0xhash");
        assertThat(tx.blockNumber()).isEqualTo("0x11");
    }

    @Test
    void getTransactionDetailsUsesV2EndpointAndReturnsPayload() {
        TestConfig config = baseProperties();
        AtomicReference<URI> lastUrl = new AtomicReference<>();
        AtomicReference<HttpMethod> lastMethod = new AtomicReference<>();
        WebClient.Builder webClientBuilder = WebClient.builder()
                .exchangeFunction(request -> {
                    lastUrl.set(request.url());
                    lastMethod.set(request.method());
                    return Mono.just(jsonResponse("{\"hash\":\"0xhash\",\"blockNumber\":12345,\"method\":\"multicall\"}"));
                });
        BlockScoutExplorerProvider provider = new BlockScoutExplorerProvider(
                webClientBuilder, objectMapper, config.explorerProperties(), config.networkProperties());

        ExplorerTransactionDetails details = provider.getTransactionDetails("0xhash", NetworkId.ARBITRUM);

        assertThat(details).isNotNull();
        assertThat(details.hash()).isEqualTo("0xhash");
        assertThat(details.blockNumber()).isEqualTo("12345");
        assertThat(lastMethod.get()).isEqualTo(HttpMethod.GET);
        assertThat(lastUrl.get()).isNotNull();
        assertThat(lastUrl.get().getPath()).isEqualTo("/api/v2/transactions/0xhash");
        MultiValueMap<String, String> query = UriComponentsBuilder.fromUri(lastUrl.get()).build().getQueryParams();
        assertThat(query.getFirst("apikey")).isEqualTo("arb-blockscout-key");
    }

    @Test
    void getTransactionDetailsReturnsNullOnNotFound() {
        TestConfig config = baseProperties();
        WebClient.Builder webClientBuilder = WebClient.builder()
                .exchangeFunction(request -> Mono.just(
                        ClientResponse.create(HttpStatus.NOT_FOUND)
                                .header("Content-Type", "application/json")
                                .body("{\"message\":\"not found\"}")
                                .build()
                ));
        BlockScoutExplorerProvider provider = new BlockScoutExplorerProvider(
                webClientBuilder, objectMapper, config.explorerProperties(), config.networkProperties());

        ExplorerTransactionDetails details = provider.getTransactionDetails("0xmissing", NetworkId.ARBITRUM);

        assertThat(details).isNull();
    }

    @Test
    void supportsReturnsTrueWhenSyncMethodIsBlockscoutAndConfigPresent() {
        TestConfig config = baseProperties();
        BlockScoutExplorerProvider provider = new BlockScoutExplorerProvider(
                WebClient.builder(), objectMapper, config.explorerProperties(), config.networkProperties());

        assertThat(provider.supports(NetworkId.ARBITRUM)).isTrue();
    }

    @Test
    void supportsReturnsFalseWhenSyncMethodIsEtherscan() {
        TestConfig config = baseProperties();
        config.networkProperties().getNetwork().get(NetworkId.ARBITRUM.name())
                .setSyncMethod(IngestionNetworkProperties.NetworkIngestionEntry.SyncMethod.ETHERSCAN);
        BlockScoutExplorerProvider provider = new BlockScoutExplorerProvider(
                WebClient.builder(), objectMapper, config.explorerProperties(), config.networkProperties());

        assertThat(provider.supports(NetworkId.ARBITRUM)).isFalse();
    }

    private static TestConfig baseProperties() {
        IngestionExplorerProperties explorerProperties = new IngestionExplorerProperties();
        explorerProperties.setMaxAttempts(2);

        IngestionNetworkProperties networkProperties = new IngestionNetworkProperties();
        IngestionNetworkProperties.NetworkIngestionEntry entry = new IngestionNetworkProperties.NetworkIngestionEntry();
        entry.setSyncMethod(IngestionNetworkProperties.NetworkIngestionEntry.SyncMethod.BLOCKSCOUT);
        IngestionNetworkProperties.NetworkIngestionEntry.ExplorerSource blockscoutEntry =
                new IngestionNetworkProperties.NetworkIngestionEntry.ExplorerSource();
        blockscoutEntry.setBaseUrl("https://arbitrum.blockscout.com");
        blockscoutEntry.setApiKey("arb-blockscout-key");
        blockscoutEntry.setEnabled(true);
        entry.getExplorer().setBlockscout(blockscoutEntry);
        networkProperties.getNetwork().put(NetworkId.ARBITRUM.name(), entry);

        return new TestConfig(explorerProperties, networkProperties);
    }

    private record TestConfig(IngestionExplorerProperties explorerProperties,
                              IngestionNetworkProperties networkProperties) {
    }

    private static ClientResponse jsonResponse(String body) {
        return ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", "application/json")
                .body(body)
                .build();
    }
}
