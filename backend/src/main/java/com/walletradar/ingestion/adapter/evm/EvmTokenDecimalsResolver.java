package com.walletradar.ingestion.adapter.evm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.ingestion.adapter.RpcEndpointRotator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Resolves ERC20 token decimals and symbol via eth_call. Cached per (networkId, tokenAddress)
 * using Caffeine tokenMetaCache (02-architecture, ADR-005: TTL 24h, max 5000).
 * WBTC/USDC etc. use 8/6 decimals; symbol() returns e.g. "WBTC", "USDC" for assetSymbol.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class EvmTokenDecimalsResolver {

    /** ERC20 decimals() selector: keccak256("decimals()") first 4 bytes. */
    private static final String DECIMALS_SELECTOR = "0x313ce567";
    /** ERC20 symbol() selector: keccak256("symbol()") first 4 bytes. */
    private static final String SYMBOL_SELECTOR = "0x95d89b41";

    private final EvmRpcClient rpcClient;
    @Qualifier("evmRotatorsByNetwork")
    private final Map<String, RpcEndpointRotator> rotatorsByNetwork;
    @Qualifier("evmDefaultRpcEndpointRotator")
    private final RpcEndpointRotator defaultRotator;
    private final ObjectMapper objectMapper;
    private final CacheManager cacheManager;

    /** Default when contract is not ERC20 or RPC fails. */
    public static final int DEFAULT_DECIMALS = 18;

    private static final Map<String, Integer> KNOWN_DECIMALS = Map.of(
            "ARBITRUM:0xaf88d065e77c8cc2239327c5edb3a432268e5831", 6,
            "ARBITRUM:0x2f2a2543b76a4166549f7aab2e75bef0aefc5b0f", 8,
            "ETHEREUM:0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48", 6,
            "ETHEREUM:0xdac17f958d2ee523a2206206994597c13d831ec7", 6,
            "ETHEREUM:0x2260fac5e5542a773aa44fbcfedf7c193bc2c599", 8,
            "BASE:0x833589fcd6edb6e08f4c7c32d4f71b54bda02913", 6,
            "OPTIMISM:0x0b2c639c533813f4aa9d7837caf62653d097ff85", 6,
            "POLYGON:0x3c499c542cef5e3811e1192ce70d8cc03d5c3359", 6
    );

    private static String cacheKey(String networkId, String tokenAddress) {
        return networkId + ":" + (tokenAddress != null ? tokenAddress.toLowerCase() : "");
    }

    /**
     * Returns decimals for the token on the given network. Uses tokenMetaCache; on miss calls eth_call decimals().
     *
     * @param networkId   e.g. "ARBITRUM"
     * @param tokenAddress contract address (any case)
     * @return decimals (typically 6, 8, or 18), or {@link #DEFAULT_DECIMALS} on failure
     */
    public int getDecimals(String networkId, String tokenAddress) {
        if (networkId == null || tokenAddress == null) return DEFAULT_DECIMALS;
        return getTokenMeta(networkId, tokenAddress).decimals();
    }

    /**
     * Returns symbol for the token (e.g. "WBTC", "USDC"). Uses tokenMetaCache; on miss calls eth_call symbol().
     *
     * @param networkId   e.g. "ARBITRUM"
     * @param tokenAddress contract address (any case)
     * @return symbol or empty string on failure
     */
    public String getSymbol(String networkId, String tokenAddress) {
        if (networkId == null || tokenAddress == null) return "";
        return getTokenMeta(networkId, tokenAddress).symbol();
    }

    private TokenMeta getTokenMeta(String networkId, String tokenAddress) {
        var cache = cacheManager.getCache(com.walletradar.config.CaffeineConfig.TOKEN_META_CACHE);
        if (cache == null) {
            return fetchTokenMeta(networkId, tokenAddress);
        }
        String key = cacheKey(networkId, tokenAddress);
        return cache.get(key, () -> fetchTokenMeta(networkId, tokenAddress));
    }

    private TokenMeta fetchTokenMeta(String networkId, String tokenAddress) {
        int decimals = fetchDecimals(networkId, tokenAddress);
        String symbol = fetchSymbol(networkId, tokenAddress);
        return new TokenMeta(decimals, symbol != null ? symbol : "");
    }

    private int fetchDecimals(String networkId, String tokenAddress) {
        String key = cacheKey(networkId, tokenAddress);
        Integer known = KNOWN_DECIMALS.get(key);
        if (known != null) return known;
        RpcEndpointRotator rotator = rotatorsByNetwork.getOrDefault(networkId, defaultRotator);
        String endpoint = rotator.getNextEndpoint();
        List<Object> params = List.of(
                Map.of("to", tokenAddress, "data", DECIMALS_SELECTOR),
                "latest"
        );
        try {
            String json = rpcClient.call(endpoint, "eth_call", params).block();
            if (json == null) return DEFAULT_DECIMALS;
            JsonNode root = objectMapper.readTree(json);
            JsonNode error = root.path("error");
            if (!error.isMissingNode()) {
                log.debug("eth_call decimals() error for {} on {}: {}", tokenAddress, networkId, error);
                return DEFAULT_DECIMALS;
            }
            String result = root.path("result").asText(null);
            if (result == null || !result.startsWith("0x")) return DEFAULT_DECIMALS;
            String hex = result.length() > 2 ? result.substring(2) : "";
            if (hex.length() < 2) return DEFAULT_DECIMALS;
            // Result is right-padded 32 bytes; we want lowest byte (decimals is uint8)
            int len = hex.length();
            String low = len <= 2 ? hex : hex.substring(len - 2);
            int decimals = Integer.parseInt(low, 16);
            if (decimals < 0 || decimals > 255) return DEFAULT_DECIMALS;
            return decimals;
        } catch (Exception e) {
            log.debug("Failed to get decimals for {} on {}: {}", tokenAddress, networkId, e.getMessage());
            return DEFAULT_DECIMALS;
        }
    }

    private String fetchSymbol(String networkId, String tokenAddress) {
        RpcEndpointRotator rotator = rotatorsByNetwork.getOrDefault(networkId, defaultRotator);
        String endpoint = rotator.getNextEndpoint();
        List<Object> params = List.of(
                Map.of("to", tokenAddress, "data", SYMBOL_SELECTOR),
                "latest"
        );
        try {
            String json = rpcClient.call(endpoint, "eth_call", params).block();
            if (json == null) return "";
            JsonNode root = objectMapper.readTree(json);
            JsonNode error = root.path("error");
            if (!error.isMissingNode()) {
                log.debug("eth_call symbol() error for {} on {}: {}", tokenAddress, networkId, error);
                return "";
            }
            String result = root.path("result").asText(null);
            if (result == null || !result.startsWith("0x")) return "";
            String decoded = decodeSymbolResult(result);
            return decoded != null ? decoded : "";
        } catch (Exception e) {
            log.debug("Failed to get symbol for {} on {}: {}", tokenAddress, networkId, e.getMessage());
            return "";
        }
    }

    /** Decode ABI-encoded string: dynamic (offset + length + data) or bytes32 left-padded. */
    private static String decodeSymbolResult(String hex) {
        String raw = hex.length() > 2 ? hex.substring(2) : "";
        if (raw.length() < 128) return ""; // min 2 words
        try {
            // Check for dynamic string: first word = 0x20 (offset to data)
            String firstWord = raw.length() >= 64 ? raw.substring(0, 64) : "";
            if (firstWord.equals("0000000000000000000000000000000000000000000000000000000000000020")) {
                // Second word = length in bytes
                if (raw.length() < 128) return "";
                int len = Integer.parseInt(raw.substring(64, 128), 16);
                if (len <= 0 || len > 32) return "";
                int dataStart = 128;
                int dataLen = len * 2;
                if (raw.length() < dataStart + dataLen) return "";
                String dataHex = raw.substring(dataStart, dataStart + dataLen);
                return new String(hexToBytes(dataHex), StandardCharsets.UTF_8).trim();
            }
            // bytes32: left-padded, take until first 00 or end
            byte[] bytes = hexToBytes(raw.substring(0, 64));
            int end = 0;
            while (end < bytes.length && bytes[end] != 0) end++;
            return new String(bytes, 0, end, StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            out[i / 2] = (byte) Integer.parseInt(hex.substring(i, i + 2), 16);
        }
        return out;
    }
}
