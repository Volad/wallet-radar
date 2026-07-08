package com.walletradar.application.normalization.pipeline.classification.baseline;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Canonical row-level baseline snapshot for one normalized transaction.
 */
public record NormalizedRowSnapshot(
        @JsonProperty("_id")
        String id,
        String txHash,
        String source,
        String networkId,
        String walletAddress,
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
        String matchedCounterparty,
        String classificationFingerprint,
        String flowFingerprint,
        String priceableFingerprint,
        List<CanonicalFlowSnapshot> flowsCanonical
) {

    public int flowCount() {
        return flowsCanonical == null ? 0 : flowsCanonical.size();
    }

    public long priceableFlowCount() {
        return flowsCanonical == null ? 0L : flowsCanonical.stream().filter(CanonicalFlowSnapshot::priceable).count();
    }
}
