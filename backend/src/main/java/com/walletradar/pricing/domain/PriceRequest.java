package com.walletradar.pricing.domain;

import com.walletradar.domain.common.NetworkId;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/**
 * Canonical request for external price lookup and price-cache access.
 */
public record PriceRequest(
        String normalizedTransactionId,
        NetworkId networkId,
        String assetContract,
        String assetSymbol,
        Instant occurredAt
) {

    public PriceRequest {
        Objects.requireNonNull(normalizedTransactionId, "normalizedTransactionId");
        Objects.requireNonNull(networkId, "networkId");
        Objects.requireNonNull(assetSymbol, "assetSymbol");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }

    public String assetKey() {
        if (assetContract != null && !assetContract.isBlank()) {
            return networkId + ":" + assetContract.trim().toLowerCase(Locale.ROOT);
        }
        return networkId + ":SYMBOL:" + assetSymbol.trim().toUpperCase(Locale.ROOT);
    }
}
