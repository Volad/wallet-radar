package com.walletradar.ingestion.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.domain.NetworkId;
import com.walletradar.domain.RawTransaction;
import com.walletradar.common.RetryPolicy;
import com.walletradar.ingestion.config.IngestionNetworkProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvmNetworkAdapterTest {

    private EvmNetworkAdapter adapter;
    private MockEvmRpcClient mockRpc;
    private RpcEndpointRotator rotator;

    @BeforeEach
    void setUp() {
        mockRpc = new MockEvmRpcClient();
        rotator = new RpcEndpointRotator(List.of("https://test.rpc"), RetryPolicy.defaultPolicy());
        IngestionNetworkProperties properties = new IngestionNetworkProperties();
        EvmBatchBlockSizeResolver resolver = new EvmBatchBlockSizeResolver(properties);
        Map<String, RpcEndpointRotator> rotatorsByNetwork = Map.of("ETHEREUM", rotator, "ARBITRUM", rotator);
        adapter = new EvmNetworkAdapter(mockRpc, rotatorsByNetwork, rotator, new ObjectMapper(), resolver);
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
        String json = """
                {"jsonrpc":"2.0","id":1,"result":[
                  {"transactionHash":"0xabc","blockNumber":"0x64","address":"0xtoken","topics":["0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef","%s","0x0000000000000000000000000000000000000000000000000000000000005678"],"data":"0x1","logIndex":"0x0"}
                ]}
                """.formatted(walletTopic);
        mockRpc.setResponse(json);

        List<RawTransaction> result = adapter.fetchTransactions("0x1234", NetworkId.ETHEREUM, 1L, 1L);

        assertThat(result).hasSize(1);
        RawTransaction tx = result.get(0);
        assertThat(tx.getTxHash()).isEqualTo("0xabc");
        assertThat(tx.getNetworkId()).isEqualTo("ETHEREUM");
        assertThat(tx.getRawData()).isNotNull();
        assertThat(tx.getRawData().get("blockNumber")).isEqualTo("0x64");
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
        EvmNetworkAdapter adapterWithResolver = new EvmNetworkAdapter(mockRpc, Map.of("ARBITRUM", r), r, new ObjectMapper(), resolver);

        List<RawTransaction> result = adapterWithResolver.fetchTransactions("0x1234", NetworkId.POLYGON, 1L, 10L);
        assertThat(result).isEmpty();
    }

    private static IngestionNetworkProperties.NetworkIngestionEntry entry(int batchBlockSize) {
        IngestionNetworkProperties.NetworkIngestionEntry e = new IngestionNetworkProperties.NetworkIngestionEntry();
        e.setUrls(List.of("https://example.com"));
        e.setBatchBlockSize(batchBlockSize);
        return e;
    }

    private static class MockEvmRpcClient implements EvmRpcClient {
        private String response = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":[]}";

        void setResponse(String response) {
            this.response = response;
        }

        @Override
        public Mono<String> call(String endpointUrl, String method, Object params) {
            return Mono.just(response);
        }
    }
}
