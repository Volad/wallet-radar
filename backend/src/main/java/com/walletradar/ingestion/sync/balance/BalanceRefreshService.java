package com.walletradar.ingestion.sync.balance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.config.AsyncConfig;
import com.walletradar.domain.EconomicEventRepository;
import com.walletradar.domain.NetworkId;
import com.walletradar.domain.NormalizedTransactionRepository;
import com.walletradar.domain.NormalizedTransactionStatus;
import com.walletradar.domain.OnChainBalance;
import com.walletradar.domain.OnChainBalanceRepository;
import com.walletradar.domain.SyncStatus;
import com.walletradar.domain.SyncStatusRepository;
import com.walletradar.ingestion.adapter.RpcEndpointRotator;
import com.walletradar.ingestion.adapter.RpcException;
import com.walletradar.ingestion.adapter.evm.EvmRpcClient;
import com.walletradar.ingestion.adapter.evm.EvmTokenDecimalsResolver;
import com.walletradar.ingestion.adapter.solana.SolanaRpcClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Refreshes current on-chain balances for wallets/networks and stores them in on_chain_balances.
 * Strategy: always refresh native balance; refresh token balances for known asset contracts
 * (from economic_events and previously stored on_chain_balances for the same wallet×network).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BalanceRefreshService {

    private static final int SCALE = 18;
    private static final String ETH_CALL_SELECTOR_BALANCE_OF = "0x70a08231";
    private static final String ZERO_ADDRESS = "0x0000000000000000000000000000000000000000";
    private static final String EVM_NATIVE_CONTRACT = "0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee";
    private static final String SOLANA_NATIVE_CONTRACT = "So11111111111111111111111111111111111111112";

    private static final Map<NetworkId, String> NATIVE_CONTRACT = new EnumMap<>(NetworkId.class);

    static {
        NATIVE_CONTRACT.put(NetworkId.ETHEREUM, EVM_NATIVE_CONTRACT);
        NATIVE_CONTRACT.put(NetworkId.ARBITRUM, EVM_NATIVE_CONTRACT);
        NATIVE_CONTRACT.put(NetworkId.OPTIMISM, EVM_NATIVE_CONTRACT);
        NATIVE_CONTRACT.put(NetworkId.POLYGON, EVM_NATIVE_CONTRACT);
        NATIVE_CONTRACT.put(NetworkId.BASE, EVM_NATIVE_CONTRACT);
        NATIVE_CONTRACT.put(NetworkId.BSC, EVM_NATIVE_CONTRACT);
        NATIVE_CONTRACT.put(NetworkId.AVALANCHE, EVM_NATIVE_CONTRACT);
        NATIVE_CONTRACT.put(NetworkId.MANTLE, EVM_NATIVE_CONTRACT);
        NATIVE_CONTRACT.put(NetworkId.SOLANA, SOLANA_NATIVE_CONTRACT);
    }

    private final SyncStatusRepository syncStatusRepository;
    private final OnChainBalanceRepository onChainBalanceRepository;
    private final EconomicEventRepository economicEventRepository;
    private final NormalizedTransactionRepository normalizedTransactionRepository;
    private final EvmTokenDecimalsResolver evmTokenDecimalsResolver;
    private final EvmRpcClient evmRpcClient;
    private final SolanaRpcClient solanaRpcClient;
    private final ObjectMapper objectMapper;

    @Qualifier("evmRotatorsByNetwork")
    private final Map<String, RpcEndpointRotator> evmRotatorsByNetwork;
    @Qualifier("evmDefaultRpcEndpointRotator")
    private final RpcEndpointRotator evmDefaultRotator;
    @Qualifier("solanaRotatorsByNetwork")
    private final Map<String, RpcEndpointRotator> solanaRotatorsByNetwork;
    @Qualifier("solanaDefaultRpcEndpointRotator")
    private final RpcEndpointRotator solanaDefaultRotator;

    @Async(AsyncConfig.SYNC_EXECUTOR)
    public void refreshWalletsAsync(List<String> wallets, List<NetworkId> networks) {
        refreshWallets(wallets, networks);
    }

    /**
     * Synchronous refresh for explicit wallet/network sets.
     */
    public void refreshWallets(List<String> wallets, List<NetworkId> networks) {
        if (wallets == null || wallets.isEmpty()) {
            return;
        }
        List<NetworkId> targetNetworks = (networks == null || networks.isEmpty())
                ? List.of(NetworkId.values())
                : networks;

        for (String wallet : wallets) {
            if (wallet == null || wallet.isBlank()) {
                continue;
            }
            String normalizedWallet = wallet.trim();
            for (NetworkId networkId : targetNetworks) {
                try {
                    refreshWalletNetwork(normalizedWallet, networkId);
                } catch (Exception e) {
                    log.warn("Balance refresh failed for {} on {}: {}", normalizedWallet, networkId, e.getMessage());
                }
            }
        }
    }

    /**
     * Poll refresh for all known wallet×network pairs from sync_status.
     */
    public void refreshAllKnownWalletNetworks() {
        List<SyncStatus> statuses = syncStatusRepository.findAll();
        for (SyncStatus status : statuses) {
            if (status.getWalletAddress() == null || status.getWalletAddress().isBlank()
                    || status.getNetworkId() == null || status.getNetworkId().isBlank()) {
                continue;
            }
            NetworkId networkId;
            try {
                networkId = NetworkId.valueOf(status.getNetworkId());
            } catch (IllegalArgumentException e) {
                continue;
            }
            try {
                refreshWalletNetwork(status.getWalletAddress(), networkId);
            } catch (Exception e) {
                log.warn("Scheduled balance refresh failed for {} on {}: {}",
                        status.getWalletAddress(), networkId, e.getMessage());
            }
        }
    }

    private void refreshWalletNetwork(String walletAddress, NetworkId networkId) {
        if (networkId == NetworkId.SOLANA) {
            refreshSolanaBalances(walletAddress, networkId);
            return;
        }
        refreshEvmBalances(walletAddress, networkId);
    }

    private void refreshEvmBalances(String walletAddress, NetworkId networkId) {
        RpcEndpointRotator rotator = evmRotatorsByNetwork.getOrDefault(networkId.name(), evmDefaultRotator);
        Instant now = Instant.now();

        // Native balance (wei -> ETH units)
        String nativeHex = callEvmWithRetry(rotator, "eth_getBalance", List.of(walletAddress, "latest"));
        BigDecimal nativeQty = hexToDecimal(nativeHex, 18);
        upsertBalance(walletAddress, networkId.name(), nativeContract(networkId), nativeQty, now);

        // Known tokens only: from economic_events + previously stored on_chain_balances.
        Set<String> tokenContracts = resolveKnownTokenContracts(walletAddress, networkId);
        for (String tokenContract : tokenContracts) {
            try {
                String data = balanceOfCallData(walletAddress);
                String tokenHex = callEvmWithRetry(rotator, "eth_call",
                        List.of(Map.of("to", tokenContract, "data", data), "latest"));
                int decimals = evmTokenDecimalsResolver.getDecimals(networkId.name(), tokenContract);
                BigDecimal tokenQty = hexToDecimal(tokenHex, decimals);
                upsertBalance(walletAddress, networkId.name(), tokenContract, tokenQty, now);
            } catch (Exception e) {
                log.warn("Token balance refresh failed for {} {} on {}: {}",
                        walletAddress, tokenContract, networkId, e.getMessage());
            }
        }
    }

    private void refreshSolanaBalances(String walletAddress, NetworkId networkId) {
        RpcEndpointRotator rotator = solanaRotatorsByNetwork.getOrDefault(networkId.name(), solanaDefaultRotator);
        Instant now = Instant.now();
        String json = callSolanaWithRetry(rotator, "getBalance", List.of(walletAddress, Map.of("commitment", "confirmed")));
        long lamports = extractSolanaLamports(json);
        BigDecimal solQty = new BigDecimal(lamports).divide(BigDecimal.TEN.pow(9), SCALE, RoundingMode.HALF_UP);
        upsertBalance(walletAddress, networkId.name(), nativeContract(networkId), solQty, now);
    }

    private Set<String> resolveKnownTokenContracts(String walletAddress, NetworkId networkId) {
        Set<String> contracts = new LinkedHashSet<>();
        contracts.addAll(normalizedTransactionRepository
                .findDistinctAssetContractsByWalletAddressAndNetworkIdAndStatus(
                        walletAddress, networkId, NormalizedTransactionStatus.CONFIRMED));
        contracts.addAll(economicEventRepository.findDistinctAssetContractsByWalletAddressAndNetworkId(walletAddress, networkId));
        contracts.addAll(onChainBalanceRepository.findByWalletAddressAndNetworkId(walletAddress, networkId.name()).stream()
                .map(OnChainBalance::getAssetContract)
                .toList());
        String nativeContract = nativeContract(networkId).toLowerCase(Locale.ROOT);
        contracts.removeIf(c -> c == null
                || c.isBlank()
                || nativeContract.equals(c.toLowerCase(Locale.ROOT))
                || EVM_NATIVE_CONTRACT.equals(c.toLowerCase(Locale.ROOT))
                || ZERO_ADDRESS.equals(c.toLowerCase(Locale.ROOT)));
        Set<String> normalized = new LinkedHashSet<>();
        for (String contract : contracts) {
            normalized.add(contract.toLowerCase(Locale.ROOT));
        }
        return normalized;
    }

    private void upsertBalance(String walletAddress, String networkId, String assetContract, BigDecimal quantity, Instant capturedAt) {
        Optional<OnChainBalance> existing = onChainBalanceRepository
                .findByWalletAddressAndNetworkIdAndAssetContract(walletAddress, networkId, assetContract);
        OnChainBalance balance = existing.orElseGet(OnChainBalance::new);
        balance.setWalletAddress(walletAddress);
        balance.setNetworkId(networkId);
        balance.setAssetContract(assetContract);
        balance.setQuantity(quantity);
        balance.setCapturedAt(capturedAt);
        onChainBalanceRepository.save(balance);
    }

    private String callEvmWithRetry(RpcEndpointRotator rotator, String method, Object params) {
        Exception lastException = null;
        for (int attempt = 0; attempt < rotator.getMaxAttempts(); attempt++) {
            if (attempt > 0) {
                sleepQuietly(rotator.retryDelayMs(attempt - 1));
            }
            String endpoint = rotator.getNextEndpoint();
            try {
                String json = evmRpcClient.call(endpoint, method, params).block();
                return extractEvmResult(json);
            } catch (Exception e) {
                lastException = e;
            }
        }
        throw new RpcException("RPC failed after " + rotator.getMaxAttempts() + " attempts: " + messageOf(lastException), lastException);
    }

    private String callSolanaWithRetry(RpcEndpointRotator rotator, String method, Object params) {
        Exception lastException = null;
        for (int attempt = 0; attempt < rotator.getMaxAttempts(); attempt++) {
            if (attempt > 0) {
                sleepQuietly(rotator.retryDelayMs(attempt - 1));
            }
            String endpoint = rotator.getNextEndpoint();
            try {
                String json = solanaRpcClient.call(endpoint, method, params).block();
                if (json == null || json.isBlank()) {
                    throw new RpcException("Empty Solana RPC response");
                }
                JsonNode root = objectMapper.readTree(json);
                if (root.has("error")) {
                    throw new RpcException("Solana RPC error: " + root.get("error"));
                }
                return json;
            } catch (Exception e) {
                lastException = e;
            }
        }
        throw new RpcException("Solana RPC failed after " + rotator.getMaxAttempts() + " attempts: " + messageOf(lastException), lastException);
    }

    private String extractEvmResult(String json) throws Exception {
        if (json == null || json.isBlank()) {
            throw new RpcException("Empty RPC response");
        }
        JsonNode root = objectMapper.readTree(json);
        if (root.has("error")) {
            throw new RpcException("RPC error: " + root.get("error"));
        }
        JsonNode result = root.get("result");
        if (result == null || result.isNull()) {
            throw new RpcException("RPC result is null");
        }
        return result.asText();
    }

    private long extractSolanaLamports(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode value = root.path("result").path("value");
            if (!value.isNumber()) {
                return 0L;
            }
            return value.asLong();
        } catch (Exception e) {
            throw new RpcException("Failed to parse Solana getBalance response", e);
        }
    }

    private static String balanceOfCallData(String walletAddress) {
        String hex = walletAddress != null && walletAddress.startsWith("0x")
                ? walletAddress.substring(2)
                : walletAddress;
        if (hex == null) {
            return ETH_CALL_SELECTOR_BALANCE_OF + "0".repeat(64);
        }
        String padded = String.format("%64s", hex).replace(' ', '0');
        return ETH_CALL_SELECTOR_BALANCE_OF + padded;
    }

    private static BigDecimal hexToDecimal(String hex, int decimals) {
        if (hex == null || hex.isBlank()) {
            return BigDecimal.ZERO;
        }
        String normalized = hex.startsWith("0x") || hex.startsWith("0X") ? hex.substring(2) : hex;
        if (normalized.isBlank()) {
            return BigDecimal.ZERO;
        }
        BigInteger raw = new BigInteger(normalized, 16);
        if (decimals <= 0) {
            return new BigDecimal(raw).setScale(SCALE, RoundingMode.HALF_UP);
        }
        return new BigDecimal(raw).divide(BigDecimal.TEN.pow(decimals), SCALE, RoundingMode.HALF_UP);
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(Math.max(0L, millis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RpcException("Interrupted during RPC retry", e);
        }
    }

    private static String messageOf(Exception e) {
        if (e == null) {
            return "unknown";
        }
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    private static String nativeContract(NetworkId networkId) {
        return NATIVE_CONTRACT.getOrDefault(networkId, EVM_NATIVE_CONTRACT);
    }
}
