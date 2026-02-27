package com.walletradar.ingestion.adapter.evm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.domain.ClassificationStatus;
import com.walletradar.domain.NetworkId;
import com.walletradar.domain.RawTransaction;
import com.walletradar.ingestion.adapter.NetworkAdapter;
import com.walletradar.ingestion.adapter.RpcEndpointRotator;
import com.walletradar.ingestion.adapter.RpcException;
import com.walletradar.ingestion.config.IngestionEvmRpcProperties;
import io.github.resilience4j.ratelimiter.RateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.StreamSupport;

/**
 * EVM adapter: eth_getLogs with per-network batch block size (ADR-011) and per-network RPC rotators (ADR-012).
 * Fetches ERC20 Transfer logs where the wallet is from or to, then enriches each tx with full receipt logs
 * so classifiers see Swap and other topics (e.g. Uniswap V3 Swap) and emit SWAP_BUY/SWAP_SELL instead of EXTERNAL_INBOUND.
 */
@Slf4j
@Component
public class EvmNetworkAdapter implements NetworkAdapter {

    private static final String TRANSFER_TOPIC = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";
    static final int MIN_CHUNK_SIZE = 50;
    static final int MAX_BATCH_SIZE = 50;

    private final Map<String, Long> batchUnsupportedUntilMs = new ConcurrentHashMap<>();
    private final Map<String, Long> endpointCooldownUntilMs = new ConcurrentHashMap<>();

    private final EvmRpcClient rpcClient;
    @Qualifier("evmRotatorsByNetwork")
    private final Map<String, RpcEndpointRotator> rotatorsByNetwork;
    @Qualifier("evmDefaultRpcEndpointRotator")
    private final RpcEndpointRotator defaultRotator;
    @Qualifier("evmRpcRateLimiter")
    private final RateLimiter evmRpcRateLimiter;
    private final IngestionEvmRpcProperties evmRpcProperties;
    private final ObjectMapper objectMapper;
    private final EvmBatchBlockSizeResolver batchBlockSizeResolver;

    public EvmNetworkAdapter(
            EvmRpcClient rpcClient,
            @Qualifier("evmRotatorsByNetwork") Map<String, RpcEndpointRotator> rotatorsByNetwork,
            @Qualifier("evmDefaultRpcEndpointRotator") RpcEndpointRotator defaultRotator,
            @Qualifier("evmRpcRateLimiter") RateLimiter evmRpcRateLimiter,
            IngestionEvmRpcProperties evmRpcProperties,
            ObjectMapper objectMapper,
            EvmBatchBlockSizeResolver batchBlockSizeResolver
    ) {
        this.rpcClient = rpcClient;
        this.rotatorsByNetwork = rotatorsByNetwork;
        this.defaultRotator = defaultRotator;
        this.evmRpcRateLimiter = evmRpcRateLimiter;
        this.evmRpcProperties = evmRpcProperties;
        this.objectMapper = objectMapper;
        this.batchBlockSizeResolver = batchBlockSizeResolver;
    }

    @Override
    public boolean supports(NetworkId networkId) {
        return networkId != NetworkId.SOLANA && networkId != null;
    }

    @Override
    public int getMaxBlockBatchSize() {
        return EvmBatchBlockSizeResolver.DEFAULT_BATCH_BLOCK_SIZE;
    }

    @Override
    public List<RawTransaction> fetchTransactions(String walletAddress, NetworkId networkId, long fromBlock, long toBlock) {
        if (fromBlock > toBlock) {
            return List.of();
        }
        int batchBlocks = batchBlockSizeResolver.resolve(networkId);
        String networkIdStr = networkId.name();
        RpcEndpointRotator rotator = rotatorsByNetwork.getOrDefault(networkIdStr, defaultRotator);
        String fromTopic = padAddressForTopic(walletAddress);
        List<RawTransaction> all = new ArrayList<>();
        long start = fromBlock;
        while (start <= toBlock) {
            long end = Math.min(start + batchBlocks - 1, toBlock);
            List<RawTransaction> batch = fetchChunkWithRetry(walletAddress, fromTopic, networkIdStr, start, end, rotator);
            all.addAll(batch);
            start = end + 1;
        }
        return all;
    }

    private List<RawTransaction> fetchChunkWithRetry(String walletAddress, String fromTopic, String networkIdStr, long fromBlock, long toBlock, RpcEndpointRotator rotator) {
        Exception lastException = null;
        for (int attempt = 0; attempt < rotator.getMaxAttempts(); attempt++) {
            if (attempt > 0) {
                try {
                    Thread.sleep(rotator.retryDelayMs(attempt - 1));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RpcException("Interrupted during retry", e);
                }
            }
            String endpoint = nextEndpoint(rotator);
            try {
                List<JsonNode> fromLogs;
                List<JsonNode> toLogs;
                boolean batchSupported = isBatchSupported(endpoint);
                if (batchSupported) {
                    try {
                        List<JsonNode>[] bothLogs = batchEthGetLogs(endpoint, fromBlock, toBlock,
                                Arrays.asList(TRANSFER_TOPIC, fromTopic),
                                Arrays.asList(TRANSFER_TOPIC, null, fromTopic));
                        fromLogs = bothLogs[0];
                        toLogs = bothLogs[1];
                    } catch (Exception batchEx) {
                        if (isRateLimitOrTransient(batchEx)) {
                            log.warn("Batch eth_getLogs transient/rate-limit on {}. Will retry with next endpoint. cause={}",
                                    endpoint, messageOf(batchEx));
                            throw batchEx;
                        }
                        markBatchUnsupported(endpoint, "eth_getLogs", batchEx);
                        fromLogs = ethGetLogs(endpoint, fromBlock, toBlock, Arrays.asList(TRANSFER_TOPIC, fromTopic), null);
                        toLogs = ethGetLogs(endpoint, fromBlock, toBlock, Arrays.asList(TRANSFER_TOPIC, null, fromTopic), null);
                    }
                } else {
                    fromLogs = ethGetLogs(endpoint, fromBlock, toBlock, Arrays.asList(TRANSFER_TOPIC, fromTopic), null);
                    toLogs = ethGetLogs(endpoint, fromBlock, toBlock, Arrays.asList(TRANSFER_TOPIC, null, fromTopic), null);
                }
                Map<String, List<JsonNode>> byTx = new HashMap<>();
                for (JsonNode log : fromLogs) {
                    String txHash = log.path("transactionHash").asText();
                    byTx.computeIfAbsent(txHash, k -> new ArrayList<>()).add(log);
                }
                for (JsonNode log : toLogs) {
                    String txHash = log.path("transactionHash").asText();
                    byTx.computeIfAbsent(txHash, k -> new ArrayList<>()).add(log);
                }
                Map<String, JsonNode> receiptsByTx = new HashMap<>();
                boolean receiptsBatchSupported = batchSupported && isBatchSupported(endpoint);
                if (receiptsBatchSupported) {
                    try {
                        Map<String, JsonNode> batchReceipts = batchGetTransactionReceipts(endpoint, byTx.keySet());
                        receiptsByTx.putAll(batchReceipts);
                        for (String txHash : byTx.keySet()) {
                            if (!receiptsByTx.containsKey(txHash)) {
                                JsonNode fullReceipt = getFullTransactionReceipt(endpoint, txHash);
                                if (fullReceipt != null) {
                                    receiptsByTx.put(txHash, fullReceipt);
                                }
                            }
                        }
                    } catch (Exception batchEx) {
                        if (isRateLimitOrTransient(batchEx)) {
                            log.warn("Batch receipts transient/rate-limit on {}. Will retry with next endpoint. cause={}",
                                    endpoint, messageOf(batchEx));
                            throw batchEx;
                        }
                        markBatchUnsupported(endpoint, "eth_getTransactionReceipt(batch)", batchEx);
                        for (String txHash : byTx.keySet()) {
                            JsonNode fullReceipt = getFullTransactionReceipt(endpoint, txHash);
                            if (fullReceipt != null) {
                                receiptsByTx.put(txHash, fullReceipt);
                            }
                        }
                    }
                } else {
                    for (String txHash : byTx.keySet()) {
                        JsonNode fullReceipt = getFullTransactionReceipt(endpoint, txHash);
                        if (fullReceipt != null) {
                            receiptsByTx.put(txHash, fullReceipt);
                        }
                    }
                }
                return receiptsByTx.entrySet().stream()
                        .map(e -> toRawTransaction(e.getKey(), networkIdStr, e.getValue(), walletAddress))
                        .toList();
            } catch (Exception e) {
                lastException = e;
                if (isRateLimited(e)) {
                    markEndpointCoolingDown(endpoint, e);
                } else if (isEndpointTransientUnavailable(e)) {
                    markEndpointCoolingDown(endpoint, e, evmRpcProperties.getTransientErrorCooldownMs(), "transient upstream error");
                }
                if (isRangeTooWideError(e) && (toBlock - fromBlock) > MIN_CHUNK_SIZE) {
                    log.warn("Reducing block range [{}-{}] due to RPC limitation on {}: {}",
                            fromBlock, toBlock, endpoint, e.getMessage());
                    long mid = fromBlock + (toBlock - fromBlock) / 2;
                    List<RawTransaction> first = fetchChunkWithRetry(walletAddress, fromTopic, networkIdStr, fromBlock, mid, rotator);
                    List<RawTransaction> second = fetchChunkWithRetry(walletAddress, fromTopic, networkIdStr, mid + 1, toBlock, rotator);
                    List<RawTransaction> combined = new ArrayList<>(first);
                    combined.addAll(second);
                    return combined;
                }
                if (isRangeTooWideError(e)) {
                    log.warn("RPC at {} requires address filter for eth_getLogs or block range is too narrow to split further. "
                            + "Consider replacing with a more permissive RPC.", endpoint);
                }
            }
        }
        String msg = "RPC failed after " + rotator.getMaxAttempts() + " attempts";
        if (lastException != null && lastException.getMessage() != null && !lastException.getMessage().isBlank()) {
            msg += ": " + lastException.getMessage();
        }
        throw new RpcException(msg, lastException);
    }

    public static boolean isRangeTooWideError(Exception e) {
        if (e == null || e.getMessage() == null) return false;
        String msg = e.getMessage().toLowerCase();
        return msg.contains("-32701") || msg.contains("specify an address")
                || msg.contains("query returned more than") || msg.contains("too many results")
                || msg.contains("block range is too wide") || msg.contains("exceed maximum block range")
                || msg.contains("log response size exceeded");
    }

    /**
     * True if the error is transient or rate-limit related. For such errors we should retry (possibly with another
     * endpoint), not permanently mark the endpoint as "batch unsupported" (which would double request volume).
     */
    public static boolean isRateLimitOrTransient(Exception e) {
        if (e == null) return false;
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        return msg.contains("429") || msg.contains("too many requests")
                || msg.contains("rate limit") || msg.contains("limit exceeded")
                || msg.contains("request limit") || msg.contains("throughput")
                || msg.contains("quota")
                || msg.contains("401") || msg.contains("unauthorized")
                || msg.contains("503") || msg.contains("502") || msg.contains("500") || msg.contains("504")
                || msg.contains("timeout") || msg.contains("timed out")
                || msg.contains("failed to resolve") || msg.contains("connection refused")
                || msg.contains("temporary") || msg.contains("retry")
                || msg.contains("code:19") || msg.contains("code\":19")
                || msg.contains("code:30") || msg.contains("code\":30")
                || msg.contains("-32005");
    }

    static boolean isRateLimited(Exception e) {
        if (e == null || e.getMessage() == null) return false;
        String msg = e.getMessage().toLowerCase();
        return msg.contains("429") || msg.contains("too many requests")
                || msg.contains("rate limit") || msg.contains("limit exceeded")
                || msg.contains("request limit") || msg.contains("-32005");
    }

    static boolean isEndpointTransientUnavailable(Exception e) {
        if (e == null || e.getMessage() == null) return false;
        String msg = e.getMessage().toLowerCase();
        return msg.contains("temporary internal error")
                || msg.contains("please retry")
                || msg.contains("code:19") || msg.contains("code\":19")
                || msg.contains("code:30") || msg.contains("code\":30")
                || msg.contains("timed out") || msg.contains("timeout")
                || msg.contains("503") || msg.contains("502") || msg.contains("504");
    }

    /**
     * Batch two eth_getLogs calls into a single JSON-RPC batch HTTP request.
     * Returns a two-element array: [fromLogs, toLogs].
     */
    @SuppressWarnings("unchecked")
    private List<JsonNode>[] batchEthGetLogs(String endpoint, long fromBlock, long toBlock,
                                              List<Object> fromTopics, List<Object> toTopics) {
        Map<String, Object> filterFrom = buildLogFilter(fromBlock, toBlock, fromTopics, null);
        Map<String, Object> filterTo = buildLogFilter(fromBlock, toBlock, toTopics, null);

        List<RpcRequest> requests = List.of(
                new RpcRequest("eth_getLogs", Collections.singletonList(filterFrom)),
                new RpcRequest("eth_getLogs", Collections.singletonList(filterTo))
        );
        String json = batchCallRpc(endpoint, requests);
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new RpcException("Failed to parse batch eth_getLogs response", e);
        }
        if (!root.isArray() || root.size() < 2) {
            throw new RpcException("Batch eth_getLogs: expected array of 2 responses, got: " + (root.isArray() ? root.size() : "non-array"));
        }

        Map<Integer, JsonNode> byId = new HashMap<>();
        for (JsonNode resp : root) {
            byId.put(resp.path("id").asInt(), resp);
        }

        List<JsonNode>[] result = new List[2];
        for (int i = 0; i < 2; i++) {
            JsonNode resp = byId.get(i + 1);
            if (resp == null) {
                throw new RpcException("Batch eth_getLogs: missing response for id " + (i + 1));
            }
            JsonNode error = resp.path("error");
            if (!error.isMissingNode()) {
                throw new RpcException("eth_getLogs error: " + error.toString());
            }
            JsonNode respResult = resp.path("result");
            if (!respResult.isArray()) {
                result[i] = List.of();
            } else {
                List<JsonNode> list = new ArrayList<>();
                respResult.forEach(list::add);
                result[i] = list;
            }
        }
        return result;
    }

    private Map<String, Object> buildLogFilter(long fromBlock, long toBlock, List<Object> topics, String address) {
        Map<String, Object> filter = new HashMap<>();
        filter.put("fromBlock", "0x" + Long.toHexString(fromBlock));
        filter.put("toBlock", "0x" + Long.toHexString(toBlock));
        if (topics != null) {
            filter.put("topics", topics);
        }
        if (address != null) {
            filter.put("address", address);
        }
        return filter;
    }

    /**
     * Batch-fetch full transaction receipts (ADR-020). Returns full eth_getTransactionReceipt response per txHash.
     */
    private Map<String, JsonNode> batchGetTransactionReceipts(String endpoint, Set<String> txHashes) {
        if (txHashes.isEmpty()) return Map.of();
        List<String> txHashList = new ArrayList<>(txHashes);
        Map<String, JsonNode> result = new HashMap<>();

        for (int i = 0; i < txHashList.size(); i += MAX_BATCH_SIZE) {
            int end = Math.min(i + MAX_BATCH_SIZE, txHashList.size());
            List<String> subBatch = txHashList.subList(i, end);
            List<RpcRequest> subRequests = subBatch.stream()
                    .map(hash -> new RpcRequest("eth_getTransactionReceipt", Collections.singletonList(hash)))
                    .toList();

            String json = batchCallRpc(endpoint, subRequests);
            parseBatchReceiptResponse(json, subBatch, result);
        }
        return result;
    }

    private void parseBatchReceiptResponse(String json, List<String> txHashes, Map<String, JsonNode> result) {
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new RpcException("Failed to parse batch receipt response", e);
        }
        if (!root.isArray()) {
            throw new RpcException("Batch receipt: expected JSON array response");
        }
        Map<Integer, JsonNode> byId = new HashMap<>();
        for (JsonNode resp : root) {
            byId.put(resp.path("id").asInt(), resp);
        }
        for (int i = 0; i < txHashes.size(); i++) {
            JsonNode resp = byId.get(i + 1);
            if (resp == null) continue;
            JsonNode error = resp.path("error");
            if (!error.isMissingNode()) continue;
            JsonNode receipt = resp.path("result");
            if (receipt.isMissingNode() || receipt.isNull() || !receipt.has("logs")) continue;
            result.put(txHashes.get(i), receipt);
        }
    }

    /** Fetches full receipt (ADR-020) — blockNumber, blockHash, logs, gasUsed, status, from, to, etc. */
    private JsonNode getFullTransactionReceipt(String endpoint, String txHash) {
        try {
            String json = callRpc(endpoint, "eth_getTransactionReceipt", Collections.singletonList(txHash));
            if (json == null) return null;
            JsonNode root = objectMapper.readTree(json);
            JsonNode error = root.path("error");
            if (!error.isMissingNode()) return null;
            JsonNode result = root.path("result");
            if (result.isMissingNode() || result.isNull() || !result.has("logs")) return null;
            return result;
        } catch (Exception e) {
            return null;
        }
    }

    private List<JsonNode> ethGetLogs(String endpoint, long fromBlock, long toBlock, List<Object> topics, String address) {
        Map<String, Object> filter = buildLogFilter(fromBlock, toBlock, topics, address);
        String json = callRpc(endpoint, "eth_getLogs", Collections.singletonList(filter));
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new RpcException("Failed to parse eth_getLogs response", e);
        }
        JsonNode error = root.path("error");
        if (!error.isMissingNode()) {
            throw new RpcException("eth_getLogs error: " + error.toString());
        }
        JsonNode result = root.path("result");
        if (!result.isArray()) {
            return List.of();
        }
        List<JsonNode> list = new ArrayList<>();
        result.forEach(list::add);
        return list;
    }

    /**
     * Build RawTransaction with full receipt in rawData (ADR-020). Classifiers read rawData.get("logs").
     */
    private RawTransaction toRawTransaction(String txHash, String networkId, JsonNode receipt, String walletAddress) {
        RawTransaction tx = new RawTransaction();
        tx.setId(txHash + ":" + networkId);
        tx.setTxHash(txHash);
        tx.setNetworkId(networkId);
        tx.setWalletAddress(walletAddress);
        tx.setClassificationStatus(ClassificationStatus.PENDING);
        tx.setCreatedAt(Instant.now());
        Long blockNum = parseBlockNumber(receipt.path("blockNumber").asText());
        tx.setBlockNumber(blockNum);
        Document rawData = jsonNodeToDocument(receipt);
        tx.setRawData(rawData);
        return tx;
    }

    private static Long parseBlockNumber(String hex) {
        if (hex == null || !hex.startsWith("0x")) return 0L;
        try {
            return Long.parseLong(hex.substring(2), 16);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** Convert JsonNode to BSON Document for full receipt storage. */
    private Document jsonNodeToDocument(JsonNode node) {
        if (node == null || node.isNull()) return new Document();
        try {
            return Document.parse(objectMapper.writeValueAsString(node));
        } catch (JsonProcessingException e) {
            throw new RpcException("Failed to convert receipt to Document", e);
        }
    }

    private static Document logToDocument(JsonNode log) {
        Document d = new Document();
        if (log.has("address")) d.put("address", log.get("address").asText());
        if (log.has("topics")) {
            List<String> topics = StreamSupport.stream(log.get("topics").spliterator(), false)
                    .map(JsonNode::asText).toList();
            d.put("topics", topics);
        }
        if (log.has("data")) d.put("data", log.get("data").asText());
        if (log.has("transactionHash")) d.put("transactionHash", log.get("transactionHash").asText());
        if (log.has("blockNumber")) d.put("blockNumber", log.get("blockNumber").asText());
        if (log.has("logIndex")) d.put("logIndex", log.get("logIndex").asText());
        return d;
    }

    private static String padAddressForTopic(String address) {
        String hex = address.toLowerCase().startsWith("0x") ? address.substring(2) : address;
        return "0x" + "0".repeat(24) + hex;
    }

    private String callRpc(String endpoint, String method, Object params) {
        long acquireStart = System.nanoTime();
        boolean permitted = evmRpcRateLimiter.acquirePermission();
        long waitedMs = (System.nanoTime() - acquireStart) / 1_000_000L;
        if (!permitted) {
            throw new RpcException("Local limiter timeout before " + method + " on " + endpoint);
        }
        if (waitedMs >= Math.max(1L, evmRpcProperties.getLocalLimiterLogThresholdMs())) {
            log.info("Local EVM RPC limiter delayed {} ms before {} on {}", waitedMs, method, endpoint);
        }
        return rpcClient.call(endpoint, method, params).block();
    }

    private String batchCallRpc(String endpoint, List<RpcRequest> requests) {
        long acquireStart = System.nanoTime();
        boolean permitted = evmRpcRateLimiter.acquirePermission();
        long waitedMs = (System.nanoTime() - acquireStart) / 1_000_000L;
        if (!permitted) {
            String method = requests.isEmpty() ? "batch" : requests.get(0).method();
            throw new RpcException("Local limiter timeout before batch " + method + " on " + endpoint);
        }
        if (waitedMs >= Math.max(1L, evmRpcProperties.getLocalLimiterLogThresholdMs())) {
            String method = requests.isEmpty() ? "batch" : requests.get(0).method();
            log.info("Local EVM RPC limiter delayed {} ms before batch {} ({} req) on {}",
                    waitedMs, method, requests.size(), endpoint);
        }
        return rpcClient.batchCall(endpoint, requests).block();
    }

    private String nextEndpoint(RpcEndpointRotator rotator) {
        long nowMs = System.currentTimeMillis();
        List<String> endpoints = rotator.getEndpoints();
        int checked = Math.max(1, endpoints.size());
        for (int i = 0; i < checked; i++) {
            String endpoint = rotator.getNextEndpoint();
            Long cooldownUntil = endpointCooldownUntilMs.get(endpoint);
            if (cooldownUntil == null || cooldownUntil <= nowMs) {
                return endpoint;
            }
            log.debug("Skipping cooled-down endpoint {} for {} ms", endpoint, cooldownUntil - nowMs);
        }
        return rotator.getNextEndpoint();
    }

    private void markEndpointCoolingDown(String endpoint, Exception cause) {
        long cooldownMs = Math.max(1_000L, evmRpcProperties.getEndpointCooldownMs());
        markEndpointCoolingDown(endpoint, cause, cooldownMs, "suspected RPC rate-limit");
    }

    private void markEndpointCoolingDown(String endpoint, Exception cause, long cooldownMs, String reason) {
        long effectiveCooldownMs = Math.max(1_000L, cooldownMs);
        long nowMs = System.currentTimeMillis();
        long until = nowMs + effectiveCooldownMs;
        Long prevUntil = endpointCooldownUntilMs.put(endpoint, until);
        if (prevUntil == null || prevUntil <= nowMs) {
            log.warn("Endpoint {} cooled down for {} ms due to {}: {}",
                    endpoint, effectiveCooldownMs, reason, messageOf(cause));
        } else {
            log.debug("Endpoint {} already in cooldown ({} ms left)", endpoint, prevUntil - nowMs);
        }
    }

    private void markBatchUnsupported(String endpoint, String operation, Exception cause) {
        long cooldownMs = Math.max(30_000L, evmRpcProperties.getBatchUnsupportedCooldownMs());
        long nowMs = System.currentTimeMillis();
        long until = nowMs + cooldownMs;
        AtomicBoolean firstMark = new AtomicBoolean(false);
        batchUnsupportedUntilMs.compute(endpoint, (ep, prevUntil) -> {
            if (prevUntil == null || prevUntil <= nowMs) {
                firstMark.set(true);
                return until;
            }
            return prevUntil;
        });
        if (firstMark.get()) {
            log.info("Batch {} disabled on {} for {} ms (fallback to sequential). cause={}",
                    operation, endpoint, cooldownMs, messageOf(cause));
        }
    }

    private boolean isBatchSupported(String endpoint) {
        Long until = batchUnsupportedUntilMs.get(endpoint);
        return until == null || until <= System.currentTimeMillis();
    }

    private static String messageOf(Exception e) {
        if (e == null || e.getMessage() == null || e.getMessage().isBlank()) {
            return "unknown";
        }
        return e.getMessage();
    }
}
