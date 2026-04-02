package com.walletradar.ingestion.pipeline.classification.onchain.family;

import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.transaction.normalized.ClassificationSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.ingestion.pipeline.classification.ClassificationDecision;
import com.walletradar.ingestion.pipeline.classification.OnChainClassificationContext;
import com.walletradar.ingestion.pipeline.classification.support.LpPositionLifecycleSupport;
import com.walletradar.ingestion.pipeline.classification.support.OnChainClassificationSupport;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * LP family classifier for clarified `routeSingle` NFT-backed LP entry paths.
 */
@Component
public class LpClassifier implements OnChainFamilyClassifier {

    private static final String ROUTE_SINGLE_SELECTOR = "0xb94c3609";

    @Override
    public OnChainClassificationInsertionPoint insertionPoint() {
        return OnChainClassificationInsertionPoint.PRE_PROTOCOL_REVIEW;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 200;
    }

    @Override
    public Optional<ClassificationDecision> classify(OnChainClassificationContext context) {
        if (!ROUTE_SINGLE_SELECTOR.equals(context.view().methodId())) {
            return Optional.empty();
        }
        if (!context.view().hasFullReceiptClarificationEvidence()) {
            return Optional.empty();
        }
        if (!LpPositionLifecycleSupport.hasAnyErc721TransferToWallet(context.view())) {
            return Optional.empty();
        }
        return Optional.of(new ClassificationDecision(
                NormalizedTransactionType.LP_ENTRY,
                OnChainClassificationSupport.initialStatus(context.view(), NormalizedTransactionType.LP_ENTRY, ConfidenceLevel.MEDIUM),
                ClassificationSource.HEURISTIC,
                ConfidenceLevel.MEDIUM,
                OnChainClassificationSupport.toFlows(context.movementLegs(), NormalizedTransactionType.LP_ENTRY),
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                null
        ));
    }
}
