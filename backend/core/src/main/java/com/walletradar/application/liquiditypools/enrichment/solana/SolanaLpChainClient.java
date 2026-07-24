package com.walletradar.application.liquiditypools.enrichment.solana;

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
 * Read-only Solana JSON-RPC helper for LP on-chain enrichment. Wraps {@code getAccountInfo} and a
 * targeted {@code getProgramAccounts} memcmp lookup used to resolve DLMM / CLMM position state.
 *
 * <p>Endpoint wiring mirrors {@code SolanaOnChainBalanceProvider} ({@code solanaRotatorsByNetwork}
 * + {@code solanaDefaultRpcEndpointRotator}). Base58 pubkeys are case-sensitive and never lowercased.
 * Never throws: transport/parse failures and missing accounts resolve to {@link Optional#empty()}.</p>
 */
@Component
@Slf4j
public class SolanaLpChainClient {

    private final SolanaRpcClient rpcClient;
    private final Map<String, RpcEndpointRotator> rotatorsByNetwork;
    private final RpcEndpointRotator defaultRotator;
    private final ObjectMapper objectMapper;

    public SolanaLpChainClient(
            SolanaRpcClient rpcClient,
            @Qualifier("solanaRotatorsByNetwork") Map<String, RpcEndpointRotator> rotatorsByNetwork,
            @Qualifier("solanaDefaultRpcEndpointRotator") RpcEndpointRotator defaultRotator,
            ObjectMapper objectMapper
    ) {
        this.rpcClient = rpcClient;
        this.rotatorsByNetwork = rotatorsByNetwork;
        this.defaultRotator = defaultRotator;
        this.objectMapper = objectMapper;
    }

    /**
     * Reads a raw account via {@code getAccountInfo} (base64). Returns empty when the account does
     * not exist ({@code value == null}, i.e. the position was closed and the account was reclaimed).
     */
    public Optional<OnChainAccount> getAccountInfo(String pubkey) {
        if (pubkey == null || pubkey.isBlank()) {
            return Optional.empty();
        }
        JsonNode result = call("getAccountInfo",
                List.of(pubkey.trim(), Map.of("encoding", "base64", "commitment", "finalized")));
        if (result == null) {
            return Optional.empty();
        }
        JsonNode value = result.path("value");
        if (value.isMissingNode() || value.isNull()) {
            return Optional.empty();
        }
        String owner = value.path("owner").asText(null);
        byte[] data = decodeBase64Data(value.path("data"));
        if (data == null) {
            return Optional.empty();
        }
        return Optional.of(new OnChainAccount(owner, data));
    }

    /**
     * Reads the mint held by an SPL token account (e.g. a Raydium CLMM position NFT account) via
     * {@code getAccountInfo} with {@code jsonParsed} encoding.
     */
    public Optional<String> getTokenAccountMint(String pubkey) {
        if (pubkey == null || pubkey.isBlank()) {
            return Optional.empty();
        }
        JsonNode result = call("getAccountInfo",
                List.of(pubkey.trim(), Map.of("encoding", "jsonParsed", "commitment", "finalized")));
        if (result == null) {
            return Optional.empty();
        }
        JsonNode info = result.path("value").path("data").path("parsed").path("info");
        String mint = info.path("mint").asText(null);
        return mint == null || mint.isBlank() ? Optional.empty() : Optional.of(mint.trim());
    }

    /**
     * Targeted {@code getProgramAccounts} lookup returning the (base64-decoded) data of the first
     * account owned by {@code programId} whose bytes at {@code memcmpOffset} equal
     * {@code memcmpBase58}. Used to resolve a Raydium CLMM {@code PersonalPositionState} from its
     * position-NFT mint. A {@code dataSlice} bounds the response size; the memcmp filter makes the
     * server-side scan selective (a mint pubkey is unique, so at most one account matches).
     */
    public Optional<byte[]> findProgramAccountData(
            String programId, int memcmpOffset, String memcmpBase58, int sliceLength) {
        if (programId == null || memcmpBase58 == null || memcmpBase58.isBlank()) {
            return Optional.empty();
        }
        Map<String, Object> config = Map.of(
                "encoding", "base64",
                "commitment", "finalized",
                "dataSlice", Map.of("offset", 0, "length", Math.max(1, sliceLength)),
                "filters", List.of(Map.of("memcmp", Map.of("offset", memcmpOffset, "bytes", memcmpBase58.trim())))
        );
        JsonNode result = call("getProgramAccounts", List.of(programId.trim(), config));
        if (result == null || !result.isArray() || result.isEmpty()) {
            return Optional.empty();
        }
        byte[] data = decodeBase64Data(result.get(0).path("account").path("data"));
        return Optional.ofNullable(data);
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

    /** A raw on-chain account: owning program id and its (optionally sliced) data bytes. */
    public record OnChainAccount(String ownerProgram, byte[] data) {
    }
}
