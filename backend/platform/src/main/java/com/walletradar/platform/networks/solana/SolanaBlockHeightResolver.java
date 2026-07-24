package com.walletradar.platform.networks.solana;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.platform.networks.BlockHeightResolver;
import com.walletradar.platform.networks.RpcEndpointRotator;
import com.walletradar.platform.networks.RpcException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Resolves the current slot (block height equivalent) for Solana via the {@code getSlot} RPC method.
 * Registered with {@link Order}(1) so it takes priority over the EVM resolver for SOLANA.
 */
@Component
@Order(1)
@RequiredArgsConstructor
@Slf4j
public class SolanaBlockHeightResolver implements BlockHeightResolver {

    private final SolanaRpcClient rpcClient;
    @Qualifier("solanaRotatorsByNetwork")
    private final Map<String, RpcEndpointRotator> rotatorsByNetwork;
    @Qualifier("solanaDefaultRpcEndpointRotator")
    private final RpcEndpointRotator defaultRotator;
    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(NetworkId networkId) {
        return networkId == NetworkId.SOLANA;
    }

    @Override
    public long getCurrentBlock(NetworkId networkId) {
        RpcEndpointRotator rotator = rotatorsByNetwork.getOrDefault(NetworkId.SOLANA.name(), defaultRotator);
        Exception lastException = null;
        for (int attempt = 0; attempt < rotator.getMaxAttempts(); attempt++) {
            if (attempt > 0) {
                try {
                    Thread.sleep(rotator.retryDelayMs(attempt - 1));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RpcException("Interrupted during getSlot retry", e);
                }
            }
            String endpoint = rotator.getNextEndpoint();
            try {
                String json = rpcClient.call(endpoint, "getSlot",
                        List.of(Map.of("commitment", "finalized"))).block();
                if (json == null) {
                    throw new RpcException("getSlot returned null");
                }
                JsonNode root = objectMapper.readTree(json);
                JsonNode error = root.path("error");
                if (!error.isMissingNode()) {
                    throw new RpcException("getSlot error: " + error);
                }
                return root.path("result").asLong(-1L);
            } catch (RpcException e) {
                lastException = e;
                log.warn("getSlot attempt {} failed: {}", attempt + 1, e.getMessage());
            } catch (Exception e) {
                lastException = e;
                log.warn("getSlot attempt {} failed with unexpected error", attempt + 1, e);
            }
        }
        throw new RpcException("Solana getSlot failed after " + rotator.getMaxAttempts() + " attempts", lastException);
    }
}
