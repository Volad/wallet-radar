package com.walletradar.platform.networks.ton;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.platform.networks.RpcException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 0 no-complete-on-partial-fetch coverage for {@link TonNetworkAdapter}.
 *
 * <p>A mid-stream {@link RpcException} (after a full first page) must rethrow so the backfill
 * segment is marked FAILED and retried, rather than returning partial results and letting the
 * checkpoint advance past un-fetched history. A short/empty page is a natural end and returns
 * cleanly.</p>
 */
class TonNetworkAdapterPartialFetchTest {

    private static final String OWNER = "UQAe4Uho4bZdfmCEqiyyuUH8ujmrsGJOwE2124OBDMVbS1Ms";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private TonNetworkProperties properties() {
        TonNetworkProperties props = new TonNetworkProperties();
        props.setPageSize(100);
        props.setJettonFetchMaxAttempts(1);
        props.setJettonFetchBackoffMillis(0L);
        return props;
    }

    private static String fullTransactionsPage(int count) {
        StringBuilder sb = new StringBuilder("{\"transactions\":[");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append("{\"hash\":\"hash").append(i).append("=\",\"now\":1700000000,\"trace_id\":\"trace")
                    .append(i).append("=\"}");
        }
        sb.append("]}");
        return sb.toString();
    }

    @Test
    @DisplayName("Mid-stream RpcException after a full first page rethrows (segment must fail, not complete)")
    void fetchTransactions_midStreamFailureAfterFullPage_rethrows() {
        StubTonRpcClient rpc = new StubTonRpcClient();
        rpc.addTransactionsPage(fullTransactionsPage(100));
        rpc.addTransactionsError(new RpcException("TON Center HTTP 429: rate limit"));
        TonNetworkAdapter adapter = new TonNetworkAdapter(rpc, properties(), objectMapper);

        assertThatThrownBy(() -> adapter.fetchTransactions(OWNER, NetworkId.TON, 0L, 0L))
                .isInstanceOf(RpcException.class)
                .hasMessageContaining("rate limit");
    }

    @Test
    @DisplayName("Short first page ends cleanly without throwing (natural end of history)")
    void fetchTransactions_shortPage_endsCleanly() {
        StubTonRpcClient rpc = new StubTonRpcClient();
        rpc.addTransactionsPage(fullTransactionsPage(1));
        TonNetworkAdapter adapter = new TonNetworkAdapter(rpc, properties(), objectMapper);

        List<RawTransaction> result = adapter.fetchTransactions(OWNER, NetworkId.TON, 0L, 0L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTxHash()).isEqualTo("hash0=");
    }

    /** Stub returning queued transaction pages/errors; jetton transfers always resolve to empty. */
    private static final class StubTonRpcClient implements TonRpcClient {
        private final Deque<Object> transactionsQueue = new ArrayDeque<>();

        void addTransactionsPage(String body) {
            transactionsQueue.add(body);
        }

        void addTransactionsError(RpcException ex) {
            transactionsQueue.add(ex);
        }

        @Override
        public String get(String relativePath, Map<String, String> queryParams) {
            if ("transactions".equals(relativePath)) {
                Object next = transactionsQueue.poll();
                if (next == null) {
                    return "{\"transactions\":[]}";
                }
                if (next instanceof RpcException ex) {
                    throw ex;
                }
                return (String) next;
            }
            if ("jetton/transfers".equals(relativePath)) {
                return "{\"jetton_transfers\":[]}";
            }
            throw new RpcException("no stub for " + relativePath);
        }

        @Override
        public long getMasterchainSeqno() {
            return 0L;
        }
    }
}
