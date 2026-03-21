package com.walletradar.ingestion.adapter.evm.rpc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.ingestion.adapter.RpcException;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Discovers direct native EVM transactions for a wallet using standard JSON-RPC only.
 * It narrows block ranges by comparing wallet balance+nonce state, then inspects
 * full blocks only where a direct tx must exist.
 */
final class DirectWalletRpcDiscovery {

    private static final WalletBlockState ZERO_STATE = new WalletBlockState(BigInteger.ZERO, BigInteger.ZERO);

    private final ObjectMapper objectMapper;

    DirectWalletRpcDiscovery(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    Map<String, DiscoveredTransaction> discover(
            String endpoint,
            String walletAddress,
            long fromBlock,
            long toBlock,
            RpcInvoker rpcInvoker
    ) {
        if (walletAddress == null || walletAddress.isBlank() || fromBlock > toBlock) {
            return Map.of();
        }

        String normalizedWallet = normalizeAddress(walletAddress);
        if (normalizedWallet == null) {
            return Map.of();
        }

        Map<Long, WalletBlockState> stateCache = new HashMap<>();
        Map<String, DiscoveredTransaction> discovered = new LinkedHashMap<>();
        WalletBlockState startState = stateAt(endpoint, normalizedWallet, fromBlock - 1L, rpcInvoker, stateCache);
        WalletBlockState endState = stateAt(endpoint, normalizedWallet, toBlock, rpcInvoker, stateCache);
        collect(endpoint, normalizedWallet, fromBlock, toBlock, startState, endState, rpcInvoker, stateCache, discovered);
        return discovered;
    }

    private void collect(
            String endpoint,
            String walletAddress,
            long fromBlock,
            long toBlock,
            WalletBlockState startState,
            WalletBlockState endState,
            RpcInvoker rpcInvoker,
            Map<Long, WalletBlockState> stateCache,
            Map<String, DiscoveredTransaction> discovered
    ) {
        if (!hasStateChange(startState, endState)) {
            return;
        }

        if (fromBlock == toBlock) {
            inspectBlock(endpoint, walletAddress, fromBlock, rpcInvoker, discovered);
            return;
        }

        long mid = fromBlock + (toBlock - fromBlock) / 2L;
        WalletBlockState midState = stateAt(endpoint, walletAddress, mid, rpcInvoker, stateCache);
        collect(endpoint, walletAddress, fromBlock, mid, startState, midState, rpcInvoker, stateCache, discovered);
        collect(endpoint, walletAddress, mid + 1L, toBlock, midState, endState, rpcInvoker, stateCache, discovered);
    }

    private WalletBlockState stateAt(
            String endpoint,
            String walletAddress,
            long blockNumber,
            RpcInvoker rpcInvoker,
            Map<Long, WalletBlockState> stateCache
    ) {
        if (blockNumber < 0L) {
            return ZERO_STATE;
        }
        WalletBlockState cached = stateCache.get(blockNumber);
        if (cached != null) {
            return cached;
        }

        String blockHex = "0x" + Long.toHexString(blockNumber);
        List<RpcRequest> requests = List.of(
                new RpcRequest("eth_getBalance", List.of(walletAddress, blockHex)),
                new RpcRequest("eth_getTransactionCount", List.of(walletAddress, blockHex))
        );
        WalletBlockState state = null;
        try {
            state = tryParseBatchStateResponse(rpcInvoker.batchCall(endpoint, requests));
        } catch (Exception batchFailure) {
            state = null;
        }
        if (state == null) {
            state = parseBatchStateResponse(sequentialBatchResponse(endpoint, requests, rpcInvoker));
        }
        stateCache.put(blockNumber, state);
        return state;
    }

    private String sequentialBatchResponse(String endpoint, List<RpcRequest> requests, RpcInvoker rpcInvoker) {
        List<String> responses = new ArrayList<>(requests.size());
        for (int i = 0; i < requests.size(); i++) {
            RpcRequest request = requests.get(i);
            String json = rpcInvoker.call(endpoint, request.method(), request.params());
            responses.add(rewriteResponseId(json, i + 1));
        }
        return "[" + String.join(",", responses) + "]";
    }

    private WalletBlockState tryParseBatchStateResponse(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            if (!root.isArray()) {
                return null;
            }
            return stateFromBatchArray(root);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private WalletBlockState parseBatchStateResponse(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            if (!root.isArray()) {
                throw new RpcException("Direct wallet discovery expected batch array response");
            }
            return stateFromBatchArray(root);
        } catch (JsonProcessingException e) {
            throw new RpcException("Failed to parse direct wallet state batch response", e);
        }
    }

    private WalletBlockState stateFromBatchArray(JsonNode root) {
        Map<Integer, JsonNode> byId = new HashMap<>();
        for (JsonNode response : root) {
            byId.put(response.path("id").asInt(), response);
        }
        BigInteger balance = parseHexQuantity(resultValue(byId.get(1), "eth_getBalance"));
        BigInteger nonce = parseHexQuantity(resultValue(byId.get(2), "eth_getTransactionCount"));
        return new WalletBlockState(balance, nonce);
    }

    private void inspectBlock(
            String endpoint,
            String walletAddress,
            long blockNumber,
            RpcInvoker rpcInvoker,
            Map<String, DiscoveredTransaction> discovered
    ) {
        String blockHex = "0x" + Long.toHexString(blockNumber);
        String json = rpcInvoker.call(endpoint, "eth_getBlockByNumber", List.of(blockHex, true));
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode error = root.path("error");
            if (!error.isMissingNode()) {
                throw new RpcException("eth_getBlockByNumber error: " + error);
            }
            JsonNode block = root.path("result");
            if (block.isMissingNode() || block.isNull()) {
                return;
            }
            long timestamp = parseHexQuantity(block.path("timestamp").asText("0x0")).longValue();
            JsonNode transactions = block.path("transactions");
            if (!transactions.isArray()) {
                return;
            }
            for (JsonNode transaction : transactions) {
                String from = normalizeAddress(transaction.path("from").asText(null));
                String to = normalizeAddress(transaction.path("to").asText(null));
                if (!walletAddress.equals(from) && !walletAddress.equals(to)) {
                    continue;
                }
                String txHash = normalizeHash(transaction.path("hash").asText(null));
                if (txHash == null) {
                    continue;
                }
                discovered.put(txHash, new DiscoveredTransaction(txHash, transaction, blockNumber, timestamp));
            }
        } catch (JsonProcessingException e) {
            throw new RpcException("Failed to parse eth_getBlockByNumber response", e);
        }
    }

    private static boolean hasStateChange(WalletBlockState startState, WalletBlockState endState) {
        if (startState == null || endState == null) {
            return false;
        }
        return startState.balance.compareTo(endState.balance) != 0
                || startState.nonce.compareTo(endState.nonce) != 0;
    }

    private static String resultValue(JsonNode response, String method) {
        if (response == null) {
            throw new RpcException(method + " batch response missing");
        }
        JsonNode error = response.path("error");
        if (!error.isMissingNode()) {
            throw new RpcException(method + " error: " + error);
        }
        JsonNode result = response.path("result");
        if (result.isMissingNode() || result.isNull()) {
            throw new RpcException(method + " returned null result");
        }
        return result.asText();
    }

    private static BigInteger parseHexQuantity(String value) {
        if (value == null || value.isBlank()) {
            return BigInteger.ZERO;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("0x")) {
            normalized = normalized.substring(2);
        }
        if (normalized.isBlank()) {
            return BigInteger.ZERO;
        }
        return new BigInteger(normalized, 16);
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

    private static String normalizeHash(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String rewriteResponseId(String json, int id) {
        try {
            JsonNode root = objectMapper.readTree(json);
            if (!(root instanceof com.fasterxml.jackson.databind.node.ObjectNode objectNode)) {
                return json;
            }
            objectNode.put("id", id);
            return objectMapper.writeValueAsString(objectNode);
        } catch (Exception e) {
            return json;
        }
    }

    record DiscoveredTransaction(String txHash, JsonNode transaction, long blockNumber, long timestamp) {
    }

    @FunctionalInterface
    interface RpcInvoker {
        String call(String endpoint, String method, Object params);

        default String batchCall(String endpoint, List<RpcRequest> requests) {
            throw new UnsupportedOperationException("batchCall not implemented");
        }
    }

    private record WalletBlockState(BigInteger balance, BigInteger nonce) {
    }
}
