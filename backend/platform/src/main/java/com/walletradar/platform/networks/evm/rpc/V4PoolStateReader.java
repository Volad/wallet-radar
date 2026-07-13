package com.walletradar.platform.networks.evm.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.platform.networks.RpcEndpointRotator;
import com.walletradar.platform.networks.evm.abi.EvmAbiSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads the current {@code sqrtPriceX96} of a Uniswap V4 / Pancake Infinity CL pool at a
 * specific historical block by calling {@code getSlot0(bytes32 poolId)} on the PoolManager.
 *
 * <p>This reader is used exclusively at classification time (during normalization) to compute the
 * V4 fee/principal split. It is block-tagged (archive-RPC) and fail-safe: if any RPC call fails
 * or the archive node is unavailable, {@link Optional#empty()} is returned and the caller falls
 * back to {@code fee = 0} (no fabrication).
 *
 * <p>In-memory cache: per {@code (networkId, poolId, blockNumber)} tuple, the result is cached
 * for the lifetime of the JVM. For durable rebuild-reproducibility, callers must also persist the
 * result to the {@code v4_pool_state_cache} MongoDB collection via {@code V4PoolStateLookupService}.
 *
 * <p>PoolManager addresses per network are supplied via {@link V4PoolManagerRegistry}.
 */
@Slf4j
@Component
public class V4PoolStateReader {

    /**
     * {@code getSlot0(bytes32)} ABI selector — Uniswap V4 PoolManager / StateView.
     * Computed via keccak256("getSlot0(bytes32)")[0..3].
     */
    static final String GET_SLOT0_SELECTOR = "0x" + EvmAbiSupport.selector("getSlot0(bytes32)");

    private final EvmRpcClient rpcClient;
    private final Map<String, RpcEndpointRotator> rotatorsByNetwork;
    private final RpcEndpointRotator defaultRotator;
    private final ObjectMapper objectMapper;
    private final V4PoolManagerRegistry poolManagerRegistry;

    /** In-memory cache: {@code networkId|poolId|block} → sqrtPriceX96 (null = UNRESOLVED). */
    private final Map<String, BigInteger> cache = new ConcurrentHashMap<>();
    /** Sentinel value stored in cache to represent an already-attempted-but-failed lookup. */
    private static final BigInteger UNRESOLVED_SENTINEL = BigInteger.valueOf(-1L);

    public V4PoolStateReader(
            EvmRpcClient rpcClient,
            @Qualifier("evmRotatorsByNetwork") Map<String, RpcEndpointRotator> rotatorsByNetwork,
            @Qualifier("evmDefaultRpcEndpointRotator") RpcEndpointRotator defaultRotator,
            ObjectMapper objectMapper,
            V4PoolManagerRegistry poolManagerRegistry
    ) {
        this.rpcClient = rpcClient;
        this.rotatorsByNetwork = rotatorsByNetwork;
        this.defaultRotator = defaultRotator;
        this.objectMapper = objectMapper;
        this.poolManagerRegistry = poolManagerRegistry;
    }

    /**
     * Resolves {@code sqrtPriceX96} of the given V4 pool at the given historical block.
     *
     * @param network     EVM network
     * @param poolId      32-byte pool ID (hex, with or without 0x prefix)
     * @param blockNumber block number at which the price should be read (archive-level)
     * @return sqrtPriceX96 as unsigned {@link BigInteger}, or {@link Optional#empty()} when
     *         the lookup fails (RPC unavailable, pruned block, unknown network — fall back to fee=0)
     */
    public Optional<BigInteger> getSqrtPriceX96(NetworkId network, String poolId, long blockNumber) {
        if (network == null || poolId == null || poolId.isBlank() || blockNumber <= 0) {
            return Optional.empty();
        }
        String poolIdNorm = normalizeHex(poolId);
        String cacheKey = network.name() + "|" + poolIdNorm + "|" + blockNumber;

        BigInteger cached = cache.get(cacheKey);
        if (cached != null) {
            return UNRESOLVED_SENTINEL.equals(cached) ? Optional.empty() : Optional.of(cached);
        }

        String poolManagerAddress = poolManagerRegistry.poolManagerFor(network);
        if (poolManagerAddress == null) {
            log.debug("V4 pool state: no PoolManager configured for network={}", network);
            cache.put(cacheKey, UNRESOLVED_SENTINEL);
            return Optional.empty();
        }

        RpcEndpointRotator rotator = rotatorsByNetwork.getOrDefault(network.name(), defaultRotator);
        if (rotator == null) {
            cache.put(cacheKey, UNRESOLVED_SENTINEL);
            return Optional.empty();
        }

        BigInteger sqrtPrice = callGetSlot0(rotator, poolManagerAddress, poolIdNorm, blockNumber);
        if (sqrtPrice == null || sqrtPrice.signum() <= 0) {
            log.debug("V4 pool state: UNRESOLVED network={} poolId={} block={}", network, poolIdNorm, blockNumber);
            cache.put(cacheKey, UNRESOLVED_SENTINEL);
            return Optional.empty();
        }

        log.debug("V4 pool state: resolved network={} poolId={} block={} sqrtPrice={}", network, poolIdNorm, blockNumber, sqrtPrice);
        cache.put(cacheKey, sqrtPrice);
        return Optional.of(sqrtPrice);
    }

    private BigInteger callGetSlot0(
            RpcEndpointRotator rotator,
            String poolManagerAddress,
            String poolId,
            long blockNumber
    ) {
        // ABI-encode: selector + bytes32 poolId (already 64 hex chars)
        String paddedPoolId = pad32(poolId);
        String callData = GET_SLOT0_SELECTOR + paddedPoolId;
        String blockTag = "0x" + Long.toHexString(blockNumber);

        for (String endpoint : rotator.getEndpoints()) {
            try {
                Object params = List.of(
                        Map.of("to", poolManagerAddress, "data", callData),
                        blockTag
                );
                String json = rpcClient.call(endpoint, "eth_call", params).block();
                if (json == null) {
                    continue;
                }
                JsonNode root = objectMapper.readTree(json);
                if (!root.path("error").isMissingNode() && !root.path("error").isNull()) {
                    log.debug("V4 getSlot0 RPC error network={} block={} endpoint={} error={}",
                            poolManagerAddress, blockNumber, endpoint, root.path("error").toString());
                    continue;
                }
                JsonNode resultNode = root.path("result");
                if (resultNode.isMissingNode() || resultNode.isNull()) {
                    continue;
                }
                String result = resultNode.asText(null);
                if (result == null || result.isBlank() || "0x".equalsIgnoreCase(result)) {
                    continue;
                }
                // First 32 bytes of result = sqrtPriceX96 (uint160, padded to 32 bytes)
                String word0 = EvmAbiSupport.wordAt(result, 0);
                if (word0 == null) {
                    continue;
                }
                BigInteger sqrtPrice = EvmAbiSupport.uintFromWord(word0);
                if (sqrtPrice.signum() > 0) {
                    return sqrtPrice;
                }
            } catch (RuntimeException | java.io.IOException ex) {
                log.debug("V4 getSlot0 call failed endpoint={} cause={}", endpoint, ex.getMessage());
            }
        }
        return null;
    }

    /** Pads a hex string (with or without 0x) to exactly 64 hex characters (32 bytes). */
    private static String pad32(String hex) {
        String clean = hex.startsWith("0x") || hex.startsWith("0X") ? hex.substring(2) : hex;
        clean = clean.toLowerCase(Locale.ROOT);
        if (clean.length() >= 64) {
            return clean.substring(clean.length() - 64);
        }
        return "0".repeat(64 - clean.length()) + clean;
    }

    private static String normalizeHex(String hex) {
        if (hex == null) return "";
        String clean = hex.trim().toLowerCase(Locale.ROOT);
        return clean.startsWith("0x") ? clean.substring(2) : clean;
    }
}
