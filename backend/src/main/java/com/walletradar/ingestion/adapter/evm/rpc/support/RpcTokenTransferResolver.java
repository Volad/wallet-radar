package com.walletradar.ingestion.adapter.evm.rpc.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.ingestion.adapter.RpcException;
import com.walletradar.ingestion.adapter.evm.rpc.EvmRpcClient;
import com.walletradar.ingestion.config.IngestionEvmRpcProperties;
import io.github.resilience4j.ratelimiter.RateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
public class RpcTokenTransferResolver {

    private static final String TRANSFER_TOPIC = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";
    private static final String ERC20_DECIMALS_SELECTOR = "0x313ce567";
    private static final String ERC20_SYMBOL_SELECTOR = "0x95d89b41";
    private static final String ERC20_NAME_SELECTOR = "0x06fdde03";

    private final Map<String, TokenMetadata> tokenMetadataCache = new ConcurrentHashMap<>();

    private final EvmRpcClient rpcClient;
    private final RateLimiter evmRpcRateLimiter;
    private final IngestionEvmRpcProperties evmRpcProperties;
    private final ObjectMapper objectMapper;

    public RpcTokenTransferResolver(
            EvmRpcClient rpcClient,
            RateLimiter evmRpcRateLimiter,
            IngestionEvmRpcProperties evmRpcProperties,
            ObjectMapper objectMapper
    ) {
        this.rpcClient = rpcClient;
        this.evmRpcRateLimiter = evmRpcRateLimiter;
        this.evmRpcProperties = evmRpcProperties;
        this.objectMapper = objectMapper;
    }

    public List<Document> buildTokenTransfers(String endpoint, String networkId, JsonNode logs) {
        if (logs == null || !logs.isArray()) {
            return List.of();
        }
        List<Document> documents = new ArrayList<>();
        logs.forEach(log -> documents.add(jsonNodeToDocument(log)));
        return buildTokenTransfersFromDocuments(endpoint, networkId, documents);
    }

    public List<Document> buildTokenTransfersFromDocuments(String endpoint, String networkId, List<Document> logs) {
        if (logs == null || logs.isEmpty()) {
            return List.of();
        }
        List<Document> transfers = new ArrayList<>();
        for (Document log : logs) {
            if (!isErc20TransferLog(log)) {
                continue;
            }
            String contractAddress = normalizeAddress(stringValue(log.get("address")));
            if (contractAddress == null) {
                continue;
            }
            TokenMetadata metadata = resolveTokenMetadata(endpoint, networkId, contractAddress);
            if (metadata.decimals() == null) {
                continue;
            }
            List<String> topics = topicList(log);
            if (topics.size() < 3) {
                continue;
            }
            Document transfer = new Document();
            transfer.put("contractAddress", contractAddress);
            transfer.put("from", topicAddress(topics.get(1)));
            transfer.put("to", topicAddress(topics.get(2)));
            transfer.put("value", parseHexQuantity(stringValue(log.get("data"))).toString());
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
            String json = callRpc(endpoint, "eth_call", List.of(Map.of("to", contractAddress, "data", data), "latest"));
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

    private static List<String> topicList(Document log) {
        Object topicsRaw = log.get("topics");
        if (!(topicsRaw instanceof List<?> topics)) {
            return List.of();
        }
        return topics.stream()
                .map(RpcTokenTransferResolver::stringValue)
                .filter(Objects::nonNull)
                .toList();
    }

    private static boolean isErc20TransferLog(Document log) {
        List<String> topics = topicList(log);
        return topics.size() == 3 && TRANSFER_TOPIC.equalsIgnoreCase(topics.get(0));
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

    public static String normalizeAddress(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.startsWith("0x")) {
            normalized = "0x" + normalized;
        }
        return normalized.length() == 42 ? normalized : null;
    }

    private Document jsonNodeToDocument(JsonNode node) {
        if (node == null || node.isNull()) {
            return new Document();
        }
        try {
            return Document.parse(objectMapper.writeValueAsString(node));
        } catch (Exception e) {
            throw new RpcException("Failed to convert provider log to Document", e);
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

    private static String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String string = value.toString().trim();
        return string.isEmpty() ? null : string;
    }

    private record TokenMetadata(Integer decimals, String symbol, String name) {
    }
}
