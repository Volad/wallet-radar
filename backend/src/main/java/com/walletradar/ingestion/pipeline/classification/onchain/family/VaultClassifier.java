package com.walletradar.ingestion.pipeline.classification.onchain.family;

import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.ingestion.pipeline.classification.ClassificationDecision;
import com.walletradar.ingestion.pipeline.classification.OnChainClassificationContext;
import com.walletradar.ingestion.pipeline.classification.onchain.protocol.ProtocolSemanticHint;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryFamily;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryRole;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryService;
import com.walletradar.ingestion.pipeline.classification.support.OnChainClassificationSupport;
import com.walletradar.ingestion.pipeline.classification.support.RegistryDecisionSupport;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class VaultClassifier implements OnChainFamilyClassifier {

    private final ProtocolRegistryService protocolRegistryService;

    public VaultClassifier(ProtocolRegistryService protocolRegistryService) {
        this.protocolRegistryService = protocolRegistryService;
    }

    @Override
    public OnChainClassificationInsertionPoint insertionPoint() {
        return OnChainClassificationInsertionPoint.POST_SPAM_REVIEW;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 440;
    }

    @Override
    public Optional<ClassificationDecision> classify(OnChainClassificationContext context) {
        Optional<ClassificationDecision> semanticDecision = classifyFromSemantics(context);
        if (semanticDecision.isPresent()) {
            return semanticDecision;
        }
        Optional<ProtocolRegistryEntry> entry = protocolRegistryService.lookup(
                context.view().networkId(),
                context.view().toAddress()
        );
        if (entry.isEmpty()
                || entry.get().specialHandler() != null
                || entry.get().role() != ProtocolRegistryRole.VAULT) {
            return Optional.empty();
        }
        NormalizedTransactionType type = VaultRegistryFamilySupport.resolveVaultType(context.view());
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

    private Optional<ClassificationDecision> classifyFromSemantics(OnChainClassificationContext context) {
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
                    com.walletradar.domain.transaction.normalized.ClassificationSource.PROTOCOL_REGISTRY,
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
