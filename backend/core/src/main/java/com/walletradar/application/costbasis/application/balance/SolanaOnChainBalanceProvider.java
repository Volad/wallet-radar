package com.walletradar.application.costbasis.application.balance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.application.normalization.pipeline.solana.JupiterSplTokenMetadataResolver;
import com.walletradar.application.normalization.pipeline.solana.SolanaSplTokenMetadataRegistry;
import com.walletradar.domain.common.Decimal128Support;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.platform.networks.RpcEndpointRotator;
import com.walletradar.platform.networks.RpcException;
import com.walletradar.platform.networks.solana.SolanaRpcClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Solana on-chain balance provider (ADR-067): enumerates native SOL (lamports) and SPL token
 * balances for a wallet via the shared {@link SolanaRpcClient}.
 *
 * <ul>
 *   <li>Native SOL: {@code getBalance} → lamports at 9 decimals, booked as {@code NATIVE:SOLANA}.</li>
 *   <li>SPL tokens: {@code getTokenAccountsByOwner} with the SPL Token program id and
 *       {@code jsonParsed} encoding → one balance per mint (amount + decimals from the parsed
 *       token account). Symbols resolve via {@link JupiterSplTokenMetadataResolver} (config-seeded
 *       registry first, then the free Jupiter Tokens API, cached); unknown mints keep the mint as
 *       the symbol.</li>
 * </ul>
 *
 * <p>Endpoint wiring mirrors {@code SolanaNetworkAdapter} ({@code solanaRotatorsByNetwork} +
 * {@code solanaDefaultRpcEndpointRotator}). Base58 mint/owner addresses are case-sensitive and are
 * never lowercased.</p>
 */
@Component
@Slf4j
public class SolanaOnChainBalanceProvider implements OnChainBalanceProvider {

    /** SPL Token program id — owner of every classic SPL token account. */
    private static final String SPL_TOKEN_PROGRAM_ID = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA";
    private static final int SOL_DECIMALS = 9;
    private static final String NATIVE_SOL_SYMBOL = "SOL";
    /**
     * Booked as the network-native accounting identity (matches how {@code SolanaNormalizedTransactionBuilder}
     * books native SOL via the wSOL mint, which the native-alias set maps to {@code NATIVE:SOLANA}).
     */
    private static final String NATIVE_SOL_IDENTITY = "NATIVE:SOLANA";

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
            balances.add(new ProviderBalance(NATIVE_SOL_SYMBOL, NATIVE_SOL_IDENTITY, SOL_DECIMALS, solQuantity, true));
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
                new BigDecimal(value.bigIntegerValue()).movePointLeft(SOL_DECIMALS)
        );
    }

    private List<ProviderBalance> fetchSplTokens(String owner) {
        JsonNode result = callWithRetry(
                "getTokenAccountsByOwner",
                List.of(
                        owner,
                        Map.of("programId", SPL_TOKEN_PROGRAM_ID),
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
