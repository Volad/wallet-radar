package com.walletradar.application.normalization.pipeline.classification.onchain.family;

import com.walletradar.application.normalization.pipeline.classification.ClassificationDecision;
import com.walletradar.application.normalization.pipeline.classification.OnChainClassificationContext;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolResourceCatalog;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryFamily;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryRole;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryService;
import com.walletradar.application.normalization.pipeline.classification.support.RegistryDecisionSupport;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class LendingRegistryClassifier implements OnChainFamilyClassifier {

    private final ProtocolRegistryService protocolRegistryService;
    private final ProtocolResourceCatalog protocolResourceCatalog;

    public LendingRegistryClassifier(
            ProtocolRegistryService protocolRegistryService,
            ProtocolResourceCatalog protocolResourceCatalog
    ) {
        this.protocolRegistryService = protocolRegistryService;
        this.protocolResourceCatalog = protocolResourceCatalog;
    }

    @Override
    public OnChainClassificationInsertionPoint insertionPoint() {
        return OnChainClassificationInsertionPoint.POST_SPAM_REVIEW;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 430;
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
        if (entry.get().specialHandler() != null) {
            return Optional.empty();
        }
        if (entry.get().family() != ProtocolRegistryFamily.LENDING
                || entry.get().role() != ProtocolRegistryRole.POOL) {
            return Optional.empty();
        }
        var type = LendingRegistryFamilySupport.resolveLendingPoolType(
                context.view(),
                entry.get(),
                protocolResourceCatalog
        );
        if (type == null) {
            return Optional.empty();
        }
        return Optional.of(RegistryDecisionSupport.registryResult(
                context.view(),
                entry.get(),
                type,
                context.movementLegs()
        ));
    }
}
