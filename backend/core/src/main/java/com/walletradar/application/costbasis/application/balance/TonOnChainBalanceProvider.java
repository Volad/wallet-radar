package com.walletradar.application.costbasis.application.balance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.application.normalization.pipeline.ton.TonJettonMetadataRegistry;
import com.walletradar.domain.common.Decimal128Support;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.common.NetworkNativeAssets;
import com.walletradar.domain.common.ton.TonAddressCanonicalizer;
import com.walletradar.platform.networks.RpcException;
import com.walletradar.platform.networks.ton.TonNetworkProperties;
import com.walletradar.platform.networks.ton.TonRpcClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * TON on-chain balance provider (ADR-067): enumerates native TON (nanoTON) and jetton balances for
 * a wallet via the shared {@link TonRpcClient} (TON Center v3 REST API).
 *
 * <ul>
 *   <li>Native TON: {@code GET /accountStates?address=...} → {@code balance} nanoTON at
 *       {@code native-decimals} from the network descriptor (9), booked with the
 *       {@code native-identity} sentinel ({@code TONCOIN}) that {@code TonNormalizedTransactionBuilder}
 *       stamps on native flows so the accounting identity matches the ledger.</li>
 *   <li>Jettons: {@code GET /jetton/wallets?owner_address=...} → one balance per jetton master.
 *       Decimals resolve via {@link TonJettonMetadataRegistry} (then the response {@code metadata}
 *       block); symbols resolve via the registry (then metadata, then a truncated master fallback).
 *       Jettons with no resolvable decimals are skipped — booking them at the wrong native precision
 *       (9) would be off by orders of magnitude.</li>
 * </ul>
 *
 * <p>The owner address is passed to toncenter in friendly form (raw {@code 0:hex} causes the
 * endpoint to time out), consistent with {@code TonNetworkAdapter}. The jetton master is stored
 * lowercased to match {@code TonNormalizedTransactionBuilder#canonicalJettonContract}.</p>
 */
@Component
@Slf4j
public class TonOnChainBalanceProvider implements OnChainBalanceProvider {

    private static final int JETTON_PAGE_SIZE = 128;

    private final TonRpcClient rpcClient;
    private final TonNetworkProperties properties;
    private final ObjectMapper objectMapper;

    public TonOnChainBalanceProvider(
            TonRpcClient rpcClient,
            TonNetworkProperties properties,
            ObjectMapper objectMapper
    ) {
        this.rpcClient = rpcClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public NetworkId networkId() {
        return NetworkId.TON;
    }

    @Override
    public List<ProviderBalance> fetchBalances(String walletAddress) {
        if (walletAddress == null || walletAddress.isBlank()) {
            return List.of();
        }
        String owner = TonAddressCanonicalizer.preferredMemberRef(walletAddress.trim());
        if (owner.isBlank()) {
            owner = walletAddress.trim();
        }
        List<ProviderBalance> balances = new ArrayList<>();

        BigDecimal nativeTon = fetchNativeTon(owner);
        if (nativeTon != null && nativeTon.signum() > 0) {
            balances.add(new ProviderBalance(
                    NetworkNativeAssets.nativeSymbol(NetworkId.TON),
                    NetworkNativeAssets.nativeIdentity(NetworkId.TON),
                    NetworkNativeAssets.nativeDecimals(NetworkId.TON),
                    nativeTon,
                    true));
        }

        balances.addAll(fetchJettons(owner));
        return List.copyOf(balances);
    }

    private BigDecimal fetchNativeTon(String owner) {
        JsonNode root = get("accountStates", Map.of("address", owner, "include_boc", "false"));
        JsonNode accounts = root.path("accounts");
        JsonNode account = accounts.isArray() && accounts.size() > 0 ? accounts.get(0) : root.path("account");
        String balance = account.path("balance").asText(null);
        if (balance == null || balance.isBlank()) {
            return null;
        }
        BigInteger nano = parseBigInteger(balance);
        if (nano == null || nano.signum() <= 0) {
            return null;
        }
        return Decimal128Support.normalize(new BigDecimal(nano).movePointLeft(NetworkNativeAssets.nativeDecimals(NetworkId.TON)));
    }

    private List<ProviderBalance> fetchJettons(String owner) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("owner_address", owner);
        params.put("limit", String.valueOf(Math.min(JETTON_PAGE_SIZE, Math.max(1, properties.getPageSize()))));
        params.put("offset", "0");
        JsonNode root = get("jetton/wallets", params);
        JsonNode wallets = root.path("jetton_wallets");
        if (!wallets.isArray()) {
            return List.of();
        }
        JsonNode metadata = root.path("metadata");
        List<ProviderBalance> balances = new ArrayList<>();
        for (JsonNode wallet : wallets) {
            String master = firstNonBlank(wallet.path("jetton").asText(null), wallet.path("jetton_master").asText(null));
            String rawBalance = wallet.path("balance").asText(null);
            if (master == null || rawBalance == null || rawBalance.isBlank()) {
                continue;
            }
            BigInteger raw = parseBigInteger(rawBalance);
            if (raw == null || raw.signum() <= 0) {
                continue;
            }
            Integer decimals = resolveJettonDecimals(master, metadata);
            if (decimals == null) {
                log.warn("TON balance provider skipped jetton with unresolved decimals: owner={}, master={}", owner, master);
                continue;
            }
            BigDecimal quantity = Decimal128Support.normalize(
                    new BigDecimal(raw).movePointLeft(Math.max(0, decimals))
            );
            if (quantity.signum() <= 0) {
                continue;
            }
            balances.add(new ProviderBalance(
                    resolveJettonSymbol(master, metadata),
                    master.trim().toLowerCase(Locale.ROOT),
                    decimals,
                    quantity,
                    false
            ));
        }
        return balances;
    }

    private Integer resolveJettonDecimals(String master, JsonNode metadata) {
        Integer configured = TonJettonMetadataRegistry.decimals(master);
        if (configured != null) {
            return configured;
        }
        JsonNode tokenInfo = metadataTokenInfo(master, metadata);
        if (tokenInfo != null) {
            JsonNode decimals = tokenInfo.path("extra").path("decimals");
            if (decimals.isMissingNode() || decimals.isNull()) {
                decimals = tokenInfo.path("decimals");
            }
            if (decimals.isInt()) {
                return decimals.asInt();
            }
            if (decimals.isTextual()) {
                try {
                    return Integer.parseInt(decimals.asText().trim());
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private String resolveJettonSymbol(String master, JsonNode metadata) {
        String configured = TonJettonMetadataRegistry.symbol(master);
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        JsonNode tokenInfo = metadataTokenInfo(master, metadata);
        if (tokenInfo != null) {
            String symbol = tokenInfo.path("symbol").asText(null);
            if (symbol != null && !symbol.isBlank()) {
                return symbol.trim().toUpperCase(Locale.ROOT);
            }
        }
        String trimmed = master.trim();
        return trimmed.length() > 8 ? trimmed.substring(0, 8).toUpperCase(Locale.ROOT) : trimmed.toUpperCase(Locale.ROOT);
    }

    private JsonNode metadataTokenInfo(String master, JsonNode metadata) {
        if (metadata == null || !metadata.isObject()) {
            return null;
        }
        for (String key : TonAddressCanonicalizer.lookupKeys(master.trim())) {
            JsonNode entry = metadata.path(key);
            if (entry.isObject()) {
                JsonNode tokenInfo = entry.path("token_info");
                if (tokenInfo.isArray() && tokenInfo.size() > 0) {
                    return tokenInfo.get(0);
                }
                if (tokenInfo.isObject()) {
                    return tokenInfo;
                }
            }
        }
        return null;
    }

    private JsonNode get(String relativePath, Map<String, String> params) {
        String json = rpcClient.get(relativePath, params);
        try {
            return objectMapper.readTree(json);
        } catch (Exception ex) {
            throw new RpcException("TON balance refresh failed to parse response for " + relativePath, ex);
        }
    }

    private static BigInteger parseBigInteger(String value) {
        try {
            return new BigInteger(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second != null && !second.isBlank() ? second : null;
    }
}
