package com.walletradar.ingestion.pipeline.clarification;

import com.walletradar.ingestion.adapter.evm.explorer.model.ExplorerReceipt;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;

import java.util.Locale;
import java.util.Optional;

/**
 * Allowed clarification-only receipt enrichment fields.
 */
public record ClarificationReceiptEnrichment(
        String txReceiptStatus,
        String gasUsed,
        String effectiveGasPrice,
        String contractAddress
) {

    public static Optional<ClarificationReceiptEnrichment> fromReceipt(ExplorerReceipt receipt) {
        if (receipt == null) {
            return Optional.empty();
        }

        String txReceiptStatus = normalizeStatus(receipt.getString("status"));
        String gasUsed = trimToNull(receipt.getString("gasUsed"));
        String effectiveGasPrice = trimToNull(receipt.getString("effectiveGasPrice"));
        if (effectiveGasPrice == null) {
            effectiveGasPrice = trimToNull(receipt.getString("gasPrice"));
        }
        String contractAddress = OnChainRawTransactionView.normalizeAddress(receipt.getString("contractAddress"));

        if (txReceiptStatus == null && gasUsed == null && effectiveGasPrice == null && contractAddress == null) {
            return Optional.empty();
        }
        return Optional.of(new ClarificationReceiptEnrichment(
                txReceiptStatus,
                gasUsed,
                effectiveGasPrice,
                contractAddress
        ));
    }

    private static String normalizeStatus(String value) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            return null;
        }
        normalized = normalized.toLowerCase(Locale.ROOT);
        if ("0x1".equals(normalized) || "1".equals(normalized)) {
            return "1";
        }
        if ("0x0".equals(normalized) || "0".equals(normalized)) {
            return "0";
        }
        return null;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
