package com.walletradar.ingestion.pipeline.clarification;

import com.walletradar.domain.transaction.raw.RawTransaction;
import org.bson.Document;
import org.springframework.stereotype.Component;

/**
 * Merges only clarification-approved receipt fields into raw evidence.
 */
@Component
public class RawTransactionClarificationEnricher {

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

        Document clarificationEvidence = rawData.get("clarificationEvidence", Document.class);
        if (clarificationEvidence == null) {
            clarificationEvidence = new Document();
        }
        if (enrichment.sourceFamily() != null) {
            clarificationEvidence.put("sourceFamily", enrichment.sourceFamily().name());
        }

        Document receiptEvidence = clarificationEvidence.get("receipt", Document.class);
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

        if (enrichment.hasFullReceiptEvidence()) {
            Document transfersEvidence = clarificationEvidence.get("transfers", Document.class);
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
                clarificationEvidence.put("fullReceipt", new Document(enrichment.fullReceiptPayload()));
            }
        }
        rawData.put("clarificationEvidence", clarificationEvidence);
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
                copies.add(new Document(document));
            }
        }
        return java.util.List.copyOf(copies);
    }
}
