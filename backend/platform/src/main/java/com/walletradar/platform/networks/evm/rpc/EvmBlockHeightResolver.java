package com.walletradar.platform.networks.evm.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.platform.networks.BlockHeightResolver;
import com.walletradar.platform.networks.ReactorBlocking;
import com.walletradar.platform.networks.RpcEndpointRotator;
import com.walletradar.platform.networks.RpcException;
import com.walletradar.platform.networks.evm.explorer.ExplorerProvider;
import com.walletradar.platform.networks.config.IngestionNetworkProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;

/**
 * Resolves current block for EVM networks via eth_blockNumber.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EvmBlockHeightResolver implements BlockHeightResolver {

    private final EvmRpcClient rpcClient;
    private final ExplorerProvider explorerProvider;
    @Qualifier("evmRotatorsByNetwork")
    private final Map<String, RpcEndpointRotator> rotatorsByNetwork;
    @Qualifier("evmDefaultRpcEndpointRotator")
    private final RpcEndpointRotator defaultRotator;
    private final IngestionNetworkProperties ingestionNetworkProperties;
    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(NetworkId networkId) {
        return networkId != null && networkId != NetworkId.SOLANA;
    }

    @Override
    public long getCurrentBlock(NetworkId networkId) {
        Long explorerBlock = resolveCurrentBlockViaExplorer(networkId);
        if (explorerBlock != null && explorerBlock > 0L) {
            Long rpcBlock = resolveCurrentBlockViaRpcSafely(networkId);
            if (rpcBlock != null && rpcBlock > 0L) {
                if (!explorerBlock.equals(rpcBlock)) {
                    log.info(
                            "Head block mismatch on {}: explorerHead={}, rpcHead={}, usingMaxHead={}",
                            networkId,
                            explorerBlock,
                            rpcBlock,
                            Math.max(explorerBlock, rpcBlock)
                    );
                }
                return Math.max(explorerBlock, rpcBlock);
            }
            return explorerBlock;
        }
        return resolveCurrentBlockViaRpc(networkId);
    }

    private Long resolveCurrentBlockViaExplorer(NetworkId networkId) {
        if (!shouldUseExplorerForHeadBlock(networkId)) {
            return null;
        }
        try {
            return explorerProvider.getCurrentBlockNumber(networkId);
        } catch (Exception e) {
            log.warn("Explorer head block resolution failed on {}: {}", networkId, e.getMessage());
            return null;
        }
    }

    private boolean shouldUseExplorerForHeadBlock(NetworkId networkId) {
        if (networkId == null || networkId == NetworkId.SOLANA) {
            return false;
        }
        if (!explorerProvider.supports(networkId)) {
            return false;
        }
        var entry = ingestionNetworkProperties.getNetwork() != null
                ? ingestionNetworkProperties.getNetwork().get(networkId.name())
                : null;
        if (entry == null || entry.getSyncMethod() == null) {
            return false;
        }
        return entry.getSyncMethod() != IngestionNetworkProperties.NetworkIngestionEntry.SyncMethod.RPC;
    }

    private long resolveCurrentBlockViaRpc(NetworkId networkId) {
        String networkIdStr = networkId.name();
        RpcEndpointRotator rotator = rotatorsByNetwork.getOrDefault(networkIdStr, defaultRotator);
        String endpoint = rotator.getNextEndpoint();
        String json = ReactorBlocking.block(
                rpcClient.call(endpoint, "eth_blockNumber", Collections.emptyList()),
                Duration.ofSeconds(60)
        );
        if (json == null) {
            throw new RpcException("eth_blockNumber returned null");
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode error = root.path("error");
            if (!error.isMissingNode()) {
                throw new RpcException("eth_blockNumber error: " + error.toString());
            }
            String result = root.path("result").asText(null);
            if (result == null || !result.startsWith("0x")) {
                throw new RpcException("eth_blockNumber invalid result: " + result);
            }
            return Long.parseLong(result.substring(2), 16);
        } catch (Exception e) {
            if (e instanceof RpcException) throw (RpcException) e;
            throw new RpcException("Failed to parse eth_blockNumber", e);
        }
    }

    private Long resolveCurrentBlockViaRpcSafely(NetworkId networkId) {
        try {
            return resolveCurrentBlockViaRpc(networkId);
        } catch (Exception e) {
            log.warn("RPC head block resolution failed on {}: {}", networkId, e.getMessage());
            return null;
        }
    }
}
