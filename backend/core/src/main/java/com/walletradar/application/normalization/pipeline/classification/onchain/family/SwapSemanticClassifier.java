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

@Component
public class SwapSemanticClassifier implements OnChainFamilyClassifier {

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
        Optional<ProtocolSemanticHint> hint = context.protocolSemantics().firstBySuggestedType(NormalizedTransactionType.SWAP);
        if (hint.isEmpty()) {
            return Optional.empty();
        }
        ProtocolSemanticHint value = hint.orElseThrow();
        return Optional.of(FamilyDecisionSupport.buildWithView(
                context.view(),
                NormalizedTransactionType.SWAP,
                OnChainClassificationSupport.initialStatus(context.view(), NormalizedTransactionType.SWAP, value.confidence()),
                ClassificationSource.PROTOCOL_REGISTRY,
                value.confidence(),
                OnChainClassificationSupport.toFlows(context.movementLegs(), NormalizedTransactionType.SWAP),
                List.of(),
                value.protocolName(),
                value.protocolVersion()
        ));
    }
}
