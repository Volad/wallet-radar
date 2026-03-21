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
    }
}
