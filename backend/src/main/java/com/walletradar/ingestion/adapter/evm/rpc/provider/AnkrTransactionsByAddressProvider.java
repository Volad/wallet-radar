package com.walletradar.ingestion.adapter.evm.rpc.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.ingestion.adapter.RpcException;
import com.walletradar.ingestion.adapter.evm.rpc.EvmRpcClient;
import com.walletradar.ingestion.config.IngestionNetworkProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@Component
@Slf4j
@RequiredArgsConstructor
public class AnkrTransactionsByAddressProvider {

    private final EvmRpcClient rpcClient;
    private final ObjectMapper objectMapper;
    private final IngestionNetworkProperties ingestionNetworkProperties;

    public List<Document> fetchTransactionsByAddress(String walletAddress, NetworkId networkId, long fromBlock, long toBlock) {
        IngestionNetworkProperties.NetworkIngestionEntry.Provider providerConfig = providerConfig(networkId);
        if (providerConfig == null || !providerConfig.isEnabled() || providerConfig.getBaseUrl() == null || providerConfig.getBaseUrl().isBlank()) {
            return List.of();
        }
        String endpoint = providerConfig.getBaseUrl();
        String blockchain = blockchainCode(networkId);
        if (blockchain == null) {
            return List.of();
        }

        JsonNode result = callTransactionsByAddress(endpoint, walletAddress, blockchain, fromBlock, toBlock);
        String nextPageToken = textValue(result.path("nextPageToken"));
        if (nextPageToken != null) {
            log.warn(
                    "ankr_getTransactionsByAddress returned nextPageToken for wallet={}, network={}, fromBlock={}, toBlock={}; "
                            + "provider-first mode requires one response per segment",
                    walletAddress,
                    networkId,
                    fromBlock,
                    toBlock
            );
            throw new RpcException("ankr_getTransactionsByAddress returned nextPageToken for one-pass segment fetch");
        }
        JsonNode transactionNodes = result.path("transactions");
        if (!transactionNodes.isArray() || transactionNodes.isEmpty()) {
            return List.of();
        }
        return transactionNodesToDocuments(transactionNodes);
    }

    private JsonNode callTransactionsByAddress(
            String endpoint,
            String walletAddress,
            String blockchain,
            long fromBlock,
            long toBlock
    ) {
        Document params = new Document("address", walletAddress)
                .append("blockchain", blockchain)
                .append("includeLogs", true)
                .append("descOrder", false)
                .append("fromBlock", fromBlock)
                .append("toBlock", toBlock);

        String json = rpcClient.call(endpoint, "ankr_getTransactionsByAddress", params).block();
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode error = root.path("error");
            if (!error.isMissingNode() && !error.isNull() && !error.isEmpty()) {
                throw new RpcException("ankr_getTransactionsByAddress error: " + error);
            }
            JsonNode result = root.path("result");
            if (result.isMissingNode() || result.isNull()) {
                throw new RpcException("ankr_getTransactionsByAddress returned null result");
            }
            return result;
        } catch (RpcException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new RpcException("Failed to parse ankr_getTransactionsByAddress response", ex);
        }
    }

    private List<Document> transactionNodesToDocuments(JsonNode transactionNodes) {
        java.util.ArrayList<Document> transactions = new java.util.ArrayList<>(transactionNodes.size());
        for (JsonNode transactionNode : transactionNodes) {
            transactions.add(toDocument(transactionNode));
        }
        return List.copyOf(transactions);
    }

    private IngestionNetworkProperties.NetworkIngestionEntry.Provider providerConfig(NetworkId networkId) {
        if (networkId == null || ingestionNetworkProperties.getNetwork() == null) {
            return null;
        }
        IngestionNetworkProperties.NetworkIngestionEntry entry = ingestionNetworkProperties.getNetwork().get(networkId.name());
        return entry == null ? null : entry.getProvider();
    }

    private static String blockchainCode(NetworkId networkId) {
        if (networkId == null) {
            return null;
        }
        return switch (networkId) {
            case ETHEREUM -> "eth";
            case ARBITRUM -> "arbitrum";
            case OPTIMISM -> "optimism";
            case POLYGON -> "polygon";
            case BASE -> "base";
            case BSC -> "bsc";
            case AVALANCHE -> "avalanche";
            case LINEA -> "linea";
            default -> networkId.name().toLowerCase(Locale.ROOT);
        };
    }

    private Document toDocument(JsonNode node) {
        try {
            return Document.parse(objectMapper.writeValueAsString(node));
        } catch (Exception ex) {
            throw new RpcException("Failed to convert provider transaction to Document", ex);
        }
    }

    private static String textValue(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String text = node.asText(null);
        return text == null || text.isBlank() ? null : text;
    }
}
