package com.walletradar.platform.networks.solana.metaplex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.platform.networks.RpcEndpointRotator;
import com.walletradar.platform.networks.solana.SolanaRpcClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link MetaplexMetadataClient} backed by the shared Solana JSON-RPC transport.
 *
 * <p>Derives the Metaplex metadata PDA from the mint ({@link MetaplexMetadataPda}), reads the raw
 * account via {@code getAccountInfo} (base64), and Borsh-decodes {@code name}/{@code symbol}
 * ({@link MetaplexMetadataDecoder}). Endpoint wiring mirrors {@code SolanaOnChainBalanceProvider}
 * / {@code SolanaLpChainClient} ({@code solanaRotatorsByNetwork} + {@code
 * solanaDefaultRpcEndpointRotator}). Base58 pubkeys are case-sensitive and never lowercased.</p>
 *
 * <p>Never throws: a missing metadata account ({@code value == null}), transport error, or decode
 * failure resolves to {@link Optional#empty()}.</p>
 */
@Component
@Slf4j
public class RpcMetaplexMetadataClient implements MetaplexMetadataClient {

    private final SolanaRpcClient rpcClient;
    private final Map<String, RpcEndpointRotator> rotatorsByNetwork;
    private final RpcEndpointRotator defaultRotator;
    private final ObjectMapper objectMapper;

    public RpcMetaplexMetadataClient(
            SolanaRpcClient rpcClient,
            @Qualifier("solanaRotatorsByNetwork") Map<String, RpcEndpointRotator> rotatorsByNetwork,
            @Qualifier("solanaDefaultRpcEndpointRotator") RpcEndpointRotator defaultRotator,
            ObjectMapper objectMapper) {
        this.rpcClient = rpcClient;
        this.rotatorsByNetwork = rotatorsByNetwork;
        this.defaultRotator = defaultRotator;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<MetaplexTokenMetadata> fetchMetadata(String mint) {
        if (mint == null || mint.isBlank()) {
            return Optional.empty();
        }
        Optional<String> metadataPda = MetaplexMetadataPda.metadataAddress(mint.trim());
        if (metadataPda.isEmpty()) {
            return Optional.empty();
        }
        byte[] accountData = getAccountData(metadataPda.get());
        if (accountData == null) {
            return Optional.empty();
        }
        try {
            return MetaplexMetadataDecoder.decode(accountData);
        } catch (RuntimeException ex) {
            log.debug("Metaplex metadata decode failed for mint {}: {}", mint, ex.getMessage());
            return Optional.empty();
        }
    }

    private byte[] getAccountData(String pubkey) {
        JsonNode result = call("getAccountInfo",
                List.of(pubkey, Map.of("encoding", "base64", "commitment", "finalized")));
        if (result == null) {
            return null;
        }
        JsonNode value = result.path("value");
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        return decodeBase64Data(value.path("data"));
    }

    private byte[] decodeBase64Data(JsonNode dataNode) {
        String encoded = null;
        if (dataNode.isArray() && !dataNode.isEmpty()) {
            encoded = dataNode.get(0).asText(null);
        } else if (dataNode.isTextual()) {
            encoded = dataNode.asText(null);
        }
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            return Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private JsonNode call(String method, Object params) {
        RpcEndpointRotator rotator = rotatorsByNetwork.getOrDefault(NetworkId.SOLANA.name(), defaultRotator);
        for (int attempt = 0; attempt < rotator.getMaxAttempts(); attempt++) {
            if (attempt > 0) {
                sleep(rotator.retryDelayMs(attempt - 1));
            }
            String endpoint = rotator.getNextEndpoint();
            try {
                String json = rpcClient.call(endpoint, method, params).block();
                if (json == null) {
                    continue;
                }
                JsonNode root = objectMapper.readTree(json);
                JsonNode error = root.path("error");
                if (!error.isMissingNode() && !error.isNull() && !error.isEmpty()) {
                    log.debug("Solana {} rpc error: {}", method, error);
                    continue;
                }
                JsonNode result = root.path("result");
                if (result.isMissingNode() || result.isNull()) {
                    continue;
                }
                return result;
            } catch (Exception failure) {
                log.debug("Solana {} rpc attempt {} failed: {}", method, attempt, failure.toString());
            }
        }
        return null;
    }

    private void sleep(long delayMs) {
        if (delayMs <= 0L) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
