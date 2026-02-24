package com.walletradar.ingestion.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.domain.NetworkId;
import com.walletradar.domain.RawTransaction;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Solana adapter: getSignaturesForAddress + getTransaction per signature, within batch and rate limits.
 * Reuses RetryPolicy and endpoint rotation via RpcEndpointRotator. Builds RawTransaction with rawData (slot, blockTime, tx payload).
 */
@Component
@Slf4j
public class SolanaNetworkAdapter implements NetworkAdapter {

    /** Max signatures per getSignaturesForAddress call (Solana RPC limit 1–1000). */
    public static final int DEFAULT_SIGNATURES_LIMIT = 1000;

    private final SolanaRpcClient rpcClient;
    private final Map<String, RpcEndpointRotator> rotatorsByNetwork;
    private final RpcEndpointRotator defaultRotator;
    private final ObjectMapper objectMapper;

    public SolanaNetworkAdapter(SolanaRpcClient rpcClient,
                                Map<String, RpcEndpointRotator> rotatorsByNetwork,
                                @Qualifier("solanaDefaultRpcEndpointRotator") RpcEndpointRotator defaultRotator,
                                ObjectMapper objectMapper) {
        this.rpcClient = rpcClient;
        this.rotatorsByNetwork = rotatorsByNetwork;
        this.defaultRotator = defaultRotator;
        this.objectMapper = objectMapper;
    }

    @Value("${walletradar.ingestion.solana.signatures-limit:1000}")
    private int signaturesLimit = DEFAULT_SIGNATURES_LIMIT;

    @Override
    public boolean supports(NetworkId networkId) {
        return networkId == NetworkId.SOLANA;
    }

    @Override
    public int getMaxBlockBatchSize() {
        return Math.min(Math.max(1, signaturesLimit), 1000);
    }

    @Override
    public List<RawTransaction> fetchTransactions(String walletAddress, NetworkId networkId, long fromBlock, long toBlock) {
        if (networkId != NetworkId.SOLANA) {
            return List.of();
        }
        String networkIdStr = networkId.name();
        RpcEndpointRotator rotator = rotatorsByNetwork.getOrDefault(networkIdStr, defaultRotator);
        List<RawTransaction> all = new ArrayList<>();
        String before = null;
        int limit = getMaxBlockBatchSize();
        long maxTxs = (toBlock > 0 && fromBlock >= 0) ? (toBlock - fromBlock + 1) : Long.MAX_VALUE;
        while (all.size() < maxTxs) {
            List<RawTransaction> batch = fetchSignaturesPageWithRetry(walletAddress, networkIdStr, before, limit, rotator);
            if (batch.isEmpty()) {
                break;
            }
            for (RawTransaction tx : batch) {
                if (all.size() >= maxTxs) break;
                all.add(tx);
            }
            if (batch.size() < limit) {
                break;
            }
            before = batch.get(batch.size() - 1).getTxHash();
        }
        return all;
    }

    private List<RawTransaction> fetchSignaturesPageWithRetry(String walletAddress, String networkIdStr, String before, int limit, RpcEndpointRotator rotator) {
        Exception lastException = null;
        for (int attempt = 0; attempt < rotator.getMaxAttempts(); attempt++) {
            if (attempt > 0) {
                try {
                    Thread.sleep(rotator.retryDelayMs(attempt - 1));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RpcException("Interrupted during retry", e);
                }
            }
            String endpoint = rotator.getNextEndpoint();
            try {
                List<JsonNode> sigInfos = getSignaturesForAddress(endpoint, walletAddress, before, limit);
                List<RawTransaction> txs = new ArrayList<>();
                for (JsonNode sigInfo : sigInfos) {
                    String signature = sigInfo.path("signature").asText();
                    if (sigInfo.path("err").isNull() == false) {
                        continue;
                    }
                    RawTransaction tx = getTransactionAndBuildRaw(endpoint, signature, networkIdStr, sigInfo, rotator);
                    if (tx != null) {
                        txs.add(tx);
                    }
                }
                return txs;
            } catch (Exception e) {
                lastException = e;
            }
        }
        throw new RpcException("Solana RPC failed after " + rotator.getMaxAttempts() + " attempts", lastException);
    }

    private List<JsonNode> getSignaturesForAddress(String endpoint, String address, String before, int limit) throws JsonProcessingException {
        List<Object> params = new ArrayList<>();
        params.add(address);
        Map<String, Object> config = new java.util.HashMap<>();
        config.put("limit", limit);
        config.put("commitment", "finalized");
        if (before != null) {
            config.put("before", before);
        }
        params.add(config);
        String json = rpcClient.call(endpoint, "getSignaturesForAddress", params).block();
        JsonNode root = objectMapper.readTree(json);
        JsonNode error = root.path("error");
        if (!error.isMissingNode()) {
            throw new RpcException("getSignaturesForAddress error: " + error.toString());
        }
        JsonNode result = root.path("result");
        if (!result.isArray()) {
            return List.of();
        }
        List<JsonNode> list = new ArrayList<>();
        result.forEach(list::add);
        return list;
    }

    private RawTransaction getTransactionAndBuildRaw(String endpoint, String signature, String networkIdStr, JsonNode sigInfo, RpcEndpointRotator rotator) {
        Exception lastException = null;
        for (int attempt = 0; attempt < rotator.getMaxAttempts(); attempt++) {
            if (attempt > 0) {
                try {
                    Thread.sleep(rotator.retryDelayMs(attempt - 1));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RpcException("Interrupted during getTransaction retry", e);
                }
            }
            String ep = rotator.getNextEndpoint();
            try {
                String json = rpcClient.call(ep, "getTransaction", List.of(signature, Map.of("encoding", "jsonParsed", "maxSupportedTransactionVersion", 0))).block();
                JsonNode root = objectMapper.readTree(json);
                JsonNode error = root.path("error");
                if (!error.isMissingNode()) {
                    throw new RpcException("getTransaction error: " + error.toString());
                }
                JsonNode result = root.path("result");
                if (result.isNull() || result.isMissingNode()) {
                    return null;
                }
                RawTransaction tx = new RawTransaction();
                tx.setTxHash(signature);
                tx.setNetworkId(networkIdStr);
                Document rawData = new Document("signature", signature);
                if (sigInfo.has("slot")) {
                    rawData.put("slot", sigInfo.get("slot").asLong());
                }
                if (sigInfo.has("blockTime") && !sigInfo.get("blockTime").isNull()) {
                    rawData.put("blockTime", sigInfo.get("blockTime").asLong());
                }
                rawData.put("transaction", result.toString());
                tx.setRawData(rawData);
                return tx;
            } catch (Exception e) {
                lastException = e;
            }
        }
        log.warn("getTransaction failed for {} after retries: {}", signature, lastException != null ? lastException.getMessage() : "");
        return null;
    }
}
