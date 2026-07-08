package com.walletradar.application.pricing.domain;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/**
 * Canonical request for external price lookup and price-cache access.
 */
public record PriceRequest(
        String normalizedTransactionId,
        NormalizedTransactionSource transactionSource,
        NetworkId networkId,
        String assetContract,
        String assetSymbol,
        Instant occurredAt
) {

    public PriceRequest {
        Objects.requireNonNull(normalizedTransactionId, "normalizedTransactionId");
        Objects.requireNonNull(transactionSource, "transactionSource");
        Objects.requireNonNull(assetSymbol, "assetSymbol");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }

    public String assetKey() {
        String scope = networkId == null ? "GLOBAL" : networkId.name();
        if (assetContract != null && !assetContract.isBlank()) {
            return scope + ":" + assetContract.trim().toLowerCase(Locale.ROOT);
        }
        return scope + ":SYMBOL:" + assetSymbol.trim().toUpperCase(Locale.ROOT);
    }
}
