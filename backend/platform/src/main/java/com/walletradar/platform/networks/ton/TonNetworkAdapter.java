package com.walletradar.platform.networks.ton;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.raw.NormalizationStatus;
import com.walletradar.domain.transaction.raw.RawSyncMethod;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.platform.networks.NetworkAdapter;
import com.walletradar.platform.networks.RpcException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TON {@link NetworkAdapter} backed by the TON Center v3 REST API.
 *
 * <p>Fetches TON transactions (native + jetton) for a wallet address using offset-based
 * pagination. For each transaction the full TON Center JSON is stored in
 * {@code rawData.transaction}; associated jetton transfers are stored in
 * {@code rawData.jettonTransfers}.</p>
 *
 * <p>Block range parameters (fromBlock / toBlock) are used to cap the maximum number of
 * transactions fetched per segment invocation, consistent with the Solana offset model.</p>
 *
 * <p>Registered with {@link Order}(1) to take precedence over any generic adapter.</p>
 */
@Component
@Order(1)
@RequiredArgsConstructor
@Slf4j
public class TonNetworkAdapter implements NetworkAdapter {

    private final TonRpcClient rpcClient;
    private final TonNetworkProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(NetworkId networkId) {
        return networkId == NetworkId.TON;
    }

    @Override
    public int getMaxBlockBatchSize() {
        return properties.getPageSize();
    }

    @Override
    public boolean supportsBlockCheckpointing() {
        // TON uses offset-based pagination, not block ranges
        return false;
    }

    @Override
    public List<RawTransaction> fetchTransactions(String walletAddress,
                                                   NetworkId networkId,
                                                   long fromBlock,
                                                   long toBlock) {
        if (networkId != NetworkId.TON) {
            return List.of();
        }
        long maxTxs = (toBlock > 0 && fromBlock >= 0) ? (toBlock - fromBlock + 1) : Long.MAX_VALUE;
        List<RawTransaction> all = new ArrayList<>();
        int pageSize = Math.min(properties.getPageSize(), 100);
        int offset = 0;

        while (all.size() < maxTxs) {
            List<JsonNode> page;
            try {
                page = fetchTransactionPage(walletAddress, pageSize, offset);
            } catch (RpcException e) {
                // No-complete-on-partial-fetch: a mid-stream failure that is not a natural end
                // (empty/short page) must fail the segment so BackfillNetworkExecutor marks it FAILED
                // and retries it, rather than returning partial results and letting the checkpoint
                // advance past un-fetched history (permanent gaps). Client-level backoff handles
                // transient rate limiting.
                log.warn("TON transaction page fetch failed for {} after {} txs; failing segment for retry: {}",
                        walletAddress, all.size(), e.getMessage());
                throw e;
            }
            if (page.isEmpty()) {
                break;
            }
            // Fetch jetton transfers once per page for this address, keyed by trace_id (primary)
            // and transaction_hash (fallback). A jetton transfer's transaction_hash points at a
            // *different* transaction in the trace than the owner-account transaction row, so the
            // shared trace_id is the correct correlation key.
            JettonTransferIndex jettonIndex = fetchJettonTransfers(walletAddress, pageSize, offset);

            for (JsonNode txNode : page) {
                if (all.size() >= maxTxs) {
                    break;
                }
                RawTransaction raw = buildRaw(txNode, walletAddress, jettonIndex);
                if (raw != null) {
                    all.add(raw);
                }
            }
            if (page.size() < pageSize) {
                break;
            }
            offset += pageSize;
        }
        return all;
    }

    private List<JsonNode> fetchTransactionPage(String address, int limit, int offset) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("account", address);
        params.put("limit", String.valueOf(limit));
        params.put("offset", String.valueOf(offset));
        String body = rpcClient.get("transactions", params);
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode txArray = root.path("transactions");
            if (!txArray.isArray()) {
                log.warn("TON transactions response missing 'transactions' array for {}", address);
                return List.of();
            }
            List<JsonNode> result = new ArrayList<>(txArray.size());
            txArray.forEach(result::add);
            return result;
        } catch (Exception e) {
            throw new RpcException("Failed to parse TON transactions response for " + address, e);
        }
    }

    /**
     * Fetches owner-addressed jetton transfers for a page and indexes them by {@code trace_id}
     * (primary correlation key) and {@code transaction_hash} (fallback). The owner address is
     * passed in friendly form (raw {@code 0:hex} causes the endpoint to time out) and no direction
     * filter is used, so both incoming and outgoing jetton transfers are captured.
     *
     * <p>The {@code /jetton/transfers} endpoint is flaky on the free tier (spurious {@code count=0}
     * / timeouts on consecutive identical calls), so it is retried on timeout / 5xx / empty. A
     * persistent failure is logged (WARN) rather than silently swallowed, and never aborts the page.</p>
     */
    private JettonTransferIndex fetchJettonTransfers(String address, int limit, int offset) {
        int maxAttempts = Math.max(1, properties.getJettonFetchMaxAttempts());
        RpcException lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                Map<String, String> params = new LinkedHashMap<>();
                params.put("owner_address", address);
                params.put("limit", String.valueOf(limit));
                params.put("offset", String.valueOf(offset));
                String body = rpcClient.get("jetton/transfers", params);
                JsonNode root = objectMapper.readTree(body);
                JsonNode transfers = root.path("jetton_transfers");
                JettonTransferIndex index = new JettonTransferIndex();
                if (transfers.isArray()) {
                    transfers.forEach(jt -> index.add(jsonNodeToDocument(jt),
                            jt.path("trace_id").asText(null),
                            jt.path("transaction_hash").asText(null)));
                }
                if (!index.isEmpty()) {
                    return index;
                }
                // Empty result: may be a spurious free-tier count=0. Retry; if consistently empty
                // (no error) it is treated as a genuinely jetton-free page and returned empty.
            } catch (RpcException e) {
                lastError = e;
            } catch (Exception e) {
                lastError = new RpcException("Failed to parse TON jetton transfers for " + address, e);
            }
            backoffBeforeRetry(attempt, maxAttempts);
        }
        if (lastError != null) {
            log.warn("TON jetton transfers unresolved after {} attempts for {} (offset {}): {}",
                    maxAttempts, address, offset, lastError.getMessage());
        }
        return new JettonTransferIndex();
    }

    private void backoffBeforeRetry(int attempt, int maxAttempts) {
        if (attempt >= maxAttempts) {
            return;
        }
        long backoff = Math.max(0L, properties.getJettonFetchBackoffMillis()) * attempt;
        if (backoff <= 0L) {
            return;
        }
        try {
            Thread.sleep(backoff);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private RawTransaction buildRaw(JsonNode txNode, String walletAddress,
                                     JettonTransferIndex jettonIndex) {
        String hash = txNode.path("hash").asText(null);
        if (hash == null || hash.isBlank()) {
            return null;
        }
        long nowEpoch = txNode.path("now").asLong(0L);

        RawTransaction tx = new RawTransaction();
        tx.setId(hash + ":" + NetworkId.TON.name() + ":" + walletAddress);
        tx.setTxHash(hash);
        tx.setNetworkId(NetworkId.TON.name());
        tx.setWalletAddress(walletAddress);
        tx.setSyncMethod(RawSyncMethod.RPC);
        tx.setNormalizationStatus(NormalizationStatus.PENDING);
        tx.setRetryCount(0);
        tx.setCreatedAt(Instant.now());

        Document rawData = new Document();
        rawData.put("transaction", jsonNodeToDocument(txNode));
        String traceId = txNode.path("trace_id").asText(null);
        List<Document> jettonTransfers = jettonIndex.lookup(traceId, hash);
        rawData.put("jettonTransfers", new ArrayList<>(jettonTransfers));
        rawData.put("source", "TONCENTER_V3");
        // Canonical ordering fields expected by PendingRawTransactionQueryService (EVM path)
        // TON transactions carry their own now timestamp — surfaced here for cross-chain sorting.
        if (nowEpoch > 0) {
            rawData.put("timeStamp", String.valueOf(nowEpoch));
        }
        rawData.put("transactionIndex", 0);
        tx.setRawData(rawData);

        return tx;
    }

    /**
     * Per-page jetton-transfer index. A jetton transfer is correlated to an owner-account
     * transaction by its shared {@code trace_id} (primary); {@code transaction_hash} is retained as
     * a fallback even though it usually names a different transaction in the trace.
     */
    private static final class JettonTransferIndex {
        private final Map<String, List<Document>> byTrace = new LinkedHashMap<>();
        private final Map<String, List<Document>> byHash = new LinkedHashMap<>();

        void add(Document transfer, String traceId, String transactionHash) {
            if (transfer == null) {
                return;
            }
            if (traceId != null && !traceId.isBlank()) {
                byTrace.computeIfAbsent(traceId, k -> new ArrayList<>()).add(transfer);
            }
            if (transactionHash != null && !transactionHash.isBlank()) {
                byHash.computeIfAbsent(transactionHash, k -> new ArrayList<>()).add(transfer);
            }
        }

        List<Document> lookup(String traceId, String transactionHash) {
            if (traceId != null && !traceId.isBlank()) {
                List<Document> byTraceMatch = byTrace.get(traceId);
                if (byTraceMatch != null && !byTraceMatch.isEmpty()) {
                    return byTraceMatch;
                }
            }
            if (transactionHash != null && !transactionHash.isBlank()) {
                List<Document> byHashMatch = byHash.get(transactionHash);
                if (byHashMatch != null && !byHashMatch.isEmpty()) {
                    return byHashMatch;
                }
            }
            return List.of();
        }

        boolean isEmpty() {
            return byTrace.isEmpty() && byHash.isEmpty();
        }
    }

    private Document jsonNodeToDocument(JsonNode node) {
        if (node == null || node.isNull()) {
            return new Document();
        }
        if (node.isObject()) {
            Document doc = new Document();
            node.fields().forEachRemaining(f -> {
                // Skip null/blank keys — MongoDB UpdateMapper rejects them
                if (f.getKey() != null && !f.getKey().isEmpty()) {
                    doc.put(f.getKey(), jsonNodeToValue(f.getValue()));
                }
            });
            return doc;
        }
        return new Document();
    }

    private Object jsonNodeToValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isIntegralNumber()) {
            return node.asLong();
        }
        if (node.isFloatingPointNumber()) {
            return node.asDouble();
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>(node.size());
            node.forEach(child -> list.add(jsonNodeToValue(child)));
            return list;
        }
        if (node.isObject()) {
            Document doc = new Document();
            node.fields().forEachRemaining(f -> {
                if (f.getKey() != null && !f.getKey().isEmpty()) {
                    doc.put(f.getKey(), jsonNodeToValue(f.getValue()));
                }
            });
            return doc;
        }
        return null;
    }
}
