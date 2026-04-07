package com.walletradar.ingestion.pipeline.classification.onchain.family;

import com.walletradar.ingestion.pipeline.classification.ClassificationDecision;
import com.walletradar.ingestion.pipeline.classification.OnChainClassificationContext;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryFamily;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryRole;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryService;
import com.walletradar.ingestion.pipeline.classification.reason.ClassificationReasonCode;
import com.walletradar.ingestion.pipeline.classification.support.OnChainClassificationSupport;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

import static com.walletradar.domain.transaction.normalized.ClassificationSource.PROTOCOL_REGISTRY;
import static com.walletradar.domain.transaction.normalized.NormalizedTransactionType.EXTERNAL_TRANSFER_OUT;

/**
 * Outbound-only aggregator-router demotion that must resolve before protocol lifecycle review.
 */
@Component
public class RoutedAggregatorSendClassifier implements OnChainFamilyClassifier {

    private final ProtocolRegistryService protocolRegistryService;

    public RoutedAggregatorSendClassifier(ProtocolRegistryService protocolRegistryService) {
        this.protocolRegistryService = protocolRegistryService;
    }

    @Override
    public OnChainClassificationInsertionPoint insertionPoint() {
        return OnChainClassificationInsertionPoint.PRE_PROTOCOL_REVIEW;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }

    @Override
    public Optional<ClassificationDecision> classify(OnChainClassificationContext context) {
        if (!onlyOutbound(context)) {
            return Optional.empty();
        }
        Optional<ProtocolRegistryEntry> entry = protocolRegistryService.lookup(context.view().networkId(), context.view().toAddress());
        if (entry.isEmpty()) {
            return Optional.empty();
        }
        ProtocolRegistryEntry aggregatorEntry = entry.get();
        if (aggregatorEntry.family() != ProtocolRegistryFamily.AGGREGATOR
                || aggregatorEntry.role() != ProtocolRegistryRole.ROUTER) {
            return Optional.empty();
        }

        return Optional.of(new ClassificationDecision(
                EXTERNAL_TRANSFER_OUT,
                OnChainClassificationSupport.initialStatus(context.view(), EXTERNAL_TRANSFER_OUT, aggregatorEntry.confidence()),
                PROTOCOL_REGISTRY,
                aggregatorEntry.confidence(),
                OnChainClassificationSupport.toFlows(context.movementLegs(), EXTERNAL_TRANSFER_OUT),
                List.of(ClassificationReasonCode.ROUTED_AGGREGATOR_OUTBOUND_ONLY.code()),
                null,
                null,
                null,
                null,
                null,
                aggregatorEntry.protocolName(),
                aggregatorEntry.protocolVersion()
        ));
    }

    private boolean onlyOutbound(OnChainClassificationContext context) {
        boolean hasInbound = context.movementLegs().stream()
                .anyMatch(leg -> !leg.fee() && leg.quantityDelta().signum() > 0);
        boolean hasOutbound = context.movementLegs().stream()
                .anyMatch(leg -> !leg.fee() && leg.quantityDelta().signum() < 0);
        return hasOutbound && !hasInbound;
    }
}
