package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.platform.persistence.support.BsonCoercionSupport;
import org.bson.Document;
import org.springframework.stereotype.Component;

/**
 * Merges only clarification-approved receipt fields into raw evidence.
 */
@Component
public class RawTransactionClarificationEnricher {

    private static final String CLARIFICATION_ATTEMPTS_KEY = "clarificationAttempts";
    private static final String FULL_RECEIPT_ATTEMPTS_KEY = "fullReceiptClarificationAttempts";
    private static final String LAST_METADATA_FAILURE_REASON_KEY = "lastClarificationFailureReason";
    private static final String LAST_FULL_RECEIPT_FAILURE_REASON_KEY = "lastFullReceiptClarificationFailureReason";

    public void merge(RawTransaction rawTransaction, ClarificationReceiptEnrichment enrichment) {
        if (rawTransaction.getRawData() == null) {
            rawTransaction.setRawData(new Document());
        }
        Document rawData = rawTransaction.getRawData();

        if (enrichment.txReceiptStatus() != null) {
            rawData.put("txreceipt_status", enrichment.txReceiptStatus());
        }
        if (enrichment.gasUsed() != null) {
            rawData.put("gasUsed", enrichment.gasUsed());
        }
        if (enrichment.effectiveGasPrice() != null) {
            rawData.put("effectiveGasPrice", enrichment.effectiveGasPrice());
        }
        if (enrichment.contractAddress() != null) {
            rawData.put("contractAddress", enrichment.contractAddress());
        }
        if (!hasPersistableEvidence(enrichment)) {
            return;
        }

        Document clarificationEvidence = canonicalClarificationEvidence(rawTransaction);
        if (enrichment.sourceFamily() != null) {
            clarificationEvidence.put("sourceFamily", enrichment.sourceFamily().name());
        }

        Document receiptEvidence = BsonCoercionSupport.asDocument(clarificationEvidence.get("receipt"));
        if (receiptEvidence == null) {
            receiptEvidence = new Document();
        }
        if (enrichment.txReceiptStatus() != null) {
            receiptEvidence.put("txReceiptStatus", enrichment.txReceiptStatus());
        }
        if (enrichment.gasUsed() != null) {
            receiptEvidence.put("gasUsed", enrichment.gasUsed());
        }
        if (enrichment.effectiveGasPrice() != null) {
            receiptEvidence.put("effectiveGasPrice", enrichment.effectiveGasPrice());
        }
        if (enrichment.contractAddress() != null) {
            receiptEvidence.put("contractAddress", enrichment.contractAddress());
        }
        if (enrichment.blockNumber() != null) {
            receiptEvidence.put("blockNumber", enrichment.blockNumber());
        }
        if (!enrichment.receiptLogs().isEmpty()) {
            receiptEvidence.put("logs", copyDocuments(enrichment.receiptLogs()));
        }
        if (!receiptEvidence.isEmpty()) {
            clarificationEvidence.put("receipt", receiptEvidence);
        }

        Document transfersEvidence = BsonCoercionSupport.asDocument(clarificationEvidence.get("transfers"));
        if (transfersEvidence == null) {
            transfersEvidence = new Document();
        }
        if (!enrichment.tokenTransfers().isEmpty()) {
            transfersEvidence.put("tokenTransfers", copyDocuments(enrichment.tokenTransfers()));
        }
        if (!enrichment.internalTransfers().isEmpty()) {
            transfersEvidence.put("internalTransfers", copyDocuments(enrichment.internalTransfers()));
        }
        if (!transfersEvidence.isEmpty()) {
            clarificationEvidence.put("transfers", transfersEvidence);
        }
        if (enrichment.fullReceiptPayload() != null) {
            clarificationEvidence.put("fullReceipt", BsonCoercionSupport.copyDocument(enrichment.fullReceiptPayload()));
        }

        persistCanonicalClarificationEvidence(rawTransaction, clarificationEvidence);
    }

    public void mergeProtocolStatus(RawTransaction rawTransaction, Document protocolStatusEvidence) {
        mergeClarificationEvidence(rawTransaction, "protocolStatus", protocolStatusEvidence);
    }

    public void mergeClarificationEvidence(
            RawTransaction rawTransaction,
            String evidenceKey,
            Document evidence
    ) {
        if (rawTransaction == null
                || evidenceKey == null
                || evidenceKey.isBlank()
                || evidence == null
                || evidence.isEmpty()) {
            return;
        }
        if (rawTransaction.getRawData() == null) {
            rawTransaction.setRawData(new Document());
        }
        Document clarificationEvidence = canonicalClarificationEvidence(rawTransaction);
        clarificationEvidence.put(evidenceKey, BsonCoercionSupport.copyDocument(evidence));
        persistCanonicalClarificationEvidence(rawTransaction, clarificationEvidence);
    }

    public int recordMetadataAttempt(RawTransaction rawTransaction, String failureReason) {
        return recordMetadataAttempt(rawTransaction, 0, failureReason);
    }

    public int recordMetadataAttempt(
            RawTransaction rawTransaction,
            int minimumAttempts,
            String failureReason
    ) {
        return recordAttempt(rawTransaction, false, minimumAttempts, failureReason);
    }

    public int recordFullReceiptAttempt(RawTransaction rawTransaction, String failureReason) {
        return recordFullReceiptAttempt(rawTransaction, 0, failureReason);
    }

    public int recordFullReceiptAttempt(
            RawTransaction rawTransaction,
            int minimumAttempts,
            String failureReason
    ) {
        return recordAttempt(rawTransaction, true, minimumAttempts, failureReason);
    }

    private int recordAttempt(
            RawTransaction rawTransaction,
            boolean fullReceiptAttempt,
            int minimumAttempts,
            String failureReason
    ) {
        if (rawTransaction.getRawData() == null) {
            rawTransaction.setRawData(new Document());
        }
        Document clarificationEvidence = canonicalClarificationEvidence(rawTransaction);

        String counterKey = fullReceiptAttempt
                ? FULL_RECEIPT_ATTEMPTS_KEY
                : CLARIFICATION_ATTEMPTS_KEY;
        int nextAttempts = Math.max(safeCounter(clarificationEvidence.get(counterKey)), Math.max(0, minimumAttempts)) + 1;
        clarificationEvidence.put(counterKey, nextAttempts);

        if (failureReason != null && !failureReason.isBlank()) {
            clarificationEvidence.put(
                    fullReceiptAttempt
                            ? LAST_FULL_RECEIPT_FAILURE_REASON_KEY
                            : LAST_METADATA_FAILURE_REASON_KEY,
                    failureReason
            );
        } else if (fullReceiptAttempt) {
            clarificationEvidence.remove(LAST_FULL_RECEIPT_FAILURE_REASON_KEY);
        } else {
            clarificationEvidence.remove(LAST_METADATA_FAILURE_REASON_KEY);
        }

        persistCanonicalClarificationEvidence(rawTransaction, clarificationEvidence);
        return nextAttempts;
    }

    private static Document canonicalClarificationEvidence(RawTransaction rawTransaction) {
        if (rawTransaction.getClarificationEvidence() != null) {
            return BsonCoercionSupport.copyDocument(rawTransaction.getClarificationEvidence());
        }
        if (rawTransaction.getRawData() == null) {
            return new Document();
        }
        Document legacy = BsonCoercionSupport.asDocument(rawTransaction.getRawData().get("clarificationEvidence"));
        return legacy == null ? new Document() : legacy;
    }

    private static void persistCanonicalClarificationEvidence(RawTransaction rawTransaction, Document clarificationEvidence) {
        rawTransaction.setClarificationEvidence(BsonCoercionSupport.copyDocument(clarificationEvidence));
        if (rawTransaction.getRawData() != null) {
            rawTransaction.getRawData().remove("clarificationEvidence");
        }
    }

    private static boolean hasPersistableEvidence(ClarificationReceiptEnrichment enrichment) {
        return enrichment != null
                && (enrichment.txReceiptStatus() != null
                || enrichment.gasUsed() != null
                || enrichment.effectiveGasPrice() != null
                || enrichment.contractAddress() != null
                || enrichment.blockNumber() != null
                || enrichment.hasFullReceiptEvidence()
                || enrichment.sourceFamily() != null);
    }

    private static java.util.List<Document> copyDocuments(java.util.List<Document> documents) {
        java.util.List<Document> copies = new java.util.ArrayList<>(documents.size());
        for (Document document : documents) {
            if (document != null) {
                copies.add(BsonCoercionSupport.copyDocument(document));
            }
        }
        return java.util.List.copyOf(copies);
    }

    private static int safeCounter(Object rawValue) {
        if (rawValue instanceof Number number) {
            return Math.max(0, number.intValue());
        }
        if (rawValue == null) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(rawValue.toString().trim()));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
