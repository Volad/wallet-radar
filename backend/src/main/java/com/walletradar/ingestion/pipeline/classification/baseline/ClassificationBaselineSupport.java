package com.walletradar.ingestion.pipeline.classification.baseline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.pricing.application.PriceableFlowPolicy;
import org.bson.Document;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Canonical serialization and fingerprint helpers for ADR-001 baseline tooling.
 */
public final class ClassificationBaselineSupport {

    private static final ObjectMapper CANONICAL_OBJECT_MAPPER = new ObjectMapper()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private static final Comparator<CanonicalFlowSnapshot> CANONICAL_FLOW_COMPARATOR =
            Comparator.comparing(CanonicalFlowSnapshot::assetSymbol, Comparator.nullsFirst(String::compareTo))
                    .thenComparing(CanonicalFlowSnapshot::assetAddress, Comparator.nullsFirst(String::compareTo))
                    .thenComparing(CanonicalFlowSnapshot::networkId, Comparator.nullsFirst(String::compareTo))
                    .thenComparing(CanonicalFlowSnapshot::direction, Comparator.nullsFirst(String::compareTo))
                    .thenComparing(CanonicalFlowSnapshot::assetRole, Comparator.nullsFirst(String::compareTo))
                    .thenComparing(CanonicalFlowSnapshot::quantity, Comparator.nullsFirst(String::compareTo))
                    .thenComparing(CanonicalFlowSnapshot::priceable)
                    .thenComparing(CanonicalFlowSnapshot::counterparty, Comparator.nullsFirst(String::compareTo))
                    .thenComparing(CanonicalFlowSnapshot::continuityGroup, Comparator.nullsFirst(String::compareTo))
                    .thenComparing(CanonicalFlowSnapshot::notes, Comparator.nullsFirst(String::compareTo))
                    .thenComparingInt(CanonicalFlowSnapshot::index);

    private ClassificationBaselineSupport() {
    }

    public static List<String> canonicalMissingReasons(List<String> reasons) {
        if (reasons == null || reasons.isEmpty()) {
            return List.of();
        }
        return reasons.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .sorted()
                .toList();
    }

    public static List<CanonicalFlowSnapshot> canonicalFlows(NormalizedTransaction transaction) {
        if (transaction == null || transaction.getFlows() == null || transaction.getFlows().isEmpty()) {
            return List.of();
        }
        List<CanonicalFlowSnapshot> snapshots = new ArrayList<>();
        List<NormalizedTransaction.Flow> flows = transaction.getFlows();
        for (int i = 0; i < flows.size(); i++) {
            NormalizedTransaction.Flow flow = flows.get(i);
            snapshots.add(new CanonicalFlowSnapshot(
                    i,
                    normalizeNullable(flow.getAssetSymbol()),
                    normalizeAddress(flow.getAssetContract()),
                    transaction.getNetworkId() == null ? null : transaction.getNetworkId().name(),
                    deriveDirection(flow.getQuantityDelta()),
                    flow.getRole() == null ? null : flow.getRole().name(),
                    decimalString(flow.getQuantityDelta()),
                    PriceableFlowPolicy.requiresMarketPrice(transaction, flow),
                    null,
                    null,
                    normalizeNullable(flow.getInferenceReason())
            ));
        }
        return snapshots.stream().sorted(CANONICAL_FLOW_COMPARATOR).toList();
    }

    public static String flowFingerprint(List<CanonicalFlowSnapshot> flows) {
        return sha256Hex(canonicalJson(flows == null ? List.of() : flows));
    }

    public static String priceableFingerprint(List<CanonicalFlowSnapshot> flows) {
        List<CanonicalFlowSnapshot> priceable = flows == null ? List.of() : flows.stream()
                .filter(CanonicalFlowSnapshot::priceable)
                .sorted(CANONICAL_FLOW_COMPARATOR)
                .toList();
        return sha256Hex(canonicalJson(priceable));
    }

    public static String classificationFingerprint(
            String type,
            String status,
            String protocolName,
            String protocolVersion,
            boolean excludedFromAccounting,
            boolean clarificationEvidencePresent,
            boolean fullReceiptPresent,
            boolean relatedLifecycleEvidencePresent,
            List<String> missingDataReasons,
            String correlationId,
            String matchedCounterparty
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", type);
        payload.put("status", status);
        payload.put("protocolName", protocolName);
        payload.put("protocolVersion", protocolVersion);
        payload.put("excludedFromAccounting", excludedFromAccounting);
        payload.put("clarificationEvidencePresent", clarificationEvidencePresent);
        payload.put("fullReceiptPresent", fullReceiptPresent);
        payload.put("relatedLifecycleEvidencePresent", relatedLifecycleEvidencePresent);
        payload.put("missingDataReasons", canonicalMissingReasons(missingDataReasons));
        payload.put("correlationId", correlationId);
        payload.put("matchedCounterparty", matchedCounterparty);
        return sha256Hex(canonicalJson(payload));
    }

    public static String canonicalJson(Object value) {
        try {
            return CANONICAL_OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize canonical baseline payload", e);
        }
    }

    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    public static Map<String, Object> aggregateKey(Object... keyValues) {
        Map<String, Object> key = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            key.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return key;
    }

    public static String decimalString(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    public static String normalizeNullable(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public static String normalizeAddress(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim().toLowerCase(Locale.ROOT);
        return trimmed.isEmpty() ? null : trimmed;
    }

    public static boolean hasClarificationEvidence(Document clarificationEvidence) {
        return clarificationEvidence != null && !clarificationEvidence.isEmpty();
    }

    public static boolean hasFullReceiptEvidence(Document clarificationEvidence) {
        if (clarificationEvidence == null || clarificationEvidence.isEmpty()) {
            return false;
        }
        Object fullReceipt = clarificationEvidence.get("fullReceipt");
        if (fullReceipt instanceof Document document && !document.isEmpty()) {
            return true;
        }
        Object receipt = clarificationEvidence.get("receipt");
        if (receipt instanceof Document document && !document.isEmpty()) {
            return true;
        }
        Object transfers = clarificationEvidence.get("transfers");
        return transfers instanceof Document document && !document.isEmpty();
    }

    private static String deriveDirection(BigDecimal quantityDelta) {
        if (quantityDelta == null) {
            return null;
        }
        return quantityDelta.signum() < 0 ? "OUT" : "IN";
    }
}
