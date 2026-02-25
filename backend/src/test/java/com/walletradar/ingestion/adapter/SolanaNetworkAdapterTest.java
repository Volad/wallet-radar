package com.walletradar.ingestion.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.common.RetryPolicy;
import com.walletradar.domain.NetworkId;
import com.walletradar.domain.RawTransaction;
import com.walletradar.ingestion.adapter.solana.SolanaNetworkAdapter;
import com.walletradar.ingestion.adapter.solana.SolanaRpcClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SolanaNetworkAdapterTest {

    private SolanaNetworkAdapter adapter;
    private MockSolanaRpcClient mockRpc;
    private RpcEndpointRotator rotator;

    @BeforeEach
    void setUp() {
        mockRpc = new MockSolanaRpcClient();
        rotator = new RpcEndpointRotator(List.of("https://solana.test"), RetryPolicy.defaultPolicy());
        Map<String, RpcEndpointRotator> rotators = Map.of("SOLANA", rotator);
        adapter = new SolanaNetworkAdapter(mockRpc, rotators, rotator, new ObjectMapper());
    }

    @Test
    void supports_solana_returnsTrue() {
        assertThat(adapter.supports(NetworkId.SOLANA)).isTrue();
    }

    @Test
    void supports_ethereum_returnsFalse() {
        assertThat(adapter.supports(NetworkId.ETHEREUM)).isFalse();
    }

    @Test
    void getMaxBlockBatchSize_returnsPositive() {
        assertThat(adapter.getMaxBlockBatchSize()).isBetween(1, 1000);
    }

    @Test
    void fetchTransactions_solanaOnly_returnsEmptyForEthereum() {
        List<RawTransaction> result = adapter.fetchTransactions("0xabc", NetworkId.ETHEREUM, 0L, 100L);
        assertThat(result).isEmpty();
    }

    @Test
    void fetchTransactions_mockReturnsSignaturesAndTx_buildsRawTransactions() {
        String sigsJson = """
                {"jsonrpc":"2.0","id":1,"result":[
                  {"signature":"5h6xBEauJ3PK6SWCZ1PGjBvj8vDdWG3KpwATGy1ARAXFSDwt8GFXM7W5Ncn16wmqokgpiKRLuS83KUxyZyv2sUYv","slot":114,"err":null,"blockTime":1609459200}
                ]}
                """;
        String txJson = """
                {"jsonrpc":"2.0","id":1,"result":{"slot":114,"transaction":{"message":{"accountKeys":[]}}}}
                """;
        mockRpc.setSignaturesResponse(sigsJson);
        mockRpc.setTransactionResponse(txJson);

        List<RawTransaction> result = adapter.fetchTransactions("WalletAddr11111111111111111111111111111111", NetworkId.SOLANA, 0L, 0L);

        assertThat(result).hasSize(1);
        RawTransaction tx = result.get(0);
        assertThat(tx.getTxHash()).isEqualTo("5h6xBEauJ3PK6SWCZ1PGjBvj8vDdWG3KpwATGy1ARAXFSDwt8GFXM7W5Ncn16wmqokgpiKRLuS83KUxyZyv2sUYv");
        assertThat(tx.getNetworkId()).isEqualTo("SOLANA");
        assertThat(tx.getWalletAddress()).isEqualTo("WalletAddr11111111111111111111111111111111");
        assertThat(tx.getSlot()).isEqualTo(114L);
        assertThat(tx.getRawData()).isNotNull();
        assertThat(tx.getRawData()).containsKeys("slot", "blockTime", "signature", "transaction");
        assertThat(tx.getRawData().get("slot")).isNotNull();
        assertThat(((Number) tx.getRawData().get("slot")).longValue()).isEqualTo(114L);
        assertThat(((Number) tx.getRawData().get("blockTime")).longValue()).isEqualTo(1609459200L);
        assertThat(tx.getRawData().getString("signature")).isNotNull();
    }

    @Test
    void fetchTransactions_emptySignatures_returnsEmpty() {
        mockRpc.setSignaturesResponse("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":[]}");

        List<RawTransaction> result = adapter.fetchTransactions("WalletAddr11111111111111111111111111111111", NetworkId.SOLANA, 0L, 0L);

        assertThat(result).isEmpty();
    }

    @Test
    void fetchTransactions_txWithErr_skipped() {
        String sigsJson = """
                {"jsonrpc":"2.0","id":1,"result":[
                  {"signature":"sig1","slot":1,"err":{"code":1},"blockTime":1609459200}
                ]}
                """;
        mockRpc.setSignaturesResponse(sigsJson);

        List<RawTransaction> result = adapter.fetchTransactions("WalletAddr11111111111111111111111111111111", NetworkId.SOLANA, 0L, 0L);

        assertThat(result).isEmpty();
    }

    private static class MockSolanaRpcClient implements SolanaRpcClient {
        private String signaturesResponse = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":[]}";
        private String transactionResponse = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":null}";

        void setSignaturesResponse(String s) {
            this.signaturesResponse = s;
        }

        void setTransactionResponse(String s) {
            this.transactionResponse = s;
        }

        @Override
        public Mono<String> call(String endpointUrl, String method, Object params) {
            if ("getSignaturesForAddress".equals(method)) {
                return Mono.just(signaturesResponse);
            }
            if ("getTransaction".equals(method)) {
                return Mono.just(transactionResponse);
            }
            return Mono.just("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":null}");
        }
    }
}
