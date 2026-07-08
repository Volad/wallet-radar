package com.walletradar.application.normalization.pipeline.classification.onchain.family;

import com.walletradar.domain.transaction.normalized.ClassificationSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.application.normalization.pipeline.classification.ClassificationDecision;
import com.walletradar.application.normalization.pipeline.classification.OnChainClassificationContext;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolSemanticHint;
import com.walletradar.application.normalization.pipeline.classification.support.OnChainClassificationSupport;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Converts a {@code BORROW} protocol-semantic hint (e.g. from {@code InitCapitalSemanticClassifier})
 * into a final {@link NormalizedTransactionType#BORROW} classification decision.
 *
 * <p>Runs at PRE_PROTOCOL_REVIEW order 150, just before {@link LendingSemanticClassifier} (+152)
 * and {@link LpSemanticClassifier} (+151), so the semantic hint wins over any registry fallback.
 */
@Component
public class BorrowSemanticClassifier implements OnChainFamilyClassifier {

    @Override
    public OnChainClassificationInsertionPoint insertionPoint() {
        return OnChainClassificationInsertionPoint.PRE_PROTOCOL_REVIEW;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 150;
    }

    @Override
    public Optional<ClassificationDecision> classify(OnChainClassificationContext context) {
        Optional<ProtocolSemanticHint> hint = context.protocolSemantics()
                .firstBySuggestedType(NormalizedTransactionType.BORROW);
        if (hint.isEmpty()) {
            return Optional.empty();
        }
        ProtocolSemanticHint value = hint.orElseThrow();
        return Optional.of(FamilyDecisionSupport.buildWithView(
                context.view(),
                NormalizedTransactionType.BORROW,
                OnChainClassificationSupport.initialStatus(
                        context.view(),
                        NormalizedTransactionType.BORROW,
                        value.confidence()
                ),
                ClassificationSource.PROTOCOL_REGISTRY,
                value.confidence(),
                OnChainClassificationSupport.toFlows(
                        context.movementLegs(),
                        NormalizedTransactionType.BORROW
                ),
                List.of(),
                value.protocolName(),
                value.protocolVersion()
        ));
    }
}
