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
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class EtherscanV2ExplorerProviderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void getTransactionsBuildsV2UrlWithRequiredParams() {
        TestConfig config = baseProperties();
        AtomicReference<URI> lastUrl = new AtomicReference<>();
        WebClient.Builder webClientBuilder = WebClient.builder()
                .exchangeFunction(request -> {
                    lastUrl.set(request.url());
                    return Mono.just(jsonResponse("{\"status\":\"1\",\"message\":\"OK\",\"result\":[]}"));
                });
        EtherscanV2ExplorerProvider provider = new EtherscanV2ExplorerProvider(
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
        assertThat(query.getFirst("offset")).isEqualTo("1000");
        assertThat(query.getFirst("chainid")).isEqualTo("42161");
        assertThat(query.getFirst("apikey")).isEqualTo("arb-key");
    }

    @Test
    void getCurrentBlockNumberReadsProxyEthBlockNumber() {
        TestConfig config = baseProperties();
        WebClient.Builder webClientBuilder = WebClient.builder()
                .exchangeFunction(request -> Mono.just(
                        jsonResponse("{\"status\":\"1\",\"message\":\"OK\",\"result\":\"0x64\"}")
                ));
        EtherscanV2ExplorerProvider provider = new EtherscanV2ExplorerProvider(
                webClientBuilder, objectMapper, config.explorerProperties(), config.networkProperties());

        Long block = provider.getCurrentBlockNumber(NetworkId.ARBITRUM);

        assertThat(block).isEqualTo(100L);
    }

    @Test
    void getNativeBalanceBuildsExplorerBalanceRequest() {
        TestConfig config = baseProperties();
        AtomicReference<URI> lastUrl = new AtomicReference<>();
        WebClient.Builder webClientBuilder = WebClient.builder()
                .exchangeFunction(request -> {
                    lastUrl.set(request.url());
                    return Mono.just(jsonResponse("{\"status\":\"1\",\"message\":\"OK\",\"result\":\"1230000000000000000\"}"));
                });
        EtherscanV2ExplorerProvider provider = new EtherscanV2ExplorerProvider(
                webClientBuilder, objectMapper, config.explorerProperties(), config.networkProperties());

        java.math.BigInteger balance = provider.getNativeBalance("0xabc", NetworkId.ARBITRUM);

        assertThat(balance).isEqualTo(new java.math.BigInteger("1230000000000000000"));
        MultiValueMap<String, String> query = UriComponentsBuilder.fromUri(lastUrl.get()).build().getQueryParams();
        assertThat(query.getFirst("module")).isEqualTo("account");
        assertThat(query.getFirst("action")).isEqualTo("balance");
        assertThat(query.getFirst("address")).isEqualTo("0xabc");
        assertThat(query.getFirst("tag")).isEqualTo("latest");
    }

    @Test
    void getTokenBalanceBuildsExplorerTokenBalanceRequest() {
        TestConfig config = baseProperties();
        AtomicReference<URI> lastUrl = new AtomicReference<>();
        WebClient.Builder webClientBuilder = WebClient.builder()
                .exchangeFunction(request -> {
                    lastUrl.set(request.url());
                    return Mono.just(jsonResponse("{\"status\":\"1\",\"message\":\"OK\",\"result\":\"2500000\"}"));
                });
        EtherscanV2ExplorerProvider provider = new EtherscanV2ExplorerProvider(
                webClientBuilder, objectMapper, config.explorerProperties(), config.networkProperties());

        java.math.BigInteger balance = provider.getTokenBalance("0xabc", "0xdef", NetworkId.ARBITRUM);

        assertThat(balance).isEqualTo(new java.math.BigInteger("2500000"));
        MultiValueMap<String, String> query = UriComponentsBuilder.fromUri(lastUrl.get()).build().getQueryParams();
        assertThat(query.getFirst("module")).isEqualTo("account");
        assertThat(query.getFirst("action")).isEqualTo("tokenbalance");
        assertThat(query.getFirst("address")).isEqualTo("0xabc");
        assertThat(query.getFirst("contractaddress")).isEqualTo("0xdef");
        assertThat(query.getFirst("tag")).isEqualTo("latest");
    }

    @Test
    void getTokenDecimalsReadsTokenDecimalFromTokenTransfers() {
        TestConfig config = baseProperties();
        WebClient.Builder webClientBuilder = WebClient.builder()
                .exchangeFunction(request -> Mono.just(
                        jsonResponse("{\"status\":\"1\",\"message\":\"OK\",\"result\":[{\"tokenDecimal\":\"18\"}]}")
                ));
        EtherscanV2ExplorerProvider provider = new EtherscanV2ExplorerProvider(
                webClientBuilder, objectMapper, config.explorerProperties(), config.networkProperties());

        Integer decimals = provider.getTokenDecimals("0xabc", "0xdef", NetworkId.ARBITRUM);

        assertThat(decimals).isEqualTo(18);
    }

    @Test
    void getTransactionsReturnsEmptyWhenPageExceedsEtherscanResultWindow() {
        TestConfig config = baseProperties();
        AtomicInteger requestCount = new AtomicInteger(0);
        WebClient.Builder webClientBuilder = WebClient.builder()
                .exchangeFunction(request -> {
                    requestCount.incrementAndGet();
                    return Mono.just(jsonResponse("{\"status\":\"1\",\"message\":\"OK\",\"result\":[]}"));
                });
        EtherscanV2ExplorerProvider provider = new EtherscanV2ExplorerProvider(
                webClientBuilder, objectMapper, config.explorerProperties(), config.networkProperties());

        List<ExplorerTransaction> result = provider.getTransactions("0xabc", NetworkId.ARBITRUM, 10L, 20L, 11);

        assertThat(result).isEmpty();
        assertThat(requestCount.get()).isZero();
    }

    @Test
    void getTransactionsReturnsEmptyForNoTransactionsFound() {
        TestConfig config = baseProperties();
        WebClient.Builder webClientBuilder = WebClient.builder()
                .exchangeFunction(request -> Mono.just(
                        jsonResponse("{\"status\":\"0\",\"message\":\"No transactions found\",\"result\":[]}")
                ));
        EtherscanV2ExplorerProvider provider = new EtherscanV2ExplorerProvider(
                webClientBuilder, objectMapper, config.explorerProperties(), config.networkProperties());

        List<ExplorerTransaction> result = provider.getTransactions("0xabc", NetworkId.ARBITRUM, 1L, 2L, 1);

        assertThat(result).isEmpty();
    }

    @Test
    void getTransactionsReturnsEmptyWhenExplorerReturnsError() {
        TestConfig config = baseProperties();
        config.explorerProperties().setMaxAttempts(1);
        WebClient.Builder webClientBuilder = WebClient.builder()
                .exchangeFunction(request -> Mono.just(
                        jsonResponse("{\"status\":\"0\",\"message\":\"NOTOK\",\"result\":\"Max rate limit reached\"}")
                ));
        EtherscanV2ExplorerProvider provider = new EtherscanV2ExplorerProvider(
                webClientBuilder, objectMapper, config.explorerProperties(), config.networkProperties());

        List<ExplorerTransaction> result = provider.getTransactions("0xabc", NetworkId.ARBITRUM, 1L, 2L, 1);

        assertThat(result).isEmpty();
    }

    @Test
    void getTransactionsFallbacksToUnifiedV2WhenScannerReturnsDeprecatedV1() {
        TestConfig config = baseProperties();
        AtomicReference<URI> firstUrl = new AtomicReference<>();
        AtomicReference<URI> secondUrl = new AtomicReference<>();
        WebClient.Builder webClientBuilder = WebClient.builder()
                .exchangeFunction(request -> {
                    URI url = request.url();
                    if (firstUrl.get() == null) {
                        firstUrl.set(url);
                        return Mono.just(jsonResponse("{\"status\":\"0\",\"message\":\"NOTOK\",\"result\":\"You are using a deprecated V1 endpoint, switch to Etherscan API V2 using https://docs.etherscan.io/v2-migration\"}"));
                    }
                    secondUrl.set(url);
                    return Mono.just(jsonResponse("{\"status\":\"1\",\"message\":\"OK\",\"result\":[]}"));
                });
        EtherscanV2ExplorerProvider provider = new EtherscanV2ExplorerProvider(
                webClientBuilder, objectMapper, config.explorerProperties(), config.networkProperties());

        List<ExplorerTransaction> result = provider.getTransactions("0xabc", NetworkId.ARBITRUM, 1L, 2L, 1);

        assertThat(result).isEmpty();
        assertThat(firstUrl.get()).isNotNull();
        assertThat(firstUrl.get().getHost()).isEqualTo("api.arbiscan.io");
        assertThat(firstUrl.get().getPath()).isEqualTo("/api");
        assertThat(secondUrl.get()).isNotNull();
        assertThat(secondUrl.get().getHost()).isEqualTo("api.etherscan.io");
        assertThat(secondUrl.get().getPath()).isEqualTo("/v2/api");
    }

    @Test
    void getReceiptReturnsReceiptResultNode() {
        TestConfig config = baseProperties();
        WebClient.Builder webClientBuilder = WebClient.builder()
                .exchangeFunction(request -> Mono.just(
                        jsonResponse("{\"status\":\"1\",\"message\":\"OK\",\"result\":{\"blockNumber\":\"0x10\"}}")
                ));
        EtherscanV2ExplorerProvider provider = new EtherscanV2ExplorerProvider(
                webClientBuilder, objectMapper, config.explorerProperties(), config.networkProperties());

        ExplorerReceipt receipt = provider.getReceipt("0xhash", NetworkId.ARBITRUM);

        assertThat(receipt).isNotNull();
        assertThat(receipt.blockNumber()).isEqualTo("0x10");
    }

    @Test
    void getTransactionReturnsTransactionResultNode() {
        TestConfig config = baseProperties();
        WebClient.Builder webClientBuilder = WebClient.builder()
                .exchangeFunction(request -> Mono.just(
                        jsonResponse("{\"status\":\"1\",\"message\":\"OK\",\"result\":{\"hash\":\"0xhash\",\"blockNumber\":\"0x11\"}}")
                ));
        EtherscanV2ExplorerProvider provider = new EtherscanV2ExplorerProvider(
                webClientBuilder, objectMapper, config.explorerProperties(), config.networkProperties());

        ExplorerTransaction tx = provider.getTransaction("0xhash", NetworkId.ARBITRUM);

        assertThat(tx).isNotNull();
        assertThat(tx.hash()).isEqualTo("0xhash");
        assertThat(tx.blockNumber()).isEqualTo("0x11");
    }

    @Test
    void getTransactionDetailsReturnsTransactionResultNode() {
        TestConfig config = baseProperties();
        WebClient.Builder webClientBuilder = WebClient.builder()
                .exchangeFunction(request -> Mono.just(
                        jsonResponse("{\"status\":\"1\",\"message\":\"OK\",\"result\":{\"hash\":\"0xhash\",\"blockNumber\":\"0x12\"}}")
                ));
        EtherscanV2ExplorerProvider provider = new EtherscanV2ExplorerProvider(
                webClientBuilder, objectMapper, config.explorerProperties(), config.networkProperties());

        ExplorerTransactionDetails txDetails = provider.getTransactionDetails("0xhash", NetworkId.ARBITRUM);

        assertThat(txDetails).isNotNull();
        assertThat(txDetails.hash()).isEqualTo("0xhash");
        assertThat(txDetails.blockNumber()).isEqualTo("0x12");
    }

    @Test
    void supportsUsesNetworkConfiguration() {
        TestConfig config = baseProperties();
        EtherscanV2ExplorerProvider provider = new EtherscanV2ExplorerProvider(
                WebClient.builder(), objectMapper, config.explorerProperties(), config.networkProperties());

        assertThat(provider.supports(NetworkId.ARBITRUM)).isTrue();
        assertThat(provider.supports(NetworkId.SOLANA)).isFalse();
    }

    @Test
    void supportsReturnsFalseWhenSyncMethodIsRpc() {
        TestConfig config = baseProperties();
        config.networkProperties().getNetwork().get(NetworkId.ARBITRUM.name())
                .setSyncMethod(IngestionNetworkProperties.NetworkIngestionEntry.SyncMethod.RPC);
        EtherscanV2ExplorerProvider provider = new EtherscanV2ExplorerProvider(
                WebClient.builder(), objectMapper, config.explorerProperties(), config.networkProperties());

        assertThat(provider.supports(NetworkId.ARBITRUM)).isFalse();
    }

    @Test
    void supportsReturnsFalseWhenSyncMethodIsBlockscout() {
        TestConfig config = baseProperties();
        IngestionNetworkProperties.NetworkIngestionEntry entry =
                config.networkProperties().getNetwork().get(NetworkId.ARBITRUM.name());
        entry.setSyncMethod(IngestionNetworkProperties.NetworkIngestionEntry.SyncMethod.BLOCKSCOUT);

        EtherscanV2ExplorerProvider provider = new EtherscanV2ExplorerProvider(
                WebClient.builder(), objectMapper, config.explorerProperties(), config.networkProperties());

        assertThat(provider.supports(NetworkId.ARBITRUM)).isFalse();
    }

    private static TestConfig baseProperties() {
        IngestionExplorerProperties explorerProperties = new IngestionExplorerProperties();
        explorerProperties.setMaxAttempts(2);

        IngestionNetworkProperties networkProperties = new IngestionNetworkProperties();
        IngestionNetworkProperties.NetworkIngestionEntry entry = new IngestionNetworkProperties.NetworkIngestionEntry();
        entry.setSyncMethod(IngestionNetworkProperties.NetworkIngestionEntry.SyncMethod.ETHERSCAN);
        entry.setChainId("42161");
        IngestionNetworkProperties.NetworkIngestionEntry.ExplorerSource etherscanEntry =
                new IngestionNetworkProperties.NetworkIngestionEntry.ExplorerSource();
        etherscanEntry.setBaseUrl("https://api.arbiscan.io");
        etherscanEntry.setApiKey("arb-key");
        etherscanEntry.setEnabled(true);
        entry.getExplorer().setEtherscan(etherscanEntry);
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
