package com.walletradar.ingestion.pipeline.clarification;

import com.walletradar.domain.transaction.raw.RawSyncMethod;
import com.walletradar.ingestion.adapter.evm.explorer.model.ExplorerReceipt;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;
import org.bson.Document;

import java.util.List;

import java.util.Locale;
import java.util.Optional;

/**
 * Allowed clarification-only receipt enrichment fields.
 */
public record ClarificationReceiptEnrichment(
        String txReceiptStatus,
        String gasUsed,
        String effectiveGasPrice,
        String contractAddress,
        String blockNumber,
        List<Document> receiptLogs,
        List<Document> tokenTransfers,
        List<Document> internalTransfers,
        Document fullReceiptPayload,
        RawSyncMethod sourceFamily
) {

    public static Optional<ClarificationReceiptEnrichment> fromReceipt(
            ExplorerReceipt receipt,
            ClarificationMode mode,
            RawSyncMethod sourceFamily,
            List<Document> tokenTransfers,
            List<Document> internalTransfers
    ) {
        if (receipt == null) {
            return Optional.empty();
        }
        return fromReceiptDocument(
                receipt.asDocument(),
                mode,
                sourceFamily,
                tokenTransfers,
                internalTransfers
        );
    }

    public static Optional<ClarificationReceiptEnrichment> fromReceiptDocument(
            Document receipt,
            ClarificationMode mode,
            RawSyncMethod sourceFamily,
            List<Document> tokenTransfers,
            List<Document> internalTransfers
    ) {
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
        String blockNumber = trimToNull(receipt.getString("blockNumber"));
        List<Document> receiptLogs = mode == ClarificationMode.FULL_RECEIPT
                ? copyDocuments(readDocumentList(receipt, "logs"))
                : List.of();
        List<Document> copiedTokenTransfers = mode == ClarificationMode.FULL_RECEIPT
                ? copyDocuments(tokenTransfers)
                : List.of();
        List<Document> copiedInternalTransfers = mode == ClarificationMode.FULL_RECEIPT
                ? copyDocuments(internalTransfers)
                : List.of();
        Document fullReceiptPayload = mode == ClarificationMode.FULL_RECEIPT ? new Document(receipt) : null;

        if (txReceiptStatus == null
                && gasUsed == null
                && effectiveGasPrice == null
                && contractAddress == null
                && receiptLogs.isEmpty()
                && copiedTokenTransfers.isEmpty()
                && copiedInternalTransfers.isEmpty()
                && fullReceiptPayload == null) {
            return Optional.empty();
        }
        return Optional.of(new ClarificationReceiptEnrichment(
                txReceiptStatus,
                gasUsed,
                effectiveGasPrice,
                contractAddress,
                blockNumber,
                receiptLogs,
                copiedTokenTransfers,
                copiedInternalTransfers,
                fullReceiptPayload,
                sourceFamily
        ));
    }

    public boolean hasFullReceiptEvidence() {
        return !receiptLogs.isEmpty()
                || !tokenTransfers.isEmpty()
                || !internalTransfers.isEmpty()
                || fullReceiptPayload != null;
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

    private static List<Document> readDocumentList(Document parent, String key) {
        if (parent == null) {
            return List.of();
        }
        Object raw = parent.get(key);
        if (!(raw instanceof List<?> items) || items.isEmpty()) {
            return List.of();
        }
        List<Document> documents = new java.util.ArrayList<>(items.size());
        for (Object item : items) {
            if (item instanceof Document document) {
                documents.add(new Document(document));
            }
        }
        return List.copyOf(documents);
    }

    private static List<Document> copyDocuments(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        List<Document> copies = new java.util.ArrayList<>(documents.size());
        for (Document document : documents) {
            if (document != null) {
                copies.add(new Document(document));
            }
        }
        return List.copyOf(copies);
    }
}
