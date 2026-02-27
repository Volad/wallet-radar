package com.walletradar.ingestion.adapter.evm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.domain.NetworkId;
import com.walletradar.ingestion.adapter.BlockTimestampResolver;
import com.walletradar.ingestion.adapter.RpcEndpointRotator;
import com.walletradar.ingestion.adapter.RpcException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Resolves block timestamp for EVM via eth_getBlockByNumber.
 */
@Component
@RequiredArgsConstructor
public class EvmBlockTimestampResolver implements BlockTimestampResolver {

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
    public Instant getBlockTimestamp(NetworkId networkId, long blockNumber) {
        String networkIdStr = networkId.name();
        RpcEndpointRotator rotator = rotatorsByNetwork.getOrDefault(networkIdStr, defaultRotator);
        String blockHex = "0x" + Long.toHexString(blockNumber);
        Exception lastException = null;
        for (int attempt = 0; attempt < rotator.getMaxAttempts(); attempt++) {
            String endpoint = rotator.getNextEndpoint();
            try {
                return callGetBlockByNumber(endpoint, blockHex, blockNumber);
            } catch (Exception e) {
                lastException = e;
            }
        }
        throw new RpcException("eth_getBlockByNumber failed after " + rotator.getMaxAttempts()
                + " attempts for block " + blockNumber, lastException);
    }

    private Instant callGetBlockByNumber(String endpoint, String blockHex, long blockNumber) {
        String json = rpcClient.call(endpoint, "eth_getBlockByNumber", List.of(blockHex, false)).block();
        if (json == null) {
            throw new RpcException("eth_getBlockByNumber returned null");
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode error = root.path("error");
            if (!error.isMissingNode()) {
                throw new RpcException("eth_getBlockByNumber error: " + error.toString());
            }
            JsonNode result = root.path("result");
            if (result.isMissingNode() || result.isNull()) {
                throw new RpcException("eth_getBlockByNumber no result for block " + blockNumber);
            }
            String timestampHex = result.path("timestamp").asText(null);
            if (timestampHex == null || !timestampHex.startsWith("0x")) {
                throw new RpcException("eth_getBlockByNumber invalid timestamp: " + timestampHex);
            }
            long epochSec = Long.parseLong(timestampHex.substring(2), 16);
            return Instant.ofEpochSecond(epochSec);
        } catch (Exception e) {
            if (e instanceof RpcException) throw (RpcException) e;
            throw new RpcException("Failed to parse eth_getBlockByNumber", e);
        }
    }
}
