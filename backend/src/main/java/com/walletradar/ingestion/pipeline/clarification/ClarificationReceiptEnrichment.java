package com.walletradar.ingestion.pipeline.clarification;

import com.walletradar.domain.transaction.raw.RawSyncMethod;
import com.walletradar.ingestion.adapter.evm.explorer.model.ExplorerReceipt;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;
import com.walletradar.ingestion.pipeline.support.BsonCoercionSupport;
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
            RawSyncMethod sourceFamily,
            List<Document> tokenTransfers,
            List<Document> internalTransfers
    ) {
        if (receipt == null) {
            return Optional.empty();
        }
        return fromReceiptDocument(
                receipt.asDocument(),
                sourceFamily,
                tokenTransfers,
                internalTransfers
        );
    }

    public static Optional<ClarificationReceiptEnrichment> fromReceiptDocument(
            Document receipt,
            RawSyncMethod sourceFamily,
            List<Document> tokenTransfers,
            List<Document> internalTransfers
    ) {
        Document normalizedReceipt = BsonCoercionSupport.asDocument(receipt);
        if (normalizedReceipt == null) {
            return Optional.empty();
        }

        String txReceiptStatus = normalizeStatus(normalizedReceipt.getString("status"));
        String gasUsed = trimToNull(normalizedReceipt.getString("gasUsed"));
        String effectiveGasPrice = trimToNull(normalizedReceipt.getString("effectiveGasPrice"));
        if (effectiveGasPrice == null) {
            effectiveGasPrice = trimToNull(normalizedReceipt.getString("gasPrice"));
        }
        String contractAddress = OnChainRawTransactionView.normalizeAddress(normalizedReceipt.getString("contractAddress"));
        String blockNumber = trimToNull(normalizedReceipt.getString("blockNumber"));
        List<Document> receiptLogs = copyDocuments(readDocumentList(normalizedReceipt, "logs"));
        List<Document> copiedTokenTransfers = copyDocuments(tokenTransfers);
        List<Document> copiedInternalTransfers = copyDocuments(internalTransfers);
        Document fullReceiptPayload = BsonCoercionSupport.copyDocument(normalizedReceipt);

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
        return fullReceiptPayload != null
                || !receiptLogs.isEmpty()
                || !tokenTransfers.isEmpty()
                || !internalTransfers.isEmpty();
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
        return BsonCoercionSupport.asDocumentList(parent.get(key));
    }

    private static List<Document> copyDocuments(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        List<Document> copies = new java.util.ArrayList<>(documents.size());
        for (Document document : documents) {
            if (document != null) {
                copies.add(BsonCoercionSupport.copyDocument(document));
            }
        }
        return List.copyOf(copies);
    }
}
