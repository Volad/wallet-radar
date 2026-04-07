package com.walletradar.ingestion.pipeline.clarification;

import com.fasterxml.jackson.databind.JsonNode;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.ingestion.config.OnChainClarificationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Official Mayan source-tx status lookup for deterministic fee-bearing bridge settlement pairing.
 */
@Service
public class MayanStatusGateway {

    private static final Logger log = LoggerFactory.getLogger(MayanStatusGateway.class);
    private static final Map<String, NetworkId> NETWORK_BY_MAYAN_CHAIN = Map.of(
            "2", NetworkId.ETHEREUM,
            "4", NetworkId.BSC,
            "5", NetworkId.POLYGON,
            "6", NetworkId.AVALANCHE,
            "23", NetworkId.ARBITRUM,
            "24", NetworkId.OPTIMISM,
            "30", NetworkId.BASE
    );

    private final WebClient webClient;
    private final OnChainClarificationProperties clarificationProperties;

    public MayanStatusGateway(
            WebClient.Builder webClientBuilder,
            OnChainClarificationProperties clarificationProperties
    ) {
        this.webClient = webClientBuilder.baseUrl(clarificationProperties.getMayanStatus().getBaseUrl()).build();
        this.clarificationProperties = clarificationProperties;
    }

    public Optional<MayanBridgeStatus> fetchBridgeStatus(String txHash) {
        if (!clarificationProperties.getMayanStatus().isEnabled()) {
            return Optional.empty();
        }
        String normalizedTxHash = normalizeHash(txHash);
        if (normalizedTxHash == null) {
            return Optional.empty();
        }
        try {
            JsonNode response = webClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/v3/swap/trx/{txHash}").build(normalizedTxHash))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofMillis(Math.max(1L, clarificationProperties.getMayanStatus().getTimeoutMs())));
            if (response == null || response.isMissingNode() || response.isNull()) {
                return Optional.empty();
            }
            return parse(response);
        } catch (WebClientResponseException error) {
            log.debug("Mayan status lookup failed for txHash={} with status={}", normalizedTxHash, error.getStatusCode());
            return Optional.empty();
        } catch (RuntimeException error) {
            log.debug("Mayan status lookup failed for txHash={}: {}", normalizedTxHash, error.getMessage());
            return Optional.empty();
        }
    }

    private Optional<MayanBridgeStatus> parse(JsonNode response) {
        String receivingTxHash = firstHash(response, "redeemTxHash", "fulfillTxHash");
        NetworkId receivingNetworkId = resolveReceivingNetworkId(response).orElse(null);
        if (receivingTxHash == null || receivingNetworkId == null) {
            return Optional.empty();
        }
        return Optional.of(new MayanBridgeStatus(
                firstHash(response, "sourceTxHash", "createTxHash"),
                receivingTxHash,
                receivingNetworkId,
                normalizeAddress(response.path("destAddress").asText(null)),
                trimToNull(response.path("service").asText(null)),
                trimToNull(response.path("status").asText(null)),
                trimToNull(response.path("clientStatus").asText(null)),
                trimToNull(response.path("fromAmount").asText(null)),
                trimToNull(response.path("toAmount").asText(null)),
                trimToNull(response.path("redeemRelayerFee").asText(null)),
                trimToNull(response.path("bridgeFee").asText(null))
        ));
    }

    private Optional<NetworkId> resolveReceivingNetworkId(JsonNode response) {
        String destChain = trimToNull(response.path("destChain").asText(null));
        if (destChain != null) {
            NetworkId byChain = NETWORK_BY_MAYAN_CHAIN.get(destChain);
            if (byChain != null) {
                return Optional.of(byChain);
            }
        }
        JsonNode txs = response.path("txs");
        if (txs.isArray()) {
            for (JsonNode tx : txs) {
                if (!hasGoal(tx.path("goals"), "SETTLE")) {
                    continue;
                }
                NetworkId byUrl = resolveNetworkFromUrl(tx.path("scannerUrl").asText(null)).orElse(null);
                if (byUrl != null) {
                    return Optional.of(byUrl);
                }
            }
        }
        return resolveNetworkFromUrl(response.path("toTokenScannerUrl").asText(null));
    }

    private boolean hasGoal(JsonNode goals, String expectedGoal) {
        if (!goals.isArray() || expectedGoal == null) {
            return false;
        }
        for (JsonNode goal : goals) {
            if (expectedGoal.equalsIgnoreCase(goal.asText(null))) {
                return true;
            }
        }
        return false;
    }

    private Optional<NetworkId> resolveNetworkFromUrl(String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return Optional.empty();
        }
        try {
            URI uri = URI.create(trimmed);
            String host = trimToNull(uri.getHost());
            if (host == null) {
                return Optional.empty();
            }
            String normalizedHost = host.toLowerCase(Locale.ROOT);
            if (normalizedHost.contains("arbiscan")) {
                return Optional.of(NetworkId.ARBITRUM);
            }
            if (normalizedHost.contains("snowtrace")) {
                return Optional.of(NetworkId.AVALANCHE);
            }
            if (normalizedHost.contains("basescan") || normalizedHost.contains("base.blockscout")) {
                return Optional.of(NetworkId.BASE);
            }
            if (normalizedHost.contains("bscscan")) {
                return Optional.of(NetworkId.BSC);
            }
            if (normalizedHost.contains("polygonscan")) {
                return Optional.of(NetworkId.POLYGON);
            }
            if (normalizedHost.contains("optimism") || normalizedHost.contains("optimistic.etherscan")) {
                return Optional.of(NetworkId.OPTIMISM);
            }
            if (normalizedHost.contains("mantlescan")) {
                return Optional.of(NetworkId.MANTLE);
            }
            if (normalizedHost.contains("etherscan")) {
                return Optional.of(NetworkId.ETHEREUM);
            }
            return Optional.empty();
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private String firstHash(JsonNode response, String... fieldNames) {
        if (response == null || fieldNames == null) {
            return null;
        }
        for (String fieldName : fieldNames) {
            String candidate = normalizeHash(response.path(fieldName).asText(null));
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    private static String normalizeHash(String value) {
        String trimmed = trimToNull(value);
        return trimmed == null ? null : trimmed.toLowerCase(Locale.ROOT);
    }

    private static String normalizeAddress(String value) {
        String trimmed = trimToNull(value);
        return trimmed == null ? null : trimmed.toLowerCase(Locale.ROOT);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
