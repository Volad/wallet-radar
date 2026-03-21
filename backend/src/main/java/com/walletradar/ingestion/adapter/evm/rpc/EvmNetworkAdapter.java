package com.walletradar.ingestion.adapter.evm.rpc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.domain.transaction.raw.RawSyncMethod;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.raw.NormalizationStatus;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.ingestion.adapter.NetworkAdapter;
import com.walletradar.ingestion.adapter.RpcEndpointRotator;
import com.walletradar.ingestion.adapter.RpcException;
import com.walletradar.ingestion.config.IngestionEvmRpcProperties;
import io.github.resilience4j.ratelimiter.RateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.StreamSupport;

/**
 * EVM adapter that fetches wallet-related Transfer logs and enriches each transaction with full receipts.
 */
@Slf4j
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class EvmNetworkAdapter implements NetworkAdapter {

    private static final String TRANSFER_TOPIC = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";
    private static final String ERC20_DECIMALS_SELECTOR = "0x313ce567";
    private static final String ERC20_SYMBOL_SELECTOR = "0x95d89b41";
    private static final String ERC20_NAME_SELECTOR = "0x06fdde03";
    static final int MIN_CHUNK_SIZE = 50;
    static final int MAX_BATCH_SIZE = 50;

    private final Map<String, Long> batchUnsupportedUntilMs = new ConcurrentHashMap<>();
    private final Map<String, Long> endpointCooldownUntilMs = new ConcurrentHashMap<>();
    private final Map<String, TokenMetadata> tokenMetadataCache = new ConcurrentHashMap<>();

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
    private final DirectWalletRpcDiscovery directWalletRpcDiscovery;

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
        this.directWalletRpcDiscovery = new DirectWalletRpcDiscovery(objectMapper);
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
        String lastEndpoint = null;
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
            lastEndpoint = endpoint;
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
                            log.debug("Batch eth_getLogs transient/rate-limit on {}. Will retry with next endpoint. cause={}",
                                    endpoint, messageOf(batchEx));
                            throw batchEx;
                        }
                        if (isRangeTooWideError(batchEx) || isUnknownBlockError(batchEx)) {
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
                Set<String> txHashes = new LinkedHashSet<>();
                for (JsonNode log : fromLogs) {
                    String txHash = log.path("transactionHash").asText();
                    if (!txHash.isBlank()) {
                        txHashes.add(txHash.toLowerCase(Locale.ROOT));
                    }
                }
                for (JsonNode log : toLogs) {
                    String txHash = log.path("transactionHash").asText();
                    if (!txHash.isBlank()) {
                        txHashes.add(txHash.toLowerCase(Locale.ROOT));
                    }
                }

                Map<String, DirectWalletRpcDiscovery.DiscoveredTransaction> directTransactions = supportsDirectWalletDiscovery(networkIdStr)
                        ? directWalletRpcDiscovery.discover(
                        endpoint,
                        walletAddress,
                        fromBlock,
                        toBlock,
                        new DirectWalletRpcDiscovery.RpcInvoker() {
                            @Override
                            public String call(String rpcEndpoint, String method, Object params) {
                                return callRpc(rpcEndpoint, method, params);
                            }

                            @Override
                            public String batchCall(String rpcEndpoint, List<RpcRequest> requests) {
                                return batchCallRpc(rpcEndpoint, requests);
                            }
                        }
                )
                        : Map.of();
                txHashes.addAll(directTransactions.keySet());

                if (txHashes.isEmpty()) {
                    return List.of();
                }

                Map<String, JsonNode> receiptsByTx = new HashMap<>();
                boolean receiptsBatchSupported = batchSupported && isBatchSupported(endpoint);
                if (receiptsBatchSupported) {
                    try {
                        Map<String, JsonNode> batchReceipts = batchGetTransactionReceipts(endpoint, txHashes);
                        receiptsByTx.putAll(batchReceipts);
                        for (String txHash : txHashes) {
                            if (!receiptsByTx.containsKey(txHash)) {
                                JsonNode fullReceipt = getFullTransactionReceipt(endpoint, txHash);
                                if (fullReceipt != null) {
                                    receiptsByTx.put(txHash, fullReceipt);
                                }
                            }
                        }
                    } catch (Exception batchEx) {
                        if (isRateLimitOrTransient(batchEx)) {
                            log.debug("Batch receipts transient/rate-limit on {}. Will retry with next endpoint. cause={}",
                                    endpoint, messageOf(batchEx));
                            throw batchEx;
                        }
                        markBatchUnsupported(endpoint, "eth_getTransactionReceipt(batch)", batchEx);
                        for (String txHash : txHashes) {
                            JsonNode fullReceipt = getFullTransactionReceipt(endpoint, txHash);
                            if (fullReceipt != null) {
                                receiptsByTx.put(txHash, fullReceipt);
                            }
                        }
                    }
                } else {
                    for (String txHash : txHashes) {
                        JsonNode fullReceipt = getFullTransactionReceipt(endpoint, txHash);
                        if (fullReceipt != null) {
                            receiptsByTx.put(txHash, fullReceipt);
                        }
                    }
                }

                Map<String, JsonNode> transactionsByTx = new HashMap<>();
                directTransactions.forEach((txHash, discovered) -> transactionsByTx.put(txHash, discovered.transaction()));
                Set<String> missingTransactions = new LinkedHashSet<>(txHashes);
                missingTransactions.removeAll(transactionsByTx.keySet());
                transactionsByTx.putAll(batchGetTransactionsByHash(endpoint, missingTransactions));

                Map<Long, Long> timestampByBlock = new HashMap<>();
                directTransactions.values().forEach(discovered ->
                        timestampByBlock.put(discovered.blockNumber(), discovered.timestamp())
                );
                resolveMissingBlockTimestamps(endpoint, collectBlockNumbers(receiptsByTx, transactionsByTx), timestampByBlock);

                return txHashes.stream()
                        .map(txHash -> toRawTransaction(
                                txHash,
                                networkIdStr,
                                receiptsByTx.get(txHash),
                                transactionsByTx.get(txHash),
                                timestampByBlock.get(resolveBlockNumber(receiptsByTx.get(txHash), transactionsByTx.get(txHash))),
                                walletAddress,
                                endpoint
                        ))
                        .filter(Objects::nonNull)
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
                if (isUnknownBlockError(lastException)) {
                    log.warn("Skipping block range [{}-{}] on {} (endpoint={}) due to persistent unknown-block error: {}",
                            fromBlock, toBlock, networkIdStr, lastEndpoint, messageOf(lastException));
                    return List.of();
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

    static boolean isUnknownBlockError(Exception e) {
        if (e == null || e.getMessage() == null) return false;
        String msg = e.getMessage().toLowerCase();
        return msg.contains("unknown block")
                || msg.contains("code:26")
                || msg.contains("code\":26")
                || msg.contains("code\": 26");
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

    private boolean supportsDirectWalletDiscovery(String networkIdStr) {
        return "BSC".equalsIgnoreCase(networkIdStr);
    }

    /**
     * Batch-fetches full transaction receipts and returns the eth_getTransactionReceipt payload per tx hash.
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

    private Map<String, JsonNode> batchGetTransactionsByHash(String endpoint, Set<String> txHashes) {
        if (txHashes.isEmpty()) {
            return Map.of();
        }
        List<String> txHashList = new ArrayList<>(txHashes);
        Map<String, JsonNode> result = new HashMap<>();
        for (int i = 0; i < txHashList.size(); i += MAX_BATCH_SIZE) {
            int end = Math.min(i + MAX_BATCH_SIZE, txHashList.size());
            List<String> subBatch = txHashList.subList(i, end);
            try {
                List<RpcRequest> requests = subBatch.stream()
                        .map(hash -> new RpcRequest("eth_getTransactionByHash", Collections.singletonList(hash)))
                        .toList();
                String json = batchCallRpc(endpoint, requests);
                parseBatchTransactionResponse(json, subBatch, result);
            } catch (Exception batchFailure) {
                for (String txHash : subBatch) {
                    JsonNode transaction = getTransactionByHash(endpoint, txHash);
                    if (transaction != null) {
                        result.put(txHash, transaction);
                    }
                }
            }
        }
        return result;
    }

    private void parseBatchTransactionResponse(String json, List<String> txHashes, Map<String, JsonNode> result) {
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new RpcException("Failed to parse batch transaction response", e);
        }
        if (!root.isArray()) {
            throw new RpcException("Batch transaction: expected JSON array response");
        }
        Map<Integer, JsonNode> byId = new HashMap<>();
        for (JsonNode response : root) {
            byId.put(response.path("id").asInt(), response);
        }
        for (int i = 0; i < txHashes.size(); i++) {
            JsonNode response = byId.get(i + 1);
            if (response == null) {
                continue;
            }
            JsonNode error = response.path("error");
            if (!error.isMissingNode()) {
                continue;
            }
            JsonNode transaction = response.path("result");
            if (transaction.isMissingNode() || transaction.isNull()) {
                continue;
            }
            result.put(txHashes.get(i), transaction);
        }
    }

    private void resolveMissingBlockTimestamps(String endpoint, Set<Long> blockNumbers, Map<Long, Long> timestampByBlock) {
        Set<Long> missing = new LinkedHashSet<>();
        for (Long blockNumber : blockNumbers) {
            if (blockNumber != null && blockNumber > 0L && !timestampByBlock.containsKey(blockNumber)) {
                missing.add(blockNumber);
            }
        }
        if (missing.isEmpty()) {
            return;
        }

        List<Long> blockList = new ArrayList<>(missing);
        for (int i = 0; i < blockList.size(); i += MAX_BATCH_SIZE) {
            int end = Math.min(i + MAX_BATCH_SIZE, blockList.size());
            List<Long> subBatch = blockList.subList(i, end);
            try {
                List<RpcRequest> requests = subBatch.stream()
                        .map(blockNumber -> new RpcRequest(
                                "eth_getBlockByNumber",
                                List.of("0x" + Long.toHexString(blockNumber), false)
                        ))
                        .toList();
                String json = batchCallRpc(endpoint, requests);
                parseBatchBlockTimestampResponse(json, subBatch, timestampByBlock);
            } catch (Exception batchFailure) {
                for (Long blockNumber : subBatch) {
                    Long epochSeconds = getBlockTimestamp(endpoint, blockNumber);
                    if (epochSeconds != null) {
                        timestampByBlock.put(blockNumber, epochSeconds);
                    }
                }
            }
        }
    }

    private void parseBatchBlockTimestampResponse(String json, List<Long> blockNumbers, Map<Long, Long> timestampByBlock) {
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new RpcException("Failed to parse batch block response", e);
        }
        if (!root.isArray()) {
            throw new RpcException("Batch block: expected JSON array response");
        }
        Map<Integer, JsonNode> byId = new HashMap<>();
        for (JsonNode response : root) {
            byId.put(response.path("id").asInt(), response);
        }
        for (int i = 0; i < blockNumbers.size(); i++) {
            JsonNode response = byId.get(i + 1);
            if (response == null) {
                continue;
            }
            JsonNode error = response.path("error");
            if (!error.isMissingNode()) {
                continue;
            }
            JsonNode block = response.path("result");
            if (block.isMissingNode() || block.isNull()) {
                continue;
            }
            Long epochSeconds = parseHexLong(block.path("timestamp").asText(null));
            if (epochSeconds != null) {
                timestampByBlock.put(blockNumbers.get(i), epochSeconds);
            }
        }
    }

    /** Fetches the full receipt payload: blockNumber, blockHash, logs, gasUsed, status, from, to, and related fields. */
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

    private JsonNode getTransactionByHash(String endpoint, String txHash) {
        try {
            String json = callRpc(endpoint, "eth_getTransactionByHash", Collections.singletonList(txHash));
            if (json == null) {
                return null;
            }
            JsonNode root = objectMapper.readTree(json);
            JsonNode error = root.path("error");
            if (!error.isMissingNode()) {
                return null;
            }
            JsonNode result = root.path("result");
            return result.isMissingNode() || result.isNull() ? null : result;
        } catch (Exception e) {
            return null;
        }
    }

    private Long getBlockTimestamp(String endpoint, Long blockNumber) {
        if (blockNumber == null || blockNumber <= 0L) {
            return null;
        }
        try {
            String json = callRpc(endpoint, "eth_getBlockByNumber", List.of("0x" + Long.toHexString(blockNumber), false));
            if (json == null) {
                return null;
            }
            JsonNode root = objectMapper.readTree(json);
            JsonNode error = root.path("error");
            if (!error.isMissingNode()) {
                return null;
            }
            JsonNode result = root.path("result");
            if (result.isMissingNode() || result.isNull()) {
                return null;
            }
            return parseHexLong(result.path("timestamp").asText(null));
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
     * Builds RawTransaction with the full receipt stored in rawData.
     */
    private RawTransaction toRawTransaction(
            String txHash,
            String networkId,
            JsonNode receipt,
            JsonNode transaction,
            Long epochSeconds,
            String walletAddress,
            String endpoint
    ) {
        if (receipt == null && transaction == null) {
            return null;
        }
        RawTransaction tx = new RawTransaction();
        tx.setId(txHash + ":" + networkId + ":" + walletAddress);
        tx.setTxHash(txHash);
        tx.setNetworkId(networkId);
        tx.setSyncMethod(RawSyncMethod.RPC);
        tx.setWalletAddress(walletAddress);
        tx.setNormalizationStatus(NormalizationStatus.PENDING);
        tx.setRetryCount(0);
        tx.setCreatedAt(Instant.now());
        Long blockNum = resolveBlockNumber(receipt, transaction);
        tx.setBlockNumber(blockNum);
        Document rawData = buildRawPayload(networkId, endpoint, receipt, transaction, epochSeconds, txHash);
        tx.setRawData(rawData);
        return tx;
    }

    private Document buildRawPayload(
            String networkId,
            String endpoint,
            JsonNode receipt,
            JsonNode transaction,
            Long epochSeconds,
            String txHash
    ) {
        Document rawData = receipt == null ? new Document() : jsonNodeToDocument(receipt);
        mergeTransactionFields(rawData, transaction);
        if (epochSeconds != null) {
            rawData.put("timeStamp", Long.toString(epochSeconds));
        }
        Long blockNumber = resolveBlockNumber(receipt, transaction);
        if (blockNumber != null && blockNumber > 0L) {
            rawData.put("blockNumber", Long.toString(blockNumber));
        }
        Integer transactionIndex = resolveTransactionIndex(receipt, transaction);
        if (transactionIndex != null) {
            rawData.put("transactionIndex", Integer.toString(transactionIndex));
        }
        String input = stringValue(rawData.get("input"));
        if (input != null && input.length() >= 10) {
            rawData.put("methodId", input.substring(0, 10).toLowerCase(Locale.ROOT));
        }
        String receiptStatus = normalizeReceiptStatus(receipt == null ? null : stringValue(receipt.path("status").asText(null)));
        if (receiptStatus != null) {
            rawData.put("txreceipt_status", receiptStatus);
            rawData.put("isError", "0".equals(receiptStatus) ? "1" : "0");
        }

        Document explorer = new Document();
        explorer.put("tx", buildExplorerTxDocument(rawData, txHash));
        explorer.put("tokenTransfers", buildRpcTokenTransfers(endpoint, networkId, receipt));
        explorer.put("internalTransfers", List.of());
        rawData.put("explorer", explorer);
        return rawData;
    }

    private void mergeTransactionFields(Document rawData, JsonNode transaction) {
        if (rawData == null || transaction == null || transaction.isMissingNode() || transaction.isNull()) {
            return;
        }
        copyIfPresent(rawData, "hash", transaction, "hash");
        copyIfPresent(rawData, "blockHash", transaction, "blockHash");
        copyIfPresent(rawData, "blockNumber", transaction, "blockNumber");
        copyIfPresent(rawData, "from", transaction, "from");
        copyIfPresent(rawData, "to", transaction, "to");
        copyIfPresent(rawData, "input", transaction, "input");
        copyIfPresent(rawData, "value", transaction, "value");
        copyIfPresent(rawData, "nonce", transaction, "nonce");
        copyIfPresent(rawData, "gas", transaction, "gas");
        copyIfPresent(rawData, "gasPrice", transaction, "gasPrice");
        copyIfPresent(rawData, "maxFeePerGas", transaction, "maxFeePerGas");
        copyIfPresent(rawData, "maxPriorityFeePerGas", transaction, "maxPriorityFeePerGas");
        copyIfPresent(rawData, "type", transaction, "type");
        copyIfPresent(rawData, "transactionIndex", transaction, "transactionIndex");
    }

    private Document buildExplorerTxDocument(Document rawData, String txHash) {
        Document explorerTx = new Document();
        if (txHash != null) {
            explorerTx.put("hash", txHash);
            explorerTx.put("txhash", txHash);
        }
        copyIfPresent(explorerTx, "blockNumber", rawData, "blockNumber");
        copyIfPresent(explorerTx, "timeStamp", rawData, "timeStamp");
        copyIfPresent(explorerTx, "transactionIndex", rawData, "transactionIndex");
        copyIfPresent(explorerTx, "from", rawData, "from");
        copyIfPresent(explorerTx, "to", rawData, "to");
        copyIfPresent(explorerTx, "input", rawData, "input");
        copyIfPresent(explorerTx, "value", rawData, "value");
        copyIfPresent(explorerTx, "methodId", rawData, "methodId");
        copyIfPresent(explorerTx, "txreceipt_status", rawData, "txreceipt_status");
        copyIfPresent(explorerTx, "isError", rawData, "isError");
        return explorerTx;
    }

    private List<Document> buildRpcTokenTransfers(String endpoint, String networkId, JsonNode receipt) {
        if (receipt == null || receipt.isMissingNode() || receipt.isNull()) {
            return List.of();
        }
        JsonNode logs = receipt.path("logs");
        if (!logs.isArray()) {
            return List.of();
        }
        List<Document> transfers = new ArrayList<>();
        for (JsonNode log : logs) {
            if (!isErc20TransferLog(log)) {
                continue;
            }
            String contractAddress = normalizeAddress(log.path("address").asText(null));
            if (contractAddress == null) {
                continue;
            }
            TokenMetadata metadata = resolveTokenMetadata(endpoint, networkId, contractAddress);
            if (metadata.decimals() == null) {
                continue;
            }
            Document transfer = new Document();
            transfer.put("contractAddress", contractAddress);
            transfer.put("from", topicAddress(log.path("topics").get(1).asText(null)));
            transfer.put("to", topicAddress(log.path("topics").get(2).asText(null)));
            transfer.put("value", parseHexQuantity(log.path("data").asText("0x0")).toString());
            transfer.put("tokenDecimal", Integer.toString(metadata.decimals()));
            if (metadata.symbol() != null) {
                transfer.put("tokenSymbol", metadata.symbol());
            }
            if (metadata.name() != null) {
                transfer.put("tokenName", metadata.name());
            }
            transfers.add(transfer);
        }
        return List.copyOf(transfers);
    }

    private TokenMetadata resolveTokenMetadata(String endpoint, String networkId, String contractAddress) {
        String cacheKey = networkId + "|" + contractAddress.toLowerCase(Locale.ROOT);
        return tokenMetadataCache.computeIfAbsent(cacheKey, key -> loadTokenMetadata(endpoint, contractAddress));
    }

    private TokenMetadata loadTokenMetadata(String endpoint, String contractAddress) {
        Integer decimals = decodeUint256(callContract(endpoint, contractAddress, ERC20_DECIMALS_SELECTOR));
        String symbol = decodeAbiString(callContract(endpoint, contractAddress, ERC20_SYMBOL_SELECTOR));
        String name = decodeAbiString(callContract(endpoint, contractAddress, ERC20_NAME_SELECTOR));
        return new TokenMetadata(decimals, symbol, name);
    }

    private String callContract(String endpoint, String contractAddress, String data) {
        try {
            String json = callRpc(
                    endpoint,
                    "eth_call",
                    List.of(Map.of("to", contractAddress, "data", data), "latest")
            );
            JsonNode root = objectMapper.readTree(json);
            JsonNode error = root.path("error");
            if (!error.isMissingNode()) {
                return null;
            }
            JsonNode result = root.path("result");
            if (result.isMissingNode() || result.isNull()) {
                return null;
            }
            return result.asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static Integer decodeUint256(String hexData) {
        BigInteger value = parseHexQuantityOrNull(hexData);
        if (value == null) {
            return null;
        }
        try {
            return value.intValueExact();
        } catch (ArithmeticException ex) {
            return null;
        }
    }

    private static String decodeAbiString(String hexData) {
        if (hexData == null || hexData.isBlank() || "0x".equalsIgnoreCase(hexData)) {
            return null;
        }
        byte[] bytes = hexToBytes(hexData);
        if (bytes.length == 0) {
            return null;
        }
        if (bytes.length == 32) {
            return trimAscii(bytes);
        }
        if (bytes.length < 64) {
            return null;
        }

        int offset = decodeWordAsInt(bytes, 0);
        if (offset < 0 || offset + 32 > bytes.length) {
            return trimAscii(Arrays.copyOf(bytes, Math.min(bytes.length, 32)));
        }
        int length = decodeWordAsInt(bytes, offset);
        int start = offset + 32;
        if (length < 0 || start + length > bytes.length) {
            return null;
        }
        return trimAscii(Arrays.copyOfRange(bytes, start, start + length));
    }

    private static int decodeWordAsInt(byte[] bytes, int offset) {
        byte[] word = Arrays.copyOfRange(bytes, offset, Math.min(bytes.length, offset + 32));
        if (word.length == 0) {
            return -1;
        }
        try {
            return new BigInteger(1, word).intValueExact();
        } catch (ArithmeticException ex) {
            return -1;
        }
    }

    private static byte[] hexToBytes(String hexData) {
        String normalized = hexData.startsWith("0x") || hexData.startsWith("0X")
                ? hexData.substring(2)
                : hexData;
        if (normalized.length() % 2 != 0) {
            normalized = "0" + normalized;
        }
        byte[] bytes = new byte[normalized.length() / 2];
        for (int i = 0; i < normalized.length(); i += 2) {
            bytes[i / 2] = (byte) Integer.parseInt(normalized.substring(i, i + 2), 16);
        }
        return bytes;
    }

    private static String trimAscii(byte[] bytes) {
        int end = bytes.length;
        while (end > 0 && bytes[end - 1] == 0) {
            end--;
        }
        if (end <= 0) {
            return null;
        }
        String decoded = new String(bytes, 0, end, StandardCharsets.UTF_8).trim();
        return decoded.isBlank() ? null : decoded;
    }

    private static boolean isErc20TransferLog(JsonNode log) {
        JsonNode topics = log.path("topics");
        return topics.isArray()
                && topics.size() == 3
                && TRANSFER_TOPIC.equalsIgnoreCase(topics.get(0).asText());
    }

    private static String topicAddress(String topic) {
        if (topic == null || topic.isBlank()) {
            return null;
        }
        String normalized = topic.toLowerCase(Locale.ROOT);
        if (!normalized.startsWith("0x") || normalized.length() < 42) {
            return null;
        }
        return normalizeAddress("0x" + normalized.substring(normalized.length() - 40));
    }

    private static String normalizeAddress(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.startsWith("0x")) {
            normalized = "0x" + normalized;
        }
        return normalized.length() == 42 ? normalized : null;
    }

    private static void copyIfPresent(Document target, String targetKey, JsonNode source, String sourceKey) {
        if (target == null || source == null || source.isMissingNode() || source.isNull()) {
            return;
        }
        JsonNode value = source.path(sourceKey);
        if (value.isMissingNode() || value.isNull()) {
            return;
        }
        target.put(targetKey, value.asText());
    }

    private static void copyIfPresent(Document target, String targetKey, Document source, String sourceKey) {
        if (target == null || source == null) {
            return;
        }
        Object value = source.get(sourceKey);
        if (value != null) {
            target.put(targetKey, value);
        }
    }

    private static Long resolveBlockNumber(JsonNode receipt, JsonNode transaction) {
        Long fromReceipt = parseBlockNumber(receipt == null ? null : receipt.path("blockNumber").asText(null));
        if (fromReceipt != null && fromReceipt > 0L) {
            return fromReceipt;
        }
        Long fromTransaction = parseBlockNumber(transaction == null ? null : transaction.path("blockNumber").asText(null));
        return fromTransaction != null ? fromTransaction : 0L;
    }

    private static Integer resolveTransactionIndex(JsonNode receipt, JsonNode transaction) {
        Integer fromReceipt = parseHexInteger(receipt == null ? null : receipt.path("transactionIndex").asText(null));
        if (fromReceipt != null) {
            return fromReceipt;
        }
        return parseHexInteger(transaction == null ? null : transaction.path("transactionIndex").asText(null));
    }

    private static Set<Long> collectBlockNumbers(Map<String, JsonNode> receiptsByTx, Map<String, JsonNode> transactionsByTx) {
        Set<Long> blockNumbers = new LinkedHashSet<>();
        receiptsByTx.values().forEach(receipt -> {
            Long blockNumber = resolveBlockNumber(receipt, null);
            if (blockNumber != null && blockNumber > 0L) {
                blockNumbers.add(blockNumber);
            }
        });
        transactionsByTx.values().forEach(transaction -> {
            Long blockNumber = resolveBlockNumber(null, transaction);
            if (blockNumber != null && blockNumber > 0L) {
                blockNumbers.add(blockNumber);
            }
        });
        return blockNumbers;
    }

    private static Long parseBlockNumber(String hex) {
        if (hex == null || hex.isBlank()) return 0L;
        try {
            if (hex.startsWith("0x") || hex.startsWith("0X")) {
                return Long.parseLong(hex.substring(2), 16);
            }
            return Long.parseLong(hex);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static Long parseHexLong(String hex) {
        if (hex == null || hex.isBlank()) {
            return null;
        }
        try {
            if (hex.startsWith("0x") || hex.startsWith("0X")) {
                return Long.parseLong(hex.substring(2), 16);
            }
            return Long.parseLong(hex);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer parseHexInteger(String hex) {
        Long value = parseHexLong(hex);
        if (value == null) {
            return null;
        }
        return value > Integer.MAX_VALUE ? null : value.intValue();
    }

    private static String normalizeReceiptStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        if ("0".equals(status) || "1".equals(status)) {
            return status;
        }
        Long numeric = parseHexLong(status);
        if (numeric == null) {
            return null;
        }
        return numeric == 0L ? "0" : "1";
    }

    private static String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String string = value.toString().trim();
        return string.isEmpty() ? null : string;
    }

    private static BigInteger parseHexQuantity(String hex) {
        BigInteger parsed = parseHexQuantityOrNull(hex);
        return parsed == null ? BigInteger.ZERO : parsed;
    }

    private static BigInteger parseHexQuantityOrNull(String hex) {
        if (hex == null || hex.isBlank()) {
            return null;
        }
        String normalized = hex.trim();
        if (normalized.startsWith("0x") || normalized.startsWith("0X")) {
            normalized = normalized.substring(2);
        }
        if (normalized.isBlank()) {
            return BigInteger.ZERO;
        }
        try {
            return new BigInteger(normalized, 16);
        } catch (NumberFormatException e) {
            return null;
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

    private record TokenMetadata(Integer decimals, String symbol, String name) {
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
