package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.domain.common.NetworkId;
import org.bson.Document;

import java.util.Locale;
import java.util.Optional;

/**
 * Minimal persisted LI.FI status evidence needed for bridge-pair linkage.
 *
 * <p>{@code toAddress} is the final on-chain recipient reported by LI.FI's {@code /v1/status}
 * response once {@code status=DONE}. A {@code null} value means either the status has not yet
 * resolved to {@code DONE} or the evidence predates this field (legacy/incrementally-cached
 * document) — callers must treat that as "unresolved", never as a false non-match against the
 * tracked-wallet set.</p>
 */
public record LiFiBridgeStatus(
        String sendingTxHash,
        String receivingTxHash,
        NetworkId receivingNetworkId,
        String apiStatus,
        String substatus,
        String toAddress
) {

    private static final String PROVIDER = "LIFI";
    private static final String DONE_STATUS = "DONE";
    private static final String COMPLETED_SUBSTATUS = "COMPLETED";

    /**
     * Legacy/incrementally-cached call sites predating the {@code toAddress} field.
     */
    public LiFiBridgeStatus(
            String sendingTxHash,
            String receivingTxHash,
            NetworkId receivingNetworkId,
            String apiStatus,
            String substatus
    ) {
        this(sendingTxHash, receivingTxHash, receivingNetworkId, apiStatus, substatus, null);
    }

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
                    trimToNull(document.getString("substatus")),
                    normalizeAddress(document.getString("toAddress"))
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
        if (toAddress != null) {
            document.put("toAddress", toAddress);
        }
        return document;
    }

    public boolean isSameTransactionEcho() {
        return sendingTxHash != null
                && receivingTxHash != null
                && sendingTxHash.equalsIgnoreCase(receivingTxHash);
    }

    /**
     * True only for a fully settled, non-partial LI.FI transfer — {@code DONE}+{@code PARTIAL} is a
     * materially different (out-of-scope) case and must not be treated as a completed settlement.
     */
    public boolean isDoneAndCompleted() {
        return DONE_STATUS.equalsIgnoreCase(apiStatus) && COMPLETED_SUBSTATUS.equalsIgnoreCase(substatus);
    }

    /**
     * {@code false} for a not-yet-resolved status or a legacy document predating this field —
     * both must be treated as "unresolved", not as a false non-match.
     */
    public boolean hasResolvedToAddress() {
        return toAddress != null && !toAddress.isBlank();
    }

    private static String normalizeHash(String value) {
        String trimmed = trimToNull(value);
        return trimmed == null ? null : trimmed.toLowerCase(Locale.ROOT);
    }

    private static String normalizeAddress(String value) {
        return normalizeHash(value);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
