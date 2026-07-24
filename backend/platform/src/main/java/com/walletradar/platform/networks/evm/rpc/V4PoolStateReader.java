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
 * specific historical block by reading the packed {@code Pool.State.slot0} storage word directly
 * from the PoolManager via {@code extsload(bytes32 slot)}.
 *
 * <p><b>Why not {@code getSlot0(bytes32)}?</b> {@code getSlot0} is <i>not</i> an external function
 * on the V4 {@code PoolManager}; it lives only on the periphery {@code StateView} lens contract
 * (and as an internal {@code StateLibrary} helper). The canonical singleton PoolManager exposes
 * pool storage exclusively through {@code extsload}. The slot of {@code pools[poolId]} is
 * {@code keccak256(abi.encodePacked(poolId, POOLS_SLOT))} with {@code POOLS_SLOT = 6}; the returned
 * 32-byte word packs {@code sqrtPriceX96} in its bottom 160 bits (the upper bits hold tick /
 * protocolFee / lpFee), so we mask to 160 bits. Source: Uniswap {@code v4-core}
 * {@code StateLibrary.getSlot0} / {@code _getPoolStateSlot}.
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
     * {@code extsload(bytes32)} ABI selector — Uniswap V4 PoolManager / Pancake Infinity CLPoolManager
     * (both inherit {@code Extsload}). Computed via keccak256("extsload(bytes32)")[0..3].
     */
    static final String EXTSLOAD_SELECTOR = "0x" + EvmAbiSupport.selector("extsload(bytes32)");

    /**
     * Index of the {@code pools} mapping in {@code PoolManager} storage (v4-core
     * {@code StateLibrary.POOLS_SLOT}). {@code pools[poolId]}'s slot0 lives at
     * {@code keccak256(abi.encodePacked(poolId, POOLS_SLOT))}.
     */
    private static final int POOLS_SLOT = 6;

    /** Mask for the bottom 160 bits ({@code sqrtPriceX96} occupies the low 160 bits of slot0). */
    private static final BigInteger SQRT_PRICE_MASK = BigInteger.ONE.shiftLeft(160).subtract(BigInteger.ONE);

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

        BigInteger sqrtPrice = callSlot0Extsload(rotator, poolManagerAddress, poolIdNorm, blockNumber);
        if (sqrtPrice == null || sqrtPrice.signum() <= 0) {
            log.debug("V4 pool state: UNRESOLVED network={} poolId={} block={}", network, poolIdNorm, blockNumber);
            cache.put(cacheKey, UNRESOLVED_SENTINEL);
            return Optional.empty();
        }

        log.debug("V4 pool state: resolved network={} poolId={} block={} sqrtPrice={}", network, poolIdNorm, blockNumber, sqrtPrice);
        cache.put(cacheKey, sqrtPrice);
        return Optional.of(sqrtPrice);
    }

    private BigInteger callSlot0Extsload(
            RpcEndpointRotator rotator,
            String poolManagerAddress,
            String poolId,
            long blockNumber
    ) {
        // slot0 of pools[poolId] = keccak256(abi.encodePacked(poolId(32B), POOLS_SLOT(32B)))
        String stateSlot = poolStateSlot(poolId);
        // ABI-encode: extsload(bytes32) selector + the 32-byte storage slot key
        String callData = EXTSLOAD_SELECTOR + stateSlot;
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
                // The extsload result is the packed slot0 word:
                //   bits [0..159]   sqrtPriceX96
                //   bits [160..183] tick, [184..207] protocolFee, [208..231] lpFee
                // Mask the bottom 160 bits to recover sqrtPriceX96.
                String word0 = EvmAbiSupport.wordAt(result, 0);
                if (word0 == null) {
                    continue;
                }
                BigInteger sqrtPrice = EvmAbiSupport.uintFromWord(word0).and(SQRT_PRICE_MASK);
                if (sqrtPrice.signum() > 0) {
                    return sqrtPrice;
                }
            } catch (RuntimeException | java.io.IOException ex) {
                log.debug("V4 getSlot0 call failed endpoint={} cause={}", endpoint, ex.getMessage());
            }
        }
        return null;
    }

    /**
     * Computes the storage slot of {@code pools[poolId].slot0}:
     * {@code keccak256(abi.encodePacked(poolId(32B), POOLS_SLOT(32B)))}. Returns 64 lowercase hex.
     */
    static String poolStateSlot(String poolId) {
        String poolIdWord = pad32(poolId);
        String slotIndexWord = pad32(Integer.toHexString(POOLS_SLOT));
        return EvmAbiSupport.keccak256Hex(poolIdWord + slotIndexWord);
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
