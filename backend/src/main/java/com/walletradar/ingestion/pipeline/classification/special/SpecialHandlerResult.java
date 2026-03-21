package com.walletradar.ingestion.pipeline.classification.special;

import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.ingestion.pipeline.classification.support.OnChainClassificationSupport;
import com.walletradar.ingestion.pipeline.classification.support.RawLeg;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;

import java.util.List;

public record SpecialHandlerResult(
        NormalizedTransactionType type,
        List<NormalizedTransaction.Flow> flows,
        ConfidenceLevel confidence,
        NormalizedTransactionStatus status,
        List<String> missingDataReasons
) {
    public static SpecialHandlerResult of(
            OnChainRawTransactionView view,
            NormalizedTransactionType type,
            ConfidenceLevel confidence,
            List<RawLeg> legs
    ) {
        return new SpecialHandlerResult(
                type,
                OnChainClassificationSupport.toFlows(legs, type),
                confidence,
                OnChainClassificationSupport.initialStatus(view, type, confidence),
                List.of()
        );
    }

    public static SpecialHandlerResult unsupported() {
        return new SpecialHandlerResult(
                NormalizedTransactionType.UNKNOWN,
                List.of(),
                ConfidenceLevel.HIGH,
                NormalizedTransactionStatus.NEEDS_REVIEW,
                List.of("HANDLER_UNSUPPORTED_METHOD")
        );
    }

    public static SpecialHandlerResult missingHandler() {
        return new SpecialHandlerResult(
                NormalizedTransactionType.UNKNOWN,
                List.of(),
                ConfidenceLevel.HIGH,
                NormalizedTransactionStatus.NEEDS_REVIEW,
                List.of("REGISTRY_SPECIAL_HANDLER_REQUIRED")
        );
    }
}
