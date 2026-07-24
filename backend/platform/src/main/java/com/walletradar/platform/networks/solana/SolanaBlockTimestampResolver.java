package com.walletradar.platform.networks.solana;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.platform.networks.BlockTimestampResolver;
import com.walletradar.platform.networks.RpcEndpointRotator;
import com.walletradar.platform.networks.RpcException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Resolves block (slot) timestamp for Solana via {@code getBlockTime} RPC.
 * Registered with {@link Order}(1) so it takes priority over the EVM resolver for SOLANA.
 */
@Component
@Order(1)
@RequiredArgsConstructor
@Slf4j
public class SolanaBlockTimestampResolver implements BlockTimestampResolver {

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
    public Instant getBlockTimestamp(NetworkId networkId, long blockNumber) {
        RpcEndpointRotator rotator = rotatorsByNetwork.getOrDefault(NetworkId.SOLANA.name(), defaultRotator);
        Exception lastException = null;
        for (int attempt = 0; attempt < rotator.getMaxAttempts(); attempt++) {
            if (attempt > 0) {
                try {
                    Thread.sleep(rotator.retryDelayMs(attempt - 1));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RpcException("Interrupted during getBlockTime retry", e);
                }
            }
            String endpoint = rotator.getNextEndpoint();
            try {
                String json = rpcClient.call(endpoint, "getBlockTime", List.of(blockNumber)).block();
                if (json == null) {
                    throw new RpcException("getBlockTime returned null for slot " + blockNumber);
                }
                JsonNode root = objectMapper.readTree(json);
                JsonNode error = root.path("error");
                if (!error.isMissingNode()) {
                    throw new RpcException("getBlockTime error for slot " + blockNumber + ": " + error);
                }
                JsonNode result = root.path("result");
                if (result.isNull() || result.isMissingNode()) {
                    // Slot may have been skipped; return epoch-based fallback
                    log.warn("getBlockTime null result for slot {} — slot may have been skipped", blockNumber);
                    return Instant.EPOCH;
                }
                return Instant.ofEpochSecond(result.asLong());
            } catch (RpcException e) {
                lastException = e;
                log.warn("getBlockTime attempt {} failed for slot {}: {}", attempt + 1, blockNumber, e.getMessage());
            } catch (Exception e) {
                lastException = e;
                log.warn("getBlockTime attempt {} failed for slot {} with unexpected error", attempt + 1, blockNumber, e);
            }
        }
        throw new RpcException("Solana getBlockTime failed after " + rotator.getMaxAttempts()
                + " attempts for slot " + blockNumber, lastException);
    }
}
