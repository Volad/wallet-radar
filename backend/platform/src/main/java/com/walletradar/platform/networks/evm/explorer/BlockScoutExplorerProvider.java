package com.walletradar.platform.networks.evm.explorer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.platform.common.RetryPolicy;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.platform.networks.evm.explorer.model.ExplorerInternalTransfer;
import com.walletradar.platform.networks.evm.explorer.model.ExplorerReceipt;
import com.walletradar.platform.networks.evm.explorer.model.ExplorerTokenTransfer;
import com.walletradar.platform.networks.evm.explorer.model.ExplorerTransactionDetails;
import com.walletradar.platform.networks.evm.explorer.model.ExplorerTransaction;
import com.walletradar.platform.networks.ReactorBlocking;
import com.walletradar.platform.networks.config.IngestionExplorerProperties;
import com.walletradar.platform.networks.config.IngestionNetworkProperties;
import com.walletradar.platform.persistence.support.BsonCoercionSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import java.math.BigInteger;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Explorer provider for Blockscout module/action API endpoints.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BlockScoutExplorerProvider implements ExplorerProvider {

    private static final int PAGE_SIZE_TX = 500;
    private static final int PAGE_SIZE_TOKEN = 500;
    private static final int PAGE_SIZE_INTERNAL = 500;

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;
    private final IngestionExplorerProperties explorerProperties;
    private final IngestionNetworkProperties ingestionNetworkProperties;
    private volatile WebClient explorerClient;

    @Override
    public boolean supports(NetworkId networkId) {
        if (networkId == null || networkId == NetworkId.SOLANA) {
            return false;
        }
        ResolvedExplorerConfig cfg = resolveConfig(networkId);
        IngestionNetworkProperties.NetworkIngestionEntry.ExplorerSource entry = cfg != null ? cfg.entry() : null;
        return cfg != null
                && entry != null
                && entry.isEnabled()
                && entry.getBaseUrl() != null
                && !entry.getBaseUrl().isBlank();
    }

    @Override
    public Long getCurrentBlockNumber(NetworkId networkId) {
        JsonNode root = callRpc(networkId, "eth_blockNumber", List.of());
        JsonNode result = resultNode(root);
        if (result == null || result.isMissingNode() || result.isNull()) {
            return null;
        }
        String blockHex = result.asText(null);
        if (blockHex == null || !blockHex.startsWith("0x")) {
            return null;
        }
        try {
            return Long.parseLong(blockHex.substring(2), 16);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    @Override
    public List<ExplorerTransaction> getTransactions(String walletAddress, NetworkId networkId, long fromBlock, long toBlock, int page) {
        return callAccountList(networkId, walletAddress, fromBlock, toBlock, page, "txlist", PAGE_SIZE_TX, ExplorerTransaction::new);
    }

    @Override
    public List<ExplorerTokenTransfer> getTokenTransfers(String walletAddress, NetworkId networkId, long fromBlock, long toBlock, int page) {
        return callAccountList(networkId, walletAddress, fromBlock, toBlock, page, "tokentx", PAGE_SIZE_TOKEN, ExplorerTokenTransfer::new);
    }

    @Override
    public List<ExplorerInternalTransfer> getInternalTransfers(String walletAddress, NetworkId networkId, long fromBlock, long toBlock, int page) {
        return callAccountList(networkId, walletAddress, fromBlock, toBlock, page, "txlistinternal", PAGE_SIZE_INTERNAL, ExplorerInternalTransfer::new);
    }

    @Override
    public ExplorerTransaction getTransaction(String txHash, NetworkId networkId) {
        JsonNode root = callRpc(networkId, "eth_getTransactionByHash", List.of(txHash));
        JsonNode result = resultNode(root);
        if (result == null || result.isMissingNode() || result.isNull() || !result.isObject()) {
            return null;
        }
        return new ExplorerTransaction(toDocument(result));
    }

    @Override
    public ExplorerTransactionDetails getTransactionDetails(String txHash, NetworkId networkId) {
        JsonNode result = callTransactionDetails(networkId, txHash);
        if (result == null || result.isMissingNode() || result.isNull() || !result.isObject()) {
            return null;
        }
        return new ExplorerTransactionDetails(toDocument(result));
    }

    @Override
    public ExplorerReceipt getReceipt(String txHash, NetworkId networkId) {
        JsonNode root = callRpc(networkId, "eth_getTransactionReceipt", List.of(txHash));
        JsonNode result = resultNode(root);
        if (result == null || result.isMissingNode() || result.isNull() || !result.isObject()) {
            return null;
        }
        return new ExplorerReceipt(toDocument(result));
    }

    public BigInteger getNativeBalance(String walletAddress, NetworkId networkId) {
        JsonNode root = callAddressInfo(networkId, walletAddress);
        if (root == null) {
            return null;
        }
        return parseDecimalValue(root.path("coin_balance"));
    }

    public Map<String, TokenBalanceSnapshot> getTokenBalances(String walletAddress, NetworkId networkId) {
        JsonNode root = callAddressTokenBalances(networkId, walletAddress);
        if (root == null) {
            return Map.of();
        }
        JsonNode items = root.isArray() ? root : root.path("items");
        if (!items.isArray()) {
            return Map.of();
        }
        Map<String, TokenBalanceSnapshot> balances = new LinkedHashMap<>();
        for (JsonNode item : items) {
            JsonNode token = item.path("token");
            String contractAddress = token.path("address_hash").asText(null);
            BigInteger rawQuantity = parseDecimalValue(item.path("value"));
            Integer decimals = parseOptionalInteger(token.path("decimals"));
            if (contractAddress == null || contractAddress.isBlank() || rawQuantity == null || decimals == null) {
                continue;
            }
            balances.putIfAbsent(
                    contractAddress.toLowerCase(Locale.ROOT),
                    new TokenBalanceSnapshot(rawQuantity, decimals)
            );
        }
        return Map.copyOf(balances);
    }

    public Integer getTokenDecimals(String contractAddress, NetworkId networkId) {
        JsonNode root = callTokenInfo(networkId, contractAddress);
        if (root == null) {
            return null;
        }
        return parseOptionalInteger(root.path("decimals"));
    }

    public List<ExplorerTokenTransfer> getTransactionTokenTransfers(String txHash, NetworkId networkId) {
        JsonNode root = callTransactionSubresource(networkId, txHash, "token-transfers");
        JsonNode items = itemsNode(root);
        if (items == null || !items.isArray()) {
            return List.of();
        }
        List<ExplorerTokenTransfer> out = new ArrayList<>();
        for (JsonNode node : items) {
            Document raw = toDocument(node);
            Document token = BsonCoercionSupport.asDocument(raw.get("token"));
            Document from = BsonCoercionSupport.asDocument(raw.get("from"));
            Document to = BsonCoercionSupport.asDocument(raw.get("to"));
            Document normalized = new Document();
            normalized.put("hash", stringValue(raw.get("transaction_hash")));
            normalized.put("blockNumber", stringifyNumber(raw.get("block_number")));
            normalized.put("timeStamp", stringValue(raw.get("timestamp")));
            normalized.put("contractAddress", stringValue(token == null ? null : token.get("address_hash")));
            normalized.put("from", stringValue(from == null ? null : from.get("hash")));
            normalized.put("to", stringValue(to == null ? null : to.get("hash")));
            normalized.put("value", stringValue(raw.get("total")));
            normalized.put("tokenDecimal", stringValue(token == null ? null : token.get("decimals")));
            normalized.put("tokenSymbol", stringValue(token == null ? null : token.get("symbol")));
            normalized.put("tokenName", stringValue(token == null ? null : token.get("name")));
            out.add(new ExplorerTokenTransfer(normalized));
        }
        return List.copyOf(out);
    }

    public List<ExplorerInternalTransfer> getTransactionInternalTransfers(String txHash, NetworkId networkId) {
        JsonNode root = callTransactionSubresource(networkId, txHash, "internal-transactions");
        JsonNode items = itemsNode(root);
        if (items == null || !items.isArray()) {
            return List.of();
        }
        List<ExplorerInternalTransfer> out = new ArrayList<>();
        for (JsonNode node : items) {
            Document raw = toDocument(node);
            Document from = BsonCoercionSupport.asDocument(raw.get("from"));
            Document to = BsonCoercionSupport.asDocument(raw.get("to"));
            Document normalized = new Document();
            normalized.put("hash", stringValue(raw.get("transaction_hash")));
            normalized.put("blockNumber", stringifyNumber(raw.get("block_number")));
            normalized.put("timeStamp", stringValue(raw.get("timestamp")));
            normalized.put("from", stringValue(from == null ? null : from.get("hash")));
            normalized.put("to", stringValue(to == null ? null : to.get("hash")));
            normalized.put("value", stringValue(raw.get("value")));
            normalized.put("isError", isSuccessful(raw) ? "0" : "1");
            out.add(new ExplorerInternalTransfer(normalized));
        }
        return List.copyOf(out);
    }

    private <T> List<T> callAccountList(
            NetworkId networkId,
            String walletAddress,
            long fromBlock,
            long toBlock,
            int page,
            String action,
            int pageSize,
            Function<Document, T> mapper
    ) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("module", "account");
        params.put("action", action);
        params.put("address", walletAddress);
        params.put("startblock", Long.toString(fromBlock));
        params.put("endblock", Long.toString(toBlock));
        params.put("page", Integer.toString(Math.max(1, page)));
        params.put("offset", Integer.toString(Math.max(1, pageSize)));
        params.put("sort", "asc");

        JsonNode root = call(networkId, params);
        JsonNode result = resultNode(root);
        if (result == null || !result.isArray()) {
            return List.of();
        }
        List<T> out = new ArrayList<>();
        for (JsonNode node : result) {
            out.add(mapper.apply(toDocument(node)));
        }
        return out;
    }

    private JsonNode call(NetworkId networkId, Map<String, String> params) {
        ResolvedExplorerConfig cfg = resolveConfig(networkId);
        if (cfg == null || cfg.entry() == null) {
            return null;
        }
        RetryPolicy retryPolicy = new RetryPolicy(
                Math.max(100L, explorerProperties.getBaseDelayMs()),
                Math.max(0.0, explorerProperties.getJitterFactor()),
                Math.max(1, explorerProperties.getMaxAttempts())
        );

        Exception last = null;
        for (int attempt = 0; attempt < retryPolicy.getMaxAttempts(); attempt++) {
            if (attempt > 0) {
                sleepQuietly(retryPolicy.delayMs(attempt - 1));
            }
            try {
                JsonNode root = execute(buildUrl(cfg, params));
                if (root == null) {
                    continue;
                }
                if (isExplorerError(root)) {
                    throw new IllegalStateException("Blockscout API error: " + errorMessage(root));
                }
                return root;
            } catch (Exception e) {
                last = e;
            }
        }
        if (last != null) {
            log.warn("Blockscout call failed on {} after retries: params={}, cause={}",
                    networkId, params, last.getMessage(), last);
        } else {
            log.warn("Blockscout call failed on {} after retries: params={}, cause=unknown",
                    networkId, params);
        }
        return null;
    }

    private JsonNode callRpc(NetworkId networkId, String method, List<Object> rpcParams) {
        ResolvedExplorerConfig cfg = resolveConfig(networkId);
        if (cfg == null || cfg.entry() == null) {
            return null;
        }
        RetryPolicy retryPolicy = new RetryPolicy(
                Math.max(100L, explorerProperties.getBaseDelayMs()),
                Math.max(0.0, explorerProperties.getJitterFactor()),
                Math.max(1, explorerProperties.getMaxAttempts())
        );

        Exception last = null;
        for (int attempt = 0; attempt < retryPolicy.getMaxAttempts(); attempt++) {
            if (attempt > 0) {
                sleepQuietly(retryPolicy.delayMs(attempt - 1));
            }
            try {
                JsonNode root = executeRpc(buildRpcUrl(cfg), method, rpcParams);
                if (root == null) {
                    continue;
                }
                if (isRpcError(root)) {
                    throw new IllegalStateException("Blockscout RPC error: " + errorMessage(root));
                }
                return root;
            } catch (Exception e) {
                if (isUnsupportedRpcEndpoint(e)) {
                    log.debug("Blockscout eth-rpc endpoint unsupported: method={}, cause={}", method, e.getMessage());
                    return null;
                }
                last = e;
            }
        }
        if (last != null) {
            log.warn("Blockscout RPC call failed on {} after retries: method={}, cause={}",
                    networkId, method, last.getMessage(), last);
        } else {
            log.warn("Blockscout RPC call failed on {} after retries: method={}, cause=unknown",
                    networkId, method);
        }
        return null;
    }

    private JsonNode callTransactionDetails(NetworkId networkId, String txHash) {
        ResolvedExplorerConfig cfg = resolveConfig(networkId);
        if (cfg == null || cfg.entry() == null || txHash == null || txHash.isBlank()) {
            return null;
        }
        RetryPolicy retryPolicy = new RetryPolicy(
                Math.max(100L, explorerProperties.getBaseDelayMs()),
                Math.max(0.0, explorerProperties.getJitterFactor()),
                Math.max(1, explorerProperties.getMaxAttempts())
        );

        Exception last = null;
        Map<String, String> queryParams = new LinkedHashMap<>();
        if (cfg.entry().getApiKey() != null && !cfg.entry().getApiKey().isBlank()) {
            queryParams.put("apikey", cfg.entry().getApiKey());
        }

        for (int attempt = 0; attempt < retryPolicy.getMaxAttempts(); attempt++) {
            if (attempt > 0) {
                sleepQuietly(retryPolicy.delayMs(attempt - 1));
            }
            try {
                JsonNode root = execute(buildV2TransactionUrl(cfg, txHash, queryParams));
                if (root == null) {
                    continue;
                }
                if (isV2Error(root)) {
                    throw new IllegalStateException("Blockscout tx-details error: " + errorMessage(root));
                }
                return root;
            } catch (Exception e) {
                if (isNotFound(e)) {
                    return null;
                }
                last = e;
            }
        }
        if (last != null) {
            log.warn("Blockscout tx-details call failed on {} after retries: txHash={}, cause={}",
                    networkId, txHash, last.getMessage(), last);
        } else {
            log.warn("Blockscout tx-details call failed on {} after retries: txHash={}, cause=unknown",
                    networkId, txHash);
        }
        return null;
    }

    private JsonNode callAddressInfo(NetworkId networkId, String walletAddress) {
        ResolvedExplorerConfig cfg = resolveConfig(networkId);
        if (cfg == null || cfg.entry() == null || walletAddress == null || walletAddress.isBlank()) {
            return null;
        }
        return callV2(
                networkId,
                buildV2AddressUrl(cfg, walletAddress, apiKeyParams(cfg)),
                "address-info",
                walletAddress
        );
    }

    private JsonNode callAddressTokenBalances(NetworkId networkId, String walletAddress) {
        ResolvedExplorerConfig cfg = resolveConfig(networkId);
        if (cfg == null || cfg.entry() == null || walletAddress == null || walletAddress.isBlank()) {
            return null;
        }
        return callV2(
                networkId,
                buildV2AddressSubresourceUrl(cfg, walletAddress, "token-balances", apiKeyParams(cfg)),
                "address-token-balances",
                walletAddress
        );
    }

    private JsonNode callTokenInfo(NetworkId networkId, String contractAddress) {
        ResolvedExplorerConfig cfg = resolveConfig(networkId);
        if (cfg == null || cfg.entry() == null || contractAddress == null || contractAddress.isBlank()) {
            return null;
        }
        return callV2(
                networkId,
                buildV2TokenUrl(cfg, contractAddress, apiKeyParams(cfg)),
                "token-info",
                contractAddress
        );
    }

    private JsonNode callTransactionSubresource(NetworkId networkId, String txHash, String subresource) {
        ResolvedExplorerConfig cfg = resolveConfig(networkId);
        if (cfg == null || cfg.entry() == null || txHash == null || txHash.isBlank() || subresource == null || subresource.isBlank()) {
            return null;
        }
        RetryPolicy retryPolicy = new RetryPolicy(
                Math.max(100L, explorerProperties.getBaseDelayMs()),
                Math.max(0.0, explorerProperties.getJitterFactor()),
                Math.max(1, explorerProperties.getMaxAttempts())
        );

        Exception last = null;
        Map<String, String> queryParams = new LinkedHashMap<>();
        if (cfg.entry().getApiKey() != null && !cfg.entry().getApiKey().isBlank()) {
            queryParams.put("apikey", cfg.entry().getApiKey());
        }

        for (int attempt = 0; attempt < retryPolicy.getMaxAttempts(); attempt++) {
            if (attempt > 0) {
                sleepQuietly(retryPolicy.delayMs(attempt - 1));
            }
            try {
                JsonNode root = execute(buildV2TransactionSubresourceUrl(cfg, txHash, subresource, queryParams));
                if (root == null) {
                    continue;
                }
                if (isV2Error(root)) {
                    throw new IllegalStateException("Blockscout tx-subresource error: " + errorMessage(root));
                }
                return root;
            } catch (Exception e) {
                if (isNotFound(e)) {
                    return null;
                }
                last = e;
            }
        }
        if (last != null) {
            log.warn("Blockscout tx-subresource call failed on {} after retries: txHash={}, subresource={}, cause={}",
                    networkId, txHash, subresource, last.getMessage(), last);
        } else {
            log.warn("Blockscout tx-subresource call failed on {} after retries: txHash={}, subresource={}, cause=unknown",
                    networkId, txHash, subresource);
        }
        return null;
    }

    private JsonNode callV2(NetworkId networkId, String url, String operation, String subject) {
        RetryPolicy retryPolicy = new RetryPolicy(
                Math.max(100L, explorerProperties.getBaseDelayMs()),
                Math.max(0.0, explorerProperties.getJitterFactor()),
                Math.max(1, explorerProperties.getMaxAttempts())
        );

        Exception last = null;
        for (int attempt = 0; attempt < retryPolicy.getMaxAttempts(); attempt++) {
            if (attempt > 0) {
                sleepQuietly(retryPolicy.delayMs(attempt - 1));
            }
            try {
                JsonNode root = execute(url);
                if (root == null) {
                    continue;
                }
                if (isV2Error(root)) {
                    throw new IllegalStateException("Blockscout " + operation + " error: " + errorMessage(root));
                }
                return root;
            } catch (Exception e) {
                if (isNotFound(e)) {
                    return null;
                }
                last = e;
            }
        }
        if (last != null) {
            log.warn("Blockscout {} call failed on {} after retries: subject={}, cause={}",
                    operation, networkId, subject, last.getMessage(), last);
        } else {
            log.warn("Blockscout {} call failed on {} after retries: subject={}, cause=unknown",
                    operation, networkId, subject);
        }
        return null;
    }

    private JsonNode execute(String url) throws Exception {
        long timeoutMs = Math.max(1_000L, explorerProperties.getRequestTimeoutMs());
        String body = ReactorBlocking.block(
                explorerClient()
                        .get()
                        .uri(url)
                        .retrieve()
                        .bodyToMono(String.class)
                        .timeout(Duration.ofMillis(timeoutMs)),
                Duration.ofMillis(timeoutMs + 5_000L)
        );
        if (body == null || body.isBlank()) {
            return null;
        }
        return objectMapper.readTree(body);
    }

    private JsonNode executeRpc(String url, String method, List<Object> rpcParams) throws Exception {
        long timeoutMs = Math.max(1_000L, explorerProperties.getRequestTimeoutMs());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jsonrpc", "2.0");
        payload.put("method", method);
        payload.put("params", rpcParams == null ? List.of() : rpcParams);
        payload.put("id", 1);

        String body = ReactorBlocking.block(
                explorerClient()
                        .post()
                        .uri(url)
                        .bodyValue(payload)
                        .retrieve()
                        .bodyToMono(String.class)
                        .timeout(Duration.ofMillis(timeoutMs)),
                Duration.ofMillis(timeoutMs + 5_000L)
        );
        if (body == null || body.isBlank()) {
            return null;
        }
        return objectMapper.readTree(body);
    }

    private WebClient explorerClient() {
        WebClient local = explorerClient;
        if (local != null) {
            return local;
        }
        synchronized (this) {
            if (explorerClient == null) {
                int maxBytes = Math.max(262_144, explorerProperties.getMaxResponseBytes());
                explorerClient = webClientBuilder.clone()
                        .codecs(cfg -> cfg.defaultCodecs().maxInMemorySize(maxBytes))
                        .build();
            }
            return explorerClient;
        }
    }

    private static JsonNode resultNode(JsonNode root) {
        if (root == null || root.isNull() || root.isMissingNode()) {
            return null;
        }
        JsonNode result = root.path("result");
        if (!result.isMissingNode()) {
            return result;
        }
        return root;
    }

    private static boolean isExplorerError(JsonNode root) {
        if (root == null || root.isNull() || root.isMissingNode()) {
            return true;
        }
        String status = root.path("status").asText("");
        String message = root.path("message").asText("");
        JsonNode result = root.path("result");
        if ("0".equals(status)) {
            if (isNoTransactionsMessage(message) || isNoTransactionsResult(result)) {
                return false;
            }
            if (message.isBlank() && result.isArray() && result.isEmpty()) {
                return false;
            }
            return true;
        }
        JsonNode error = root.path("error");
        return !error.isMissingNode() && !error.isNull();
    }

    private static boolean isRpcError(JsonNode root) {
        if (root == null || root.isNull() || root.isMissingNode()) {
            return true;
        }
        JsonNode error = root.path("error");
        return !error.isMissingNode() && !error.isNull();
    }

    private static boolean isV2Error(JsonNode root) {
        if (root == null || root.isNull() || root.isMissingNode()) {
            return true;
        }
        JsonNode error = root.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            return true;
        }
        JsonNode status = root.path("status");
        if (!status.isMissingNode() && status.isTextual() && "error".equalsIgnoreCase(status.asText())) {
            return true;
        }
        return false;
    }

    private static String errorMessage(JsonNode root) {
        if (root == null) {
            return "unknown explorer error";
        }
        JsonNode error = root.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            String message = error.path("message").asText(null);
            if (message != null && !message.isBlank()) {
                return message;
            }
            String errorText = error.asText(null);
            if (errorText != null && !errorText.isBlank()) {
                return errorText;
            }
        }
        JsonNode result = root.path("result");
        if (result.isTextual()) {
            String text = result.asText("");
            if (!text.isBlank()) {
                return text;
            }
        }
        String message = root.path("message").asText("");
        if (!message.isBlank()) {
            return message;
        }
        return "unknown explorer error";
    }

    private static boolean isNoTransactionsMessage(String message) {
        if (message == null) {
            return false;
        }
        return message.equalsIgnoreCase("No transactions found");
    }

    private static boolean isNoTransactionsResult(JsonNode result) {
        if (result == null || result.isMissingNode() || result.isNull()) {
            return true;
        }
        if (result.isArray()) {
            return result.isEmpty();
        }
        if (result.isTextual()) {
            String text = result.asText("").trim();
            return text.isEmpty()
                    || text.equalsIgnoreCase("No transactions found")
                    || text.equals("[]");
        }
        return false;
    }

    private IngestionNetworkProperties.NetworkIngestionEntry networkEntryOf(NetworkId networkId) {
        return ingestionNetworkProperties.getNetwork().get(networkId.name());
    }

    private ResolvedExplorerConfig resolveConfig(NetworkId networkId) {
        IngestionNetworkProperties.NetworkIngestionEntry networkEntry = networkEntryOf(networkId);
        if (networkEntry == null
                || networkEntry.getSyncMethod() != IngestionNetworkProperties.NetworkIngestionEntry.SyncMethod.BLOCKSCOUT) {
            return null;
        }
        IngestionNetworkProperties.NetworkIngestionEntry.Explorer explorer = networkEntry.getExplorer();
        IngestionNetworkProperties.NetworkIngestionEntry.ExplorerSource entry =
                explorer != null ? explorer.getBlockscout() : null;
        return new ResolvedExplorerConfig(entry);
    }

    private static String buildUrl(ResolvedExplorerConfig cfg, Map<String, String> params) {
        IngestionNetworkProperties.NetworkIngestionEntry.ExplorerSource entry = cfg.entry();
        String baseUrl = entry.getBaseUrl().trim();
        UriComponentsBuilder builder;
        if (baseUrl.endsWith("/api") || baseUrl.endsWith("/api/")) {
            builder = UriComponentsBuilder.fromHttpUrl(baseUrl);
        } else if (baseUrl.endsWith("/")) {
            builder = UriComponentsBuilder.fromHttpUrl(baseUrl + "api");
        } else {
            builder = UriComponentsBuilder.fromHttpUrl(baseUrl + "/api");
        }
        if (entry.getApiKey() != null && !entry.getApiKey().isBlank()) {
            builder.queryParam("apikey", entry.getApiKey());
        }
        for (Map.Entry<String, String> p : params.entrySet()) {
            builder.queryParam(p.getKey(), p.getValue());
        }
        return builder.build(true).toUriString();
    }

    private static String buildRpcUrl(ResolvedExplorerConfig cfg) {
        IngestionNetworkProperties.NetworkIngestionEntry.ExplorerSource entry = cfg.entry();
        String baseUrl = entry.getBaseUrl().trim();
        UriComponentsBuilder builder;
        if (baseUrl.endsWith("/api/eth-rpc") || baseUrl.endsWith("/api/eth-rpc/")) {
            builder = UriComponentsBuilder.fromHttpUrl(baseUrl);
        } else if (baseUrl.endsWith("/")) {
            builder = UriComponentsBuilder.fromHttpUrl(baseUrl + "api/eth-rpc");
        } else {
            builder = UriComponentsBuilder.fromHttpUrl(baseUrl + "/api/eth-rpc");
        }
        if (entry.getApiKey() != null && !entry.getApiKey().isBlank()) {
            builder.queryParam("apikey", entry.getApiKey());
        }
        return builder.build(true).toUriString();
    }

    private static String buildV2TransactionUrl(ResolvedExplorerConfig cfg, String txHash, Map<String, String> params) {
        IngestionNetworkProperties.NetworkIngestionEntry.ExplorerSource entry = cfg.entry();
        String baseUrl = entry.getBaseUrl().trim();
        String encodedHash = UriUtils.encodePathSegment(txHash, StandardCharsets.UTF_8);
        UriComponentsBuilder builder;
        if (baseUrl.endsWith("/api/v2")) {
            builder = UriComponentsBuilder.fromHttpUrl(baseUrl + "/transactions/" + encodedHash);
        } else if (baseUrl.endsWith("/api/v2/")) {
            builder = UriComponentsBuilder.fromHttpUrl(baseUrl + "transactions/" + encodedHash);
        } else if (baseUrl.endsWith("/")) {
            builder = UriComponentsBuilder.fromHttpUrl(baseUrl + "api/v2/transactions/" + encodedHash);
        } else {
            builder = UriComponentsBuilder.fromHttpUrl(baseUrl + "/api/v2/transactions/" + encodedHash);
        }
        if (params != null) {
            for (Map.Entry<String, String> p : params.entrySet()) {
                builder.queryParam(p.getKey(), p.getValue());
            }
        }
        return builder.build(true).toUriString();
    }

    private static String buildV2AddressUrl(ResolvedExplorerConfig cfg, String walletAddress, Map<String, String> params) {
        IngestionNetworkProperties.NetworkIngestionEntry.ExplorerSource entry = cfg.entry();
        String baseUrl = entry.getBaseUrl().trim();
        String encodedAddress = UriUtils.encodePathSegment(walletAddress, StandardCharsets.UTF_8);
        UriComponentsBuilder builder;
        if (baseUrl.endsWith("/api/v2")) {
            builder = UriComponentsBuilder.fromHttpUrl(baseUrl + "/addresses/" + encodedAddress);
        } else if (baseUrl.endsWith("/api/v2/")) {
            builder = UriComponentsBuilder.fromHttpUrl(baseUrl + "addresses/" + encodedAddress);
        } else if (baseUrl.endsWith("/")) {
            builder = UriComponentsBuilder.fromHttpUrl(baseUrl + "api/v2/addresses/" + encodedAddress);
        } else {
            builder = UriComponentsBuilder.fromHttpUrl(baseUrl + "/api/v2/addresses/" + encodedAddress);
        }
        if (params != null) {
            for (Map.Entry<String, String> p : params.entrySet()) {
                builder.queryParam(p.getKey(), p.getValue());
            }
        }
        return builder.build(true).toUriString();
    }

    private static String buildV2TransactionSubresourceUrl(
            ResolvedExplorerConfig cfg,
            String txHash,
            String subresource,
            Map<String, String> params
    ) {
        IngestionNetworkProperties.NetworkIngestionEntry.ExplorerSource entry = cfg.entry();
        String baseUrl = entry.getBaseUrl().trim();
        String encodedHash = UriUtils.encodePathSegment(txHash, StandardCharsets.UTF_8);
        String encodedSubresource = UriUtils.encodePathSegment(subresource, StandardCharsets.UTF_8);
        UriComponentsBuilder builder;
        if (baseUrl.endsWith("/api/v2")) {
            builder = UriComponentsBuilder.fromHttpUrl(baseUrl + "/transactions/" + encodedHash + "/" + encodedSubresource);
        } else if (baseUrl.endsWith("/api/v2/")) {
            builder = UriComponentsBuilder.fromHttpUrl(baseUrl + "transactions/" + encodedHash + "/" + encodedSubresource);
        } else if (baseUrl.endsWith("/")) {
            builder = UriComponentsBuilder.fromHttpUrl(baseUrl + "api/v2/transactions/" + encodedHash + "/" + encodedSubresource);
        } else {
            builder = UriComponentsBuilder.fromHttpUrl(baseUrl + "/api/v2/transactions/" + encodedHash + "/" + encodedSubresource);
        }
        if (params != null) {
            for (Map.Entry<String, String> p : params.entrySet()) {
                builder.queryParam(p.getKey(), p.getValue());
            }
        }
        return builder.build(true).toUriString();
    }

    private static String buildV2AddressSubresourceUrl(
            ResolvedExplorerConfig cfg,
            String walletAddress,
            String subresource,
            Map<String, String> params
    ) {
        IngestionNetworkProperties.NetworkIngestionEntry.ExplorerSource entry = cfg.entry();
        String baseUrl = entry.getBaseUrl().trim();
        String encodedAddress = UriUtils.encodePathSegment(walletAddress, StandardCharsets.UTF_8);
        String encodedSubresource = UriUtils.encodePathSegment(subresource, StandardCharsets.UTF_8);
        UriComponentsBuilder builder;
        if (baseUrl.endsWith("/api/v2")) {
            builder = UriComponentsBuilder.fromHttpUrl(baseUrl + "/addresses/" + encodedAddress + "/" + encodedSubresource);
        } else if (baseUrl.endsWith("/api/v2/")) {
            builder = UriComponentsBuilder.fromHttpUrl(baseUrl + "addresses/" + encodedAddress + "/" + encodedSubresource);
        } else if (baseUrl.endsWith("/")) {
            builder = UriComponentsBuilder.fromHttpUrl(baseUrl + "api/v2/addresses/" + encodedAddress + "/" + encodedSubresource);
        } else {
            builder = UriComponentsBuilder.fromHttpUrl(baseUrl + "/api/v2/addresses/" + encodedAddress + "/" + encodedSubresource);
        }
        if (params != null) {
            for (Map.Entry<String, String> p : params.entrySet()) {
                builder.queryParam(p.getKey(), p.getValue());
            }
        }
        return builder.build(true).toUriString();
    }

    private static String buildV2TokenUrl(ResolvedExplorerConfig cfg, String contractAddress, Map<String, String> params) {
        IngestionNetworkProperties.NetworkIngestionEntry.ExplorerSource entry = cfg.entry();
        String baseUrl = entry.getBaseUrl().trim();
        String encodedAddress = UriUtils.encodePathSegment(contractAddress, StandardCharsets.UTF_8);
        UriComponentsBuilder builder;
        if (baseUrl.endsWith("/api/v2")) {
            builder = UriComponentsBuilder.fromHttpUrl(baseUrl + "/tokens/" + encodedAddress);
        } else if (baseUrl.endsWith("/api/v2/")) {
            builder = UriComponentsBuilder.fromHttpUrl(baseUrl + "tokens/" + encodedAddress);
        } else if (baseUrl.endsWith("/")) {
            builder = UriComponentsBuilder.fromHttpUrl(baseUrl + "api/v2/tokens/" + encodedAddress);
        } else {
            builder = UriComponentsBuilder.fromHttpUrl(baseUrl + "/api/v2/tokens/" + encodedAddress);
        }
        if (params != null) {
            for (Map.Entry<String, String> p : params.entrySet()) {
                builder.queryParam(p.getKey(), p.getValue());
            }
        }
        return builder.build(true).toUriString();
    }

    private static JsonNode itemsNode(JsonNode root) {
        if (root == null || root.isMissingNode() || root.isNull()) {
            return null;
        }
        JsonNode items = root.path("items");
        return items.isMissingNode() ? null : items;
    }

    private static boolean isSuccessful(Document raw) {
        Object success = raw == null ? null : raw.get("success");
        if (success instanceof Boolean value) {
            return value;
        }
        String text = stringValue(success);
        return text == null || !"false".equalsIgnoreCase(text);
    }

    private static String stringifyNumber(Object value) {
        if (value instanceof Number number) {
            return Long.toString(number.longValue());
        }
        return stringValue(value);
    }

    private static String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean isUnsupportedRpcEndpoint(Exception error) {
        if (!(error instanceof WebClientResponseException webClientResponseException)) {
            return false;
        }
        return webClientResponseException.getStatusCode().is4xxClientError();
    }

    private static boolean isNotFound(Exception error) {
        if (!(error instanceof WebClientResponseException webClientResponseException)) {
            return false;
        }
        return webClientResponseException.getStatusCode().value() == 404;
    }

    private static BigInteger parseDecimalValue(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText(null);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigInteger(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Integer parseOptionalInteger(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText(null);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Map<String, String> apiKeyParams(ResolvedExplorerConfig cfg) {
        IngestionNetworkProperties.NetworkIngestionEntry.ExplorerSource entry = cfg.entry();
        if (entry == null || entry.getApiKey() == null || entry.getApiKey().isBlank()) {
            return Map.of();
        }
        return Map.of("apikey", entry.getApiKey());
    }

    private Document toDocument(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return new Document();
        }
        return BsonCoercionSupport.asDocument(objectMapper.convertValue(node, Document.class));
    }

    public record TokenBalanceSnapshot(BigInteger rawQuantity, int decimals) {
    }

    private record ResolvedExplorerConfig(IngestionNetworkProperties.NetworkIngestionEntry.ExplorerSource entry) {
    }
}
