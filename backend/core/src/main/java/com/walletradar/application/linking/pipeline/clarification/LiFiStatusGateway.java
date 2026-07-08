package com.walletradar.application.linking.pipeline.clarification;

import com.fasterxml.jackson.databind.JsonNode;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.platform.networks.config.IngestionNetworkProperties;
import com.walletradar.application.normalization.config.OnChainClarificationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Official LI.FI status lookup for route-proven bridge starts.
 */
@Service
public class LiFiStatusGateway {

    private static final Logger log = LoggerFactory.getLogger(LiFiStatusGateway.class);

    private final WebClient webClient;
    private final OnChainClarificationProperties clarificationProperties;
    private final Map<Integer, NetworkId> networkByChainId;

    public LiFiStatusGateway(
            WebClient.Builder webClientBuilder,
            OnChainClarificationProperties clarificationProperties,
            IngestionNetworkProperties ingestionNetworkProperties
    ) {
        this.webClient = webClientBuilder.baseUrl(clarificationProperties.getLiFiStatus().getBaseUrl()).build();
        this.clarificationProperties = clarificationProperties;
        this.networkByChainId = buildNetworkByChainId(ingestionNetworkProperties);
    }

    public Optional<LiFiBridgeStatus> fetchBridgeStatus(String txHash) {
        if (!clarificationProperties.getLiFiStatus().isEnabled()) {
            return Optional.empty();
        }
        String normalizedTxHash = normalizeHash(txHash);
        if (normalizedTxHash == null) {
            return Optional.empty();
        }

        try {
            JsonNode response = webClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/v1/status").queryParam("txHash", normalizedTxHash).build())
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofMillis(Math.max(1L, clarificationProperties.getLiFiStatus().getTimeoutMs())));
            if (response == null) {
                return Optional.empty();
            }
            return parse(response);
        } catch (WebClientResponseException error) {
            log.debug("LI.FI status lookup failed for txHash={} with status={}", normalizedTxHash, error.getStatusCode());
            return Optional.empty();
        } catch (RuntimeException error) {
            log.debug("LI.FI status lookup failed for txHash={}: {}", normalizedTxHash, error.getMessage());
            return Optional.empty();
        }
    }

    private Optional<LiFiBridgeStatus> parse(JsonNode response) {
        JsonNode receiving = response.path("receiving");
        String receivingTxHash = normalizeHash(receiving.path("txHash").asText(null));
        if (receivingTxHash == null) {
            return Optional.empty();
        }
        Integer chainId = receiving.path("chainId").canConvertToInt()
                ? receiving.path("chainId").intValue()
                : null;
        NetworkId receivingNetworkId = chainId == null ? null : networkByChainId.get(chainId);
        if (receivingNetworkId == null) {
            return Optional.empty();
        }
        return Optional.of(new LiFiBridgeStatus(
                normalizeHash(response.path("sending").path("txHash").asText(null)),
                receivingTxHash,
                receivingNetworkId,
                trimToNull(response.path("status").asText(null)),
                trimToNull(response.path("substatus").asText(null)),
                normalizeHash(response.path("toAddress").asText(null))
        ));
    }

    private Map<Integer, NetworkId> buildNetworkByChainId(IngestionNetworkProperties properties) {
        java.util.LinkedHashMap<Integer, NetworkId> resolved = new java.util.LinkedHashMap<>();
        if (properties == null || properties.getNetwork() == null) {
            return resolved;
        }
        for (Map.Entry<String, IngestionNetworkProperties.NetworkIngestionEntry> entry : properties.getNetwork().entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            String chainId = trimToNull(entry.getValue().getChainId());
            if (chainId == null) {
                continue;
            }
            try {
                resolved.put(Integer.parseInt(chainId), NetworkId.valueOf(entry.getKey().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // Ignore networks not represented in NetworkId.
            }
        }
        return Map.copyOf(resolved);
    }

    private static String normalizeHash(String value) {
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
