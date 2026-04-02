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
public class VaultSemanticClassifier implements OnChainFamilyClassifier {

    @Override
    public OnChainClassificationInsertionPoint insertionPoint() {
        return OnChainClassificationInsertionPoint.PRE_PROTOCOL_REVIEW;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 153;
    }

    @Override
    public Optional<ClassificationDecision> classify(OnChainClassificationContext context) {
        for (NormalizedTransactionType type : List.of(
                NormalizedTransactionType.VAULT_DEPOSIT,
                NormalizedTransactionType.VAULT_WITHDRAW
        )) {
            Optional<ProtocolSemanticHint> hint = context.protocolSemantics().firstBySuggestedType(type);
            if (hint.isEmpty()) {
                continue;
            }
            ProtocolSemanticHint value = hint.orElseThrow();
            return Optional.of(FamilyDecisionSupport.buildWithView(
                    context.view(),
                    type,
                    OnChainClassificationSupport.initialStatus(context.view(), type, value.confidence()),
                    ClassificationSource.PROTOCOL_REGISTRY,
                    value.confidence(),
                    OnChainClassificationSupport.toFlows(context.movementLegs(), type),
                    List.of(),
                    value.protocolName(),
                    value.protocolVersion()
            ));
        }
        return Optional.empty();
    }
}
