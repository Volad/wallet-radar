package com.walletradar.ingestion.pipeline.clarification;

import com.walletradar.domain.common.NetworkId;
import org.bson.Document;

import java.util.Locale;
import java.util.Optional;

/**
 * Persisted official Mayan status evidence used for deterministic source-driven bridge pairing.
 */
public record MayanBridgeStatus(
        String sourceTxHash,
        String receivingTxHash,
        NetworkId receivingNetworkId,
        String destinationWalletAddress,
        String service,
        String apiStatus,
        String clientStatus,
        String fromAmount,
        String toAmount,
        String redeemRelayerFee,
        String bridgeFee
) {

    private static final String PROVIDER = "MAYAN";

    public static Optional<MayanBridgeStatus> fromDocument(Document document) {
        if (document == null) {
            return Optional.empty();
        }
        String provider = trimToNull(document.getString("provider"));
        if (provider == null || !PROVIDER.equalsIgnoreCase(provider)) {
            return Optional.empty();
        }
        String receivingTxHash = normalizeHash(document.getString("receivingTxHash"));
        String receivingNetworkId = trimToNull(document.getString("receivingNetworkId"));
        if (receivingTxHash == null || receivingNetworkId == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new MayanBridgeStatus(
                    normalizeHash(document.getString("sourceTxHash")),
                    receivingTxHash,
                    NetworkId.valueOf(receivingNetworkId.toUpperCase(Locale.ROOT)),
                    normalizeAddress(document.getString("destinationWalletAddress")),
                    trimToNull(document.getString("service")),
                    trimToNull(document.getString("apiStatus")),
                    trimToNull(document.getString("clientStatus")),
                    trimToNull(document.getString("fromAmount")),
                    trimToNull(document.getString("toAmount")),
                    trimToNull(document.getString("redeemRelayerFee")),
                    trimToNull(document.getString("bridgeFee"))
            ));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    public Document toDocument() {
        Document document = new Document("provider", PROVIDER);
        putIfPresent(document, "sourceTxHash", sourceTxHash);
        putIfPresent(document, "receivingTxHash", receivingTxHash);
        if (receivingNetworkId != null) {
            document.put("receivingNetworkId", receivingNetworkId.name());
        }
        putIfPresent(document, "destinationWalletAddress", destinationWalletAddress);
        putIfPresent(document, "service", service);
        putIfPresent(document, "apiStatus", apiStatus);
        putIfPresent(document, "clientStatus", clientStatus);
        putIfPresent(document, "fromAmount", fromAmount);
        putIfPresent(document, "toAmount", toAmount);
        putIfPresent(document, "redeemRelayerFee", redeemRelayerFee);
        putIfPresent(document, "bridgeFee", bridgeFee);
        return document;
    }

    public boolean isSettled() {
        if (equalsIgnoreCase(clientStatus, "COMPLETED")) {
            return true;
        }
        String normalizedApiStatus = normalizeStatus(apiStatus);
        return normalizedApiStatus != null
                && (normalizedApiStatus.startsWith("REDEEMED")
                || normalizedApiStatus.startsWith("FULFILLED")
                || normalizedApiStatus.startsWith("COMPLETED"));
    }

    public boolean isSameTransactionEcho() {
        return sourceTxHash != null
                && receivingTxHash != null
                && sourceTxHash.equalsIgnoreCase(receivingTxHash);
    }

    private static void putIfPresent(Document document, String key, String value) {
        if (document != null && key != null && value != null) {
            document.put(key, value);
        }
    }

    private static boolean equalsIgnoreCase(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    private static String normalizeStatus(String value) {
        String trimmed = trimToNull(value);
        return trimmed == null ? null : trimmed.toUpperCase(Locale.ROOT);
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
