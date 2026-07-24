package com.walletradar.application.costbasis.application.balance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.application.normalization.pipeline.solana.JupiterSplTokenMetadataResolver;
import com.walletradar.application.normalization.pipeline.solana.SolanaSplTokenMetadataRegistry;
import com.walletradar.platform.networks.solana.SolanaChain;
import com.walletradar.domain.common.Decimal128Support;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.common.NetworkNativeAssets;
import com.walletradar.platform.networks.RpcEndpointRotator;
import com.walletradar.platform.networks.RpcException;
import com.walletradar.platform.networks.solana.SolanaRpcClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Solana on-chain balance provider (ADR-067): enumerates native SOL (lamports) and SPL token
 * balances for a wallet via the shared {@link SolanaRpcClient}.
 *
 * <ul>
 *   <li>Native SOL: {@code getBalance} → lamports at {@code native-decimals} from the network descriptor
 *       (9), booked as the descriptor's {@code native-identity} sentinel ({@code NATIVE:SOLANA}).</li>
 *   <li>SPL tokens: {@code getTokenAccountsByOwner} is issued once per token program — the classic
 *       SPL Token program <em>and</em> Token-2022 (Token Extensions) — with {@code jsonParsed}
 *       encoding → one balance per mint (amount + decimals from the parsed token account), merged
 *       across both programs by mint. Symbols resolve via {@link JupiterSplTokenMetadataResolver}
 *       (config-seeded registry first, then the free Jupiter Tokens API, cached); unknown mints
 *       keep the mint as the symbol.</li>
 * </ul>
 *
 * <p>Endpoint wiring mirrors {@code SolanaNetworkAdapter} ({@code solanaRotatorsByNetwork} +
 * {@code solanaDefaultRpcEndpointRotator}). Base58 mint/owner addresses are case-sensitive and are
 * never lowercased.</p>
 */
@Component
@Slf4j
public class SolanaOnChainBalanceProvider implements OnChainBalanceProvider {

    /**
     * Both SPL token programs enumerated per wallet refresh. A wallet may hold classic SPL and
     * Token-2022 assets simultaneously, so balances from both must be collected and merged.
     * Constants sourced from {@link SolanaChain} — single source of truth for chain-runtime program IDs.
     */
    private static final List<String> TOKEN_PROGRAM_IDS = List.of(
            SolanaChain.TOKEN_PROGRAM, SolanaChain.TOKEN_2022_PROGRAM);

    private final SolanaRpcClient rpcClient;
    private final Map<String, RpcEndpointRotator> rotatorsByNetwork;
    private final RpcEndpointRotator defaultRotator;
    private final ObjectMapper objectMapper;
    private final JupiterSplTokenMetadataResolver metadataResolver;

    public SolanaOnChainBalanceProvider(
            SolanaRpcClient rpcClient,
            @Qualifier("solanaRotatorsByNetwork") Map<String, RpcEndpointRotator> rotatorsByNetwork,
            @Qualifier("solanaDefaultRpcEndpointRotator") RpcEndpointRotator defaultRotator,
            ObjectMapper objectMapper,
            JupiterSplTokenMetadataResolver metadataResolver
    ) {
        this.rpcClient = rpcClient;
        this.rotatorsByNetwork = rotatorsByNetwork;
        this.defaultRotator = defaultRotator;
        this.objectMapper = objectMapper;
        this.metadataResolver = metadataResolver;
    }

    @Override
    public NetworkId networkId() {
        return NetworkId.SOLANA;
    }

    @Override
    public List<ProviderBalance> fetchBalances(String walletAddress) {
        if (walletAddress == null || walletAddress.isBlank()) {
            return List.of();
        }
        String owner = walletAddress.trim();
        List<ProviderBalance> balances = new ArrayList<>();

        BigDecimal solQuantity = fetchNativeSol(owner);
        if (solQuantity != null && solQuantity.signum() > 0) {
            balances.add(new ProviderBalance(
                    NetworkNativeAssets.nativeSymbol(NetworkId.SOLANA),
                    NetworkNativeAssets.nativeIdentity(NetworkId.SOLANA),
                    NetworkNativeAssets.nativeDecimals(NetworkId.SOLANA),
                    solQuantity,
                    true));
        }

        balances.addAll(fetchSplTokens(owner));
        return List.copyOf(balances);
    }

    private BigDecimal fetchNativeSol(String owner) {
        JsonNode result = callWithRetry("getBalance", List.of(owner, Map.of("commitment", "finalized")));
        JsonNode value = result.path("value");
        if (!value.isNumber()) {
            return null;
        }
        return Decimal128Support.normalize(
                new BigDecimal(value.bigIntegerValue()).movePointLeft(NetworkNativeAssets.nativeDecimals(NetworkId.SOLANA))
        );
    }

    private List<ProviderBalance> fetchSplTokens(String owner) {
        // A mint is owned by exactly one token program, so keyed dedup keeps the first observation
        // defensively; iterating both programs surfaces classic SPL and Token-2022 holdings alike.
        Map<String, ProviderBalance> byMint = new LinkedHashMap<>();
        for (String programId : TOKEN_PROGRAM_IDS) {
            for (ProviderBalance balance : fetchTokenAccountsForProgram(owner, programId)) {
                byMint.putIfAbsent(balance.assetContract(), balance);
            }
        }
        return List.copyOf(byMint.values());
    }

    private List<ProviderBalance> fetchTokenAccountsForProgram(String owner, String programId) {
        JsonNode result = callWithRetry(
                "getTokenAccountsByOwner",
                List.of(
                        owner,
                        Map.of("programId", programId),
                        Map.of("encoding", "jsonParsed", "commitment", "finalized")
                )
        );
        JsonNode accounts = result.path("value");
        if (!accounts.isArray()) {
            return List.of();
        }
        List<ProviderBalance> balances = new ArrayList<>();
        for (JsonNode account : accounts) {
            JsonNode info = account.path("account").path("data").path("parsed").path("info");
            String mint = info.path("mint").asText(null);
            if (mint == null || mint.isBlank()) {
                continue;
            }
            JsonNode tokenAmount = info.path("tokenAmount");
            String rawAmount = tokenAmount.path("amount").asText(null);
            if (rawAmount == null || rawAmount.isBlank()) {
                continue;
            }
            int decimals = tokenAmount.path("decimals").asInt(defaultSplDecimals(mint));
            BigInteger raw;
            try {
                raw = new BigInteger(rawAmount.trim());
            } catch (NumberFormatException ex) {
                log.warn("Solana balance provider skipped unparseable SPL amount: owner={}, mint={}, amount={}",
                        owner, mint, rawAmount);
                continue;
            }
            if (raw.signum() <= 0) {
                continue;
            }
            BigDecimal quantity = Decimal128Support.normalize(
                    new BigDecimal(raw).movePointLeft(Math.max(0, decimals))
            );
            if (quantity.signum() <= 0) {
                continue;
            }
            String resolvedMint = mint.trim();
            String symbol = metadataResolver.resolveSymbol(resolvedMint).orElse(null);
            balances.add(new ProviderBalance(
                    symbol == null || symbol.isBlank() ? resolvedMint : symbol,
                    resolvedMint,
                    decimals,
                    quantity,
                    false
            ));
        }
        return balances;
    }

    private int defaultSplDecimals(String mint) {
        Integer configured = SolanaSplTokenMetadataRegistry.decimals(mint == null ? null : mint.trim());
        return configured == null ? 0 : configured;
    }

    private JsonNode callWithRetry(String method, Object params) {
        RpcEndpointRotator rotator = rotatorsByNetwork.getOrDefault(NetworkId.SOLANA.name(), defaultRotator);
        Exception lastException = null;
        for (int attempt = 0; attempt < rotator.getMaxAttempts(); attempt++) {
            if (attempt > 0) {
                sleep(rotator.retryDelayMs(attempt - 1));
            }
            String endpoint = rotator.getNextEndpoint();
            try {
                String json = rpcClient.call(endpoint, method, params).block();
                JsonNode root = objectMapper.readTree(json);
                JsonNode error = root.path("error");
                if (!error.isMissingNode() && !error.isNull() && !error.isEmpty()) {
                    throw new RpcException("Solana " + method + " error: " + error);
                }
                JsonNode result = root.path("result");
                if (result.isMissingNode() || result.isNull()) {
                    throw new RpcException("Solana " + method + " returned null result");
                }
                return result;
            } catch (Exception failure) {
                lastException = failure;
            }
        }
        throw new RpcException(
                "Solana balance refresh " + method + " failed after " + rotator.getMaxAttempts() + " attempts",
                lastException
        );
    }

    private void sleep(long delayMs) {
        if (delayMs <= 0L) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RpcException("Solana balance refresh interrupted", e);
        }
    }
}
