package com.walletradar.liquiditypools.enrichment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.ingestion.adapter.RpcEndpointRotator;
import com.walletradar.ingestion.adapter.evm.abi.EvmAbiSupport;
import com.walletradar.ingestion.adapter.evm.rpc.EvmRpcClient;
import com.walletradar.ingestion.adapter.evm.rpc.RpcRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class LpRpcSupport {

    private static final Duration RPC_TIMEOUT = Duration.ofSeconds(5);
    private static final String DECIMALS_SELECTOR = "0x" + EvmAbiSupport.selector("decimals()");
    private static final String SYMBOL_SELECTOR = "0x" + EvmAbiSupport.selector("symbol()");
    private static final String BALANCE_OF_SELECTOR = "0x" + EvmAbiSupport.selector("balanceOf(address)");

    private final EvmRpcClient evmRpcClient;
    private final ObjectMapper objectMapper;
    @Qualifier("evmRotatorsByNetwork")
    private final Map<String, RpcEndpointRotator> rotatorsByNetwork;

    public Optional<String> call(String networkId, String to, String data) {
        RpcEndpointRotator rotator = rotator(networkId);
        if (rotator == null) {
            return Optional.empty();
        }
        for (int attempt = 0; attempt < rotator.getMaxAttempts(); attempt++) {
            String endpoint = rotator.getNextEndpoint();
            try {
                String body = evmRpcClient.call(endpoint, "eth_call",
                        List.of(Map.of("to", to, "data", data), "latest")).block(RPC_TIMEOUT);
                String result = rpcResult(body);
                if (result != null && result.length() > 2) {
                    return Optional.of(result);
                }
            } catch (Exception ignored) {
                // try next endpoint
            }
        }
        return Optional.empty();
    }

    /** Maximum number of eth_call entries per JSON-RPC batch request. Larger payloads hit
     * provider limits (413 Payload Too Large on Ankr; 429 on drpc) and degrade reliability. */
    private static final int MAX_BATCH_CHUNK = 25;

    /**
     * When all batch attempts fail for a batch of this size or larger, skip the individual-call
     * fallback to avoid extreme latency (N × RPC_TIMEOUT seconds). Liquidity-depth scans routinely
     * send ~100 calls; if every provider rejects the batch, individual fallback would block the
     * whole LP refresh thread for minutes. Return all-empty instead and let the caller decide.
     */
    private static final int SKIP_INDIVIDUAL_FALLBACK_THRESHOLD = 10;

    /**
     * Batch eth_call to the same target with multiple callData payloads.
     * Uses JSON-RPC batch to reduce round trips: N calls → 1 HTTP request.
     * Large batches are split into chunks of at most {@value #MAX_BATCH_CHUNK} calls to stay
     * within provider payload limits. Returns one Optional<String> per input (empty if that
     * call failed or returned no data).
     */
    public List<Optional<String>> callBatch(String networkId, String to, List<String> callDatas) {
        if (callDatas.isEmpty()) {
            return List.of();
        }
        // Split oversized batches into chunks to avoid 413 / 429 from RPC providers.
        if (callDatas.size() > MAX_BATCH_CHUNK) {
            List<Optional<String>> combined = new java.util.ArrayList<>(callDatas.size());
            for (int i = 0; i < callDatas.size(); i += MAX_BATCH_CHUNK) {
                List<String> chunk = callDatas.subList(i, Math.min(i + MAX_BATCH_CHUNK, callDatas.size()));
                combined.addAll(callBatch(networkId, to, chunk));
            }
            return combined;
        }
        RpcEndpointRotator rotator = rotator(networkId);
        if (rotator == null) {
            return callDatas.stream().map(d -> Optional.<String>empty()).toList();
        }
        List<RpcRequest> requests = callDatas.stream()
                .map(data -> new RpcRequest("eth_call", List.of(Map.of("to", to, "data", data), "latest")))
                .toList();
        for (int attempt = 0; attempt < rotator.getMaxAttempts(); attempt++) {
            String endpoint = rotator.getNextEndpoint();
            try {
                String body = evmRpcClient.batchCall(endpoint, requests).block(RPC_TIMEOUT);
                List<Optional<String>> results = parseBatchResults(body, callDatas.size());
                long nonEmpty = results.stream().filter(Optional::isPresent).count();
                if (nonEmpty > 0) {
                    return results;
                }
                log.debug("batch RPC returned all empty results endpoint={} size={}", endpoint, callDatas.size());
            } catch (Exception e) {
                log.warn("batch RPC failed endpoint={} networkId={} size={} error={}",
                        endpoint, networkId, callDatas.size(), e.getMessage());
            }
        }
        // All batch attempts failed.
        // For large batches (e.g. liquidity-depth scans), skip the individual-call fallback to avoid
        // blocking the LP refresh thread for N × RPC_TIMEOUT seconds when the RPC endpoint is degraded.
        if (callDatas.size() >= SKIP_INDIVIDUAL_FALLBACK_THRESHOLD) {
            log.debug("batch RPC all-failed, skipping individual fallback networkId={} size={}", networkId, callDatas.size());
            return callDatas.stream().map(d -> Optional.<String>empty()).toList();
        }
        log.debug("batch RPC fallback to individual calls networkId={} size={}", networkId, callDatas.size());
        return callDatas.stream().map(data -> call(networkId, to, data)).toList();
    }

    private List<Optional<String>> parseBatchResults(String body, int expectedSize) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        List<Optional<String>> results = new ArrayList<>(expectedSize);
        if (root.isArray()) {
            // Sort by id to match request order
            List<JsonNode> sorted = new ArrayList<>();
            root.forEach(sorted::add);
            sorted.sort((a, b) -> {
                int idA = a.path("id").asInt(0);
                int idB = b.path("id").asInt(0);
                return Integer.compare(idA, idB);
            });
            for (JsonNode node : sorted) {
                JsonNode error = node.get("error");
                JsonNode result = node.get("result");
                if (error != null && !error.isNull()) {
                    results.add(Optional.empty());
                } else if (result == null || result.isNull()) {
                    results.add(Optional.empty());
                } else {
                    String hex = result.asText();
                    results.add(hex.length() > 2 ? Optional.of(hex) : Optional.empty());
                }
            }
        }
        // Pad with empties in case response is missing entries
        while (results.size() < expectedSize) {
            results.add(Optional.empty());
        }
        return results;
    }

    public Optional<BigInteger> erc20Balance(String networkId, String token, String wallet) {
        String data = BALANCE_OF_SELECTOR + EvmAbiSupport.encodeAddress(wallet);
        return call(networkId, token, data).map(EvmAbiSupport::uintFromWord);
    }

    public Optional<Integer> erc20Decimals(String networkId, String token) {
        return call(networkId, token, DECIMALS_SELECTOR)
                .map(EvmAbiSupport::uintFromWord)
                .map(BigInteger::intValue);
    }

    public Optional<String> erc20Symbol(String networkId, String token) {
        return call(networkId, token, SYMBOL_SELECTOR).flatMap(this::decodeString);
    }

    public Optional<String> decodeString(String hex) {
        String cleaned = EvmAbiSupport.cleanHex(hex);
        if (cleaned.length() < 128) {
            return Optional.empty();
        }
        int offset = EvmAbiSupport.uintFromWord(cleaned.substring(0, 64)).intValue() * 2;
        if (cleaned.length() < offset + 64) {
            return Optional.empty();
        }
        int length = EvmAbiSupport.uintFromWord(cleaned.substring(offset, offset + 64)).intValue();
        int start = offset + 64;
        int end = start + length * 2;
        if (cleaned.length() < end) {
            return Optional.empty();
        }
        byte[] bytes = hexToBytes(cleaned.substring(start, end));
        return Optional.of(new String(bytes));
    }

    private RpcEndpointRotator rotator(String networkId) {
        if (networkId == null) {
            return null;
        }
        return rotatorsByNetwork.get(networkId.trim().toUpperCase(Locale.ROOT));
    }

    private String rpcResult(String body) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        JsonNode error = root.get("error");
        if (error != null && !error.isNull()) {
            throw new IllegalStateException(error.toString());
        }
        JsonNode result = root.get("result");
        if (result == null || result.isNull()) {
            return null;
        }
        return result.asText();
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4) + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }
}
