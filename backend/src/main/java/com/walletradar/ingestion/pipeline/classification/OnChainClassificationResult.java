package com.walletradar.ingestion.pipeline.classification;

import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.transaction.normalized.ClassificationSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;

import java.util.List;

/**
 * Output of the v3 on-chain classifier before pricing/stat stages.
 */
public record OnChainClassificationResult(
        NormalizedTransactionType type,
        NormalizedTransactionStatus status,
        ClassificationSource classifiedBy,
        ConfidenceLevel confidence,
        List<NormalizedTransaction.Flow> flows,
        List<String> missingDataReasons,
        String protocolName,
        String protocolVersion
) {
}
