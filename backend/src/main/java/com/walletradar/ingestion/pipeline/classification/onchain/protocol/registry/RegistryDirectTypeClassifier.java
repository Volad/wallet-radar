package com.walletradar.ingestion.pipeline.classification.onchain.protocol.registry;

import com.walletradar.domain.transaction.normalized.ClassificationSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.ingestion.pipeline.classification.ClassificationDecision;
import com.walletradar.ingestion.pipeline.classification.OnChainClassificationContext;
import com.walletradar.ingestion.pipeline.classification.onchain.family.OnChainClassificationInsertionPoint;
import com.walletradar.ingestion.pipeline.classification.onchain.family.OnChainFamilyClassifier;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryService;
import com.walletradar.ingestion.pipeline.classification.support.DirectMethodIdSupport;
import com.walletradar.ingestion.pipeline.classification.support.RegistryDecisionSupport;
import com.walletradar.ingestion.pipeline.classification.support.SameWalletSwapShapeSupport;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class RegistryDirectTypeClassifier implements OnChainFamilyClassifier {

    private final ProtocolRegistryService protocolRegistryService;

    public RegistryDirectTypeClassifier(ProtocolRegistryService protocolRegistryService) {
        this.protocolRegistryService = protocolRegistryService;
    }

    @Override
    public OnChainClassificationInsertionPoint insertionPoint() {
        return OnChainClassificationInsertionPoint.POST_SPAM_REVIEW;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 470;
    }

    @Override
    public Optional<ClassificationDecision> classify(OnChainClassificationContext context) {
        Optional<ProtocolRegistryEntry> protocolMatch =
                protocolRegistryService.lookup(context.view().networkId(), context.view().toAddress());
        if (protocolMatch.isEmpty()) {
            return Optional.empty();
        }
        ProtocolRegistryEntry entry = protocolMatch.get();

        if (entry.decomposeByLegs()) {
            return Optional.of(RegistryDecisionSupport.pendingRegistryReview(
                    context.view(),
                    entry,
                    "REGISTRY_SPECIAL_HANDLER_REQUIRED"
            ));
        }

        if (shouldYieldToApproveFallback(context)) {
            return Optional.empty();
        }

        NormalizedTransactionType type = entry.normalizedType();
        if (type == null) {
            return Optional.empty();
        }
        if (type == NormalizedTransactionType.BRIDGE_OUT
                && SameWalletSwapShapeSupport.hasSameWalletInboundTransfer(context.movementLegs())) {
            return Optional.empty();
        }

        return Optional.of(RegistryDecisionSupport.registryResult(
                context.view(),
                entry,
                type,
                context.movementLegs()
        ));
    }

    private boolean shouldYieldToApproveFallback(OnChainClassificationContext context) {
        if (DirectMethodIdSupport.resolveType(context.view().methodId()) == NormalizedTransactionType.APPROVE) {
            return true;
        }
        String functionName = context.view().functionName();
        return functionName != null && functionName.startsWith("approve");
    }
}
