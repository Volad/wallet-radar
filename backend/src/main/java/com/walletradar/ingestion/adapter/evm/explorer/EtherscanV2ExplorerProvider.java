package com.walletradar.ingestion.adapter.evm.explorer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.common.RetryPolicy;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.ingestion.adapter.evm.explorer.model.ExplorerInternalTransfer;
import com.walletradar.ingestion.adapter.evm.explorer.model.ExplorerReceipt;
import com.walletradar.ingestion.adapter.evm.explorer.model.ExplorerTokenTransfer;
import com.walletradar.ingestion.adapter.evm.explorer.model.ExplorerTransactionDetails;
import com.walletradar.ingestion.adapter.evm.explorer.model.ExplorerTransaction;
import com.walletradar.ingestion.config.IngestionExplorerProperties;
import com.walletradar.ingestion.config.IngestionNetworkProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.Duration;
import java.util.function.Function;

/**
 * Explorer provider for Etherscan V2 and Etherscan-family APIs (ADR-026).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EtherscanV2ExplorerProvider implements ExplorerProvider {

    // Etherscan-compatible APIs enforce PageNo * Offset <= 10000.
    private static final int PAGE_SIZE = 1000;
    private static final int MAX_RESULT_WINDOW = 10_000;
    private static final int MAX_PAGE = MAX_RESULT_WINDOW / PAGE_SIZE;
    private static final String UNIFIED_V2_BASE_URL = "https://api.etherscan.io/v2/api";

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
    public List<ExplorerTransaction> getTransactions(String walletAddress, NetworkId networkId, long fromBlock, long toBlock, int page) {
        return callAccountList(networkId, walletAddress, fromBlock, toBlock, page, "txlist", ExplorerTransaction::new);
    }

    @Override
    public List<ExplorerTokenTransfer> getTokenTransfers(String walletAddress, NetworkId networkId, long fromBlock, long toBlock, int page) {
        return callAccountList(networkId, walletAddress, fromBlock, toBlock, page, "tokentx", ExplorerTokenTransfer::new);
    }

    @Override
    public List<ExplorerInternalTransfer> getInternalTransfers(String walletAddress, NetworkId networkId, long fromBlock, long toBlock, int page) {
        return callAccountList(networkId, walletAddress, fromBlock, toBlock, page, "txlistinternal", ExplorerInternalTransfer::new);
    }

    @Override
    public ExplorerTransaction getTransaction(String txHash, NetworkId networkId) {
        JsonNode result = getTransactionByHashResult(txHash, networkId);
        if (result == null) {
            return null;
        }
        return new ExplorerTransaction(toDocument(result));
    }

    @Override
    public ExplorerTransactionDetails getTransactionDetails(String txHash, NetworkId networkId) {
        JsonNode result = getTransactionByHashResult(txHash, networkId);
        if (result == null) {
            return null;
        }
        return new ExplorerTransactionDetails(toDocument(result));
    }

    @Override
    public ExplorerReceipt getReceipt(String txHash, NetworkId networkId) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("module", "proxy");
        params.put("action", "eth_getTransactionReceipt");
        params.put("txhash", txHash);
        JsonNode root = call(networkId, params);
        if (root == null || root.path("result").isMissingNode() || root.path("result").isNull()) {
            return null;
        }
        return new ExplorerReceipt(toDocument(root.path("result")));
    }

    private <T> List<T> callAccountList(
            NetworkId networkId,
            String walletAddress,
            long fromBlock,
            long toBlock,
            int page,
            String action,
            Function<Document, T> mapper
    ) {
        int pageNumber = Math.max(1, page);
        if (((long) pageNumber * PAGE_SIZE) > MAX_RESULT_WINDOW) {
            log.debug("Skipping {} page {} on {}: page*offset exceeds API result window {}",
                    action, pageNumber, networkId, MAX_RESULT_WINDOW);
            return List.of();
        }

        Map<String, String> params = new LinkedHashMap<>();
        params.put("module", "account");
        params.put("action", action);
        params.put("address", walletAddress);
        params.put("startblock", Long.toString(fromBlock));
        params.put("endblock", Long.toString(toBlock));
        params.put("page", Integer.toString(pageNumber));
        params.put("offset", Integer.toString(PAGE_SIZE));
        params.put("sort", "asc");

        JsonNode root = call(networkId, params);
        if (root == null || root.path("result").isMissingNode()) {
            return List.of();
        }
        JsonNode result = root.path("result");
        if (!result.isArray()) {
            return List.of();
        }
        if (pageNumber == MAX_PAGE && result.size() >= PAGE_SIZE) {
            log.warn("Explorer result window limit reached on {} for action {} (page={}, offset={}). " +
                            "Consider reducing backfill window size for this network to avoid truncation.",
                    networkId, action, pageNumber, PAGE_SIZE);
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
        var entry = cfg.entry();
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
                    String msg = errorMessage(root);
                    if (cfg.syncMethod() == IngestionNetworkProperties.NetworkIngestionEntry.SyncMethod.ETHERSCAN
                            && isDeprecatedV1(msg)
                            && shouldFallbackToUnifiedV2(entry.getBaseUrl())) {
                        JsonNode fallbackRoot = execute(buildUnifiedV2Url(cfg, params));
                        if (fallbackRoot == null) {
                            continue;
                        }
                        if (isExplorerError(fallbackRoot)) {
                            throw new IllegalStateException("Explorer API error: " + errorMessage(fallbackRoot));
                        }
                        return fallbackRoot;
                    }
                    throw new IllegalStateException("Explorer API error: " + msg);
                }
                return root;
            } catch (Exception e) {
                last = e;
            }
        }
        if (last != null) {
            log.warn("Explorer call failed on {} after retries: params={}, cause={}",
                    networkId, params, last.getMessage(), last);
        } else {
            log.warn("Explorer call failed on {} after retries: params={}, cause=unknown",
                    networkId, params);
        }
        return null;
    }

    private JsonNode getTransactionByHashResult(String txHash, NetworkId networkId) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("module", "proxy");
        params.put("action", "eth_getTransactionByHash");
        params.put("txhash", txHash);
        JsonNode root = call(networkId, params);
        if (root == null || root.path("result").isMissingNode() || root.path("result").isNull()) {
            return null;
        }
        return root.path("result");
    }

    private JsonNode execute(String url) throws Exception {
        long timeoutMs = Math.max(1_000L, explorerProperties.getRequestTimeoutMs());
        String body = explorerClient()
                .get()
                .uri(url)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofMillis(timeoutMs))
                .block();
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

    private static boolean isExplorerError(JsonNode root) {
        if (root == null) {
            return true;
        }
        return "0".equals(root.path("status").asText(""))
                && !root.path("message").asText("").equalsIgnoreCase("No transactions found");
    }

    private static String errorMessage(JsonNode root) {
        return root.path("result").asText(root.path("message").asText("unknown explorer error"));
    }

    private static boolean isDeprecatedV1(String message) {
        if (message == null) {
            return false;
        }
        return message.toLowerCase().contains("deprecated v1 endpoint");
    }

    private static boolean shouldFallbackToUnifiedV2(String baseUrl) {
        if (baseUrl == null) {
            return false;
        }
        return !baseUrl.toLowerCase().contains("api.etherscan.io");
    }

    private IngestionNetworkProperties.NetworkIngestionEntry networkEntryOf(NetworkId networkId) {
        return ingestionNetworkProperties.getNetwork().get(networkId.name());
    }

    private ResolvedExplorerConfig resolveConfig(NetworkId networkId) {
        var networkEntry = networkEntryOf(networkId);
        if (networkEntry == null || networkEntry.getSyncMethod() == null) {
            return null;
        }
        if (networkEntry.getSyncMethod() != IngestionNetworkProperties.NetworkIngestionEntry.SyncMethod.ETHERSCAN) {
            return null;
        }
        IngestionNetworkProperties.NetworkIngestionEntry.ExplorerSource entry;
        IngestionNetworkProperties.NetworkIngestionEntry.Explorer explorer = networkEntry.getExplorer();
        entry = explorer != null ? explorer.getEtherscan() : null;
        return new ResolvedExplorerConfig(networkEntry.getSyncMethod(), networkEntry.getChainId(), entry);
    }

    private static String buildUrl(ResolvedExplorerConfig cfg, Map<String, String> params) {
        IngestionNetworkProperties.NetworkIngestionEntry.ExplorerSource entry = cfg.entry();
        String baseUrl = entry.getBaseUrl().trim();
        UriComponentsBuilder builder;
        if (baseUrl.endsWith("/v2/api") || baseUrl.endsWith("/api") || baseUrl.endsWith("/api/")) {
            builder = UriComponentsBuilder.fromHttpUrl(baseUrl);
        } else if (baseUrl.endsWith("/")) {
            builder = UriComponentsBuilder.fromHttpUrl(baseUrl + defaultApiPath(baseUrl));
        } else {
            builder = UriComponentsBuilder.fromHttpUrl(baseUrl + "/" + defaultApiPath(baseUrl));
        }
        if (cfg.chainId() != null && !cfg.chainId().isBlank()) {
            builder.queryParam("chainid", cfg.chainId());
        }
        if (entry.getApiKey() != null && !entry.getApiKey().isBlank()) {
            builder.queryParam("apikey", entry.getApiKey());
        }
        for (Map.Entry<String, String> p : params.entrySet()) {
            builder.queryParam(p.getKey(), p.getValue());
        }
        return builder.build(true).toUriString();
    }

    private static String buildUnifiedV2Url(ResolvedExplorerConfig cfg, Map<String, String> params) {
        IngestionNetworkProperties.NetworkIngestionEntry.ExplorerSource entry = cfg.entry();
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(UNIFIED_V2_BASE_URL);
        if (cfg.chainId() != null && !cfg.chainId().isBlank()) {
            builder.queryParam("chainid", cfg.chainId());
        }
        if (entry.getApiKey() != null && !entry.getApiKey().isBlank()) {
            builder.queryParam("apikey", entry.getApiKey());
        }
        for (Map.Entry<String, String> p : params.entrySet()) {
            builder.queryParam(p.getKey(), p.getValue());
        }
        return builder.build(true).toUriString();
    }

    private static String defaultApiPath(String baseUrl) {
        String lower = baseUrl.toLowerCase();
        // Etherscan V2 path is /v2/api. Most sibling scanners use /api.
        if (lower.contains("api.etherscan.io")) {
            return "v2/api";
        }
        return "api";
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Document toDocument(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return new Document();
        }
        return objectMapper.convertValue(node, Document.class);
    }

    private record ResolvedExplorerConfig(
            IngestionNetworkProperties.NetworkIngestionEntry.SyncMethod syncMethod,
            String chainId,
            IngestionNetworkProperties.NetworkIngestionEntry.ExplorerSource entry
    ) {
    }
}
