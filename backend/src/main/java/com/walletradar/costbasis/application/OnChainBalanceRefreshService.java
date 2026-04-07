package com.walletradar.costbasis.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.walletradar.accounting.support.AccountingAssetIdentitySupport;
import com.walletradar.costbasis.domain.OnChainBalance;
import com.walletradar.costbasis.domain.OnChainBalanceRepository;
import com.walletradar.domain.common.Decimal128Support;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.ingestion.adapter.RpcEndpointRotator;
import com.walletradar.ingestion.adapter.RpcException;
import com.walletradar.ingestion.adapter.evm.explorer.BlockScoutExplorerProvider;
import com.walletradar.ingestion.adapter.evm.explorer.EtherscanV2ExplorerProvider;
import com.walletradar.ingestion.adapter.evm.rpc.EvmRpcClient;
import com.walletradar.ingestion.adapter.evm.rpc.RpcRequest;
import com.walletradar.ingestion.adapter.evm.rpc.provider.AnkrAccountBalanceProvider;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Refreshes latest live on-chain balance evidence for the bounded accounting asset universe.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OnChainBalanceRefreshService {

    private static final String ERC20_BALANCE_OF_SELECTOR = "0x70a08231";
    private static final String ERC20_DECIMALS_SELECTOR = "0x313ce567";
    private static final int EVM_NATIVE_DECIMALS = 18;

    private final OnChainBalanceRefreshQueryService queryService;
    private final OnChainBalanceRepository onChainBalanceRepository;
    private final EvmRpcClient rpcClient;
    @Qualifier("evmRotatorsByNetwork")
    private final Map<String, RpcEndpointRotator> rotatorsByNetwork;
    @Qualifier("evmDefaultRpcEndpointRotator")
    private final RpcEndpointRotator defaultRotator;
    private final ObjectMapper objectMapper;
    private final AnkrAccountBalanceProvider ankrAccountBalanceProvider;
    private final EtherscanV2ExplorerProvider etherscanExplorerProvider;
    private final BlockScoutExplorerProvider blockScoutExplorerProvider;

    public int refreshCurrentBalances(Instant capturedAt) {
        ResolutionResult resolution = resolveCandidates(queryService.loadCandidates());
        if (resolution.candidates().isEmpty()) {
            onChainBalanceRepository.deleteAll();
            log.info("On-chain balance refresh complete: candidates=0, refreshed=0, skippedUnsupported={}",
                    resolution.skippedUnsupported());
            return 0;
        }

        List<ResolvedCandidate> candidates = resolution.candidates();
        ProviderResolutionResult providerResult = loadProviderBalances(candidates, capturedAt);
        List<ResolvedCandidate> afterProvider = candidates.stream()
                .filter(candidate -> !providerResult.handledKeys().contains(refreshKey(candidate)))
                .toList();
        ProviderResolutionResult etherscanResult = loadExplorerBalances(afterProvider, capturedAt);
        List<ResolvedCandidate> afterEtherscan = afterProvider.stream()
                .filter(candidate -> !etherscanResult.handledKeys().contains(refreshKey(candidate)))
                .toList();
        ProviderResolutionResult blockScoutResult = loadBlockScoutBalances(afterEtherscan, capturedAt);
        List<ResolvedCandidate> rpcCandidates = afterEtherscan.stream()
                .filter(candidate -> !blockScoutResult.handledKeys().contains(refreshKey(candidate)))
                .toList();
        Map<NetworkId, Map<String, Integer>> decimalsByNetwork = loadDecimals(rpcCandidates);
        List<OnChainBalance> refreshedBalances = new ArrayList<>(providerResult.balances());
        refreshedBalances.addAll(etherscanResult.balances());
        refreshedBalances.addAll(blockScoutResult.balances());
        refreshedBalances.addAll(loadRpcBalances(rpcCandidates, decimalsByNetwork, capturedAt));

        onChainBalanceRepository.deleteAll();
        if (!refreshedBalances.isEmpty()) {
            onChainBalanceRepository.saveAll(refreshedBalances);
        }

        log.info(
                "On-chain balance refresh complete: candidates={}, refreshed={}, skippedUnsupported={}",
                candidates.size(),
                refreshedBalances.size(),
                resolution.skippedUnsupported()
        );
        return refreshedBalances.size();
    }

    private ResolutionResult resolveCandidates(List<OnChainBalanceRefreshQueryService.BalanceRefreshCandidate> rawCandidates) {
        Map<RefreshKey, ResolvedCandidate> deduplicated = new LinkedHashMap<>();
        int skippedUnsupported = 0;
        for (OnChainBalanceRefreshQueryService.BalanceRefreshCandidate rawCandidate : rawCandidates) {
            ResolvedCandidate resolved = resolveCandidate(rawCandidate);
            if (resolved == null) {
                skippedUnsupported++;
                continue;
            }
            RefreshKey key = new RefreshKey(
                    resolved.walletAddress(),
                    resolved.networkId(),
                    resolved.accountingIdentity()
            );
            deduplicated.putIfAbsent(key, resolved);
        }
        List<ResolvedCandidate> ordered = deduplicated.values().stream()
                .sorted(Comparator
                        .comparing(ResolvedCandidate::walletAddress)
                        .thenComparing(candidate -> candidate.networkId().name())
                        .thenComparing(ResolvedCandidate::accountingIdentity))
                .toList();
        return new ResolutionResult(ordered, skippedUnsupported);
    }

    private ResolvedCandidate resolveCandidate(OnChainBalanceRefreshQueryService.BalanceRefreshCandidate candidate) {
        if (candidate == null || candidate.walletAddress() == null || candidate.networkId() == null) {
            return null;
        }
        String accountingIdentity = AccountingAssetIdentitySupport.positionAssetIdentity(
                candidate.networkId(),
                candidate.assetSymbol(),
                candidate.assetContract()
        );
        if (accountingIdentity == null) {
            return null;
        }
        String symbol = normalizeSymbol(candidate.assetSymbol());
        if (accountingIdentity.startsWith("NATIVE:")) {
            return new ResolvedCandidate(
                    candidate.walletAddress(),
                    candidate.networkId(),
                    symbol == null ? accountingIdentity : symbol,
                    accountingIdentity,
                    accountingIdentity,
                    QueryKind.NATIVE
            );
        }

        String queryContract = normalizeQueryableContract(candidate.assetContract());
        if (queryContract == null) {
            return null;
        }
        return new ResolvedCandidate(
                candidate.walletAddress(),
                candidate.networkId(),
                symbol,
                queryContract,
                accountingIdentity,
                QueryKind.ERC20
        );
    }

    private ProviderResolutionResult loadProviderBalances(List<ResolvedCandidate> candidates, Instant capturedAt) {
        Map<String, List<ResolvedCandidate>> byWallet = new LinkedHashMap<>();
        for (ResolvedCandidate candidate : candidates) {
            if (!ankrAccountBalanceProvider.supports(candidate.networkId())) {
                continue;
            }
            byWallet.computeIfAbsent(candidate.walletAddress(), ignored -> new ArrayList<>()).add(candidate);
        }

        ArrayList<OnChainBalance> balances = new ArrayList<>();
        Map<RefreshKey, Boolean> handledKeys = new LinkedHashMap<>();
        for (Map.Entry<String, List<ResolvedCandidate>> entry : byWallet.entrySet()) {
            String walletAddress = entry.getKey();
            List<ResolvedCandidate> walletCandidates = entry.getValue().stream()
                    .sorted(Comparator
                            .comparing((ResolvedCandidate candidate) -> candidate.networkId().name())
                            .thenComparing(ResolvedCandidate::accountingIdentity))
                    .toList();
            try {
                Map<RefreshKey, BigDecimal> quantities = providerQuantities(
                        walletAddress,
                        walletCandidates
                );
                for (ResolvedCandidate candidate : walletCandidates) {
                    RefreshKey key = refreshKey(candidate);
                    BigDecimal quantity = quantities.getOrDefault(key, BigDecimal.ZERO);
                    balances.add(balanceDocument(candidate, quantity, capturedAt));
                    handledKeys.put(key, Boolean.TRUE);
                }
            } catch (Exception providerFailure) {
                log.warn(
                        "On-chain balance refresh provider path failed: walletAddress={}, networks={}, fallback=RPC",
                        walletAddress,
                        walletCandidates.stream().map(ResolvedCandidate::networkId).distinct().toList(),
                        providerFailure
                );
            }
        }
        return new ProviderResolutionResult(List.copyOf(balances), handledKeys.keySet());
    }

    private ProviderResolutionResult loadExplorerBalances(List<ResolvedCandidate> candidates, Instant capturedAt) {
        ArrayList<OnChainBalance> balances = new ArrayList<>();
        Map<RefreshKey, Boolean> handledKeys = new LinkedHashMap<>();
        for (ResolvedCandidate candidate : candidates) {
            if (!etherscanExplorerProvider.supports(candidate.networkId())) {
                continue;
            }
            try {
                BigInteger rawQuantity = candidate.queryKind() == QueryKind.NATIVE
                        ? etherscanExplorerProvider.getNativeBalance(candidate.walletAddress(), candidate.networkId())
                        : etherscanExplorerProvider.getTokenBalance(
                                candidate.walletAddress(),
                                candidate.assetContract(),
                                candidate.networkId()
                        );
                if (rawQuantity == null) {
                    continue;
                }
                Integer explorerDecimals;
                if (candidate.queryKind() == QueryKind.NATIVE) {
                    explorerDecimals = Integer.valueOf(EVM_NATIVE_DECIMALS);
                } else {
                    explorerDecimals = etherscanExplorerProvider.getTokenDecimals(
                            candidate.walletAddress(),
                            candidate.assetContract(),
                            candidate.networkId()
                    );
                }
                int decimals = explorerDecimals == null ? -1 : explorerDecimals;
                if (decimals < 0) {
                    continue;
                }
                BigDecimal quantity = Decimal128Support.normalize(
                        new BigDecimal(rawQuantity).movePointLeft(Math.max(0, decimals))
                );
                balances.add(balanceDocument(candidate, quantity, capturedAt));
                handledKeys.put(refreshKey(candidate), Boolean.TRUE);
            } catch (Exception explorerFailure) {
                log.warn(
                        "On-chain balance refresh explorer path failed: walletAddress={}, networkId={}, accountingIdentity={}, fallback=RPC",
                        candidate.walletAddress(),
                        candidate.networkId(),
                        candidate.accountingIdentity(),
                        explorerFailure
                );
            }
        }
        return new ProviderResolutionResult(List.copyOf(balances), handledKeys.keySet());
    }

    private ProviderResolutionResult loadBlockScoutBalances(List<ResolvedCandidate> candidates, Instant capturedAt) {
        Map<WalletNetworkKey, List<ResolvedCandidate>> grouped = new LinkedHashMap<>();
        for (ResolvedCandidate candidate : candidates) {
            if (!blockScoutExplorerProvider.supports(candidate.networkId())) {
                continue;
            }
            grouped.computeIfAbsent(
                    new WalletNetworkKey(candidate.walletAddress(), candidate.networkId()),
                    ignored -> new ArrayList<>()
            ).add(candidate);
        }

        ArrayList<OnChainBalance> balances = new ArrayList<>();
        Map<RefreshKey, Boolean> handledKeys = new LinkedHashMap<>();
        for (Map.Entry<WalletNetworkKey, List<ResolvedCandidate>> entry : grouped.entrySet()) {
            WalletNetworkKey walletNetworkKey = entry.getKey();
            List<ResolvedCandidate> walletCandidates = entry.getValue().stream()
                    .sorted(Comparator.comparing(ResolvedCandidate::accountingIdentity))
                    .toList();
            Map<String, Integer> tokenDecimalsCache = new LinkedHashMap<>();
            try {
                BigInteger nativeBalance = blockScoutExplorerProvider.getNativeBalance(
                        walletNetworkKey.walletAddress(),
                        walletNetworkKey.networkId()
                );
                Map<String, BlockScoutExplorerProvider.TokenBalanceSnapshot> tokenBalances =
                        blockScoutExplorerProvider.getTokenBalances(
                                walletNetworkKey.walletAddress(),
                                walletNetworkKey.networkId()
                        );

                for (ResolvedCandidate candidate : walletCandidates) {
                    BigInteger rawQuantity;
                    int decimals;
                    if (candidate.queryKind() == QueryKind.NATIVE) {
                        rawQuantity = nativeBalance;
                        decimals = EVM_NATIVE_DECIMALS;
                    } else {
                        BlockScoutExplorerProvider.TokenBalanceSnapshot snapshot =
                                tokenBalances.get(candidate.assetContract().toLowerCase(Locale.ROOT));
                        if (snapshot == null) {
                            Integer tokenDecimals = tokenDecimalsCache.computeIfAbsent(
                                    candidate.assetContract().toLowerCase(Locale.ROOT),
                                    ignored -> blockScoutExplorerProvider.getTokenDecimals(
                                            candidate.assetContract(),
                                            candidate.networkId()
                                    )
                            );
                            if (tokenDecimals == null) {
                                continue;
                            }
                            rawQuantity = BigInteger.ZERO;
                            decimals = tokenDecimals;
                        } else {
                            rawQuantity = snapshot.rawQuantity();
                            decimals = snapshot.decimals();
                        }
                    }
                    if (rawQuantity == null || decimals < 0) {
                        continue;
                    }
                    BigDecimal quantity = Decimal128Support.normalize(
                            new BigDecimal(rawQuantity).movePointLeft(Math.max(0, decimals))
                    );
                    balances.add(balanceDocument(candidate, quantity, capturedAt));
                    handledKeys.put(refreshKey(candidate), Boolean.TRUE);
                }
            } catch (Exception explorerFailure) {
                log.warn(
                        "On-chain balance refresh Blockscout path failed: walletAddress={}, networkId={}, fallback=RPC",
                        walletNetworkKey.walletAddress(),
                        walletNetworkKey.networkId(),
                        explorerFailure
                );
            }
        }
        return new ProviderResolutionResult(List.copyOf(balances), handledKeys.keySet());
    }

    private Map<RefreshKey, BigDecimal> providerQuantities(
            String walletAddress,
            List<ResolvedCandidate> walletCandidates
    ) {
        List<NetworkId> networks = walletCandidates.stream()
                .map(ResolvedCandidate::networkId)
                .distinct()
                .toList();
        Map<RefreshKey, BigDecimal> byKey = new LinkedHashMap<>();
        for (AnkrAccountBalanceProvider.AccountBalanceAsset asset :
                ankrAccountBalanceProvider.fetchBalances(walletAddress, new java.util.LinkedHashSet<>(networks))) {
            String accountingIdentity = AccountingAssetIdentitySupport.positionAssetIdentity(
                    asset.networkId(),
                    asset.assetSymbol(),
                    asset.assetContract()
            );
            if (accountingIdentity == null) {
                continue;
            }
            byKey.putIfAbsent(
                    new RefreshKey(walletAddress, asset.networkId(), accountingIdentity),
                    asset.quantity()
            );
        }
        return byKey;
    }

    private Map<NetworkId, Map<String, Integer>> loadDecimals(List<ResolvedCandidate> candidates) {
        Map<NetworkId, List<String>> contractsByNetwork = new EnumMap<>(NetworkId.class);
        for (ResolvedCandidate candidate : candidates) {
            if (candidate.queryKind() != QueryKind.ERC20 || candidate.assetContract() == null) {
                continue;
            }
            contractsByNetwork.computeIfAbsent(candidate.networkId(), ignored -> new ArrayList<>());
            List<String> contracts = contractsByNetwork.get(candidate.networkId());
            if (!contracts.contains(candidate.assetContract())) {
                contracts.add(candidate.assetContract());
            }
        }

        Map<NetworkId, Map<String, Integer>> decimalsByNetwork = new EnumMap<>(NetworkId.class);
        for (Map.Entry<NetworkId, List<String>> entry : contractsByNetwork.entrySet()) {
            NetworkId networkId = entry.getKey();
            List<String> contracts = entry.getValue();
            if (contracts.isEmpty()) {
                continue;
            }
            List<RpcRequest> requests = contracts.stream()
                    .map(contract -> new RpcRequest(
                            "eth_call",
                            List.of(Map.of("to", contract, "data", ERC20_DECIMALS_SELECTOR), "latest")
                    ))
                    .toList();
            JsonNode responses = callBatchWithRetry(networkId, requests);
            Map<Integer, JsonNode> byId = responsesById(responses);
            Map<String, Integer> decimalsByContract = new LinkedHashMap<>();
            for (int i = 0; i < contracts.size(); i++) {
                JsonNode response = byId.get(i + 1);
                Integer decimals = parseOptionalIntegerQuantity(response);
                if (decimals != null) {
                    decimalsByContract.put(contracts.get(i), decimals);
                } else {
                    log.warn(
                            "On-chain balance refresh skipped decimals lookup: networkId={}, assetContract={}",
                            networkId,
                            contracts.get(i)
                    );
                }
            }
            decimalsByNetwork.put(networkId, decimalsByContract);
        }
        return decimalsByNetwork;
    }

    private List<OnChainBalance> loadRpcBalances(
            List<ResolvedCandidate> candidates,
            Map<NetworkId, Map<String, Integer>> decimalsByNetwork,
            Instant capturedAt
    ) {
        Map<WalletNetworkKey, List<ResolvedCandidate>> grouped = new LinkedHashMap<>();
        for (ResolvedCandidate candidate : candidates) {
            grouped.computeIfAbsent(
                    new WalletNetworkKey(candidate.walletAddress(), candidate.networkId()),
                    ignored -> new ArrayList<>()
            ).add(candidate);
        }

        List<OnChainBalance> balances = new ArrayList<>();
        for (Map.Entry<WalletNetworkKey, List<ResolvedCandidate>> entry : grouped.entrySet()) {
            WalletNetworkKey walletNetworkKey = entry.getKey();
            List<ResolvedCandidate> walletCandidates = entry.getValue().stream()
                    .sorted(Comparator.comparing(ResolvedCandidate::accountingIdentity))
                    .toList();
            List<RequestDescriptor> descriptors = new ArrayList<>();
            List<RpcRequest> requests = new ArrayList<>();
            Map<String, Integer> decimalsByContract = decimalsByNetwork.getOrDefault(
                    walletNetworkKey.networkId(),
                    Map.of()
            );

            for (ResolvedCandidate candidate : walletCandidates) {
                if (candidate.queryKind() == QueryKind.NATIVE) {
                    descriptors.add(new RequestDescriptor(candidate, EVM_NATIVE_DECIMALS));
                    requests.add(new RpcRequest(
                            "eth_getBalance",
                            List.of(candidate.walletAddress(), "latest")
                    ));
                    continue;
                }
                Integer decimals = decimalsByContract.get(candidate.assetContract());
                if (decimals == null) {
                    continue;
                }
                descriptors.add(new RequestDescriptor(candidate, decimals));
                requests.add(new RpcRequest(
                        "eth_call",
                        List.of(
                                Map.of(
                                        "to", candidate.assetContract(),
                                        "data", encodeBalanceOf(candidate.walletAddress())
                                ),
                                "latest"
                        )
                ));
            }

            if (requests.isEmpty()) {
                continue;
            }

            JsonNode responses = callBatchWithRetry(walletNetworkKey.networkId(), requests);
            Map<Integer, JsonNode> byId = responsesById(responses);
            for (int i = 0; i < descriptors.size(); i++) {
                RequestDescriptor descriptor = descriptors.get(i);
                JsonNode response = byId.get(i + 1);
                BigInteger rawQuantity = parseOptionalBigIntegerQuantity(response);
                if (rawQuantity == null) {
                    log.warn(
                            "On-chain balance refresh skipped balance lookup: walletAddress={}, networkId={}, accountingIdentity={}",
                            descriptor.candidate().walletAddress(),
                            descriptor.candidate().networkId(),
                            descriptor.candidate().accountingIdentity()
                    );
                    continue;
                }
                BigDecimal quantity = Decimal128Support.normalize(
                        new BigDecimal(rawQuantity).movePointLeft(Math.max(0, descriptor.decimals()))
                );
                balances.add(balanceDocument(descriptor.candidate(), quantity, capturedAt));
            }
        }
        return balances;
    }

    private OnChainBalance balanceDocument(ResolvedCandidate candidate, BigDecimal quantity, Instant capturedAt) {
        OnChainBalance balance = new OnChainBalance();
        balance.setId(candidate.walletAddress() + ":" + candidate.networkId().name() + ":" + candidate.accountingIdentity());
        balance.setWalletAddress(candidate.walletAddress());
        balance.setNetworkId(candidate.networkId());
        balance.setAssetSymbol(candidate.assetSymbol());
        balance.setAssetContract(candidate.assetContract());
        balance.setQuantity(quantity);
        balance.setCapturedAt(capturedAt);
        return balance;
    }

    private RefreshKey refreshKey(ResolvedCandidate candidate) {
        return new RefreshKey(candidate.walletAddress(), candidate.networkId(), candidate.accountingIdentity());
    }

    private JsonNode callBatchWithRetry(NetworkId networkId, List<RpcRequest> requests) {
        RpcEndpointRotator rotator = rotatorsByNetwork.getOrDefault(networkId.name(), defaultRotator);
        Exception lastException = null;
        for (int attempt = 0; attempt < rotator.getMaxAttempts(); attempt++) {
            if (attempt > 0) {
                sleep(rotator.retryDelayMs(attempt - 1));
            }
            String endpoint = rotator.getNextEndpoint();
            try {
                String json = rpcClient.batchCall(endpoint, requests).block();
                return parseBatchArray(json);
            } catch (Exception batchFailure) {
                try {
                    return sequentialBatchCall(endpoint, requests);
                } catch (Exception sequentialFailure) {
                    lastException = sequentialFailure;
                }
            }
        }
        throw new RpcException(
                "On-chain balance refresh RPC failed after " + rotator.getMaxAttempts() + " attempts for network " + networkId,
                lastException
        );
    }

    private JsonNode sequentialBatchCall(String endpoint, List<RpcRequest> requests) {
        ArrayNode responses = objectMapper.createArrayNode();
        for (int i = 0; i < requests.size(); i++) {
            RpcRequest request = requests.get(i);
            try {
                String json = rpcClient.call(endpoint, request.method(), request.params()).block();
                JsonNode response = objectMapper.readTree(json);
                if (response instanceof ObjectNode objectNode) {
                    objectNode.put("id", i + 1);
                }
                responses.add(response);
            } catch (Exception e) {
                throw new RpcException("Sequential balance refresh fallback failed for method " + request.method(), e);
            }
        }
        return responses;
    }

    private JsonNode parseBatchArray(String json) {
        if (json == null || json.isBlank()) {
            throw new RpcException("On-chain balance refresh received empty batch response");
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            if (!root.isArray()) {
                throw new RpcException("On-chain balance refresh expected batch array response");
            }
            return root;
        } catch (Exception e) {
            if (e instanceof RpcException rpcException) {
                throw rpcException;
            }
            throw new RpcException("Failed to parse on-chain balance batch response", e);
        }
    }

    private Map<Integer, JsonNode> responsesById(JsonNode responses) {
        Map<Integer, JsonNode> byId = new LinkedHashMap<>();
        if (responses == null || !responses.isArray()) {
            return byId;
        }
        for (JsonNode response : responses) {
            byId.put(response.path("id").asInt(), response);
        }
        return byId;
    }

    private BigInteger parseOptionalBigIntegerQuantity(JsonNode response) {
        if (response == null) {
            return null;
        }
        JsonNode error = response.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            return null;
        }
        JsonNode result = response.path("result");
        if (result.isMissingNode() || result.isNull()) {
            return null;
        }
        String hex = result.asText(null);
        if (hex == null || hex.isBlank()) {
            return null;
        }
        return parseHexQuantity(hex);
    }

    private Integer parseOptionalIntegerQuantity(JsonNode response) {
        BigInteger raw = parseOptionalBigIntegerQuantity(response);
        return raw == null ? null : raw.intValueExact();
    }

    private BigInteger parseHexQuantity(String hexValue) {
        if (hexValue == null) {
            return BigInteger.ZERO;
        }
        String normalized = hexValue.trim().toLowerCase(Locale.ROOT);
        if (!normalized.startsWith("0x")) {
            throw new RpcException("Expected hex quantity, got: " + hexValue);
        }
        String hex = normalized.substring(2);
        if (hex.isEmpty()) {
            return BigInteger.ZERO;
        }
        return new BigInteger(hex, 16);
    }

    private String encodeBalanceOf(String walletAddress) {
        String normalized = Objects.requireNonNull(OnChainRawTransactionView.normalizeAddress(walletAddress));
        String raw = normalized.substring(2);
        return ERC20_BALANCE_OF_SELECTOR + "0".repeat(24) + raw;
    }

    private String normalizeQueryableContract(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        String upper = normalized.toUpperCase(Locale.ROOT);
        if (upper.startsWith("NATIVE:") || upper.startsWith("SYMBOL:")) {
            return null;
        }
        return OnChainRawTransactionView.normalizeAddress(normalized);
    }

    private String normalizeSymbol(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private void sleep(long delayMs) {
        if (delayMs <= 0L) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RpcException("On-chain balance refresh interrupted", e);
        }
    }

    private enum QueryKind {
        NATIVE,
        ERC20
    }

    private record RefreshKey(
            String walletAddress,
            NetworkId networkId,
            String accountingIdentity
    ) {
    }

    private record ResolutionResult(
            List<ResolvedCandidate> candidates,
            int skippedUnsupported
    ) {
    }

    private record ProviderResolutionResult(
            List<OnChainBalance> balances,
            java.util.Set<RefreshKey> handledKeys
    ) {
    }

    private record ResolvedCandidate(
            String walletAddress,
            NetworkId networkId,
            String assetSymbol,
            String assetContract,
            String accountingIdentity,
            QueryKind queryKind
    ) {
    }

    private record WalletNetworkKey(
            String walletAddress,
            NetworkId networkId
    ) {
    }

    private record RequestDescriptor(
            ResolvedCandidate candidate,
            int decimals
    ) {
    }
}
