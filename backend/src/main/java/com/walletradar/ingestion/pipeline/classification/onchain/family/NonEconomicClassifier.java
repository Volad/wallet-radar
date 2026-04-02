package com.walletradar.ingestion.pipeline.classification.onchain.family;

import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.transaction.normalized.ClassificationSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.ingestion.pipeline.classification.ClassificationDecision;
import com.walletradar.ingestion.pipeline.classification.OnChainClassificationContext;
import com.walletradar.ingestion.pipeline.classification.reason.ClassificationReasonCode;
import com.walletradar.ingestion.pipeline.classification.support.ZeroAmountTokenSupport;
import com.walletradar.ingestion.pipeline.classification.support.OnChainClassificationSupport;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Extracted post-spam non-economic review rules that should not create economic movement.
 */
@Component
public class NonEconomicClassifier implements OnChainFamilyClassifier {

    private static final Set<String> FULL_RECEIPT_NON_ECONOMIC_ALLOWLIST = Set.of(
            "0x4673757b36119b4632f798ad4e0d72fbd170ee0b7be4e4901bd1155ab3881775",
            "0x91bba2c00fc37a862f2c277e6f8378bf682156425919c66c1b37faa50e9d61b7",
            "0x927d3f458ada7e5ec67f77129e29edcaf2f69bd2b81490a42fec17c0cc3bd4fa",
            "0x9c3a93479dd926c7a6e57395b14ab48ed73e673f5cb25f6c1ae6ac9b1bbf2c19",
            "0xaf00ee8ac5154daa5f4f917d0929ddbacfb1d254ae3b228f3322312a39c798c8",
            "0xe1bc445ff05954e4d9211570bdaed633b0ddddc70ee36d043574d5b9dd1b9630",
            "0x907207001069b6c5b1c0f9aa740736a81ed0f7e8c02b2735a31c772d5bb6603e",
            "0x9867f9d202764ad9d019b0f89cb4b35e96cbc35bd5ac2fabea1edf5c7412bdf2"
    );

    @Override
    public OnChainClassificationInsertionPoint insertionPoint() {
        return OnChainClassificationInsertionPoint.POST_SPAM_REVIEW;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 300;
    }

    @Override
    public Optional<ClassificationDecision> classify(OnChainClassificationContext context) {
        Optional<ClassificationDecision> zeroAmountMatch = classifyZeroAmountTokenNoOp(context);
        if (zeroAmountMatch.isPresent()) {
            return zeroAmountMatch;
        }
        return classifyClarifiedNonEconomicReview(context);
    }

    private Optional<ClassificationDecision> classifyZeroAmountTokenNoOp(OnChainClassificationContext context) {
        if (!ZeroAmountTokenSupport.isZeroAmountOutboundOnly(context.view())) {
            return Optional.empty();
        }
        if (ZeroAmountTokenSupport.isKnownNonEconomicFamily(context.view())) {
            return Optional.of(FamilyDecisionSupport.build(
                    NormalizedTransactionType.ADMIN_CONFIG,
                    NormalizedTransactionStatus.CONFIRMED,
                    ClassificationSource.HEURISTIC,
                    ConfidenceLevel.MEDIUM,
                    context.movementLegs(),
                    List.of()
            ));
        }
        return Optional.of(new ClassificationDecision(
                NormalizedTransactionType.UNKNOWN,
                NormalizedTransactionStatus.NEEDS_REVIEW,
                ClassificationSource.HEURISTIC,
                ConfidenceLevel.LOW,
                OnChainClassificationSupport.toFlows(context.movementLegs(), NormalizedTransactionType.UNKNOWN),
                List.of(ClassificationReasonCode.ZERO_AMOUNT_TOKEN_TRANSFER.code()),
                null,
                null,
                null,
                null,
                null,
                null,
                null
        ));
    }

    private Optional<ClassificationDecision> classifyClarifiedNonEconomicReview(OnChainClassificationContext context) {
        if (!context.view().hasFullReceiptClarificationEvidence()) {
            return Optional.empty();
        }
        if (context.movementLegs().stream().anyMatch(leg -> !leg.fee() && leg.quantityDelta().signum() != 0)) {
            return Optional.empty();
        }
        String txHash = context.view().txHash();
        if (txHash == null || !FULL_RECEIPT_NON_ECONOMIC_ALLOWLIST.contains(txHash.toLowerCase(Locale.ROOT))) {
            return Optional.empty();
        }
        if (context.view().persistedLogs().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(FamilyDecisionSupport.build(
                NormalizedTransactionType.ADMIN_CONFIG,
                NormalizedTransactionStatus.CONFIRMED,
                ClassificationSource.HEURISTIC,
                ConfidenceLevel.MEDIUM,
                context.movementLegs(),
                List.of()
        ));
    }
}
