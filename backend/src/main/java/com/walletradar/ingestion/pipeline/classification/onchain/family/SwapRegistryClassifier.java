package com.walletradar.ingestion.pipeline.classification.onchain.family;

import com.walletradar.ingestion.pipeline.classification.ClassificationDecision;
import com.walletradar.ingestion.pipeline.classification.OnChainClassificationContext;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryService;
import com.walletradar.ingestion.pipeline.classification.support.NativeAssetSymbolResolver;
import com.walletradar.ingestion.pipeline.classification.support.RegistryDecisionSupport;
import com.walletradar.ingestion.pipeline.classification.support.RegistryMethodDispatchSupport;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class SwapRegistryClassifier implements OnChainFamilyClassifier {

    private final ProtocolRegistryService protocolRegistryService;
    private final NativeAssetSymbolResolver nativeAssetSymbolResolver;

    public SwapRegistryClassifier(
            ProtocolRegistryService protocolRegistryService,
            NativeAssetSymbolResolver nativeAssetSymbolResolver
    ) {
        this.protocolRegistryService = protocolRegistryService;
        this.nativeAssetSymbolResolver = nativeAssetSymbolResolver;
    }

    @Override
    public OnChainClassificationInsertionPoint insertionPoint() {
        return OnChainClassificationInsertionPoint.POST_SPAM_REVIEW;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 410;
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
        if (!RegistryMethodDispatchSupport.requiresMethodAwareDispatch(entry.get(), context.view())) {
            return Optional.empty();
        }
        if (!SwapRegistryFamilySupport.isRouterSwapLike(
                entry.get(),
                context.view(),
                context.movementLegs(),
                nativeAssetSymbolResolver
        )) {
            return Optional.empty();
        }
        return Optional.of(RegistryDecisionSupport.registryResult(
                context.view(),
                entry.get(),
                com.walletradar.domain.transaction.normalized.NormalizedTransactionType.SWAP,
                context.movementLegs()
        ));
    }
}
