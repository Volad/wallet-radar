package com.walletradar.platform.networks.solana.helius;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.platform.common.RetryPolicy;
import com.walletradar.platform.networks.RpcEndpointRotator;
import com.walletradar.platform.networks.RpcException;
import com.walletradar.platform.networks.solana.SolanaRpcClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit coverage for {@link HeliusSolanaNetworkAdapter}'s Phase 0 ingestion-correctness fixes:
 *
 * <ul>
 *   <li>ATA-inbound capture: owner history missing an inbound SPL transfer is unioned with the
 *       wallet's token-account signatures and deduped by signature (Fix 2).</li>
 *   <li>No-complete-on-partial-fetch: a mid-stream {@link RpcException} (after a full first page)
 *       is rethrown so the segment fails and retries; empty/short pages end cleanly (Fix 3).</li>
 *   <li>Token-account resolution failure falls back to owner-history-only without failing.</li>
 * </ul>
 */
class HeliusSolanaNetworkAdapterTest {

    private static final String WALLET = "9Grpx4HKXTe51Ug9nAYuND9qf2bw326WvxFyEULt1DhG";
    private static final String ATA = "GpChG2qm4PjbCK9oYGhB4oLH2YKHZ9WtmF1SyQQ6Vqhp";
    private static final String OWNER_SIG = "ownerSig11111111111111111111111111111111111";
    private static final String INBOUND_SIG =
            "48NMkoPb4Y7i8BQqgqSFA9jmnT4odXr5pjSjhd5kNo4xpfv1eYwLx8tQ13fW21SaHWYJ5XNyTBd1EkEAfFhNMao5";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HeliusSolanaProperties properties;
    private RpcEndpointRotator rotator;
    private long now;

    @BeforeEach
    void setUp() {
        properties = new HeliusSolanaProperties();
        properties.setApiKey("test-key");
        rotator = new RpcEndpointRotator(List.of("https://solana.test"), new RetryPolicy(0, 0.0, 3));
        now = Instant.now().getEpochSecond();
    }

    private HeliusSolanaNetworkAdapter adapter(StubHeliusClient helius, SolanaRpcClient rpc) {
        return new HeliusSolanaNetworkAdapter(
                helius, properties, objectMapper, rpc,
                Map.of(NetworkId.SOLANA.name(), rotator), rotator, new HeliusRequestThrottle(0L));
    }

    private JsonNode node(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("Union of owner history and ATA signatures is deduped by signature (inbound present once)")
    void fetchTransactions_unionsOwnerHistoryAndAtaInbound_dedupedBySignature() {
        StubHeliusClient helius = new StubHeliusClient();
        // Owner history returns only the owner-visible tx (short page → natural end).
        helius.addHistoryPage(List.of(node(
                "{\"signature\":\"" + OWNER_SIG + "\",\"slot\":100,\"timestamp\":" + now + "}")));
        // ATA history re-enriches the inbound bridge signature that owner history missed.
        helius.setParse(INBOUND_SIG, node(
                "{\"signature\":\"" + INBOUND_SIG + "\",\"slot\":200,\"timestamp\":" + now + "}"));

        StubSolanaRpcClient rpc = new StubSolanaRpcClient();
        rpc.setTokenAccounts(HeliusSolanaNetworkAdapter.TOKEN_PROGRAM_ID, "[{\"pubkey\":\"" + ATA + "\"}]");
        // getSignaturesForAddress for the ATA returns the new inbound sig AND the already-seen owner sig.
        rpc.setSignatures(ATA, "["
                + "{\"signature\":\"" + INBOUND_SIG + "\",\"blockTime\":" + now + ",\"err\":null},"
                + "{\"signature\":\"" + OWNER_SIG + "\",\"blockTime\":" + now + ",\"err\":null}"
                + "]");

        List<RawTransaction> result = adapter(helius, rpc).fetchTransactions(WALLET, NetworkId.SOLANA, 0L, 0L);

        List<String> signatures = result.stream().map(RawTransaction::getTxHash).toList();
        assertThat(signatures).containsExactly(OWNER_SIG, INBOUND_SIG);
        assertThat(signatures).filteredOn(INBOUND_SIG::equals).hasSize(1);
        // Only the not-yet-seen signature is re-enriched via parseTransactions.
        assertThat(helius.parsedBatches).containsExactly(List.of(INBOUND_SIG));
    }

    @Test
    @DisplayName("Mid-stream RpcException after a full first page rethrows (segment must fail, not complete)")
    void fetchTransactions_midStreamFailureAfterFullPage_rethrows() {
        StubHeliusClient helius = new StubHeliusClient();
        List<JsonNode> fullPage = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            fullPage.add(node("{\"signature\":\"sig" + i + "\",\"slot\":" + i + ",\"timestamp\":" + now + "}"));
        }
        helius.addHistoryPage(fullPage);
        helius.addHistoryError(new RpcException("Helius history HTTP 429: rate limit"));

        assertThatThrownBy(() -> adapter(helius, new StubSolanaRpcClient())
                .fetchTransactions(WALLET, NetworkId.SOLANA, 0L, 0L))
                .isInstanceOf(RpcException.class)
                .hasMessageContaining("rate limit");
    }

    @Test
    @DisplayName("Short first page ends cleanly without throwing (natural end of history)")
    void fetchTransactions_shortPage_endsCleanly() {
        StubHeliusClient helius = new StubHeliusClient();
        helius.addHistoryPage(List.of(node(
                "{\"signature\":\"" + OWNER_SIG + "\",\"slot\":100,\"timestamp\":" + now + "}")));
        // No token accounts → no ATA capture.
        StubSolanaRpcClient rpc = new StubSolanaRpcClient();

        List<RawTransaction> result = adapter(helius, rpc).fetchTransactions(WALLET, NetworkId.SOLANA, 0L, 0L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTxHash()).isEqualTo(OWNER_SIG);
    }

    @Test
    @DisplayName("getTokenAccountsByOwner failure falls back to owner-history only (does not fail the fetch)")
    void fetchTransactions_tokenAccountResolutionFails_fallsBackToOwnerHistory() {
        StubHeliusClient helius = new StubHeliusClient();
        helius.addHistoryPage(List.of(node(
                "{\"signature\":\"" + OWNER_SIG + "\",\"slot\":100,\"timestamp\":" + now + "}")));
        StubSolanaRpcClient rpc = new StubSolanaRpcClient();
        rpc.failTokenAccounts = true;

        List<RawTransaction> result = adapter(helius, rpc).fetchTransactions(WALLET, NetworkId.SOLANA, 0L, 0L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTxHash()).isEqualTo(OWNER_SIG);
    }

    /** Stub Helius client with queued history pages/errors and a signature→parsed-node map. */
    private static final class StubHeliusClient implements HeliusSolanaClient {
        private final Deque<Object> historyQueue = new ArrayDeque<>();
        private final Map<String, JsonNode> parseBySignature = new HashMap<>();
        private final List<List<String>> parsedBatches = new ArrayList<>();

        void addHistoryPage(List<JsonNode> page) {
            historyQueue.add(page);
        }

        void addHistoryError(RpcException ex) {
            historyQueue.add(ex);
        }

        void setParse(String signature, JsonNode node) {
            parseBySignature.put(signature, node);
        }

        @Override
        @SuppressWarnings("unchecked")
        public List<JsonNode> getTransactionHistory(String address, String before, int limit) {
            Object next = historyQueue.poll();
            if (next == null) {
                return List.of();
            }
            if (next instanceof RpcException ex) {
                throw ex;
            }
            return (List<JsonNode>) next;
        }

        @Override
        public List<JsonNode> parseTransactions(List<String> signatures) {
            parsedBatches.add(List.copyOf(signatures));
            List<JsonNode> out = new ArrayList<>();
            for (String signature : signatures) {
                JsonNode node = parseBySignature.get(signature);
                if (node != null) {
                    out.add(node);
                }
            }
            return out;
        }
    }

    /** Stub Solana RPC client resolving token accounts (by programId) and per-ATA signatures. */
    private static final class StubSolanaRpcClient implements SolanaRpcClient {
        private final Map<String, String> tokenAccountsByProgram = new HashMap<>();
        private final Map<String, String> signaturesByAta = new HashMap<>();
        private boolean failTokenAccounts = false;

        void setTokenAccounts(String programId, String valueArrayJson) {
            tokenAccountsByProgram.put(programId, valueArrayJson);
        }

        void setSignatures(String ata, String resultArrayJson) {
            signaturesByAta.put(ata, resultArrayJson);
        }

        @Override
        public Mono<String> call(String endpointUrl, String method, Object params) {
            List<?> paramList = (List<?>) params;
            if ("getTokenAccountsByOwner".equals(method)) {
                if (failTokenAccounts) {
                    return Mono.just("{\"error\":{\"code\":-32000,\"message\":\"account resolution failed\"}}");
                }
                String programId = String.valueOf(((Map<?, ?>) paramList.get(1)).get("programId"));
                String value = tokenAccountsByProgram.getOrDefault(programId, "[]");
                return Mono.just("{\"result\":{\"value\":" + value + "}}");
            }
            if ("getSignaturesForAddress".equals(method)) {
                String ata = String.valueOf(paramList.get(0));
                String array = signaturesByAta.getOrDefault(ata, "[]");
                return Mono.just("{\"result\":" + array + "}");
            }
            return Mono.just("{\"result\":null}");
        }
    }
}
