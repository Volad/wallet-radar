package com.walletradar.application.portfolio.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.walletradar.application.costbasis.support.AccountingAssetIdentitySupport;
import com.walletradar.application.costbasis.application.balance.NonEvmOnChainBalanceLoader;
import com.walletradar.application.costbasis.application.port.OnChainBalanceRefresher;
import com.walletradar.application.costbasis.application.OnChainBalanceRefreshQueryService;
import com.walletradar.application.costbasis.domain.OnChainBalance;
import com.walletradar.application.costbasis.domain.OnChainBalanceRepository;
import com.walletradar.application.lending.application.LendingAssetSymbolSupport;
import com.walletradar.domain.common.Decimal128Support;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.platform.networks.RpcEndpointRotator;
import com.walletradar.platform.networks.RpcException;
import com.walletradar.platform.networks.evm.explorer.BlockScoutExplorerProvider;
import com.walletradar.platform.networks.evm.explorer.EtherscanV2ExplorerProvider;
import com.walletradar.platform.networks.evm.rpc.EvmRpcClient;
import com.walletradar.platform.networks.evm.rpc.RpcRequest;
import com.walletradar.platform.networks.evm.rpc.provider.AnkrAccountBalanceProvider;
import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Refreshes latest live on-chain balance evidence for the bounded accounting asset universe.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OnChainBalanceRefreshService implements OnChainBalanceRefresher {

    private static final String ERC20_BALANCE_OF_SELECTOR = "0x70a08231";
    private static final String ERC20_DECIMALS_SELECTOR = "0x313ce567";
    private static final int EVM_NATIVE_DECIMALS = 18;
    private static final int EXPLORER_REFRESH_LANES = 4;

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
    // Non-EVM (Solana, TON) balance loader (ADR-067); EVM path stays candidate-driven above.
    private final NonEvmOnChainBalanceLoader nonEvmBalanceLoader;

    public int refreshCurrentBalances(Instant capturedAt) {
        return refreshCurrentBalancesInternal(null, queryService.loadCandidates(), capturedAt, null);
    }

    public int refreshCurrentBalances(String sessionId, Collection<String> walletAddresses, Instant capturedAt) {
        return refreshCurrentBalances(sessionId, walletAddresses, capturedAt, null);
    }

    public int refreshCurrentBalances(
            String sessionId,
            Collection<String> walletAddresses,
            Instant capturedAt,
            Runnable heartbeat
    ) {
        List<String> scopedWallets = walletAddresses == null ? List.of() : List.copyOf(walletAddresses);
        return refreshCurrentBalancesInternal(sessionId, queryService.loadCandidates(scopedWallets), capturedAt, heartbeat);
    }

    private int refreshCurrentBalancesInternal(
            String sessionId,
            List<OnChainBalanceRefreshQueryService.BalanceRefreshCandidate> rawCandidates,
            Instant capturedAt,
            Runnable heartbeat
    ) {
        heartbeat(heartbeat);
        List<OnChainBalanceRefreshQueryService.BalanceRefreshCandidate> evmRawCandidates = new ArrayList<>();
        List<OnChainBalanceRefreshQueryService.BalanceRefreshCandidate> nonEvmRawCandidates = new ArrayList<>();
        for (OnChainBalanceRefreshQueryService.BalanceRefreshCandidate candidate : rawCandidates) {
            if (candidate == null || candidate.networkId() == null) {
                continue;
            }
            if (nonEvmBalanceLoader.handles(candidate.networkId())) {
                nonEvmRawCandidates.add(candidate);
            } else {
                evmRawCandidates.add(candidate);
            }
        }

        // A3: load the last-known snapshot for this scope so a failed/partial fetch can fall back to it
        // (never drop) and so stale rows are cleaned up by targeted id-delete — never a destructive
        // delete-then-write nor an unconditional deleteAll() that a fetch failure could ride through.
        Map<String, OnChainBalance> existingById = loadExistingById(sessionId);

        boolean noCandidates = evmRawCandidates.isEmpty() && nonEvmRawCandidates.isEmpty();
        if (noCandidates) {
            // Genuinely empty universe (no tracked candidates at all) — safe to drop stale rows for
            // this scope. Targeted by id so a concurrent session's rows are never touched, and gated
            // on the raw-candidate set (not on empty fetch results) so a transient query/RPC failure
            // that yields zero candidates does not erase the read model.
            deleteStaleRows(existingById.keySet(), Set.of());
            log.info("On-chain balance refresh complete: candidates=0, refreshed=0, skippedUnsupported=0");
            return 0;
        }

        ResolutionResult resolution = resolveCandidates(evmRawCandidates);
        List<OnChainBalance> nonEvmBalances = nonEvmBalanceLoader.load(
                nonEvmRawCandidates, capturedAt, sessionId, heartbeat
        );
        // A non-EVM provider returning nothing for a non-empty candidate set is treated as a partial
        // fetch (retry-safe): skip destructive cleanup so a TON/Solana RPC hiccup cannot erase rows.
        boolean nonEvmComplete = nonEvmRawCandidates.isEmpty() || !nonEvmBalances.isEmpty();

        EvmLoadResult evmResult = loadEvmBalances(resolution.candidates(), existingById, capturedAt, sessionId, heartbeat);

        List<OnChainBalance> refreshedBalances = new ArrayList<>(evmResult.balances());
        refreshedBalances.addAll(nonEvmBalances);

        // A3: idempotent upsert per bucket (deterministic _id = prefix:wallet:network:accountingIdentity).
        if (!refreshedBalances.isEmpty()) {
            onChainBalanceRepository.saveAll(refreshedBalances);
        }

        // A3: targeted stale-row cleanup only when the fetch was complete (no fallbacks). A partial or
        // failed fetch leaves survivor rows intact rather than wiping them.
        boolean fetchComplete = !evmResult.hadFailure() && nonEvmComplete;
        Set<String> refreshedIds = refreshedBalances.stream()
                .map(OnChainBalance::getId)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));
        if (fetchComplete) {
            deleteStaleRows(existingById.keySet(), refreshedIds);
        }
        heartbeat(heartbeat);

        log.info(
                "On-chain balance refresh complete: evmCandidates={}, nonEvmBalances={}, refreshed={}, "
                        + "fallbacks={}, fetchComplete={}, skippedUnsupported={}",
                resolution.candidates().size(), nonEvmBalances.size(), refreshedBalances.size(),
                evmResult.hadFailure(), fetchComplete, resolution.skippedUnsupported()
        );
        return refreshedBalances.size();
    }

    private EvmLoadResult loadEvmBalances(
            List<ResolvedCandidate> candidates,
            Map<String, OnChainBalance> existingById,
            Instant capturedAt,
            String sessionId,
            Runnable heartbeat
    ) {
        if (candidates.isEmpty()) {
            return new EvmLoadResult(List.of(), false);
        }
        Map<NetworkId, Map<String, Integer>> knownDecimalsByNetwork = loadKnownDecimals(candidates, sessionId);

        // Interest-accruing lending receipt/debt tokens (Aave aTokens & variable/stable debt tokens)
        // rebase their balanceOf over time. Indexed balance providers (Ankr, block explorers) report the
        // scaled/principal amount, which omits accrued interest and under-reports both collateral and debt,
        // corrupting lending health, net exposure and PnL. Force these through the live RPC balanceOf path
        // (on-chain ground truth). Detection is registry-grammar based; no hardcoded token contracts,
        // wallet addresses or networks.
        List<ResolvedCandidate> liveBalanceOfCandidates = candidates.stream()
                .filter(OnChainBalanceRefreshService::requiresLiveBalanceOf)
                .toList();
        List<ResolvedCandidate> indexedCandidates = candidates.stream()
                .filter(candidate -> !requiresLiveBalanceOf(candidate))
                .toList();

        ProviderResolutionResult providerResult = loadProviderBalances(indexedCandidates, capturedAt, sessionId, heartbeat);
        List<ResolvedCandidate> afterProvider = indexedCandidates.stream()
                .filter(candidate -> !providerResult.handledKeys().contains(refreshKey(candidate)))
                .toList();
        ProviderResolutionResult blockScoutResult = loadBlockScoutBalances(
                afterProvider, knownDecimalsByNetwork, capturedAt, sessionId, heartbeat);
        List<ResolvedCandidate> afterBlockScout = afterProvider.stream()
                .filter(candidate -> !blockScoutResult.handledKeys().contains(refreshKey(candidate)))
                .toList();
        ProviderResolutionResult etherscanResult = loadExplorerBalances(
                afterBlockScout, knownDecimalsByNetwork, capturedAt, sessionId, heartbeat);
        List<ResolvedCandidate> rpcCandidates = new ArrayList<>(afterBlockScout.stream()
                .filter(candidate -> !etherscanResult.handledKeys().contains(refreshKey(candidate)))
                .toList());
        // A2: forced-live candidates keep live RPC balanceOf as the PRIMARY (accrual ground truth).
        rpcCandidates.addAll(liveBalanceOfCandidates);
        Map<NetworkId, Map<String, Integer>> decimalsByNetwork = loadDecimals(
                rpcCandidates, knownDecimalsByNetwork, heartbeat);
        ProviderResolutionResult rpcResult = loadRpcBalances(
                rpcCandidates, decimalsByNetwork, capturedAt, sessionId, heartbeat);

        List<OnChainBalance> evmBalances = new ArrayList<>(providerResult.balances());
        evmBalances.addAll(blockScoutResult.balances());
        evmBalances.addAll(etherscanResult.balances());
        evmBalances.addAll(rpcResult.balances());

        Set<RefreshKey> handled = new HashSet<>();
        handled.addAll(providerResult.handledKeys());
        handled.addAll(blockScoutResult.handledKeys());
        handled.addAll(etherscanResult.handledKeys());
        handled.addAll(rpcResult.handledKeys());

        // A2: forced-live candidates whose live RPC read failed fall through the indexed provider chain
        // (BlockScout snapshots then Etherscan) instead of the old single fallback-less RPC path. The
        // fallback accepts only a value a provider genuinely reports for the token — never a
        // synthesized/zero-default — so it can never mask a failed capture by zeroing a still-held
        // accruing lot.
        List<ResolvedCandidate> forcedLiveUnresolved = liveBalanceOfCandidates.stream()
                .filter(candidate -> !handled.contains(refreshKey(candidate)))
                .toList();
        if (!forcedLiveUnresolved.isEmpty()) {
            ProviderResolutionResult forcedLiveFallback = loadForcedLiveProviderFallback(
                    forcedLiveUnresolved, knownDecimalsByNetwork, capturedAt, sessionId, heartbeat);
            evmBalances.addAll(forcedLiveFallback.balances());
            handled.addAll(forcedLiveFallback.handledKeys());
        }

        // A1: any candidate that resolved a nonzero net-flow but has no fresh row from any live source
        // must NOT be silently dropped. Fall back to the last-known snapshot (retained value + prior
        // capturedAt, marked captureFallback so the read model flags it) and record a fetch failure so
        // destructive cleanup is skipped. When no prior snapshot exists the bucket is genuinely missing
        // and the dashboard coverage guard (ADR-078) weights it off the ledger-covered quantity.
        boolean hadFailure = false;
        for (ResolvedCandidate candidate : candidates) {
            if (handled.contains(refreshKey(candidate))) {
                continue;
            }
            hadFailure = true;
            OnChainBalance lastKnown = existingById.get(balanceId(sessionId, candidate));
            if (lastKnown != null && lastKnown.getQuantity() != null) {
                evmBalances.add(fallbackBalance(candidate, lastKnown, sessionId));
                log.warn(
                        "On-chain balance refresh retained last-known snapshot (all live sources failed): "
                                + "walletAddress={}, networkId={}, accountingIdentity={}, capturedAt={}",
                        candidate.walletAddress(), candidate.networkId(), candidate.accountingIdentity(),
                        lastKnown.getCapturedAt()
                );
            } else {
                log.warn(
                        "On-chain balance refresh has no live value and no prior snapshot (coverage gap "
                                + "handled by ledger-covered weighting): walletAddress={}, networkId={}, "
                                + "accountingIdentity={}",
                        candidate.walletAddress(), candidate.networkId(), candidate.accountingIdentity()
                );
            }
        }
        return new EvmLoadResult(evmBalances, hadFailure);
    }

    /**
     * Safe multi-provider fallback for forced-live lending receipt/debt tokens whose live RPC read
     * failed (A2). Consults the indexed providers but accepts <b>only</b> a value the provider actually
     * reports for the token — never a zero-default or synthesized zero — so a fallback never masks a
     * failed capture by writing a bogus zero for a still-held accruing lot. Ankr reported quantities
     * first, then BlockScout token-balance snapshots.
     */
    private ProviderResolutionResult loadForcedLiveProviderFallback(
            List<ResolvedCandidate> candidates,
            Map<NetworkId, Map<String, Integer>> knownDecimalsByNetwork,
            Instant capturedAt,
            String sessionId,
            Runnable heartbeat
    ) {
        ArrayList<OnChainBalance> balances = new ArrayList<>();
        Map<RefreshKey, Boolean> handledKeys = new LinkedHashMap<>();

        // Ankr: only accept genuinely-reported keys (no zero-default).
        Map<String, List<ResolvedCandidate>> byWallet = new LinkedHashMap<>();
        for (ResolvedCandidate candidate : candidates) {
            if (ankrAccountBalanceProvider.supports(candidate.networkId())) {
                byWallet.computeIfAbsent(candidate.walletAddress(), ignored -> new ArrayList<>()).add(candidate);
            }
        }
        for (Map.Entry<String, List<ResolvedCandidate>> entry : byWallet.entrySet()) {
            heartbeat(heartbeat);
            try {
                Map<RefreshKey, BigDecimal> quantities = providerQuantities(entry.getKey(), entry.getValue());
                for (ResolvedCandidate candidate : entry.getValue()) {
                    BigDecimal quantity = quantities.get(refreshKey(candidate));
                    if (quantity != null) {
                        balances.add(balanceDocument(candidate, quantity, capturedAt, sessionId));
                        handledKeys.put(refreshKey(candidate), Boolean.TRUE);
                    }
                }
            } catch (Exception providerFailure) {
                log.warn(
                        "On-chain balance refresh forced-live Ankr fallback failed: walletAddress={}, fallback=BlockScout",
                        entry.getKey(), providerFailure
                );
            }
        }

        // BlockScout: only accept present token snapshots (skip the synthesize-zero branch).
        Map<WalletNetworkKey, List<ResolvedCandidate>> grouped = new LinkedHashMap<>();
        for (ResolvedCandidate candidate : candidates) {
            if (handledKeys.containsKey(refreshKey(candidate))
                    || candidate.queryKind() != QueryKind.ERC20
                    || !blockScoutExplorerProvider.supports(candidate.networkId())) {
                continue;
            }
            grouped.computeIfAbsent(
                    new WalletNetworkKey(candidate.walletAddress(), candidate.networkId()),
                    ignored -> new ArrayList<>()
            ).add(candidate);
        }
        for (Map.Entry<WalletNetworkKey, List<ResolvedCandidate>> entry : grouped.entrySet()) {
            heartbeat(heartbeat);
            WalletNetworkKey key = entry.getKey();
            try {
                Map<String, BlockScoutExplorerProvider.TokenBalanceSnapshot> tokenBalances =
                        blockScoutExplorerProvider.getTokenBalances(key.walletAddress(), key.networkId());
                for (ResolvedCandidate candidate : entry.getValue()) {
                    BlockScoutExplorerProvider.TokenBalanceSnapshot snapshot =
                            tokenBalances.get(candidate.assetContract().toLowerCase(Locale.ROOT));
                    if (snapshot == null || snapshot.rawQuantity() == null || snapshot.decimals() < 0) {
                        continue;
                    }
                    BigDecimal quantity = Decimal128Support.normalize(
                            new BigDecimal(snapshot.rawQuantity()).movePointLeft(Math.max(0, snapshot.decimals()))
                    );
                    balances.add(balanceDocument(candidate, quantity, snapshot.decimals(), capturedAt, sessionId));
                    handledKeys.put(refreshKey(candidate), Boolean.TRUE);
                }
            } catch (Exception explorerFailure) {
                log.warn(
                        "On-chain balance refresh forced-live BlockScout fallback failed: walletAddress={}, networkId={}, "
                                + "fallback=last-known-snapshot",
                        key.walletAddress(), key.networkId(), explorerFailure
                );
            }
        }
        return new ProviderResolutionResult(List.copyOf(balances), handledKeys.keySet());
    }

    private void deleteStaleRows(Set<String> existingIds, Set<String> refreshedIds) {
        if (existingIds == null || existingIds.isEmpty()) {
            return;
        }
        List<String> staleIds = existingIds.stream()
                .filter(Objects::nonNull)
                .filter(id -> !refreshedIds.contains(id))
                .toList();
        if (!staleIds.isEmpty()) {
            onChainBalanceRepository.deleteAllById(staleIds);
        }
    }

    private Map<String, OnChainBalance> loadExistingById(String sessionId) {
        List<OnChainBalance> existing = sessionId == null || sessionId.isBlank()
                ? onChainBalanceRepository.findBySessionIdIsNull()
                : onChainBalanceRepository.findBySessionId(sessionId);
        if (existing == null || existing.isEmpty()) {
            return Map.of();
        }
        Map<String, OnChainBalance> byId = new LinkedHashMap<>();
        for (OnChainBalance balance : existing) {
            if (balance != null && balance.getId() != null) {
                byId.putIfAbsent(balance.getId(), balance);
            }
        }
        return byId;
    }

    /**
     * Retained last-known snapshot re-emitted for a candidate whose live capture failed (A1/A2). Keeps
     * the prior quantity/decimals and the prior {@code capturedAt} (does <b>not</b> backfill it to the
     * current capture time so fallback staleness stays measurable) and marks {@code captureFallback}.
     */
    private OnChainBalance fallbackBalance(ResolvedCandidate candidate, OnChainBalance lastKnown, String sessionId) {
        OnChainBalance balance = new OnChainBalance();
        balance.setId(balanceId(sessionId, candidate));
        balance.setSessionId(sessionId);
        balance.setWalletAddress(candidate.walletAddress());
        balance.setNetworkId(candidate.networkId());
        balance.setAssetSymbol(candidate.assetSymbol() == null ? lastKnown.getAssetSymbol() : candidate.assetSymbol());
        balance.setAssetContract(candidate.assetContract());
        balance.setTokenDecimals(lastKnown.getTokenDecimals());
        balance.setQuantity(lastKnown.getQuantity());
        balance.setCapturedAt(lastKnown.getCapturedAt());
        balance.setCaptureFallback(Boolean.TRUE);
        return balance;
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

    private ProviderResolutionResult loadProviderBalances(
            List<ResolvedCandidate> candidates,
            Instant capturedAt,
            String sessionId,
            Runnable heartbeat
    ) {
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
            heartbeat(heartbeat);
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
                    balances.add(balanceDocument(candidate, quantity, capturedAt, sessionId));
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

    private Map<NetworkId, Map<String, Integer>> loadKnownDecimals(
            List<ResolvedCandidate> candidates,
            String sessionId
    ) {
        List<OnChainBalanceRefreshQueryService.TokenContractRef> refs = candidates.stream()
                .filter(candidate -> candidate.queryKind() == QueryKind.ERC20)
                .map(candidate -> new OnChainBalanceRefreshQueryService.TokenContractRef(
                        candidate.networkId(),
                        candidate.assetContract()
                ))
                .toList();
        Map<OnChainBalanceRefreshQueryService.TokenContractRef, Integer> known =
                queryService.loadKnownTokenDecimals(refs, sessionId);
        Map<NetworkId, Map<String, Integer>> byNetwork = new EnumMap<>(NetworkId.class);
        if (known == null || known.isEmpty()) {
            return byNetwork;
        }
        for (Map.Entry<OnChainBalanceRefreshQueryService.TokenContractRef, Integer> entry : known.entrySet()) {
            if (entry.getKey().networkId() == null
                    || entry.getKey().assetContract() == null
                    || entry.getValue() == null
                    || entry.getValue() < 0) {
                continue;
            }
            byNetwork.computeIfAbsent(entry.getKey().networkId(), ignored -> new LinkedHashMap<>())
                    .put(entry.getKey().assetContract().toLowerCase(Locale.ROOT), entry.getValue());
        }
        return byNetwork;
    }

    private ProviderResolutionResult loadExplorerBalances(
            List<ResolvedCandidate> candidates,
            Map<NetworkId, Map<String, Integer>> knownDecimalsByNetwork,
            Instant capturedAt,
            String sessionId,
            Runnable heartbeat
    ) {
        ArrayList<OnChainBalance> balances = new ArrayList<>();
        Map<RefreshKey, Boolean> handledKeys = new LinkedHashMap<>();
        List<ResolvedCandidate> supportedCandidates = candidates.stream()
                .filter(candidate -> etherscanExplorerProvider.supports(candidate.networkId()))
                .toList();
        for (ResolvedBalance resolvedBalance : runBounded(
                "etherscan-balance-refresh",
                supportedCandidates.stream()
                        .map(candidate -> (Callable<Optional<ResolvedBalance>>) () -> loadExplorerBalance(
                                candidate,
                                knownDecimalsByNetwork,
                                capturedAt,
                                sessionId
                        ))
                        .toList(),
                heartbeat
        )) {
            balances.add(resolvedBalance.balance());
            handledKeys.put(refreshKey(resolvedBalance.candidate()), Boolean.TRUE);
        }
        return new ProviderResolutionResult(List.copyOf(balances), handledKeys.keySet());
    }

    private Optional<ResolvedBalance> loadExplorerBalance(
            ResolvedCandidate candidate,
            Map<NetworkId, Map<String, Integer>> knownDecimalsByNetwork,
            Instant capturedAt,
            String sessionId
    ) {
        try {
            BigInteger rawQuantity = candidate.queryKind() == QueryKind.NATIVE
                    ? etherscanExplorerProvider.getNativeBalance(candidate.walletAddress(), candidate.networkId())
                    : etherscanExplorerProvider.getTokenBalance(
                            candidate.walletAddress(),
                            candidate.assetContract(),
                            candidate.networkId()
                    );
            if (rawQuantity == null) {
                return Optional.empty();
            }
            Integer explorerDecimals = candidate.queryKind() == QueryKind.NATIVE
                    ? Integer.valueOf(EVM_NATIVE_DECIMALS)
                    : knownDecimals(candidate, knownDecimalsByNetwork);
            if (explorerDecimals == null && candidate.queryKind() == QueryKind.ERC20) {
                explorerDecimals = etherscanExplorerProvider.getTokenDecimals(
                        candidate.walletAddress(),
                        candidate.assetContract(),
                        candidate.networkId()
                );
            }
            int decimals = explorerDecimals == null ? -1 : explorerDecimals;
            if (decimals < 0) {
                return Optional.empty();
            }
            BigDecimal quantity = Decimal128Support.normalize(
                    new BigDecimal(rawQuantity).movePointLeft(Math.max(0, decimals))
            );
            return Optional.of(new ResolvedBalance(
                    candidate,
                    balanceDocument(candidate, quantity, decimals, capturedAt, sessionId)
            ));
        } catch (Exception explorerFailure) {
            log.warn(
                    "On-chain balance refresh explorer path failed: walletAddress={}, networkId={}, accountingIdentity={}, fallback=RPC",
                    candidate.walletAddress(),
                    candidate.networkId(),
                    candidate.accountingIdentity(),
                    explorerFailure
            );
            return Optional.empty();
        }
    }

    private ProviderResolutionResult loadBlockScoutBalances(
            List<ResolvedCandidate> candidates,
            Map<NetworkId, Map<String, Integer>> knownDecimalsByNetwork,
            Instant capturedAt,
            String sessionId,
            Runnable heartbeat
    ) {
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
        for (List<ResolvedBalance> groupBalances : runBounded(
                "blockscout-balance-refresh",
                grouped.entrySet().stream()
                        .map(entry -> (Callable<Optional<List<ResolvedBalance>>>) () -> loadBlockScoutBalanceGroup(
                                entry,
                                knownDecimalsByNetwork,
                                capturedAt,
                                sessionId
                        ))
                        .toList(),
                heartbeat
        )) {
            for (ResolvedBalance resolvedBalance : groupBalances) {
                balances.add(resolvedBalance.balance());
                handledKeys.put(refreshKey(resolvedBalance.candidate()), Boolean.TRUE);
            }
        }
        return new ProviderResolutionResult(List.copyOf(balances), handledKeys.keySet());
    }

    private Optional<List<ResolvedBalance>> loadBlockScoutBalanceGroup(
            Map.Entry<WalletNetworkKey, List<ResolvedCandidate>> entry,
            Map<NetworkId, Map<String, Integer>> knownDecimalsByNetwork,
            Instant capturedAt,
            String sessionId
    ) {
        WalletNetworkKey walletNetworkKey = entry.getKey();
        List<ResolvedCandidate> walletCandidates = entry.getValue().stream()
                .sorted(Comparator.comparing(ResolvedCandidate::accountingIdentity))
                .toList();
        Map<String, Integer> tokenDecimalsCache = new LinkedHashMap<>();
        ArrayList<ResolvedBalance> balances = new ArrayList<>();
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
                                ignored -> {
                                    Integer knownDecimals = knownDecimals(candidate, knownDecimalsByNetwork);
                                    return knownDecimals == null
                                            ? blockScoutExplorerProvider.getTokenDecimals(
                                                    candidate.assetContract(),
                                                    candidate.networkId()
                                            )
                                            : knownDecimals;
                                }
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
                balances.add(new ResolvedBalance(
                        candidate,
                        balanceDocument(candidate, quantity, decimals, capturedAt, sessionId)
                ));
            }
            return Optional.of(List.copyOf(balances));
        } catch (Exception explorerFailure) {
            log.warn(
                    "On-chain balance refresh Blockscout path failed: walletAddress={}, networkId={}, fallback=RPC",
                    walletNetworkKey.walletAddress(),
                    walletNetworkKey.networkId(),
                    explorerFailure
            );
            return Optional.empty();
        }
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

    private Map<NetworkId, Map<String, Integer>> loadDecimals(
            List<ResolvedCandidate> candidates,
            Map<NetworkId, Map<String, Integer>> knownDecimalsByNetwork,
            Runnable heartbeat
    ) {
        Map<NetworkId, List<String>> contractsByNetwork = new EnumMap<>(NetworkId.class);
        Map<NetworkId, Map<String, Integer>> decimalsByNetwork = copyDecimals(knownDecimalsByNetwork);
        for (ResolvedCandidate candidate : candidates) {
            if (candidate.queryKind() != QueryKind.ERC20 || candidate.assetContract() == null) {
                continue;
            }
            if (knownDecimals(candidate, decimalsByNetwork) != null) {
                continue;
            }
            contractsByNetwork.computeIfAbsent(candidate.networkId(), ignored -> new ArrayList<>());
            List<String> contracts = contractsByNetwork.get(candidate.networkId());
            if (!contracts.contains(candidate.assetContract())) {
                contracts.add(candidate.assetContract());
            }
        }

        for (Map.Entry<NetworkId, List<String>> entry : contractsByNetwork.entrySet()) {
            heartbeat(heartbeat);
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
            JsonNode responses;
            try {
                responses = callBatchWithRetry(networkId, requests);
            } catch (RuntimeException decimalsFailure) {
                // A1: a transient decimals() RPC failure must not abort the whole refresh. Leave these
                // contracts without decimals so their candidates stay unresolved and route to the
                // last-known-snapshot fallback rather than being silently dropped.
                log.warn(
                        "On-chain balance refresh decimals lookup failed: networkId={}, contracts={}, fallback=snapshot",
                        networkId, contracts, decimalsFailure
                );
                continue;
            }
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
            decimalsByNetwork.computeIfAbsent(networkId, ignored -> new LinkedHashMap<>()).putAll(decimalsByContract);
        }
        return decimalsByNetwork;
    }

    private ProviderResolutionResult loadRpcBalances(
            List<ResolvedCandidate> candidates,
            Map<NetworkId, Map<String, Integer>> decimalsByNetwork,
            Instant capturedAt,
            String sessionId,
            Runnable heartbeat
    ) {
        Map<WalletNetworkKey, List<ResolvedCandidate>> grouped = new LinkedHashMap<>();
        for (ResolvedCandidate candidate : candidates) {
            grouped.computeIfAbsent(
                    new WalletNetworkKey(candidate.walletAddress(), candidate.networkId()),
                    ignored -> new ArrayList<>()
            ).add(candidate);
        }

        List<OnChainBalance> balances = new ArrayList<>();
        Map<RefreshKey, Boolean> handledKeys = new LinkedHashMap<>();
        for (Map.Entry<WalletNetworkKey, List<ResolvedCandidate>> entry : grouped.entrySet()) {
            heartbeat(heartbeat);
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
                    // Decimals lookup failed for this candidate (A1): do not emit a row here — leaving
                    // the key unhandled routes it to the last-known-snapshot fallback rather than a
                    // silent drop.
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

            JsonNode responses;
            try {
                responses = callBatchWithRetry(walletNetworkKey.networkId(), requests);
            } catch (RuntimeException rpcFailure) {
                // A1/A2: RPC exhausted all endpoints for this wallet/network group. Do not propagate
                // (that would abort the whole refresh) and do not mark these keys handled — they route
                // to the forced-live provider fallback and/or last-known-snapshot fallback.
                log.warn(
                        "On-chain balance refresh RPC group failed after retries: walletAddress={}, networkId={}, fallback=snapshot",
                        walletNetworkKey.walletAddress(), walletNetworkKey.networkId(), rpcFailure
                );
                continue;
            }
            Map<Integer, JsonNode> byId = responsesById(responses);
            for (int i = 0; i < descriptors.size(); i++) {
                RequestDescriptor descriptor = descriptors.get(i);
                JsonNode response = byId.get(i + 1);
                BigInteger rawQuantity = parseOptionalBigIntegerQuantity(response);
                if (rawQuantity == null) {
                    // Transient balanceOf error / missing result (A1): leave unhandled so the candidate
                    // falls back to its last-known snapshot instead of being silently dropped.
                    log.warn(
                            "On-chain balance refresh missing balance result: walletAddress={}, networkId={}, accountingIdentity={}, fallback=snapshot",
                            descriptor.candidate().walletAddress(),
                            descriptor.candidate().networkId(),
                            descriptor.candidate().accountingIdentity()
                    );
                    continue;
                }
                BigDecimal quantity = Decimal128Support.normalize(
                        new BigDecimal(rawQuantity).movePointLeft(Math.max(0, descriptor.decimals()))
                );
                balances.add(balanceDocument(
                        descriptor.candidate(),
                        quantity,
                        descriptor.decimals(),
                        capturedAt,
                        sessionId
                ));
                handledKeys.put(refreshKey(descriptor.candidate()), Boolean.TRUE);
            }
        }
        return new ProviderResolutionResult(List.copyOf(balances), handledKeys.keySet());
    }

    private <T> List<T> runBounded(
            String operation,
            List<Callable<Optional<T>>> tasks,
            Runnable heartbeat
    ) {
        if (tasks == null || tasks.isEmpty()) {
            return List.of();
        }
        int lanes = Math.max(1, Math.min(EXPLORER_REFRESH_LANES, tasks.size()));
        ExecutorService executor = Executors.newFixedThreadPool(lanes);
        try {
            List<Future<Optional<T>>> futures = executor.invokeAll(tasks);
            ArrayList<T> results = new ArrayList<>();
            for (Future<Optional<T>> future : futures) {
                heartbeat(heartbeat);
                try {
                    future.get().ifPresent(results::add);
                } catch (ExecutionException error) {
                    log.warn("On-chain balance refresh {} task failed: fallback=RPC", operation, error);
                }
            }
            return List.copyOf(results);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new RpcException("On-chain balance refresh " + operation + " interrupted", error);
        } finally {
            executor.shutdownNow();
        }
    }

    private void heartbeat(Runnable heartbeat) {
        if (heartbeat != null) {
            heartbeat.run();
        }
    }

    private OnChainBalance balanceDocument(
            ResolvedCandidate candidate,
            BigDecimal quantity,
            Integer tokenDecimals,
            Instant capturedAt,
            String sessionId
    ) {
        OnChainBalance balance = new OnChainBalance();
        balance.setId(balanceId(sessionId, candidate));
        balance.setSessionId(sessionId);
        balance.setWalletAddress(candidate.walletAddress());
        balance.setNetworkId(candidate.networkId());
        balance.setAssetSymbol(candidate.assetSymbol());
        balance.setAssetContract(candidate.assetContract());
        balance.setTokenDecimals(tokenDecimals);
        balance.setQuantity(quantity);
        balance.setCapturedAt(capturedAt);
        return balance;
    }

    private OnChainBalance balanceDocument(
            ResolvedCandidate candidate,
            BigDecimal quantity,
            Instant capturedAt,
            String sessionId
    ) {
        Integer tokenDecimals = candidate.queryKind() == QueryKind.NATIVE ? EVM_NATIVE_DECIMALS : null;
        return balanceDocument(candidate, quantity, tokenDecimals, capturedAt, sessionId);
    }

    private Integer knownDecimals(
            ResolvedCandidate candidate,
            Map<NetworkId, Map<String, Integer>> knownDecimalsByNetwork
    ) {
        if (candidate == null || candidate.assetContract() == null || knownDecimalsByNetwork == null) {
            return null;
        }
        return knownDecimalsByNetwork
                .getOrDefault(candidate.networkId(), Map.of())
                .get(candidate.assetContract().toLowerCase(Locale.ROOT));
    }

    private Map<NetworkId, Map<String, Integer>> copyDecimals(Map<NetworkId, Map<String, Integer>> source) {
        Map<NetworkId, Map<String, Integer>> copy = new EnumMap<>(NetworkId.class);
        if (source == null) {
            return copy;
        }
        for (Map.Entry<NetworkId, Map<String, Integer>> entry : source.entrySet()) {
            copy.put(entry.getKey(), new LinkedHashMap<>(entry.getValue()));
        }
        return copy;
    }

    private String balanceId(String sessionId, ResolvedCandidate candidate) {
        return balanceId(sessionId, candidate.walletAddress(), candidate.networkId(), candidate.accountingIdentity());
    }

    private String balanceId(String sessionId, String walletAddress, NetworkId networkId, String accountingIdentity) {
        String prefix = sessionId == null || sessionId.isBlank() ? "GLOBAL" : sessionId;
        return prefix + ":" + walletAddress + ":" + networkId.name() + ":" + accountingIdentity;
    }

    private RefreshKey refreshKey(ResolvedCandidate candidate) {
        return new RefreshKey(candidate.walletAddress(), candidate.networkId(), candidate.accountingIdentity());
    }

    /**
     * True when a token's {@code balanceOf} must be read live via RPC rather than through indexed
     * balance providers. Interest-accruing lending receipt/debt tokens (Aave aTokens, variable/stable
     * debt tokens) rebase over time; indexers report the scaled/principal balance and under-report
     * accrued interest, which corrupts lending health, net exposure and PnL. RPC {@code balanceOf} is
     * the on-chain ground truth for these tokens.
     */
    private static boolean requiresLiveBalanceOf(ResolvedCandidate candidate) {
        return candidate != null
                && candidate.queryKind() == QueryKind.ERC20
                && LendingAssetSymbolSupport.isLendingReceiptOrDebtSymbol(candidate.assetSymbol());
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

    /**
     * Outcome of the EVM balance load. {@code hadFailure} is {@code true} when at least one candidate
     * that resolved a nonzero net-flow could not be freshly captured from any live source and fell
     * back to its last-known snapshot (or is genuinely missing) — the signal that destructive stale-row
     * cleanup must be skipped so a partial fetch never erases the read model.
     */
    private record EvmLoadResult(
            List<OnChainBalance> balances,
            boolean hadFailure
    ) {
    }

    private record ResolvedBalance(
            ResolvedCandidate candidate,
            OnChainBalance balance
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
