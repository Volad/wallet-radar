package com.walletradar.platform.networks.evm.rpc.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.domain.common.Decimal128Support;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.platform.networks.RpcException;
import com.walletradar.platform.networks.evm.rpc.EvmRpcClient;
import com.walletradar.platform.networks.config.IngestionNetworkProperties;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Provider-first current balance fetch via Ankr Advanced API.
 */
@Component
@RequiredArgsConstructor
public class AnkrAccountBalanceProvider {

    private static final int DEFAULT_PAGE_SIZE = 1000;

    private final EvmRpcClient rpcClient;
    private final ObjectMapper objectMapper;
    private final IngestionNetworkProperties ingestionNetworkProperties;

    public boolean supports(NetworkId networkId) {
        return providerEndpoint(networkId) != null && blockchainCode(networkId) != null;
    }

    public List<AccountBalanceAsset> fetchBalances(String walletAddress, Set<NetworkId> networks) {
        if (walletAddress == null || walletAddress.isBlank() || networks == null || networks.isEmpty()) {
            return List.of();
        }

        String endpoint = providerEndpoint(networks);
        if (endpoint == null) {
            return List.of();
        }

        List<String> blockchains = networks.stream()
                .map(AnkrAccountBalanceProvider::blockchainCode)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (blockchains.isEmpty()) {
            return List.of();
        }

        ArrayList<AccountBalanceAsset> assets = new ArrayList<>();
        String pageToken = null;
        do {
            JsonNode result = callAccountBalance(endpoint, walletAddress, blockchains, pageToken);
            JsonNode assetNodes = result.path("assets");
            if (assetNodes.isArray()) {
                for (JsonNode assetNode : assetNodes) {
                    AccountBalanceAsset asset = toAsset(assetNode);
                    if (asset != null) {
                        assets.add(asset);
                    }
                }
            }
            pageToken = textValue(result.path("nextPageToken"));
        } while (pageToken != null);

        return List.copyOf(assets);
    }

    private JsonNode callAccountBalance(
            String endpoint,
            String walletAddress,
            List<String> blockchains,
            String pageToken
    ) {
        Document params = new Document("walletAddress", walletAddress)
                .append("blockchain", blockchains.size() == 1 ? blockchains.getFirst() : blockchains)
                .append("onlyWhitelisted", false)
                .append("nativeFirst", true)
                .append("pageSize", DEFAULT_PAGE_SIZE);
        if (pageToken != null) {
            params.append("pageToken", pageToken);
        }

        String json = rpcClient.call(endpoint, "ankr_getAccountBalance", params).block();
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode error = root.path("error");
            if (!error.isMissingNode() && !error.isNull() && !error.isEmpty()) {
                throw new RpcException("ankr_getAccountBalance error: " + error);
            }
            JsonNode result = root.path("result");
            if (result.isMissingNode() || result.isNull()) {
                throw new RpcException("ankr_getAccountBalance returned null result");
            }
            return result;
        } catch (RpcException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new RpcException("Failed to parse ankr_getAccountBalance response", ex);
        }
    }

    private AccountBalanceAsset toAsset(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        NetworkId networkId = networkId(node.path("blockchain").asText(null));
        if (networkId == null) {
            return null;
        }
        String rawInteger = textValue(node.path("balanceRawInteger"));
        Integer decimals = integerValue(node.path("tokenDecimals"));
        if (rawInteger == null || decimals == null) {
            return null;
        }
        BigDecimal quantity = Decimal128Support.normalize(
                new BigDecimal(new BigInteger(rawInteger)).movePointLeft(Math.max(0, decimals))
        );
        return new AccountBalanceAsset(
                networkId,
                textValue(node.path("tokenSymbol")),
                normalizeContract(textValue(node.path("contractAddress"))),
                quantity
        );
    }

    private String providerEndpoint(Set<NetworkId> networks) {
        if (networks == null || networks.isEmpty()) {
            return null;
        }
        Set<String> endpoints = new LinkedHashSet<>();
        for (NetworkId networkId : networks) {
            String endpoint = providerEndpoint(networkId);
            if (endpoint != null) {
                endpoints.add(endpoint);
            }
        }
        return endpoints.isEmpty() ? null : endpoints.iterator().next();
    }

    private String providerEndpoint(NetworkId networkId) {
        if (networkId == null || ingestionNetworkProperties.getNetwork() == null) {
            return null;
        }
        IngestionNetworkProperties.NetworkIngestionEntry entry =
                ingestionNetworkProperties.getNetwork().get(networkId.name());
        if (entry == null || entry.getProvider() == null || !entry.getProvider().isEnabled()) {
            return null;
        }
        String baseUrl = entry.getProvider().getBaseUrl();
        return baseUrl == null || baseUrl.isBlank() ? null : baseUrl.trim();
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
            default -> null;
        };
    }

    private static NetworkId networkId(String blockchain) {
        if (blockchain == null || blockchain.isBlank()) {
            return null;
        }
        return switch (blockchain.trim().toLowerCase(Locale.ROOT)) {
            case "eth" -> NetworkId.ETHEREUM;
            case "arbitrum" -> NetworkId.ARBITRUM;
            case "optimism" -> NetworkId.OPTIMISM;
            case "polygon" -> NetworkId.POLYGON;
            case "base" -> NetworkId.BASE;
            case "bsc" -> NetworkId.BSC;
            case "avalanche" -> NetworkId.AVALANCHE;
            case "linea" -> NetworkId.LINEA;
            default -> null;
        };
    }

    private static Integer integerValue(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return node.canConvertToInt() ? node.intValue() : null;
    }

    private static String normalizeContract(String contract) {
        if (contract == null || contract.isBlank()) {
            return null;
        }
        return contract.trim().toLowerCase(Locale.ROOT);
    }

    private static String textValue(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String text = node.asText(null);
        return text == null || text.isBlank() ? null : text;
    }

    public record AccountBalanceAsset(
            NetworkId networkId,
            String assetSymbol,
            String assetContract,
            BigDecimal quantity
    ) {
    }
}
