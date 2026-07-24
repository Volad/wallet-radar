package com.walletradar.application.costbasis.application.balance;

import com.walletradar.application.costbasis.application.OnChainBalanceRefreshQueryService;
import com.walletradar.application.costbasis.domain.OnChainBalance;
import com.walletradar.application.costbasis.support.AccountingAssetIdentitySupport;
import com.walletradar.domain.common.Decimal128Support;
import com.walletradar.domain.common.NetworkId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Non-EVM (Solana / TON) live balance loader (ADR-067). Each registered {@link OnChainBalanceProvider}
 * enumerates a wallet's holdings directly from its family RPC (case-sensitive base58 / friendly
 * addresses never match the lowercased EVM candidate query), so this loader owns the provider
 * dispatch, bounding, and {@link OnChainBalance} document building for the non-EVM families.
 *
 * <p>Kept out of {@code OnChainBalanceRefreshService} so the EVM candidate-driven path stays focused
 * and so family implementations may depend on the normalization-time metadata registries without the
 * portfolio read model taking a compile-time pipeline dependency (see {@code ModuleBoundaryTest}).</p>
 *
 * <p>Results are bounded to the accounting-universe asset identities derived from the confirmed
 * canonical candidate rows, plus the always-included network-native asset, then merged per accounting
 * identity so native + wrapped-native duplicates collapse into a single conserved position.</p>
 */
@Component
@Slf4j
public class NonEvmOnChainBalanceLoader {

    private final Map<NetworkId, OnChainBalanceProvider> providersByNetwork;
    private final List<ProtocolLockedBalanceProvider> lockedBalanceProviders;

    public NonEvmOnChainBalanceLoader(
            List<OnChainBalanceProvider> providers,
            List<ProtocolLockedBalanceProvider> lockedBalanceProviders
    ) {
        Map<NetworkId, OnChainBalanceProvider> byNetwork = new EnumMap<>(NetworkId.class);
        if (providers != null) {
            for (OnChainBalanceProvider provider : providers) {
                if (provider != null && provider.networkId() != null) {
                    byNetwork.putIfAbsent(provider.networkId(), provider);
                }
            }
        }
        this.providersByNetwork = byNetwork;
        this.lockedBalanceProviders = lockedBalanceProviders == null ? List.of() : List.copyOf(lockedBalanceProviders);
    }

    /** @return true when a non-EVM provider is registered for {@code networkId}. */
    public boolean handles(NetworkId networkId) {
        return networkId != null && providersByNetwork.containsKey(networkId);
    }

    /**
     * Enumerates non-EVM balances for the given candidate scopes.
     *
     * @param rawCandidates confirmed-canonical candidate rows on non-EVM networks only
     * @return one {@link OnChainBalance} per (wallet, network, accounting identity) with quantity &gt; 0
     */
    public List<OnChainBalance> load(
            List<OnChainBalanceRefreshQueryService.BalanceRefreshCandidate> rawCandidates,
            Instant capturedAt,
            String sessionId,
            Runnable heartbeat
    ) {
        if (rawCandidates == null || rawCandidates.isEmpty() || providersByNetwork.isEmpty()) {
            return List.of();
        }

        Map<WalletNetworkKey, Set<String>> allowedIdentitiesByScope = new LinkedHashMap<>();
        for (OnChainBalanceRefreshQueryService.BalanceRefreshCandidate candidate : rawCandidates) {
            if (candidate == null || candidate.walletAddress() == null || candidate.networkId() == null) {
                continue;
            }
            String identity = AccountingAssetIdentitySupport.positionAssetIdentity(
                    candidate.networkId(),
                    candidate.assetSymbol(),
                    candidate.assetContract()
            );
            if (identity == null) {
                continue;
            }
            allowedIdentitiesByScope
                    .computeIfAbsent(
                            new WalletNetworkKey(candidate.walletAddress(), candidate.networkId()),
                            ignored -> new LinkedHashSet<>()
                    )
                    .add(identity);
        }

        List<OnChainBalance> balances = new ArrayList<>();
        for (Map.Entry<WalletNetworkKey, Set<String>> entry : allowedIdentitiesByScope.entrySet()) {
            heartbeat(heartbeat);
            WalletNetworkKey scope = entry.getKey();
            OnChainBalanceProvider provider = providersByNetwork.get(scope.networkId());
            if (provider == null) {
                log.warn("On-chain balance refresh has no provider for non-EVM network: networkId={}", scope.networkId());
                continue;
            }
            balances.addAll(loadScope(provider, scope, entry.getValue(), capturedAt, sessionId));
        }
        return balances;
    }

    private List<OnChainBalance> loadScope(
            OnChainBalanceProvider provider,
            WalletNetworkKey scope,
            Set<String> allowedIdentities,
            Instant capturedAt,
            String sessionId
    ) {
        try {
            Map<String, OnChainBalanceProvider.ProviderBalance> mergedByIdentity = new LinkedHashMap<>();
            Map<String, BigDecimal> quantityByIdentity = new LinkedHashMap<>();
            mergeBalances(provider.fetchBalances(scope.walletAddress()), scope, allowedIdentities,
                    mergedByIdentity, quantityByIdentity);
            // WS-3: contribute protocol-locked collateral (e.g. Jupiter Lend SOL) so the wallet's
            // on-chain quantity includes locked positions (Aave-parity), single-authority carry.
            for (ProtocolLockedBalanceProvider lockedProvider : lockedBalanceProviders) {
                mergeBalances(lockedProvider.fetchLockedBalances(scope.walletAddress(), scope.networkId()),
                        scope, allowedIdentities, mergedByIdentity, quantityByIdentity);
            }

            List<OnChainBalance> balances = new ArrayList<>();
            for (Map.Entry<String, OnChainBalanceProvider.ProviderBalance> merged : mergedByIdentity.entrySet()) {
                OnChainBalanceProvider.ProviderBalance providerBalance = merged.getValue();
                BigDecimal quantity = Decimal128Support.normalize(quantityByIdentity.get(merged.getKey()));
                balances.add(balanceDocument(
                        scope,
                        merged.getKey(),
                        providerBalance,
                        quantity,
                        capturedAt,
                        sessionId
                ));
            }
            return balances;
        } catch (Exception providerFailure) {
            log.warn(
                    "On-chain balance refresh non-EVM provider failed: walletAddress={}, networkId={}",
                    scope.walletAddress(),
                    scope.networkId(),
                    providerFailure
            );
            return List.of();
        }
    }

    private void mergeBalances(
            List<OnChainBalanceProvider.ProviderBalance> providerBalances,
            WalletNetworkKey scope,
            Set<String> allowedIdentities,
            Map<String, OnChainBalanceProvider.ProviderBalance> mergedByIdentity,
            Map<String, BigDecimal> quantityByIdentity
    ) {
        if (providerBalances == null) {
            return;
        }
        for (OnChainBalanceProvider.ProviderBalance providerBalance : providerBalances) {
            if (providerBalance == null || providerBalance.quantity() == null
                    || providerBalance.quantity().signum() <= 0) {
                continue;
            }
            String identity = AccountingAssetIdentitySupport.positionAssetIdentity(
                    scope.networkId(),
                    providerBalance.assetSymbol(),
                    providerBalance.assetContract()
            );
            if (identity == null) {
                continue;
            }
            if (!providerBalance.nativeAsset() && !allowedIdentities.contains(identity)) {
                continue;
            }
            mergedByIdentity.putIfAbsent(identity, providerBalance);
            quantityByIdentity.merge(identity, providerBalance.quantity(), BigDecimal::add);
        }
    }

    private OnChainBalance balanceDocument(
            WalletNetworkKey scope,
            String accountingIdentity,
            OnChainBalanceProvider.ProviderBalance providerBalance,
            BigDecimal quantity,
            Instant capturedAt,
            String sessionId
    ) {
        OnChainBalance balance = new OnChainBalance();
        String prefix = sessionId == null || sessionId.isBlank() ? "GLOBAL" : sessionId;
        balance.setId(prefix + ":" + scope.walletAddress() + ":" + scope.networkId().name() + ":" + accountingIdentity);
        balance.setSessionId(sessionId);
        balance.setWalletAddress(scope.walletAddress());
        balance.setNetworkId(scope.networkId());
        balance.setAssetSymbol(providerBalance.assetSymbol());
        balance.setAssetContract(providerBalance.assetContract());
        balance.setTokenDecimals(providerBalance.tokenDecimals());
        balance.setQuantity(quantity);
        balance.setCapturedAt(capturedAt);
        return balance;
    }

    private void heartbeat(Runnable heartbeat) {
        if (heartbeat != null) {
            heartbeat.run();
        }
    }

    private record WalletNetworkKey(String walletAddress, NetworkId networkId) {
    }
}
