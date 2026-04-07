package com.walletradar.ingestion.pipeline.classification.onchain.protocol.registry;

import com.walletradar.ingestion.pipeline.classification.ClassificationDecision;
import com.walletradar.ingestion.pipeline.classification.OnChainClassificationContext;
import com.walletradar.ingestion.pipeline.classification.onchain.family.OnChainClassificationInsertionPoint;
import com.walletradar.ingestion.pipeline.classification.onchain.family.OnChainFamilyClassifier;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryService;
import com.walletradar.ingestion.pipeline.classification.support.RegistryDecisionSupport;
import com.walletradar.ingestion.pipeline.classification.support.RegistryMethodDispatchSupport;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class MethodAwareRegistryReviewClassifier implements OnChainFamilyClassifier {

    private final ProtocolRegistryService protocolRegistryService;

    public MethodAwareRegistryReviewClassifier(ProtocolRegistryService protocolRegistryService) {
        this.protocolRegistryService = protocolRegistryService;
    }

    @Override
    public OnChainClassificationInsertionPoint insertionPoint() {
        return OnChainClassificationInsertionPoint.POST_SPAM_REVIEW;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 460;
    }

    @Override
    public Optional<ClassificationDecision> classify(OnChainClassificationContext context) {
        Optional<ProtocolRegistryEntry> entry = protocolRegistryService.lookup(
                context.view().networkId(),
                context.view().toAddress()
        );
        if (entry.isEmpty()) {
            return Optional.empty();
        }
        if (!RegistryMethodDispatchSupport.requiresMethodAwareDispatch(entry.get(), context.view())) {
            return Optional.empty();
        }
        return Optional.of(RegistryDecisionSupport.pendingRegistryReview(
                context.view(),
                entry.get(),
                "ROUTER_METHOD_OVERLOAD_UNSUPPORTED"
        ));
    }
}
