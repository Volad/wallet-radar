package com.walletradar.platform.networks.ton;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.platform.networks.RpcException;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Adapter-level coverage for the {@code trace_id}-keyed jetton correlation and resilient
 * {@code /jetton/transfers} fetch in {@link TonNetworkAdapter}.
 *
 * <p>The core bug this proves fixed: a jetton transfer's {@code transaction_hash} names a
 * *different* transaction in the trace than the owner-account transaction row, so hash-matching
 * dropped 100% of jetton evidence. Both rows share the same {@code trace_id}, which is the correct
 * correlation key.</p>
 */
class TonNetworkAdapterTest {

    /** Friendly owner address (raw 0:hex would time out on /jetton/transfers). */
    private static final String OWNER = "UQDcaquhb07rH_df1iy56EF5WUE_XGmq4KhNLPs-m7v9o37O";
    private static final String OWNER_TX_HASH = "ownerTxHashAAA=";
    private static final String JETTON_TX_HASH = "xIRIsgS72LxvFcyU6rkxpHFqZlLH2kpJSPljhHzSK5k=";
    private static final String TRACE_ID = "traceABC123=";
    private static final String USDT_MASTER_RAW =
            "0:b113a994b5024a16719f69139328eb759596c38a25f59028b146fecdc3621dfe";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private TonNetworkProperties properties() {
        TonNetworkProperties props = new TonNetworkProperties();
        props.setPageSize(100);
        props.setJettonFetchMaxAttempts(3);
        props.setJettonFetchBackoffMillis(0L);
        return props;
    }

    private static String txResponse() {
        return "{\"transactions\":[{"
                + "\"hash\":\"" + OWNER_TX_HASH + "\","
                + "\"now\":1700000000,"
                + "\"trace_id\":\"" + TRACE_ID + "\","
                + "\"total_fees\":3000000"
                + "}]}";
    }

    private static String jettonResponse(String txHash, String traceId) {
        return "{\"jetton_transfers\":[{"
                + "\"transaction_hash\":\"" + txHash + "\","
                + "\"trace_id\":\"" + traceId + "\","
                + "\"jetton_master\":\"" + USDT_MASTER_RAW + "\","
                + "\"source\":\"0:1111111111111111111111111111111111111111111111111111111111111111\","
                + "\"destination\":\"0:2222222222222222222222222222222222222222222222222222222222222222\","
                + "\"amount\":\"30725310\""
                + "}]}";
    }

    private static String emptyJettonResponse() {
        return "{\"jetton_transfers\":[]}";
    }

    @Test
    @DisplayName("Jetton transfer is attached by shared trace_id even though transaction_hash differs")
    void attachesJettonByTraceIdDespiteHashMismatch() {
        StubTonRpcClient rpc = StubTonRpcClient.builder()
                .withTransactions(txResponse())
                .withJetton(jettonResponse(JETTON_TX_HASH, TRACE_ID))
                .build();
        TonNetworkAdapter adapter = new TonNetworkAdapter(rpc, properties(), objectMapper);

        List<RawTransaction> raws = adapter.fetchTransactions(OWNER, NetworkId.TON, 0, 0);

        assertThat(raws).hasSize(1);
        List<Document> jettonTransfers = jettonTransfersOf(raws.get(0));
        assertThat(jettonTransfers).hasSize(1);
        assertThat(jettonTransfers.get(0).getString("amount")).isEqualTo("30725310");
        assertThat(jettonTransfers.get(0).getString("jetton_master")).isEqualTo(USDT_MASTER_RAW);
    }

    @Test
    @DisplayName("Falls back to transaction_hash match when trace_id is absent on the jetton transfer")
    void fallsBackToHashMatchWhenTraceMissing() {
        StubTonRpcClient rpc = StubTonRpcClient.builder()
                .withTransactions(txResponse())
                // trace_id blank → only the (matching) transaction_hash can correlate
                .withJetton(jettonResponse(OWNER_TX_HASH, ""))
                .build();
        TonNetworkAdapter adapter = new TonNetworkAdapter(rpc, properties(), objectMapper);

        List<RawTransaction> raws = adapter.fetchTransactions(OWNER, NetworkId.TON, 0, 0);

        assertThat(raws).hasSize(1);
        assertThat(jettonTransfersOf(raws.get(0))).hasSize(1);
    }

    @Test
    @DisplayName("Retries /jetton/transfers on timeout then succeeds; jetton attached")
    void retriesOnTransientFailureThenSucceeds() {
        StubTonRpcClient rpc = StubTonRpcClient.builder()
                .withTransactions(txResponse())
                .withJettonError(new RpcException("timeout: context deadline exceeded"))
                .withJettonError(new RpcException("TON Center HTTP 500"))
                .withJetton(jettonResponse(JETTON_TX_HASH, TRACE_ID))
                .build();
        TonNetworkAdapter adapter = new TonNetworkAdapter(rpc, properties(), objectMapper);

        List<RawTransaction> raws = adapter.fetchTransactions(OWNER, NetworkId.TON, 0, 0);

        assertThat(raws).hasSize(1);
        assertThat(jettonTransfersOf(raws.get(0))).hasSize(1);
        assertThat(rpc.jettonCallCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("Persistent /jetton/transfers failure does not abort the page; native tx still ingested with empty jetton list")
    void persistentFailureDoesNotAbortPage() {
        StubTonRpcClient rpc = StubTonRpcClient.builder()
                .withTransactions(txResponse())
                .withJettonError(new RpcException("timeout: context deadline exceeded"))
                .withJettonError(new RpcException("timeout: context deadline exceeded"))
                .withJettonError(new RpcException("timeout: context deadline exceeded"))
                .build();
        TonNetworkAdapter adapter = new TonNetworkAdapter(rpc, properties(), objectMapper);

        List<RawTransaction> raws = adapter.fetchTransactions(OWNER, NetworkId.TON, 0, 0);

        assertThat(raws).hasSize(1);
        assertThat(jettonTransfersOf(raws.get(0))).isEmpty();
        assertThat(rpc.jettonCallCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("Genuinely jetton-free page returns empty jetton list after retries (no error)")
    void jettonFreePageReturnsEmpty() {
        StubTonRpcClient rpc = StubTonRpcClient.builder()
                .withTransactions(txResponse())
                .withJetton(emptyJettonResponse())
                .withJetton(emptyJettonResponse())
                .withJetton(emptyJettonResponse())
                .build();
        TonNetworkAdapter adapter = new TonNetworkAdapter(rpc, properties(), objectMapper);

        List<RawTransaction> raws = adapter.fetchTransactions(OWNER, NetworkId.TON, 0, 0);

        assertThat(raws).hasSize(1);
        assertThat(jettonTransfersOf(raws.get(0))).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static List<Document> jettonTransfersOf(RawTransaction raw) {
        Object value = raw.getRawData().get("jettonTransfers");
        return value instanceof List<?> list ? (List<Document>) list : List.of();
    }

    /** Deterministic stub returning canned bodies per relative path; supports queued jetton responses. */
    private static final class StubTonRpcClient implements TonRpcClient {
        private final String transactionsBody;
        private final Deque<Object> jettonQueue;
        private int jettonCalls;

        private StubTonRpcClient(String transactionsBody, Deque<Object> jettonQueue) {
            this.transactionsBody = transactionsBody;
            this.jettonQueue = jettonQueue;
        }

        static Builder builder() {
            return new Builder();
        }

        int jettonCallCount() {
            return jettonCalls;
        }

        @Override
        public String get(String relativePath, Map<String, String> queryParams) {
            if ("transactions".equals(relativePath)) {
                return transactionsBody;
            }
            if ("jetton/transfers".equals(relativePath)) {
                jettonCalls++;
                Object next = jettonQueue.poll();
                if (next instanceof RpcException ex) {
                    throw ex;
                }
                if (next instanceof String body) {
                    return body;
                }
                throw new RpcException("no jetton stub for call " + jettonCalls);
            }
            throw new RpcException("no stub for " + relativePath);
        }

        @Override
        public long getMasterchainSeqno() {
            return 0L;
        }

        static final class Builder {
            private String transactionsBody = "{\"transactions\":[]}";
            private final Deque<Object> jettonQueue = new ArrayDeque<>();

            Builder withTransactions(String body) {
                this.transactionsBody = body;
                return this;
            }

            Builder withJetton(String body) {
                jettonQueue.add(body);
                return this;
            }

            Builder withJettonError(RpcException ex) {
                jettonQueue.add(ex);
                return this;
            }

            StubTonRpcClient build() {
                return new StubTonRpcClient(transactionsBody, jettonQueue);
            }
        }
    }
}
