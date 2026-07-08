package com.walletradar.application.liquiditypools.enrichment;

import com.fasterxml.jackson.databind.JsonNode;
import com.walletradar.application.pricing.application.ExternalPricingEndpointProperties;
import com.walletradar.domain.common.NetworkId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Queries the GMX Subsquid GraphQL endpoint for cumulative fee data per pool.
 *
 * <p>GMX V2 LP fees auto-compound into pool token price; there are no discrete LP_FEE_CLAIM
 * events. The {@code CollectedFeesInfo} entity in the Subsquid schema tracks
 * {@code cumulativeFeeUsdPerPoolValue} — the total fees collected per USD of pool value
 * since pool inception, at 10^30 scale (30-decimal fixed-point, same as GMX price format).
 *
 * <p>To compute earned fees for a user:
 * {@code earnedFees = (current - entry) / 10^30 × depositedUsd}
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GmxCollectedFeesReader {

    /** Scale used by GMX for fee-per-pool-value (30 decimal places). */
    public static final BigDecimal FEE_SCALE = BigDecimal.TEN.pow(30);

    private final WebClient.Builder webClientBuilder;
    private final ExternalPricingEndpointProperties endpointProperties;

    /**
     * Returns the {@code cumulativeFeeUsdPerPoolValue} for {@code marketAddress} at the
     * latest hourly bucket at or before {@code at}.  Returns {@code Optional.empty()} when
     * the network is unsupported or the request fails.
     *
     * <p>The returned value is in 10^30 scale (divide by {@link #FEE_SCALE} to get fraction).
     */
    public Optional<BigDecimal> getCumulativeFeePerPoolValue(
            NetworkId networkId,
            String marketAddress,
            Instant at
    ) {
        String graphqlUrl = endpointProperties.getGmxSquidGraphQl().get(networkId);
        if (graphqlUrl == null || marketAddress == null || marketAddress.isBlank()) {
            return Optional.empty();
        }
        long epochSec = at != null ? at.getEpochSecond() : Instant.now().getEpochSecond();
        // Round down to nearest hour bucket (GMX indexes hourly).
        long hourBucket = (epochSec / 3600) * 3600;

        String query = buildQuery(marketAddress, hourBucket);
        try {
            JsonNode response = webClientBuilder
                    .baseUrl(graphqlUrl)
                    .build()
                    .post()
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("query", query))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(endpointProperties.getRequestTimeout());

            if (response == null || response.isMissingNode()) {
                return Optional.empty();
            }
            JsonNode data = response.path("data").path("collectedFeesInfos");
            if (!data.isArray() || data.isEmpty()) {
                log.debug("GmxCollectedFeesReader: no data for market={} at={}", marketAddress, at);
                return Optional.empty();
            }
            JsonNode record = data.get(0);
            String rawValue = record.path("cumulativeFeeUsdPerPoolValue").asText(null);
            if (rawValue == null || rawValue.isBlank() || "0".equals(rawValue)) {
                return Optional.empty();
            }
            return Optional.of(new BigDecimal(rawValue));
        } catch (WebClientResponseException ex) {
            log.warn("GmxCollectedFeesReader: HTTP {} for market={} at={}", ex.getStatusCode(), marketAddress, at);
            return Optional.empty();
        } catch (RuntimeException ex) {
            log.warn("GmxCollectedFeesReader: error for market={} at={}: {}", marketAddress, at, ex.getMessage());
            return Optional.empty();
        }
    }

    private static String buildQuery(String marketAddress, long hourBucket) {
        // Use address_containsInsensitive because Subsquid stores checksummed (EIP-55) addresses
        // while our system normalises all addresses to lowercase.
        return """
                {
                  collectedFeesInfos(
                    where: {
                      address_containsInsensitive: "%s"
                      period_eq: "1h"
                      timestampGroup_lte: %d
                    }
                    orderBy: timestampGroup_DESC
                    limit: 1
                  ) {
                    timestampGroup
                    cumulativeFeeUsdPerPoolValue
                  }
                }
                """.formatted(marketAddress, hourBucket);
    }
}
