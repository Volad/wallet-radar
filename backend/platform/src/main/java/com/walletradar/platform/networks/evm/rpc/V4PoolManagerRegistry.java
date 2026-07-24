package com.walletradar.platform.networks.evm.rpc;

import com.walletradar.domain.common.NetworkId;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Registry of Uniswap V4 / Pancake Infinity CL PoolManager contract addresses per network.
 *
 * <p>PoolManager is the single contract whose {@code extsload(bytes32)} storage read exposes the
 * current pool price ({@code slot0}). Addresses are configured in {@code application.yml} under
 * {@code walletradar.v4.pool-manager-by-network} and fall back to known canonical defaults for
 * well-known networks.
 *
 * <p>Returns {@code null} when no PoolManager address is known for a network, causing the
 * {@link V4PoolStateReader} to skip the RPC call and fall back to {@code fee = 0}.
 */
@Component
@ConfigurationProperties(prefix = "walletradar.v4")
public class V4PoolManagerRegistry {

    /**
     * Canonical Uniswap V4 PoolManager address (deterministic CREATE2, same across all EVM chains
     * that deploy via the canonical factory).
     */
    private static final String CANONICAL_V4_POOL_MANAGER =
            "0x1f98400000000000000000000000000000000004";

    /**
     * Pancake Infinity CLPoolManager on BSC mainnet.
     * Source: https://developer.pancakeswap.finance/contracts/infinity/overview
     */
    private static final String PANCAKE_INFINITY_POOL_MANAGER_BSC =
            "0x47c09ee4bb56c0e8aceceb4d52f4ffd13e26e9c4";

    /** Overrides from {@code walletradar.v4.pool-manager-by-network} in application.yml. */
    @Setter
    private Map<String, String> poolManagerByNetwork = new HashMap<>();

    /**
     * Returns the PoolManager address for the given network, or {@code null} if unknown.
     * Precedence: YAML override → built-in defaults.
     */
    public String poolManagerFor(NetworkId network) {
        if (network == null) {
            return null;
        }
        String override = poolManagerByNetwork.get(network.name().toUpperCase(Locale.ROOT));
        if (override != null && !override.isBlank()) {
            return override.trim().toLowerCase(Locale.ROOT);
        }
        return switch (network) {
            case UNICHAIN, ETHEREUM, ARBITRUM, OPTIMISM, BASE -> CANONICAL_V4_POOL_MANAGER;
            case BSC -> PANCAKE_INFINITY_POOL_MANAGER_BSC;
            default -> null;
        };
    }
}
