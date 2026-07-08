package com.walletradar.application.normalization.pipeline.classification.onchain.protocol.registry;

import com.walletradar.application.normalization.pipeline.classification.ClassificationDecision;
import com.walletradar.application.normalization.pipeline.classification.OnChainClassificationContext;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolMatch;
import com.walletradar.application.normalization.pipeline.classification.onchain.family.OnChainClassificationInsertionPoint;
import com.walletradar.application.normalization.pipeline.classification.onchain.family.OnChainFamilyClassifier;
import com.walletradar.application.normalization.pipeline.classification.support.RegistryDecisionSupport;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class SpecialHandlerRegistryReviewClassifier implements OnChainFamilyClassifier {

    @Override
    public OnChainClassificationInsertionPoint insertionPoint() {
        return OnChainClassificationInsertionPoint.PRE_PROTOCOL_REVIEW;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 199;
    }

    @Override
    public Optional<ClassificationDecision> classify(OnChainClassificationContext context) {
        Optional<ProtocolMatch> match = context.protocolDiscovery().primaryMatch()
                .filter(value -> value.specialHandler() != null);
        if (match.isEmpty()) {
            return Optional.empty();
        }
        if (context.protocolSemantics().hints() != null && !context.protocolSemantics().hints().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(RegistryDecisionSupport.pendingRegistryReview(
                context.view(),
                match.orElseThrow(),
                "HANDLER_UNSUPPORTED_METHOD"
        ));
    }
}
