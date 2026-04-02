package com.walletradar.ingestion.pipeline.classification.onchain.family;

import com.walletradar.domain.transaction.normalized.ClassificationSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.ingestion.pipeline.classification.ClassificationDecision;
import com.walletradar.ingestion.pipeline.classification.OnChainClassificationContext;
import com.walletradar.ingestion.pipeline.classification.onchain.protocol.ProtocolSemanticHint;
import com.walletradar.ingestion.pipeline.classification.support.OnChainClassificationSupport;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class LendingSemanticClassifier implements OnChainFamilyClassifier {

    @Override
    public OnChainClassificationInsertionPoint insertionPoint() {
        return OnChainClassificationInsertionPoint.PRE_PROTOCOL_REVIEW;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 152;
    }

    @Override
    public Optional<ClassificationDecision> classify(OnChainClassificationContext context) {
        Optional<ProtocolSemanticHint> hint = context.protocolSemantics()
                .firstBySuggestedType(NormalizedTransactionType.LENDING_WITHDRAW);
        if (hint.isEmpty()) {
            return Optional.empty();
        }
        ProtocolSemanticHint value = hint.orElseThrow();
        return Optional.of(FamilyDecisionSupport.buildWithView(
                context.view(),
                NormalizedTransactionType.LENDING_WITHDRAW,
                OnChainClassificationSupport.initialStatus(
                        context.view(),
                        NormalizedTransactionType.LENDING_WITHDRAW,
                        value.confidence()
                ),
                ClassificationSource.PROTOCOL_REGISTRY,
                value.confidence(),
                OnChainClassificationSupport.toFlows(
                        context.movementLegs(),
                        NormalizedTransactionType.LENDING_WITHDRAW
                ),
                List.of(),
                value.protocolName(),
                value.protocolVersion()
        ));
    }
}
