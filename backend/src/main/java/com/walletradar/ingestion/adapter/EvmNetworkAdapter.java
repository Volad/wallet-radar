package com.walletradar.ingestion.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.domain.NetworkId;
import com.walletradar.domain.RawTransaction;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.StreamSupport;

/**
 * EVM adapter: eth_getLogs with per-network batch block size (ADR-011) and per-network RPC rotators (ADR-012).
 * Uses rotator for the request's networkId; if network is not in config, uses default rotator (single fallback URL).
 * Fetches ERC20 Transfer logs where the wallet is from or to, groups by txHash into RawTransaction.
 */
@Component
@RequiredArgsConstructor
public class EvmNetworkAdapter implements NetworkAdapter {

    private static final String TRANSFER_TOPIC = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";

    private final EvmRpcClient rpcClient;
    private final Map<String, RpcEndpointRotator> rotatorsByNetwork;
    @Qualifier("evmDefaultRpcEndpointRotator")
    private final RpcEndpointRotator defaultRotator;
    private final ObjectMapper objectMapper;
    private final EvmBatchBlockSizeResolver batchBlockSizeResolver;

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
            String endpoint = rotator.getNextEndpoint();
            try {
                List<JsonNode> fromLogs = ethGetLogs(endpoint, fromBlock, toBlock, Arrays.asList(TRANSFER_TOPIC, fromTopic), null);
                List<JsonNode> toLogs = ethGetLogs(endpoint, fromBlock, toBlock, Arrays.asList(TRANSFER_TOPIC, null, fromTopic), null);
                Map<String, List<JsonNode>> byTx = new HashMap<>();
                for (JsonNode log : fromLogs) {
                    String txHash = log.path("transactionHash").asText();
                    byTx.computeIfAbsent(txHash, k -> new ArrayList<>()).add(log);
                }
                for (JsonNode log : toLogs) {
                    String txHash = log.path("transactionHash").asText();
                    byTx.computeIfAbsent(txHash, k -> new ArrayList<>()).add(log);
                }
                return byTx.entrySet().stream()
                        .map(e -> toRawTransaction(e.getKey(), networkIdStr, e.getValue()))
                        .toList();
            } catch (Exception e) {
                lastException = e;
            }
        }
        throw new RpcException("RPC failed after " + rotator.getMaxAttempts() + " attempts", lastException);
    }

    private List<JsonNode> ethGetLogs(String endpoint, long fromBlock, long toBlock, List<Object> topics, String address) {
        Map<String, Object> filter = new HashMap<>();
        filter.put("fromBlock", "0x" + Long.toHexString(fromBlock));
        filter.put("toBlock", "0x" + Long.toHexString(toBlock));
        if (topics != null) {
            filter.put("topics", topics);
        }
        if (address != null) {
            filter.put("address", address);
        }
        String json = rpcClient.call(endpoint, "eth_getLogs", Collections.singletonList(filter)).block();
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

    private static RawTransaction toRawTransaction(String txHash, String networkId, List<JsonNode> logs) {
        RawTransaction tx = new RawTransaction();
        tx.setTxHash(txHash);
        tx.setNetworkId(networkId);
        List<Document> logDocs = logs.stream()
                .map(EvmNetworkAdapter::logToDocument)
                .toList();
        String blockNumber = logs.isEmpty() ? "0x0" : logs.get(0).path("blockNumber").asText();
        Document rawData = new Document("blockNumber", blockNumber).append("logs", logDocs);
        tx.setRawData(rawData);
        return tx;
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
}
