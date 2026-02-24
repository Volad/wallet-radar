package com.walletradar.ingestion.adapter.evm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.domain.NetworkId;
import com.walletradar.ingestion.adapter.BlockHeightResolver;
import com.walletradar.ingestion.adapter.RpcEndpointRotator;
import com.walletradar.ingestion.adapter.RpcException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;

/**
 * Resolves current block for EVM networks via eth_blockNumber.
 */
@Component
@RequiredArgsConstructor
public class EvmBlockHeightResolver implements BlockHeightResolver {

    private final EvmRpcClient rpcClient;
    @Qualifier("evmRotatorsByNetwork")
    private final Map<String, RpcEndpointRotator> rotatorsByNetwork;
    @Qualifier("evmDefaultRpcEndpointRotator")
    private final RpcEndpointRotator defaultRotator;
    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(NetworkId networkId) {
        return networkId != null && networkId != NetworkId.SOLANA;
    }

    @Override
    public long getCurrentBlock(NetworkId networkId) {
        String networkIdStr = networkId.name();
        RpcEndpointRotator rotator = rotatorsByNetwork.getOrDefault(networkIdStr, defaultRotator);
        String endpoint = rotator.getNextEndpoint();
        String json = rpcClient.call(endpoint, "eth_blockNumber", Collections.emptyList()).block();
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
}
