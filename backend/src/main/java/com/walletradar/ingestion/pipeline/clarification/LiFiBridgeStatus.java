package com.walletradar.ingestion.pipeline.clarification;

import com.walletradar.domain.common.NetworkId;
import org.bson.Document;

import java.util.Locale;
import java.util.Optional;

/**
 * Minimal persisted LI.FI status evidence needed for bridge-pair linkage.
 */
public record LiFiBridgeStatus(
        String sendingTxHash,
        String receivingTxHash,
        NetworkId receivingNetworkId,
        String apiStatus,
        String substatus
) {

    private static final String PROVIDER = "LIFI";

    public static Optional<LiFiBridgeStatus> fromDocument(Document document) {
        if (document == null) {
            return Optional.empty();
        }
        String provider = trimToNull(document.getString("provider"));
        if (provider == null || !PROVIDER.equalsIgnoreCase(provider)) {
            return Optional.empty();
        }
        String sendingTxHash = normalizeHash(document.getString("sendingTxHash"));
        String receivingTxHash = normalizeHash(document.getString("receivingTxHash"));
        String receivingNetwork = trimToNull(document.getString("receivingNetworkId"));
        if (receivingTxHash == null || receivingNetwork == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new LiFiBridgeStatus(
                    sendingTxHash,
                    receivingTxHash,
                    NetworkId.valueOf(receivingNetwork.toUpperCase(Locale.ROOT)),
                    trimToNull(document.getString("apiStatus")),
                    trimToNull(document.getString("substatus"))
            ));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    public Document toDocument() {
        Document document = new Document("provider", PROVIDER);
        if (sendingTxHash != null) {
            document.put("sendingTxHash", sendingTxHash);
        }
        if (receivingTxHash != null) {
            document.put("receivingTxHash", receivingTxHash);
        }
        if (receivingNetworkId != null) {
            document.put("receivingNetworkId", receivingNetworkId.name());
        }
        if (apiStatus != null) {
            document.put("apiStatus", apiStatus);
        }
        if (substatus != null) {
            document.put("substatus", substatus);
        }
        return document;
    }

    public boolean isSameTransactionEcho() {
        return sendingTxHash != null
                && receivingTxHash != null
                && sendingTxHash.equalsIgnoreCase(receivingTxHash);
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
