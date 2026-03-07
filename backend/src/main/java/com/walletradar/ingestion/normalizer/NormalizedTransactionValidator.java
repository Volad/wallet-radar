package com.walletradar.ingestion.normalizer;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Validation helpers for canonical normalized transactions.
 */
public final class NormalizedTransactionValidator {

    private NormalizedTransactionValidator() {
    }

    public static List<String> missingDataReasons(
            NormalizedTransactionType type,
            List<NormalizedTransaction.Flow> legs
    ) {
        Set<String> reasons = new LinkedHashSet<>();
        if (type == NormalizedTransactionType.APPROVAL) {
            return List.of();
        }
        if (legs == null || legs.isEmpty()) {
            if (type == NormalizedTransactionType.UNCLASSIFIED) {
                reasons.add("NO_CLASSIFICATION_EVIDENCE");
            } else {
                reasons.add("MISSING_LEGS");
            }
            return List.copyOf(reasons);
        }
        boolean hasInbound = false;
        boolean hasOutbound = false;
        for (NormalizedTransaction.Flow leg : legs) {
            if (leg.getAssetContract() == null || leg.getAssetContract().isBlank()) {
                reasons.add("MISSING_ASSET_CONTRACT");
            }
            BigDecimal qty = leg.getQuantityDelta();
            if (qty == null || qty.signum() == 0) {
                reasons.add("MISSING_QUANTITY");
                continue;
            }
            if (qty.signum() > 0) {
                hasInbound = true;
            } else {
                hasOutbound = true;
            }
        }
        if (type == NormalizedTransactionType.SWAP && (!hasInbound || !hasOutbound)) {
            reasons.add("MISSING_SWAP_LEG");
        }
        return List.copyOf(reasons);
    }
}
