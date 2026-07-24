package com.walletradar.application.normalization.pipeline.classification.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.platform.networks.RpcEndpointRotator;
import com.walletradar.platform.networks.evm.abi.EvmAbiSupport;
import com.walletradar.platform.networks.evm.rpc.EvmRpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects a <b>v2 (fungible) DEX gauge</b> and resolves its staked LP token by on-chain grammar,
 * used by {@link com.walletradar.application.normalization.pipeline.classification.onchain.family.LpRegistryClassifier}
 * to key a Velodrome/Aerodrome-style v2 gauge STAKE on the real staked AMM LP token rather than a
 * mis-mapped Slipstream {@code underlyingPositionManager}.
 *
 * <p><b>Grammar (address-only, no hardcoded gauges).</b>
 * <ul>
 *   <li>A concentrated-liquidity (CL / Slipstream) gauge exposes {@code nft()} returning the
 *       NonfungiblePositionManager. When {@code nft()} resolves to a non-zero address the gauge is a
 *       CL gauge → this resolver returns {@link Optional#empty()} so the caller keeps the NFPM +
 *       tokenId path.</li>
 *   <li>A v2 (AMM / fungible) gauge does <b>not</b> expose {@code nft()} (the call reverts) but does
 *       expose {@code pool()} returning the staked AMM LP token (itself an ERC-20 pair). When
 *       {@code nft()} is absent and {@code pool()} resolves, the pool address is the staked LP token
 *       and is returned.</li>
 * </ul>
 *
 * <p>The {@code gauge → stakedLpToken} mapping is immutable on-chain, so results (including negatives)
 * are cached per {@code (network, gauge)} to keep renormalization cheap. Reuses the existing EVM
 * JSON-RPC infrastructure ({@link EvmRpcClient} + {@code evmRotatorsByNetwork}); never throws:
 * transport/parse failures resolve to {@link Optional#empty()} (the caller falls back to the static
 * NFPM mapping).</p>
 */
@Component
public class DexGaugePoolResolver {

    private static final Logger log = LoggerFactory.getLogger(DexGaugePoolResolver.class);

    private static final Duration RPC_TIMEOUT = Duration.ofSeconds(5);
    private static final String POOL_SELECTOR = "0x" + EvmAbiSupport.selector("pool()");
    private static final String NFT_SELECTOR = "0x" + EvmAbiSupport.selector("nft()");
    private static final String ZERO_ADDRESS = "0x0000000000000000000000000000000000000000";

    private final EvmRpcClient evmRpcClient;
    private final ObjectMapper objectMapper;
    private final Map<String, RpcEndpointRotator> rotatorsByNetwork;
    private final Map<String, Optional<String>> cache = new ConcurrentHashMap<>();

    public DexGaugePoolResolver(
            EvmRpcClient evmRpcClient,
            ObjectMapper objectMapper,
            @Qualifier("evmRotatorsByNetwork") Map<String, RpcEndpointRotator> rotatorsByNetwork
    ) {
        this.evmRpcClient = evmRpcClient;
        this.objectMapper = objectMapper;
        this.rotatorsByNetwork = rotatorsByNetwork;
    }

    /**
     * Resolves the staked LP token ({@code gauge.pool()}) when {@code gaugeAddress} is a v2 (fungible)
     * gauge; returns {@link Optional#empty()} when the gauge is a CL/Slipstream gauge (exposes
     * {@code nft()}), when the pool cannot be resolved, or when RPC is unavailable.
     */
    public Optional<String> resolveFungibleGaugeStakedLpToken(NetworkId networkId, String gaugeAddress) {
        if (networkId == null || gaugeAddress == null || gaugeAddress.isBlank()) {
            return Optional.empty();
        }
        String gauge = gaugeAddress.trim().toLowerCase(Locale.ROOT);
        String cacheKey = networkId.name() + ":" + gauge;
        Optional<String> cached = cache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        Optional<String> resolved = resolveUncached(networkId, gauge);
        cache.put(cacheKey, resolved);
        return resolved;
    }

    private Optional<String> resolveUncached(NetworkId networkId, String gauge) {
        String network = networkId.name();
        // A CL/Slipstream gauge exposes nft() (the NFPM). If it resolves to a non-zero address the
        // gauge is NOT a v2 gauge — keep the NFPM + tokenId path.
        Optional<String> nft = ethCallAddress(network, gauge, NFT_SELECTOR);
        if (nft.isPresent()) {
            return Optional.empty();
        }
        // v2 (AMM) gauge: nft() reverts, pool() returns the staked AMM LP token.
        Optional<String> pool = ethCallAddress(network, gauge, POOL_SELECTOR);
        if (pool.isEmpty()) {
            return Optional.empty();
        }
        log.debug("Resolved v2 gauge staked LP token network={} gauge={} pool={}", network, gauge, pool.get());
        return pool;
    }

    /** eth_call returning a non-zero address word, or empty on revert / zero / transport error. */
    private Optional<String> ethCallAddress(String network, String to, String data) {
        RpcEndpointRotator rotator = rotatorsByNetwork.get(network.trim().toUpperCase(Locale.ROOT));
        if (rotator == null) {
            return Optional.empty();
        }
        for (int attempt = 0; attempt < rotator.getMaxAttempts(); attempt++) {
            String endpoint = rotator.getNextEndpoint();
            try {
                String body = evmRpcClient.call(endpoint, "eth_call",
                        List.of(Map.of("to", to, "data", data), "latest")).block(RPC_TIMEOUT);
                String address = parseAddress(body);
                if (address != null && !ZERO_ADDRESS.equals(address)) {
                    return Optional.of(address);
                }
                if (address != null) {
                    // A resolved zero address is a definitive "no value" answer, not an error.
                    return Optional.empty();
                }
            } catch (Exception ignored) {
                // Try the next endpoint; a revert surfaces as an RPC error and yields empty overall.
            }
        }
        return Optional.empty();
    }

    private String parseAddress(String body) throws Exception {
        if (body == null) {
            return null;
        }
        JsonNode root = objectMapper.readTree(body);
        JsonNode error = root.get("error");
        if (error != null && !error.isNull()) {
            return null;
        }
        JsonNode result = root.get("result");
        if (result == null || result.isNull()) {
            return null;
        }
        String hex = result.asText();
        if (hex == null || hex.length() <= 2) {
            return null;
        }
        return EvmAbiSupport.addressFromWord(hex);
    }
}
